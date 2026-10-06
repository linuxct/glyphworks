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
        if (providers.gradleProperty("pipelineSdkArtifacts").orNull == "true") {
            maven { url = uri("build/pipeline-sdk/maven") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "glyphworks"
include(":app")
include(":pipeline-core", ":pipeline-android", ":pipeline-consumer")
include(":pipeline-android-consumer")
