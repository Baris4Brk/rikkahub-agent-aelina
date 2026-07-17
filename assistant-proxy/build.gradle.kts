import org.gradle.api.tasks.testing.Test
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "me.rerere.rikkahub.assistantproxy"
    compileSdk = 37

    defaultConfig {
        applicationId = "android.voiceinteraction.service"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-probe"
    }

    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            load(FileInputStream(localPropertiesFile))
        }
    }

    signingConfigs {
        val configuredDebugStore = localProperties.getProperty("debugStoreFile")
            ?: System.getenv("RIKKAHUB_DEBUG_KEYSTORE")
        val debugStoreFile = configuredDebugStore?.let(::file)
            ?: file("${System.getProperty("user.home")}/.android/debug.keystore")
        if (debugStoreFile.isFile) {
            create("stableDebug") {
                storeFile = debugStoreFile
                storePassword = localProperties.getProperty("debugStorePassword", "android")
                keyAlias = localProperties.getProperty("debugKeyAlias", "androiddebugkey")
                keyPassword = localProperties.getProperty("debugKeyPassword", "android")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("stableDebug")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("assistantProxy.projectDir", project.projectDir.absolutePath)
}

dependencies {
    testImplementation(libs.junit)
}
