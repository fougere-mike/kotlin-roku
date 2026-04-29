pluginManagement {
    repositories {
        mavenLocal()
        maven {
            name = "nuvyyo"
            url = uri("https://maven.pkg.github.com/nuvyyo/maven-brs")
            credentials {
                username = providers.gradleProperty("nuvyyoGitHubUser").orNull
                password = providers.gradleProperty("nuvyyoGitHubToken").orNull
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven {
            name = "nuvyyo"
            url = uri("https://maven.pkg.github.com/nuvyyo/maven-brs")
            credentials {
                username = providers.gradleProperty("nuvyyoGitHubUser").orNull
                password = providers.gradleProperty("nuvyyoGitHubToken").orNull
            }
        }
        mavenCentral()
    }
}

rootProject.name = "kotlin-roku"
