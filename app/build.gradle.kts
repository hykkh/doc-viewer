plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.hprograms.docviewer"
    compileSdk = 34

    defaultConfig {
        // The LibreOffice fontconfig cache path in assets/unpack/etc/fonts/fonts.conf
        // is hard-wired to this id, so debug builds must not add a suffix.
        applicationId = "com.hprograms.docviewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.0"

        ndk {
            abiFilters += "arm64-v8a"
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
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
}
