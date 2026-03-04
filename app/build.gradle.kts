import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun localString(name: String, defaultValue: String): String {
    return localProperties.getProperty(name, defaultValue)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

fun localIntLiteral(name: String, defaultValue: Int): String {
    return localProperties.getProperty(name)?.toIntOrNull()?.toString() ?: defaultValue.toString()
}

fun localBooleanLiteral(name: String, defaultValue: Boolean): String {
    return when (localProperties.getProperty(name)?.trim()?.lowercase()) {
        "true" -> "true"
        "false" -> "false"
        else -> defaultValue.toString()
    }
}

android {
    namespace = "com.immersive.ui"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.immersive.ui"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "MOBILE_AGENT_BASE_URL",
            "\"${localString("MOBILE_AGENT_BASE_URL", "http://10.0.2.2:3000")}\""
        )
        buildConfigField(
            "String",
            "MOBILE_AGENT_MODE",
            "\"${localString("MOBILE_AGENT_MODE", "active")}\""
        )
        buildConfigField(
            "boolean",
            "MOBILE_AGENT_ENABLED",
            localBooleanLiteral("MOBILE_AGENT_ENABLED", true)
        )
        buildConfigField(
            "boolean",
            "MOBILE_AGENT_TELEMETRY_ENABLED",
            localBooleanLiteral("MOBILE_AGENT_TELEMETRY_ENABLED", true)
        )
        buildConfigField(
            "int",
            "MOBILE_AGENT_TIMEOUT_MS",
            localIntLiteral("MOBILE_AGENT_TIMEOUT_MS", 12000)
        )
        buildConfigField(
            "boolean",
            "EVENT_DRIVEN",
            localBooleanLiteral("EVENT_DRIVEN", true)
        )
        buildConfigField(
            "boolean",
            "SOM_CLOUD",
            localBooleanLiteral("SOM_CLOUD", true)
        )
        buildConfigField(
            "boolean",
            "OPEN_INTENT",
            localBooleanLiteral("OPEN_INTENT", true)
        )
        buildConfigField(
            "boolean",
            "UI_PRUNE",
            localBooleanLiteral("UI_PRUNE", true)
        )
        buildConfigField(
            "boolean",
            "VISUAL_DIFF",
            localBooleanLiteral("VISUAL_DIFF", true)
        )
        buildConfigField("int", "MAX_RETRY_ATTEMPTS", localIntLiteral("MAX_RETRY_ATTEMPTS", 3))
        buildConfigField(
            "boolean",
            "NEXT_PUBLIC_SHOW_DEV_PANEL",
            localBooleanLiteral("NEXT_PUBLIC_SHOW_DEV_PANEL", true)
        )
        buildConfigField(
            "boolean",
            "NEXT_PUBLIC_MOCK_MODE",
            localBooleanLiteral("NEXT_PUBLIC_MOCK_MODE", false)
        )
        // 灰度开关：是否在 observation 中发送 screenshot_base64 兼容字段
        // 服务端迁移完成后可关闭此开关，仅通过 GCS URI 引用截图
        buildConfigField(
            "boolean",
            "SEND_SCREENSHOT_BASE64",
            localBooleanLiteral("SEND_SCREENSHOT_BASE64", true)
        )
        // P0: Auth token for cloud API calls (prevents 401 in production)
        buildConfigField(
            "String",
            "MOBILE_AGENT_AUTH_TOKEN",
            "\"${localString("MOBILE_AGENT_AUTH_TOKEN", "")}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
