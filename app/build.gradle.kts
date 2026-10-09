import java.util.Properties

/** إعدادات Supabase: من local.properties (غير مرفوع) أو متغيرات البيئة. مفاتيح عامة فقط (anon) — لا service key. */
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun cfg(key: String, env: String) = (localProps.getProperty(key) ?: System.getenv(env) ?: "").trim()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "com.zarinet.netcontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zarinet.netcontrol"
        minSdk = 24
        targetSdk = 35
        versionCode = 60
        versionName = "3.37.0"
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("supabase.url", "SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("supabase.anonKey", "SUPABASE_ANON_KEY")}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("mikroshamel-release.jks")
            storePassword = "MikroShamel2026"
            keyAlias = "mikroshamel"
            keyPassword = "MikroShamel2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // للتجربة: التوقيع بمفتاح debug حتى يمكن تثبيت نسخة release مباشرة
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
