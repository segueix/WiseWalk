plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 1. AFEGIM AIXÒ AQUÍ: LLEGIM LA CLAU DE GITHUB SECRETS
val mapsApiKey: String = System.getenv("MAPS_API_KEY") ?: "CLAU_BUIDA"

android {
    namespace = "com.wisewalk.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wisewalk.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "2.0"
        
        // 2. AFEGIM AIXÒ AQUÍ: INJECTEM LA CLAU A L'ANDROID MANIFEST
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    signingConfigs {
        create("customDebug") {
            storeFile = file("wisewalk-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("customDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.1.0")
}
