package com.example.roku.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Configuration for build-time package validation.
 *
 * Example usage in build.gradle.kts:
 * ```kotlin
 * rokuValidation {
 *     includeMode.set("strict")                  // fail the build on include holes
 *     extraBuiltins.addAll("MyGlobalHelper")     // allowlist project-specific globals
 * }
 * ```
 */
abstract class RokuValidationExtension {

    /**
     * How component `<script>` include validation reacts to findings:
     * - "warning" (default): log findings as build warnings, don't fail.
     * - "strict": fail the build on any finding.
     */
    abstract val includeMode: Property<String>

    /**
     * Additional bare global call names the validator should never flag,
     * merged with the built-in BrightScript allowlist (case-insensitive).
     */
    abstract val extraBuiltins: SetProperty<String>

    init {
        includeMode.convention("warning")
        extraBuiltins.convention(emptySet())
    }
}
