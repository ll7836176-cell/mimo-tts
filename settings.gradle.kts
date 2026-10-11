pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MiMoTTS"

include(":app")
include(":core:tts-spi")
include(":core:rules")
include(":core:audio")
include(":data")
include(":provider:mimo")
