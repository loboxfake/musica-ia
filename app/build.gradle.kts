plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val bardoVersionCode = project.findProperty("bardoVersionCode")?.toString()?.toIntOrNull() ?: 1

android {
    namespace = "com.musicaia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.musicaia"
        minSdk = 26
        targetSdk = 34
        versionCode = bardoVersionCode
        versionName = "1.0.$bardoVersionCode"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (project.findProperty("withAudioNative") == "true") {
            ndk { abiFilters += "arm64-v8a" }
            val etSrcPath = project.findProperty("etSrcPath")?.toString()
            if (!etSrcPath.isNullOrBlank()) {
                externalNativeBuild {
                    cmake { arguments += listOf("-DET_SRC_PATH=$etSrcPath") }
                }
            }
        }
    }


    if (project.findProperty("withAudioNative") == "true") {
        ndkVersion = "27.2.12479018"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
        androidResources {
            noCompress += "pte"
            noCompress += "model"
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

    packaging {
        jniLibs {
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/libtensorflowlite_jni.so",
                "**/libtensorflowlite_gpu_jni.so"
            )
        }
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
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // LiteRT-LM 0.16.1 (Ruta A) — coordinación exacta a verificar al compilar
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    // LiteRT Interpreter + XNNPACK para generación de imágenes local Bonsai.
    implementation("com.google.ai.edge.litert:litert:2.1.3")
    // Extracción local de texto PDF; no requiere red en tiempo de ejecución.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // OCR offline empaquetado para los scripts latino, chino, devanagari, japonés y coreano.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // Clasificación visual general offline con modelo integrado.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    // ASR local; el modelo español es opcional y se instala con autorización del usuario.
    implementation("com.alphacephei:vosk-android:0.3.75")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}