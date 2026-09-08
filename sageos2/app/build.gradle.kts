plugins {
    id("com.android.application")
}

android {
    namespace = "com.pineapple.sageos2"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.pineapple.sagecommander.stable"
        minSdk = 26
        targetSdk = 35
        versionCode = 206
        versionName = "2.0.0"

        testInstrumentationRunner = "android.app.Instrumentation"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    testImplementation("junit:junit:4.13.2")
}
