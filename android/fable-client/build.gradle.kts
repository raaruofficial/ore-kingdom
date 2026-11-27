// Fable-built Ore Kingdom client (sessions 1-7, 2026-11-26) integrated
// as a standalone library module so the generated source set compiles
// on its own WITHOUT touching the existing :app module. Sources are
// copied in from the build output tree of record:
//   ~/workspace/goals/ore-kingdom-dapp-store-submission/files/fable-build/output
// Plugin versions match the root build file (AGP 8.7.3 / Kotlin 2.0.21).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.raaru.orekingdom.fableclient"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
    }

    lint {
        // Session 9: AGP 8.7 lint's NonNullableMutableLiveDataDetector
        // crashes with an IncompatibleClassChangeError on this source
        // set (CI run 45942408896) before producing any report. The
        // module uses no LiveData anywhere, so the detector has
        // nothing to check here; disabling it lets lint complete.
        disable += "NullSafeMutableLiveData"
        // Stage 4 (2026-11-26): the Compose lint detectors crash the
        // same way — IncompatibleClassChangeError inside
        // FrequentlyChangingValueDetector (via
        // RememberInCompositionDetectorKt helpers) and
        // ComposableFlowOperatorDetector (CI run 45958355987). Root
        // cause: the Compose BOM (2026.06.00) lint checks are built
        // against a far newer lint/UAST API than AGP 8.7.3's lint
        // 31.7.3 provides. Disabling the three crashing Compose
        // checks lets the rest of lint (Android + Kotlin detectors)
        // run and produce findings. The durable fix is aligning the
        // AGP/lint toolchain with the Compose BOM — a post-seal
        // decision (the seal pins AGP 8.7.3), recorded in
        // INTEGRATION_RUN_2026-11-26.md stage 4.
        disable += "FrequentlyChangingValue"
        // Issue id, not the detector class name (ComposableFlowOperatorDetector).
        disable += "FlowOperatorInvokedInComposition"
        disable += "RememberInComposition"
        // Stage 4, attempt 3 (final): the two targeted disables above
        // each exposed ANOTHER Compose detector crashing on the same
        // binary-incompatible UAST helpers (ComposableUtils /
        // ComposableBodyVisitor — CI 45959850273). The whole Compose
        // lint family from BOM 2026.06.00 is therefore disabled by
        // its documented issue ids (runtime, runtime-saveable, ui,
        // foundation registries); Android + Kotlin lint checks still
        // run and report. Re-enable family-wide when the AGP/lint
        // toolchain aligns with the BOM (post-seal decision).
        disable += setOf(
            // androidx.compose.runtime (runtime-lint)
            "AutoboxingStateCreation", "AutoboxingStateValueProperty",
            "ComposableLambdaParameterNaming", "ComposableLambdaParameterPosition",
            "ComposableNaming", "CompositionLocalNaming",
            "CoroutineCreationDuringComposition", "MutableCollectionMutableState",
            "OpaqueUnitKey", "ProduceStateDoesNotAssignValue",
            "RememberReturnType", "StateFlowValueCalledInComposition",
            "UnrememberedMutableState",
            // androidx.compose.runtime.saveable (runtime-saveable-lint)
            "RememberSaveableSaverParameter",
            // androidx.compose.ui (ui-lint)
            "UnnecessaryComposedModifier", "LocalContextConfigurationRead",
            "LocalContextGetResourceValueCall", "LocalContextResourcesRead",
            "ConfigurationScreenWidthHeight", "NonObservableLocale",
            "ModifierFactoryExtensionFunction", "ModifierFactoryReturnType",
            "ModifierFactoryUnreferencedReceiver", "ModifierNodeInspectableProperties",
            "ModifierParameter", "MultipleAwaitPointerEventScopes",
            "ReturnFromAwaitPointerEventScope", "SuspiciousCompositionLocalModifierRead",
            "SuspiciousModifierThen",
            // androidx.compose.foundation (foundation-lint)
            "UseOfNonLambdaOffsetOverload", "UnusedBoxWithConstraintsScope",
        )
        textReport = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Session 9 (Sonnet package): JVM unit tests for the logic-bearing
    // pieces (outbox, realtime ordering, entitlement store, pump copy).
    testImplementation("junit:junit:4.13.2")
}
