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
rootProject.name = "AgentHita"
include(":app")

includeBuild("../HitaSafetySDK") {
    dependencySubstitution {
        substitute(module("com.agenthita.sdk:HitaSafetySDK-android")).using(project(":"))
        substitute(module("com.agenthita.sdk:android-gemma-classifier")).using(project(":android-gemma-classifier"))
    }
}
