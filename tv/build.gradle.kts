import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.atvremote.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.atvremote.tv"
        minSdk = 29
        // The box runs Android 10; installed as a system-uid app it never sees newer target restrictions.
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    // The box's firmware is signed with the public AOSP test platform key, so signing with it
    // lets this app run as android.uid.system and inject key events.
    signingConfigs {
        create("platform") {
            storeFile = rootProject.file("keys/platform.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs["platform"] }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs["platform"]
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { checkReleaseBuilds = false }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":common"))
}
