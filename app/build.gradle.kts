import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.autoshot.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.autoshot.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 从 local.properties（优先）或 gradle.properties 读取，避免把密钥写进代码
        buildConfigField("String", "SUPABASE_URL", "\"${readSecret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${readSecret("SUPABASE_ANON_KEY")}\"")
        // 可选：填了就自动登录（行带 user_id，走 RLS 严格策略）；不填则用 anon key 直传
        buildConfigField("String", "SUPABASE_EMAIL", "\"${readSecret("SUPABASE_EMAIL")}\"")
        buildConfigField("String", "SUPABASE_PASSWORD", "\"${readSecret("SUPABASE_PASSWORD")}\"")
    }

    signingConfigs {
        create("release") {
            // 口令只从环境变量读取，绝不写进仓库
            val storePw = System.getenv("DOG_STORE_PASSWORD")
            val keyPw = System.getenv("DOG_KEY_PASSWORD") ?: storePw
            if (storePw != null) {
                storeFile = rootProject.file("keystore/dog-release.jks")
                storePassword = storePw
                keyAlias = System.getenv("DOG_KEY_ALIAS") ?: "dog"
                keyPassword = keyPw
            }
        }
    }

    buildTypes {
        debug {
            optimization {
                enable = false
            }
        }
        release {
            optimization {
                enable = true
            }
            // Release 只打 arm64（手机全都是），x86/x86_64 是模拟器用的，白占 ~19MB；debug 保持全 ABI 以便模拟器调试
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            val rel = signingConfigs.getByName("release")
            if (rel.storeFile?.exists() == true && rel.storePassword != null) {
                signingConfig = rel
            }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

fun readSecret(key: String): String {
    val props = Properties()
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        local.inputStream().use { props.load(it) }
    }
    val value = props.getProperty(key) ?: (project.findProperty(key) as String?)
    return (value ?: "").trim()
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // ---------- Compose ----------
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---------- 基础 ----------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // ---------- CameraX ----------
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    // ---------- Room ----------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // ---------- Paging ----------
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // ---------- 配置存储 ----------
    implementation(libs.androidx.datastore.preferences)

    // ---------- 后台任务 ----------
    implementation(libs.androidx.work.runtime)

    // ---------- 图片加载 ----------
    implementation(libs.coil.compose)

    // ---------- 网络 ----------
    implementation(libs.okhttp)

    // ---------- EXIF ----------
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
