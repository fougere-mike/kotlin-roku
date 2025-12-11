plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.255-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.1.255-SNAPSHOT")
    // IDE integration APIs for source set indexing
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-idea:2.1.255-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-tooling-core:2.1.255-SNAPSHOT")
}

gradlePlugin {
    plugins {
        create("kotlinRoku") {
            id = "com.example.kotlin-roku"
            implementationClass = "com.example.roku.gradle.RokuPlugin"
            displayName = "Kotlin Roku Plugin"
            description = "Compile Kotlin to BrightScript and package Roku apps"
        }
    }
}
