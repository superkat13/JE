plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pineapple.sageos2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pineapple.sagecommander.stable"
        minSdk = 26
        targetSdk = 35
        versionCode = 200
        versionName = "2.0.0"

        testInstrumentationRunner = "android.app.Instrumentation"
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
