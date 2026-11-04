plugins {
    id("com.android.application")
}

// Different recovery candidates share version 219. Report the compiled source, not just that label.
val sourceRevision = runCatching {
    val revision = providers.exec {
        workingDir(rootProject.projectDir)
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim()
    check(revision.matches(Regex("[0-9a-f]{40}")))
    val changed = providers.exec {
        workingDir(rootProject.projectDir)
        commandLine("git", "status", "--porcelain", "--untracked-files=normal", "--", ".")
    }.standardOutput.asText.get().isNotBlank()
    revision + if (changed) "-dirty" else ""
}.getOrDefault("unknown")

android {
    namespace = "com.pineapple.sageos2"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.pineapple.sagecommander.stable"
        minSdk = 26
        targetSdk = 35
        versionCode = 219
        versionName = "2.0.0"
        buildConfigField("String", "SOURCE_REVISION", "\"$sourceRevision\"")

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

    // Native-model vocabulary tests must read the same merged assets shipped in the APK.
    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    testImplementation("org.robolectric:robolectric:4.14.1")
}
