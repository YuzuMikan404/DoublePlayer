// Gradleの設定ファイル：プロジェクト名とリポジトリを定義する
pluginManagement {
    repositories {
        // Googleのリポジトリ（Android関連ライブラリ）
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Maven Centralリポジトリ
        mavenCentral()
        // Gradle Plugin Portalリポジトリ
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Googleのリポジトリ
        google()
        // Maven Centralリポジトリ
        mavenCentral()
    }
    // gradle/libs.versions.toml はGradleが自動検出するため明示的な指定は不要
}

// プロジェクト名
rootProject.name = "DoublePlayer"
// アプリモジュールを追加
include(":app")