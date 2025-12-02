package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

abstract class InstallRokuTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zipFile: RegularFileProperty

    @get:Input
    abstract val deviceIp: Property<String>

    @get:Input
    abstract val devicePassword: Property<String>

    @TaskAction
    fun install() {
        val ip = deviceIp.get()
        val password = devicePassword.get()
        val file = zipFile.get().asFile

        if (ip.isBlank()) {
            throw GradleException("Device IP not configured. Set roku.deviceIp in local.properties or ROKU_DEVICE_IP environment variable.")
        }
        if (password.isBlank()) {
            throw GradleException("Device password not configured. Set roku.devicePassword in local.properties or ROKU_PASSWORD environment variable.")
        }

        val url = "http://$ip/plugin_install"
        logger.lifecycle("Installing ${file.name} to Roku device at $ip...")

        // First request to get the Digest challenge
        val challengeResponse = sendRequest(url, file, null)

        if (challengeResponse.code == 401) {
            val authHeader = challengeResponse.wwwAuthenticate
                ?: throw GradleException("Roku device returned 401 but no WWW-Authenticate header")

            val digestParams = parseDigestChallenge(authHeader)
            val authorizationHeader = computeDigestAuth(
                username = "rokudev",
                password = password,
                method = "POST",
                uri = "/plugin_install",
                params = digestParams
            )

            // Second request with Digest auth
            val result = sendRequest(url, file, authorizationHeader)
            handleResponse(result)
        } else {
            handleResponse(challengeResponse)
        }
    }

    private data class HttpResponse(
        val code: Int,
        val body: String,
        val wwwAuthenticate: String?
    )

    private fun sendRequest(url: String, file: java.io.File, authHeader: String?): HttpResponse {
        val boundary = "----RokuInstall${System.currentTimeMillis()}"
        val connection = URL(url).openConnection() as HttpURLConnection

        connection.apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            authHeader?.let { setRequestProperty("Authorization", it) }
            connectTimeout = 30000
            readTimeout = 60000
        }

        try {
            DataOutputStream(connection.outputStream).use { out ->
                // Field: mysubmit=Install
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"mysubmit\"\r\n\r\n")
                out.writeBytes("Install\r\n")

                // Field: archive (the zip file)
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"archive\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: application/zip\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n")

                // Field: passwd (empty)
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"passwd\"\r\n\r\n")
                out.writeBytes("\r\n")

                out.writeBytes("--$boundary--\r\n")
            }

            val responseCode = connection.responseCode
            val wwwAuth = connection.getHeaderField("WWW-Authenticate")
            val body = try {
                if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                }
            } catch (e: Exception) {
                ""
            }

            return HttpResponse(responseCode, body, wwwAuth)
        } finally {
            connection.disconnect()
        }
    }

    private fun handleResponse(response: HttpResponse) {
        // Parse Roku's response for actual status messages
        val messages = parseRokuMessages(response.body)
        val errors = messages.filter { it.type == "error" }
        val successes = messages.filter { it.type == "success" }

        when {
            response.code == 401 -> {
                throw GradleException("Authentication failed. Check your device password.")
            }
            errors.isNotEmpty() -> {
                val errorText = errors.joinToString("\n") { it.text }
                throw GradleException("Roku install failed:\n$errorText")
            }
            successes.any { it.text.contains("Install Success", ignoreCase = true) } -> {
                logger.lifecycle("Successfully installed to Roku device")
            }
            response.code in 200..299 || successes.isNotEmpty() -> {
                successes.forEach { logger.lifecycle(it.text) }
                logger.lifecycle("Roku responded with status ${response.code}")
            }
            else -> {
                throw GradleException("Failed to install: HTTP ${response.code}\n${response.body.take(500)}")
            }
        }
    }

    private data class RokuMessage(val text: String, val type: String)

    private fun parseRokuMessages(body: String): List<RokuMessage> {
        // Roku embeds messages as JSON in the HTML response
        // Look for: JSON.parse('{"messages":[...]}')
        val jsonPattern = """JSON\.parse\('(\{.*?"messages":\[.*?\]\})'\)""".toRegex()
        val match = jsonPattern.find(body) ?: return emptyList()

        val json = match.groupValues[1]
            .replace("\\'", "'")
            .replace("\\n", "\n")

        // Simple parsing of the messages array
        val messagesPattern = """"text":"([^"]+)"[^}]*"type":"([^"]+)"""".toRegex()
        return messagesPattern.findAll(json).map { m ->
            RokuMessage(
                text = m.groupValues[1].replace("\\n", "\n"),
                type = m.groupValues[2]
            )
        }.toList()
    }

    private fun parseDigestChallenge(header: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val pattern = """(\w+)=(?:"([^"]+)"|([^,\s]+))""".toRegex()
        pattern.findAll(header).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            params[key] = value
        }
        return params
    }

    private fun computeDigestAuth(
        username: String,
        password: String,
        method: String,
        uri: String,
        params: Map<String, String>
    ): String {
        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val qop = params["qop"]
        val opaque = params["opaque"]

        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")

        val nc = "00000001"
        val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        val response = if (qop != null) {
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        val parts = mutableListOf(
            """username="$username"""",
            """realm="$realm"""",
            """nonce="$nonce"""",
            """uri="$uri"""",
            """response="$response""""
        )

        if (qop != null) {
            parts.add("""qop=$qop""")
            parts.add("""nc=$nc""")
            parts.add("""cnonce="$cnonce"""")
        }
        opaque?.let { parts.add("""opaque="$it"""") }

        return "Digest ${parts.joinToString(", ")}"
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
