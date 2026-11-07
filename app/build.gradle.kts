import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cfg(name: String, def: String = ""): String =
    (localProps.getProperty(name) ?: def).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "app.yoru.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.yoru.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "3.1.1"

        buildConfigField("String", "SHIKI_CLIENT_ID", "\"${cfg("shiki.clientId")}\"")
        buildConfigField("String", "SHIKI_CLIENT_SECRET", "\"${cfg("shiki.clientSecret")}\"")
        buildConfigField("String", "SHIKI_APP_NAME", "\"${cfg("shiki.appName", "Yoru TV")}\"")
        buildConfigField("String", "A365_SITE", "\"${cfg("a365.site", "https://smotret-anime.org")}\"")
        buildConfigField("String", "TMDB_TOKEN", "\"${cfg("tmdb.token")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подписываем отладочным ключом, чтобы релизный APK сразу ставился на телевизор.
            // Для публикации замените на свой signingConfig.
            signingConfig = signingConfigs.getByName("debug")
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

    lint {
        // Media3 помечает часть API как @UnstableApi — это не ошибка для нашего приложения
        disable += "UnsafeOptInUsageError"
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // устанавливает профиль базовой компиляции при первом запуске: код сразу работает скомпилированным
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
}
