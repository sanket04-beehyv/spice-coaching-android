import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Read dev secrets from local.properties — never commit credentials to source control
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "com.medtroniclabs.microcoaching.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.medtroniclabs.microcoaching.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Dev secrets injected from local.properties (gitignored — never commit real values).
        // See local.properties.example for setup instructions.
        buildConfigField("String", "HF_TOKEN",           "\"${localProps.getProperty("HF_TOKEN", "")}\"")
        buildConfigField("String", "OTEL_ENDPOINT",      "\"${localProps.getProperty("OTEL_ENDPOINT", "")}\"")
        buildConfigField("String", "OTEL_TOKEN",         "\"${localProps.getProperty("OTEL_TOKEN", "")}\"")
        buildConfigField("String", "COACHING_BACKEND_URL",
            "\"${localProps.getProperty("COACHING_BACKEND_URL", "https://api.microcoaching.example.com")}\"")
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
}

dependencies {
    // SDK under test
    implementation(project(":sdk-android"))

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
