// プロジェクトレベルのビルド設定ファイル
plugins {
    // Android Applicationプラグイン（アプリモジュール用）
    alias(libs.plugins.android.application) apply false
    // Kotlin Androidプラグイン
    alias(libs.plugins.kotlin.android) apply false
    // Kotlin Composeコンパイラプラグイン
    alias(libs.plugins.kotlin.compose) apply false
    // Hilt依存注入プラグイン
    alias(libs.plugins.hilt) apply false
    // KSPアノテーションプロセッサプラグイン
    alias(libs.plugins.ksp) apply false
}
