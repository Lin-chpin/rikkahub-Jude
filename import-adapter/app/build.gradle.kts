import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "me.rerere.rikkahub.importadapter"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.rerere.rikkahub.importadapter"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val properties = Properties()
            val propertiesFile = rootProject.file("local.properties")
            if (propertiesFile.exists()) {
                FileInputStream(propertiesFile).use(properties::load)
            }

            val storeFilePath = properties.getProperty("adapter.storeFile")
                ?: System.getenv("ADAPTER_STORE_FILE")
            val storePasswordValue = properties.getProperty("adapter.storePassword")
                ?: System.getenv("ADAPTER_STORE_PASSWORD")
            val keyAliasValue = properties.getProperty("adapter.keyAlias")
                ?: System.getenv("ADAPTER_KEY_ALIAS")
            val keyPasswordValue = properties.getProperty("adapter.keyPassword")
                ?: System.getenv("ADAPTER_KEY_PASSWORD")
            if (storeFilePath != null && storePasswordValue != null &&
                keyAliasValue != null && keyPasswordValue != null
            ) {
                storeFile = file(storeFilePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
