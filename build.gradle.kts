plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.nuvyyo.brightscript"
version = "2.2.20-brs.1"

repositories {
    mavenLocal()
    mavenCentral()
}

// The KGP -brs POMs declare their transitive utility deps (kotlin-native-utils, etc.)
// as com.nuvyyo:* because the Kotlin/ fork sets group=com.nuvyyo globally. Those utilities
// were never published under com.nuvyyo; they live at org.jetbrains.kotlin:*:2.2.20.
// Redirect any com.nuvyyo dep whose artifactId has no -brs suffix to its upstream coord.
configurations.all {
    resolutionStrategy.dependencySubstitution {
        all {
            val req = requested
            if (req is ModuleComponentSelector &&
                req.group == "com.nuvyyo" &&
                !req.module.endsWith("-brs") &&
                !req.module.endsWith("-brs-runtime")
            ) {
                useTarget("org.jetbrains.kotlin:${req.module}:2.2.20")
            }
        }
    }
}

dependencies {
    implementation("com.nuvyyo:kotlin-gradle-plugin-brs:2.2.20-brs.1")
    implementation("com.nuvyyo:kotlin-gradle-plugin-api-brs:2.2.20-brs.1")
    // IDE integration APIs for source set indexing
    implementation("com.nuvyyo:kotlin-gradle-plugin-idea-brs:2.2.20-brs.1")
    implementation("com.nuvyyo:kotlin-tooling-core-brs:2.2.20-brs.1")
}

gradlePlugin {
    plugins {
        create("kotlinRoku") {
            id = "com.nuvyyo.brightscript.kotlin-roku"
            implementationClass = "com.example.roku.gradle.RokuPlugin"
            displayName = "BrightScript Kotlin Plugin"
            description = "Compile Kotlin to BrightScript and package Roku apps"
        }
    }
}
