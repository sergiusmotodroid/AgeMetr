plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.agemetr"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.agemetr"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    // Keep the offline model but avoid packaging native libraries for every ABI.
    // Android Studio/Gradle installs the matching APK for the emulator or a modern phone.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    // Bundled face detector is available offline immediately after install.
    implementation("com.google.mlkit:face-detection:16.1.7")
    // ONNX Runtime runs the MiVOLO v2 age and gender model locally.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
