import java.net.URL

plugins {
    // AGP 9.1 auto-applies the Kotlin Android plugin when it detects .kt
    // sources, so we don't need to list it explicitly here — and the
    // `kotlin.compose` plugin can't be applied to a module without a
    // Compose runtime, so we omit that too (this module is pure non-UI Kotlin).
    alias(libs.plugins.android.library)
    id("maven-publish")
}

// ── sherpa-onnx .aar — fetched + exploded into pieces this module can ship ────
//
// AGP refuses `implementation(files("libs/*.aar"))` from an Android library
// module ("the resulting AAR would be broken because classes from the local
// .aar wouldn't be packaged"). So instead we:
//
//   1. Download the .aar with `downloadSherpaAar`
//   2. Unpack it: classes.jar → libs/sherpa-onnx-classes.jar (compile dep);
//      jni/arm64-v8a/*.so → src/main/jniLibs/arm64-v8a/ (packaged into AAR)
//
// Result: a clean Android library module that compiles against sherpa's
// Kotlin classes AND packages the native libraries into the resulting AAR
// for SPICE to consume.
val sherpaOnnxVersion = "1.13.2"
val sherpaOnnxAarName = "sherpa-onnx-$sherpaOnnxVersion.aar"
val sherpaOnnxAar = file("$projectDir/libs/$sherpaOnnxAarName")
val sherpaOnnxClassesJar = file("$projectDir/libs/sherpa-onnx-classes.jar")
val sherpaOnnxJniBaseDir = file("$projectDir/src/main/jniLibs")
// Ship both ABIs in the AAR: arm64-v8a (field devices + Apple-Silicon emulators)
// and x86_64 (Intel/Windows dev emulators). The CONSUMING app decides what actually
// lands in the final APK (via abiFilters or ABI splits), so an arm64-only split
// strips x86_64 and production size is unaffected.
val sherpaOnnxAbis = listOf("arm64-v8a", "x86_64")

tasks.register("downloadSherpaAar") {
    description = "Downloads $sherpaOnnxAarName from the k2-fsa GitHub release."
    group = "build setup"
    outputs.file(sherpaOnnxAar)
    doLast {
        if (sherpaOnnxAar.exists()) {
            logger.lifecycle("[sherpa] $sherpaOnnxAarName already present — skipping download.")
            return@doLast
        }
        sherpaOnnxAar.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "v$sherpaOnnxVersion/$sherpaOnnxAarName"
        logger.lifecycle("[sherpa] fetching $url → ${sherpaOnnxAar.absolutePath}")
        URL(url).openStream().use { input ->
            sherpaOnnxAar.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("[sherpa] downloaded ${sherpaOnnxAar.length() / 1_048_576} MB.")
    }
}

tasks.register("unpackSherpaAar") {
    description = "Extracts classes.jar + ${sherpaOnnxAbis.joinToString("/")} *.so from the .aar."
    group = "build setup"
    dependsOn("downloadSherpaAar")
    outputs.file(sherpaOnnxClassesJar)
    outputs.dir(sherpaOnnxJniBaseDir)
    doLast {
        val classesUpToDate = sherpaOnnxClassesJar.exists() &&
            sherpaOnnxClassesJar.lastModified() >= sherpaOnnxAar.lastModified()
        val jniUpToDate = sherpaOnnxAbis.all { abi ->
            file("$sherpaOnnxJniBaseDir/$abi/libsherpa-onnx-jni.so").exists() &&
                file("$sherpaOnnxJniBaseDir/$abi/libonnxruntime.so").exists()
        }
        if (classesUpToDate && jniUpToDate) {
            logger.lifecycle("[sherpa] unpacked artifacts already present — skipping.")
            return@doLast
        }
        val workDir = layout.buildDirectory.dir("sherpa-extract").get().asFile
        workDir.deleteRecursively()
        workDir.mkdirs()
        copy {
            from(zipTree(sherpaOnnxAar))
            into(workDir)
        }
        copy {
            from(file("$workDir/classes.jar"))
            into(sherpaOnnxClassesJar.parentFile)
            rename { "sherpa-onnx-classes.jar" }
        }
        sherpaOnnxAbis.forEach { abi ->
            val abiDir = file("$sherpaOnnxJniBaseDir/$abi")
            abiDir.mkdirs()
            copy {
                from(file("$workDir/jni/$abi")) { include("*.so") }
                into(abiDir)
            }
            logger.lifecycle("[sherpa] unpacked ${abiDir.listFiles()?.size ?: 0} .so files into jniLibs/$abi.")
        }
    }
}

afterEvaluate {
    tasks.named("preBuild").configure {
        dependsOn("unpackSherpaAar")
    }
}

android {
    namespace = "com.medtroniclabs.microcoaching.sherpa"
    compileSdk = 36

    defaultConfig {
        // sherpa-onnx itself declares minSdk 21 in its bundled .aar manifest,
        // so we can match SPICE's minSdk 23 without any override.
        minSdk = 23
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        noCompress += listOf("onnx")
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
            // The Kotlin bindings (com.k2fsa.sherpa.onnx.*) load only
            // libsherpa-onnx-jni.so. The standalone C / C++ API libs (~4.5 MB)
            // are for native consumers we don't have — drop them.
            excludes += "**/libsherpa-onnx-c-api.so"
            excludes += "**/libsherpa-onnx-cxx-api.so"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.medtroniclabs.microcoaching"
                artifactId = "sdk-android-sherpa"
                version = "0.4.0-SNAPSHOT"
            }
        }
    }
}

dependencies {
    implementation(project(":sdk-android"))
    // sherpa-onnx Kotlin classes from the exploded .aar. The native .so files
    // ship via src/main/jniLibs/ and are packaged automatically.
    implementation(files(sherpaOnnxClassesJar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
