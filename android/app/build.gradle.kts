plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ar.enganchados"
    compileSdk = 35

    defaultConfig {
        applicationId = "ar.enganchados"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // youtubedl-android trae Python + yt-dlp + ffmpeg compilados para cada
    // arquitectura, ~45 MB cada una. Un APK universal pesaba 161 MB aunque
    // cada celular use una sola. Asi sale un APK por arquitectura: para un
    // telefono actual (arm64) se manda solo ese, de ~65 MB.
    //
    // OJO: esto reemplaza a ndk { abiFilters }. Si estan los dos, Gradle
    // falla con "Conflicting configuration".
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Los binarios empaquetados tienen que quedar extraibles: si se
        // comprimen, Python no los puede ejecutar en runtime.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val ytdl = "0.18.1"

    // yt-dlp + Python empaquetados. De aca salen la busqueda y la descarga.
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    // ffmpeg para extraer audio, normalizar y cruzar. Sin este modulo,
    // -x / --audio-format no funcionan.
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val compose = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // miniaturas de los resultados de busqueda
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Para el JSON de yt-dlp alcanza org.json, que ya viene en el SDK.
}
