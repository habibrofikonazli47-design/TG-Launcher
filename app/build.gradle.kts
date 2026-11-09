import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { stream ->
        localProperties.load(stream)
    }
}
val rawServerUrl: String = localProperties.getProperty("tg.server.url") ?: "https://tg-launcher-admin-habibrofikonazli.vercel.app"
val rawServerApiKey: String = localProperties.getProperty("tg.server.api.key") ?: ""
val rawAiApiKey: String = localProperties.getProperty("tg.ai.api.key") ?: ""
val rawAiApiKey2: String = localProperties.getProperty("tg.ai.api.key2") ?: ""
val rawAiApiKey3: String = localProperties.getProperty("tg.ai.api.key3") ?: ""
val tgVersionCode: Int = System.getenv("TG_VERSION_CODE")?.toIntOrNull() ?: 18
val tgVersionName: String = System.getenv("TG_VERSION_NAME") ?: "1.1.6-tg1"
val tgSigningStoreFile: String? = System.getenv("TG_SIGNING_STORE_FILE")
val tgSigningStorePassword: String? = System.getenv("TG_SIGNING_STORE_PASSWORD")
val tgSigningKeyAlias: String? = System.getenv("TG_SIGNING_KEY_ALIAS")
val tgSigningKeyPassword: String? = System.getenv("TG_SIGNING_KEY_PASSWORD")
val hasStableSigning = listOf(tgSigningStoreFile, tgSigningStorePassword, tgSigningKeyAlias, tgSigningKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.israadev.nuxlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tg.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = tgVersionCode
        versionName = tgVersionName

        buildConfigField("String", "SERVER_BASE_URL", "\"$rawServerUrl\"")
        buildConfigField("String", "SERVER_API_KEY", "\"$rawServerApiKey\"")
        buildConfigField("String", "DEFAULT_AI_API_KEY", "\"$rawAiApiKey\"")
        buildConfigField("String", "DEFAULT_AI_API_KEY_2", "\"$rawAiApiKey2\"")
        buildConfigField("String", "DEFAULT_AI_API_KEY_3", "\"$rawAiApiKey3\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasStableSigning) {
            create("tgRelease") {
                storeFile = file(tgSigningStoreFile!!)
                storePassword = tgSigningStorePassword
                keyAlias = tgSigningKeyAlias
                keyPassword = tgSigningKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Use the persistent release key when configured. Debug signing is CI-only fallback;
            // it must never be published as an install-over-update release.
            signingConfig = if (hasStableSigning) signingConfigs.getByName("tgRelease") else signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.bytehook)
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation(libs.androidx.ui.tooling)
}
