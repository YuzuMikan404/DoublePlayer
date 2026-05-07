// アプリモジュールのビルド設定ファイル
plugins {
    // Android Applicationプラグインを適用
    alias(libs.plugins.android.application)
    // Kotlin Androidプラグインを適用
    alias(libs.plugins.kotlin.android)
    // Kotlin Composeコンパイラプラグインを適用
    alias(libs.plugins.kotlin.compose)
    // Hiltプラグインを適用
    alias(libs.plugins.hilt)
    // KSPプラグインを適用（Hiltのコード生成に必要）
    alias(libs.plugins.ksp)
}

android {
    // パッケージ名（アプリの識別子）
    namespace = "com.doubleplayer.app"
    // コンパイル対象のSDKバージョン
    compileSdk = 35

    defaultConfig {
        // アプリのパッケージ名
        applicationId = "com.doubleplayer.app"
        // 最小サポートSDK（Android 10）
        minSdk = 29
        // ターゲットSDK
        targetSdk = 35
        // バージョンコード（アップデート時に増やす）
        versionCode = 1
        // バージョン名（ユーザーに表示される）
        versionName = "1.0.0"
        // テストランナーの設定
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // リリースビルドの設定
        release {
            // コードの難読化・最適化を有効化
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // デバッグビルドの設定
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // Java 11でコンパイル
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        // Kotlin JVMターゲット（compilerOptionsへ移行）
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        // Jetpack Composeを有効化
        compose = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core)
    // DocumentFile（SAF経由のファイルアクセス・FileScanner.ktで使用する）
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle)
    implementation(libs.androidx.lifecycle.viewmodel)
    // collectAsStateWithLifecycle()を使うために必要
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    // Material Icons拡張パック（全アイコン含む）
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling.debug)

    // Media3（音声再生エンジン）
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // Hilt（依存注入）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // DataStore（設定・状態の永続化）
    implementation(libs.datastore.preferences)

    // Navigation Compose（画面遷移）
    implementation(libs.navigation.compose)

    // Glance（ホーム画面ウィジェット）
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Gson（JSON処理）
    implementation(libs.gson)

    // Coroutines（非同期処理）
    implementation(libs.kotlinx.coroutines)
}