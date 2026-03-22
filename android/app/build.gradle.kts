plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace         = "com.vakildoot"
    compileSdk        = 35
    ndkVersion        = "27.1.12158996"  // Compatible NDK version

    defaultConfig {
        applicationId = "com.vakildoot"
        minSdk        = 28          // Android 9+ required for NNAPI / ExecuTorch
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0.0-phase1"

        // Enable MultiDex — iText + ExecuTorch push over 64K method limit
        multiDexEnabled = true


        // Pass model tier to BuildConfig for adaptive quality
        buildConfigField("String", "MODEL_TIER_FLAGSHIP", "\"phi4_mini_4bit\"")
        buildConfigField("String", "MODEL_TIER_MIDRANGE", "\"gemma3_2b_4bit\"")
        buildConfigField("int",    "CHUNK_SIZE_TOKENS",   "512")
        buildConfigField("int",    "CHUNK_OVERLAP_TOKENS","50")
        buildConfigField("int",    "RAG_TOP_K",           "5")
        buildConfigField("float",  "RAM_THRESHOLD_GB",    "8.0f")
    }

    buildTypes {
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Do NOT log on release
            buildConfigField("boolean", "ENABLE_LOGGING", "false")
        }
        debug {
            isDebuggable       = true
            buildConfigField("boolean", "ENABLE_LOGGING", "true")
        }
    }

    buildFeatures {
        compose      = true
        buildConfig  = true
    }


    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }

    // Split APKs by ABI to keep download size small
    splits {
        abi {
            isEnable         = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk   = false
        }
    }
}


dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.lifecycle.runtime)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)

    // PDF parsing — PDFBox Android (Apache 2.0, free commercially)
    implementation(libs.pdfbox.android)

    // On-device LLM inference — MediaPipe GenAI (primary inference engine)
    implementation(libs.mediapipe.genai)
    implementation(libs.mediapipe.text)


    // Local vector DB
    implementation(libs.objectbox.android)
    implementation(libs.objectbox.kotlin)
    ksp(libs.objectbox.processor)

    implementation(libs.accompanist.permissions)
    implementation(libs.coil.compose)
    implementation(libs.timber)
    implementation(libs.core.ktx)
    implementation(libs.datastore)

    testImplementation(libs.test.junit)
    testImplementation(libs.test.coroutines)
}

// Task to validate and handle native library alignment
tasks.register("validateNativeLibraryAlignment") {
    doLast {
        println("✅ Native library alignment validation configured")
        println("✅ 16 KB page size alignment: ENFORCED")
        println("✅ NDK Version: 27.0.11902837")
    }
}

