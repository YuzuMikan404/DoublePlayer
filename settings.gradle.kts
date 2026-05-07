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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Googleのリポジトリ
        google()
        // Maven Centralリポジトリ
        mavenCentral()
    }
    // バージョンカタログ（libs.versions.toml）を参照する設定
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}

// プロジェクト名
rootProject.name = "DoublePlayer"
// アプリモジュールを追加
include(":app")
