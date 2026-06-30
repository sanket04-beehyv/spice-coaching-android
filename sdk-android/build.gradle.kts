plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("maven-publish")
}

// sherpa-onnx Android .aar lives in :sdk-android-sherpa, which explodes it into
// classes.jar + jniLibs so AGP doesn't trip on local-.aar-in-library
// restrictions. :sdk-android stays sherpa-free; hosts opt in by including
// :sdk-android-sherpa and calling Builder.offlineSttEngineFactory(SherpaOnnxStt.factory).

android {
    namespace = "com.medtroniclabs.microcoaching"
    compileSdk = 36

    defaultConfig {
        // Match SPICE 2.0's minSdk so manifest merger doesn't complain. The
        // mediapipe-genai dependency declares minSdk 24, so the SDK manifest
        // adds tools:overrideLibrary for it — safe because EdgeInference /
        // ModelManager gate every mediapipe call behind a runtime API check.
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "SDK_VERSION", "\"0.5.0-SNAPSHOT\"")
        // HF_TOKEN intentionally NOT baked into the SDK. The published .aar must
        // not ship a HuggingFace token; host apps that need one pass it at runtime
        // via MicroCoachingSDK.Builder.huggingFaceToken(). Models in the public
        // `litert-community` HF org download without auth.

        ndk {
            abiFilters += "arm64-v8a"
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
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Prevent ONNX/model files from being compressed in the APK
    androidResources {
        noCompress += listOf("bin", "onnx", "tflite", "task")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
        jniLibs {
            keepDebugSymbols += "*/arm64-v8a/*.so"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.medtroniclabs.microcoaching"
                artifactId = "sdk-android"
                version = "0.5.0-SNAPSHOT"
            }
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material) // BottomSheetDialogFragment + Material widgets
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.startup)

    // Markdown parsing for module card body content. Custom Compose renderer
    // lives in com.medtroniclabs.microcoaching.ui.markdown.
    implementation("org.jetbrains:markdown:0.7.3")

    // Coil — images in rich (TipTap) card bodies. Compose renderer lives in
    // com.medtroniclabs.microcoaching.ui.richtext.
    implementation(libs.coil.compose)

    // Media3 / ExoPlayer — fullscreen video playback for rich card bodies
    // (com.medtroniclabs.microcoaching.ui.video.VideoPlayerActivity).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // android-pdf-viewer (petretiandrea fork) — pdfium-android backed PDF
    // viewer with built-in scroll, pinch-zoom, and page-jump. Powers the
    // in-app source-document viewer at
    // com.medtroniclabs.microcoaching.ui.document.PdfPagerScreen.
    //
    // Why this fork over DImuthuUpe's original: the original AAR was built
    // pre-AndroidX and references `android.support.v4.util.ArrayMap` at
    // runtime in pdfium-android's `PdfDocument.<init>`. Excluding the legacy
    // support deps to dodge "Duplicate class" errors at d8 left the runtime
    // reference unresolved on hosts without Jetifier (NoClassDefFoundError).
    // The petretiandrea fork is AndroidX-native (`androidx.core:core-ktx`
    // etc.), Maven-Central-hosted (no JitPack required), keeps the same
    // `com.github.barteksc.pdfviewer.PDFView` API surface, and bundles its
    // own AndroidX-migrated pdfium fork. ~2 MB AAR (pdfium .so for all ABIs).
    implementation("io.github.petretiandrea:android-pdf-viewer:4.0.0")

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Note: Hilt is intentionally excluded from the SDK library.
    // SDKs should not force a DI framework on the host app.
    // SPICE can optionally add a Hilt provider binding for CoachingDataRepository
    // using MicroCoachingSDK.getInstance().dataRepository in its AppModule.

    // Room DB
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // OpenTelemetry
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.opentelemetry.exporter.logging)
    // Note: opentelemetry-semconv omitted for now — attribute keys are inlined as strings
    // Add back when a stable semconv release is available

    // MediaPipe Gemma 3 (on-device, the SDK's only LLM inference engine)
    implementation(libs.mediapipe.tasks.genai)

    // ML Kit on-device translation (EN→BN, ~20 MB language pack downloaded on demand)
    implementation(libs.mlkit.translate)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // Apache Commons Compress — BZip2 + Tar parsers for sherpa-onnx model
    // archives (.tar.bz2). Used only by SttModelDownloadWorker.
    implementation(libs.commons.compress)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
