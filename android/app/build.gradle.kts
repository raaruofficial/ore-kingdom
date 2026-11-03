plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.raaru.orekingdom"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.raaru.orekingdom"
        minSdk = 28
        targetSdk = 35
        // v0.1.3 (the sealed WebView candidate) shipped versionCode 4;
        // the native flip client continues the lineage at 5 / 0.1.4.
        versionCode = 5
        versionName = "0.1.4"

        vectorDrawables { useSupportLibrary = true }

        // The forum host endpoint: the deployed production host. Staging
        // and rig builds override with -PHOST_URL=... or the HOST_URL env.
        val hostUrl = (project.findProperty("HOST_URL") as String?)
            ?: System.getenv("HOST_URL")
            ?: "https://orekingdom.137-184-143-128.sslip.io"
        buildConfigField("String", "HOST_URL", "\"$hostUrl\"")
    }

    // Release signing comes ONLY from the environment (CI secrets). Nothing
    // secret is ever committed — same discipline as the shell family. The
    // v0.1.3 signer-lineage continuity for this project is an open
    // question (NATIVE_CLIENT_CENSUS §4.4); until it settles, no keystore
    // is wired anywhere and CI builds debug only.
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
        // Same carve-out as the shell of record: the lifecycle lint detector
        // crashes inside lint itself on this lineage (IncompatibleClassChange
        // in NonNullableMutableLiveDataDetector). That one check only.
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
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Solana Mobile Wallet Adapter (official) — the estate-pinned clientlib.
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8")

    // Session storage at rest (Keystore-backed encrypted prefs).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
