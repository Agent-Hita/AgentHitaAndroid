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

// Local dev checks this repo out as a true sibling of HitaSafetySDK
// (.../AgentHita/{AgentHitaAndroid,HitaSafetySDK}). CI's checkout action
// refuses to place a second checkout outside this repo's own directory tree,
// so the workflow instead checks the SDK out to a nested path here — this
// picks whichever one actually exists.
val sdkPath = file("../HitaSafetySDK").takeIf { it.exists() } ?: file("HitaSafetySDK-sibling")

includeBuild(sdkPath) {
    dependencySubstitution {
        substitute(module("com.agenthita.sdk:HitaSafetySDK-android")).using(project(":"))
        substitute(module("com.agenthita.sdk:android-gemma-classifier")).using(project(":android-gemma-classifier"))
    }
}
