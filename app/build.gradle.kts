plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.raaru.orekingdom"
    compileSdk = 35

    defaultConfig {
        // PROVISIONAL package name — for building only (owner commission,
        // 2026-10-27). It binds permanently only at portal registration,
        // which has NOT happened; the owner's confirmation is a tap-package
        // item. Do not treat this string as registered anywhere.
        applicationId = "com.raaru.orekingdom"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"

        // THE single named config point for the backend host: the actions
        // API base URL (see ForumConfig). Override at build time with the
        // ORE_KINGDOM_API_BASE environment variable. The default is the
        // emulator route to this workstation's locally staged backend — a
        // staging value, not a published endpoint.
        val apiBase = System.getenv("ORE_KINGDOM_API_BASE") ?: "http://10.0.2.2:8377"
        buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")

        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing comes ONLY from the environment (CI secrets or local
    // keystore.properties). Nothing secret is ever committed — see README.
    val keystoreFile: java.io.File? =
        System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed only when a keystore is provided (CI tag builds). Otherwise
            // falls back to the debug key — fine for local testing, NOT for the store.
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    lint {
        // The lifecycle lint detector crashes on this tree with an
        // IncompatibleClassChangeError inside lint itself (Kotlin analysis API
        // incompatibility, reproduced in CI run 39307990936 on MainActivity.kt).
        // Disable that one check only; every other lint check stays on.
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // No native Mobile Wallet Adapter dependency: the bundled forum client
    // carries @solana-mobile/wallet-standard-mobile itself and reaches the
    // Seeker wallet through solana: intents, which the shell passes through
    // (see ForumScreen). The shell holds no wallet session.

    debugImplementation("androidx.compose.ui:ui-tooling")
}
