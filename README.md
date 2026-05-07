# DoublePlayer

2トラック同時再生アプリ。トラックA（ファイル名昇順）とトラックB（BGMシャッフル）を同時に再生し、フェードイン/アウト、Bluetooth自動再生、スケジュール管理に対応する。

## 技術スタック

- Kotlin + Jetpack Compose + Material3
- ExoPlayer（Media3 1.4.0）による2トラック同時再生
- Hilt 2.51.1 による依存注入
- DataStore 1.1.1 による設定・再生状態の永続化
- Jetpack Glance 1.1.0 によるホーム画面ウィジェット

## 対応Android

- 最小SDK: 29（Android 10）
- ターゲットSDK: 35

## プロジェクト構成（全58ファイル・Kotlin約8,100行）

```
DoublePlayer_Phase3/
├── gradle/
│   └── libs.versions.toml          // バージョンカタログ（全ライブラリバージョン一元管理）
├── app/
│   ├── proguard-rules.pro           // リリースビルド難読化設定
│   └── src/main/
│       ├── AndroidManifest.xml      // パーミッション・コンポーネント登録
│       ├── assets/
│       │   └── default_settings.json
│       ├── res/
│       │   ├── mipmap-*/            // アプリアイコン（全密度 + アダプティブ対応）
│       │   └── values/
│       │       ├── strings.xml
│       │       ├── colors.xml
│       │       └── themes.xml
│       └── java/com/doubleplayer/app/
│           ├── MainActivity.kt
│           ├── DoublePlayerApplication.kt
│           ├── player/              // 再生エンジン（7ファイル）
│           ├── bluetooth/           // Bluetooth自動起動（1ファイル）
│           ├── schedule/            // スケジュール・祝日判定（3ファイル）
│           ├── storage/             // DataStore永続化（4ファイル）
│           ├── ui/                  // Compose UI・ViewModel（7ファイル）
│           ├── widget/              // ホーム画面ウィジェット（1ファイル）
│           ├── backup/              // 設定バックアップ（1ファイル）
│           └── di/                  // Hilt依存注入（1ファイル）
```

## Android Studioでのセットアップ手順

1. `DoublePlayer_Phase3` フォルダを Android Studio で開く
2. Gradle Sync を実行する
3. 実機（Android 10以上）に接続してビルド・実行する
4. 設定画面でトラックA・BのフォルダパスをAndroidのフルパスで入力する
   - 例: `/storage/emulated/0/Music/Morning`
5. Bluetooth接続時に自動再生を試す場合はスケジュール設定をONにする

## 変更履歴

| 日付 | 変更内容 | 担当ファイル |
|---|---|---|
| 2026-05-05 | 初版作成・仕様確定 | 全体 |
| 2026-05-06 | Phase1: 基盤セットアップ | build.gradle.kts, libs.versions.toml, Theme.kt, Color.kt, Type.kt, default_settings.json |
| 2026-05-06 | Phase2: 再生エンジン実装 | TrackAPlayer.kt, TrackBPlayer.kt, FadeController.kt, SpeedController.kt, EqualizerController.kt, PlayerService.kt, PlayerNotificationManager.kt |
| 2026-05-06 | Phase3: 自動化・スケジュール実装 | BluetoothReceiver.kt, ScheduleChecker.kt, HolidayChecker.kt, TriggerManager.kt, PlaybackStateStore.kt |
| 2026-05-06 | Phase4: ストレージ・ファイル管理実装 | SettingsStore.kt, FileScanner.kt, PlayHistoryStore.kt, BackupManager.kt |
| 2026-05-06 | Phase5: UI実装 | PlayerViewModel.kt, SettingsViewModel.kt, PlayerScreen.kt, SettingsScreen.kt, HistoryScreen.kt, MainActivity.kt, PlayerWidget.kt, AndroidManifest.xml |
| 2026-05-06 | Phase6: 整合性修正・仕上げ | PlayerViewModel.kt（startPlayback修正）, AndroidManifest.xml（BluetoothReceiver統合）, PlayerWidget.kt（Glance API修正）, build.gradle.kts（lifecycle-runtime-compose追加）, gradle/libs.versions.toml（新規作成）, settings.gradle.kts（バージョンカタログ参照追加）, proguard-rules.pro（新規作成）, mipmap/（アイコン全密度生成）, colors.xml（新規作成） |
