import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing comes from keystore.properties in the project root (gitignored). Without it,
// release builds are left unsigned.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "dev.musicpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.musicpaper"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
        ndk {
            abiFilters += listOf("arm64-v8a") // 64-bit ARM phones only; keeps the LiteRT libraries small
        }
    }

    signingConfigs {
        if (!keystoreProps.getProperty("storeFile").isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures {
        buildConfig = true // BuildConfig.DEBUG keeps song details out of release logs
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        noCompress += "tflite" // the upscaler model is memory-mapped straight from the APK
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.gpu.api)
}
