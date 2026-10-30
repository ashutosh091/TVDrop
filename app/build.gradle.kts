import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keystore.properties in the project root (local, git-ignored) or SIGNING_* env vars (CI).
// Without either, release builds fall back to the debug key so anyone can still build.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    System.getenv("SIGNING_STORE_FILE")?.let {
        put("storeFile", it)
        put("storePassword", System.getenv("SIGNING_STORE_PASSWORD"))
        put("keyAlias", System.getenv("SIGNING_KEY_ALIAS"))
        put("keyPassword", System.getenv("SIGNING_KEY_PASSWORD"))
    }
}

android {
    namespace = "dev.ashutosh.tvdrop"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.ashutosh.tvdrop"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }
    signingConfigs {
        if (signing.containsKey("storeFile")) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
