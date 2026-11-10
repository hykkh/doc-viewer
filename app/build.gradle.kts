import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Play upload key lives outside the repo (C:\H-Secrets\play). Without it the play build is debug-signed (local test only).
val uploadProps = Properties().apply {
    val f = file("C:/H-Secrets/play/upload.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hprograms.docviewer"
    compileSdk = 36

    defaultConfig {
        // The LibreOffice fontconfig cache path in assets/unpack/etc/fonts/fonts.conf
        // is hard-wired to this id, so debug builds must not add a suffix.
        applicationId = "com.hprograms.docviewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.5.7"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // direct = the APK on GitHub (self-update, all-files access), unchanged.
    // play   = Google Play closed testing: no self-update, no all-files access, Play's target API.
    signingConfigs {
        if (uploadProps.getProperty("storeFile") != null) create("upload") {
            storeFile = file(uploadProps.getProperty("storeFile"))
            storePassword = uploadProps.getProperty("storePassword")
            keyAlias = uploadProps.getProperty("keyAlias")
            keyPassword = uploadProps.getProperty("keyPassword")
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("direct") {
            dimension = "store"
            buildConfigField("boolean", "STORE", "false")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("play") {
            dimension = "store"
            targetSdk = 36
            buildConfigField("boolean", "STORE", "true")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin", "src/main/java")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signing comes from the flavor: direct = debug key (as before), play = upload key.
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
        buildConfig = true
    }

    ndkVersion = "27.1.12297006"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            // liblo-native-code.so is ~196MB; store it compressed in the APK and
            // let the installer extract it, instead of shipping it page-aligned raw.
            useLegacyPackaging = true
        }
    }

    androidResources {
        // rhwp ships a .wasm that WebView streams; keep it uncompressed.
        noCompress += listOf("wasm")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)   // approval screen (h-license/android-template)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    // Text recognition of scanned PDFs, on the phone (bundled Korean+Latin model, no network). Ocr.kt
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
