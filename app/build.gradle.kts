import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.agenthita.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.agenthita.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "FEEDBACK_API_KEY",   "\"${secrets.getProperty("FEEDBACK_API_KEY",   "")}\"")
        buildConfigField("String", "FEEDBACK_API_URL",   "\"${secrets.getProperty("FEEDBACK_API_URL",   "https://api.agenthita.org/feedback")}\"")
        buildConfigField("String", "ALERT_API_URL",      "\"${secrets.getProperty("ALERT_API_URL",      "https://api.agenthita.org/alert")}\"")
        buildConfigField("String", "TELEMETRY_API_URL",  "\"${secrets.getProperty("TELEMETRY_API_URL",  "https://api.agenthita.org/telemetry")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
        jniLibs {
            useLegacyPackaging = false  // Required for 16KB page size alignment (Android 15+)
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room + SQLCipher (encrypted local storage)
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    kapt("androidx.room:room-compiler:2.7.0")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Encrypted SharedPreferences (consent state)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager (background guardian alert dispatch)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // JavaMail Android port (guardian email alerts)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // MediaPipe LLM Inference — Gemma on-device classifier (0.10.22+ required for Gemma 3)
    implementation("com.google.mediapipe:tasks-genai:0.10.22")

    // Lifecycle (repeatOnLifecycle for dashboard UI)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // RecyclerView (recent events list)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
}
