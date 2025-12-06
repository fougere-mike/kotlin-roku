package com.example.roku.gradle

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Extension for configuring Roku test execution.
 *
 * Example usage in build.gradle.kts:
 * ```kotlin
 * rokuTest {
 *     timeout.set(600_000L)  // 10 minutes
 *     reportsDir.set(layout.buildDirectory.dir("test-results/roku"))
 *     ignoreFailures.set(false)
 * }
 * ```
 */
abstract class RokuTestExtension @Inject constructor(project: Project) {

    /**
     * Timeout for test execution in milliseconds.
     * Default: 300,000 (5 minutes)
     */
    abstract val timeout: Property<Long>

    /**
     * Directory for test result output files.
     * Default: build/test-results/roku
     */
    abstract val reportsDir: DirectoryProperty

    /**
     * Whether to continue the build if tests fail.
     * Default: false (build fails on test failure)
     */
    abstract val ignoreFailures: Property<Boolean>

    /**
     * Filter pattern for test classes/methods.
     * Default: "*" (run all tests)
     */
    abstract val filter: Property<String>

    init {
        timeout.convention(300_000L)
        reportsDir.convention(project.layout.buildDirectory.dir("test-results/roku"))
        ignoreFailures.convention(false)
        filter.convention("*")
    }
}
