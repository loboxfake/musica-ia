plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.musicaia"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.musicaia"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (project.findProperty("withAudioNative") == "true") {
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "debug123"
            keyAlias = "debugkey"
            keyPassword = "debug123"
        }
    }

    if (project.findProperty("withAudioNative") == "true") {
        ndkVersion = "27.2.12479018"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
                val etSrcPath = project.findProperty("etSrcPath")?.toString()
                if (!etSrcPath.isNullOrBlank()) {
                    arguments += listOf("-DET_SRC_PATH=$etSrcPath")
                }
            }
        }
        androidResources {
            noCompress += "pte"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // LiteRT-LM 0.16.1 (Ruta A) — coordinación exacta a verificar al compilar
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}