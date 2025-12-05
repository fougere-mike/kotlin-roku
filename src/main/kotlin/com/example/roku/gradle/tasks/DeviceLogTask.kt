package com.example.roku.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.net.Socket

@UntrackedTask(because = "Streams live output from device")
abstract class DeviceLogTask : DefaultTask() {

    @get:Input
    abstract val deviceIp: Property<String>

    @TaskAction
    fun tailLogs() {
        val ip = deviceIp.get()

        if (ip.isBlank()) {
            throw GradleException("Device IP not configured. Set roku.deviceIp in local.properties or ROKU_DEVICE_IP environment variable.")
        }

        logger.lifecycle("Connecting to Roku debug console at $ip:8085...")
        logger.lifecycle("Press Ctrl+C to stop")

        Socket(ip, 8085).use { socket ->
            socket.getInputStream().bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    println(line)
                }
            }
        }
    }
}
