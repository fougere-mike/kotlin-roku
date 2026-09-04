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

dependencies {
    implementation("com.nuvyyo:kotlin-gradle-plugin-brs:2.2.20-brs.1")
    implementation("com.nuvyyo:kotlin-gradle-plugin-api-brs:2.2.20-brs.1")
    // IDE integration APIs for source set indexing
    implementation("com.nuvyyo:kotlin-gradle-plugin-idea-brs:2.2.20-brs.1")
    implementation("com.nuvyyo:kotlin-tooling-core-brs:2.2.20-brs.1")

    testImplementation("junit:junit:4.13.2")
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

publishing {
    repositories {
        maven {
            name = "nuvyyo"
            url = uri("https://maven.pkg.github.com/nuvyyo/maven-brs")
            credentials {
                username = providers.gradleProperty("nuvyyoGitHubUser").orNull
                password = providers.gradleProperty("nuvyyoGitHubToken").orNull
            }
        }
    }
}
