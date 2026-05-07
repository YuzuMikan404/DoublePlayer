package com.doubleplayer.app.backup

// Androidコンテキスト関連のインポート
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
// Hilt依存注入のインポート
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
// Gson（JSON処理）のインポート
import com.google.gson.Gson
import com.google.gson.JsonParser
// SettingsStore経由で設定を読み書きするためのインポート
import com.doubleplayer.app.storage.SettingsStore
// コルーチン関連のインポート
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
// ファイル操作のインポート
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BackupManager - 設定のエクスポート・インポートを管理するクラス
 *
 * 仕様書セクション12「バックアップ・エクスポート仕様」に基づいて実装する。
 * - 設定JSONファイルをそのままエクスポートして共有・インポート可能
 * - エクスポート先：共有シート（他のアプリへの共有）
 * - インポート時はJSONのバリデーションを行い、エラー時は結果に含めて返す
 */
@Singleton
class BackupManager @Inject constructor(
    // アプリケーションコンテキスト（ContentResolver使用のため必要）
    @ApplicationContext private val context: Context,
    // 設定読み書き用のSettingsStore
    private val settingsStore: SettingsStore,
    // JSON変換用のGson
    private val gson: Gson
) {

    // ログ出力用タグ
    private val TAG = "BackupManager"

    // バックアップファイルのMIMEタイプ
    private val BACKUP_MIME_TYPE = "application/json"

    // バックアップファイルのデフォルトファイル名（日付付き）
    private fun generateBackupFileName(): String {
        // 現在の日時をファイル名に使用する（例: DoublePlayer_backup_20260505_0700.json）
        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN)
        return "DoublePlayer_backup_${sdf.format(Date())}.json"
    }

    /**
     * BackupResult - バックアップ操作の結果を表すシールドクラス
     */
    sealed class BackupResult {
        // エクスポート成功：共有するIntentを返す
        data class ExportSuccess(val shareIntent: Intent) : BackupResult()
        // インポート成功：インポートした設定のサマリーを返す
        data class ImportSuccess(val summary: String) : BackupResult()
        // 失敗：エラーメッセージを返す
        data class Failure(val errorMessage: String) : BackupResult()
    }

    /**
     * exportSettings - 現在の設定をJSON形式でエクスポートして共有シートを表示するメソッド
     *
     * 仕様書セクション12「設定JSONファイルをそのままエクスポートして共有」に対応する。
     * キャッシュフォルダにJSONファイルを書き出し、共有用のIntentを返す。
     *
     * @return BackupResult エクスポート結果（成功時はShareIntent、失敗時はエラーメッセージ）
     */
    suspend fun exportSettings(): BackupResult = withContext(Dispatchers.IO) {
        try {
            // 現在の設定をすべて取得してJSONオブジェクトを構築する
            val settingsJson = buildSettingsJson()

            // キャッシュフォルダに一時ファイルを作成する
            val cacheDir = context.cacheDir
            val backupFile = java.io.File(cacheDir, generateBackupFileName())

            // JSON文字列をファイルに書き出す
            OutputStreamWriter(backupFile.outputStream(), Charsets.UTF_8).use { writer ->
                writer.write(settingsJson)
            }

            // FileProviderを使ってURIを生成する（Android 7以上の要件）
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider", // AndroidManifest.xmlで定義するauthority
                backupFile
            )

            // 共有シートを開くIntentを作成する
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = BACKUP_MIME_TYPE                              // MIMEタイプを設定する
                putExtra(Intent.EXTRA_STREAM, fileUri)              // ファイルURIを添付する
                putExtra(Intent.EXTRA_SUBJECT, "DoublePlayer設定バックアップ") // メール件名（オプション）
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)     // 受け取り側に読み取り権限を付与する
            }

            // チューザー（アプリ選択画面）を経由して共有する
            val chooserIntent = Intent.createChooser(shareIntent, "設定をエクスポート")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // サービスから起動する場合に必要

            Log.i(TAG, "設定エクスポート成功: ${backupFile.name} (${backupFile.length()} bytes)")
            BackupResult.ExportSuccess(chooserIntent)

        } catch (e: Exception) {
            // エクスポート中にエラーが発生した場合はエラーメッセージを返す
            Log.e(TAG, "設定エクスポート中にエラーが発生しました", e)
            BackupResult.Failure("エクスポートに失敗しました: ${e.message}")
        }
    }

    /**
     * importSettings - 指定のURIのJSONファイルから設定をインポートするメソッド
     *
     * 仕様書セクション12「インポート時はJSONのバリデーションを行いエラー時は通知で知らせる」に対応する。
     *
     * @param uri インポートするJSONファイルのURI（ファイルピッカーで選択したURI）
     * @return BackupResult インポート結果（成功時はサマリー文字列、失敗時はエラーメッセージ）
     */
    suspend fun importSettings(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            // URIからJSON文字列を読み込む
            val jsonString = readJsonFromUri(uri)
                ?: return@withContext BackupResult.Failure("ファイルの読み込みに失敗しました")

            // JSONのバリデーションを実施する
            val validationResult = validateSettingsJson(jsonString)
            if (!validationResult.isValid) {
                // バリデーション失敗の場合はエラーメッセージを返す
                Log.w(TAG, "設定JSONのバリデーション失敗: ${validationResult.errorMessage}")
                return@withContext BackupResult.Failure(
                    "JSONファイルの形式が正しくありません: ${validationResult.errorMessage}"
                )
            }

            // バリデーション通過後に設定を保存する
            applySettingsFromJson(jsonString)

            // インポートしたデータのサマリーを生成して返す
            val summary = buildImportSummary(jsonString)
            Log.i(TAG, "設定インポート成功: $summary")
            BackupResult.ImportSuccess(summary)

        } catch (e: Exception) {
            // インポート中にエラーが発生した場合はエラーメッセージを返す
            Log.e(TAG, "設定インポート中にエラーが発生しました", e)
            BackupResult.Failure("インポートに失敗しました: ${e.message}")
        }
    }

    /**
     * buildSettingsJson - 現在の設定をJSONオブジェクト文字列に変換するプライベートメソッド
     *
     * SettingsStoreから全設定値を読み出してJSONを構築する。
     */
    private suspend fun buildSettingsJson(): String {
        // SettingsStoreからFlowで設定値を一度だけ取得する
        val trackAFolder = settingsStore.trackAFolder.first()
        val trackBFolder = settingsStore.trackBFolder.first()
        val trackAVolume = settingsStore.trackAVolume.first()
        val trackBVolume = settingsStore.trackBVolume.first()
        val playbackSpeed = settingsStore.playbackSpeed.first()
        val fadeInSeconds = settingsStore.fadeInSeconds.first()
        val fadeOutSeconds = settingsStore.fadeOutSeconds.first()
        val fadeOutBeforeEnd = settingsStore.fadeOutBeforeEnd.first()
        val linkedToTrackA = settingsStore.trackBLinked.first()
        val linkedRatio = settingsStore.trackBLinkedRatio.first()
        val scheduleConfig = settingsStore.getScheduleConfig()

        // 仕様書セクション4のJSON構造に合わせてオブジェクトを組み立てる
        val settingsMap = mapOf(
            "trackA" to mapOf(
                "folderPath" to trackAFolder,      // トラックAのフォルダパス
                "volume" to trackAVolume,          // トラックAの音量（0.0〜1.0）
                "playbackSpeed" to playbackSpeed,  // 再生速度（0.5〜2.0）
                "gapBetweenTracks" to 0            // トラック間のギャップ秒数
            ),
            "trackB" to mapOf(
                "folderPath" to trackBFolder,     // トラックBのフォルダパス
                "volume" to trackBVolume,         // トラックBの音量（0.0〜1.0）
                "linkedToTrackA" to linkedToTrackA, // トラックAに連動するかどうか
                "linkedRatio" to linkedRatio,     // 音量連動の比率
                "shuffle" to true                 // シャッフル再生（常にtrue）
            ),
            "fade" to mapOf(
                "fadeInSeconds" to fadeInSeconds,           // フェードインの秒数
                "fadeOutSeconds" to fadeOutSeconds,         // フェードアウトの秒数
                "fadeOutBeforeEndSeconds" to fadeOutBeforeEnd // 終了前フェードアウト開始秒数
            ),
            "schedule" to mapOf(
                "triggers" to scheduleConfig.triggers,         // トリガー設定リスト
                "activeDays" to scheduleConfig.activeDays,     // 有効曜日設定
                "skipHolidays" to scheduleConfig.skipHolidays, // 祝日スキップ設定
                "countdownSeconds" to scheduleConfig.countdownSeconds // カウントダウン秒数
            )
        )

        // GsonでJSONに変換して返す
        return gson.toJson(settingsMap)
    }

    /**
     * readJsonFromUri - URIからJSON文字列を読み込むプライベートメソッド
     *
     * ContentResolverを使ってURIのコンテンツを文字列として読み込む。
     *
     * @param uri 読み込み対象のURI
     * @return JSON文字列。読み込みに失敗した場合はnull
     */
    private fun readJsonFromUri(uri: Uri): String? {
        return try {
            // ContentResolverでURIを開いてストリームを取得する
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // BufferedReaderで行ごとに読み込んで文字列に結合する
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                    .readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "URIからの読み込みに失敗しました: $uri", e)
            null
        }
    }

    /**
     * ValidationResult - JSONバリデーションの結果を表すデータクラス
     */
    private data class ValidationResult(
        val isValid: Boolean,      // バリデーション通過したかどうか
        val errorMessage: String   // エラーメッセージ（成功時は空文字列）
    )

    /**
     * validateSettingsJson - 設定JSONの構造・型をバリデーションするプライベートメソッド
     *
     * 仕様書セクション12「インポート時はJSONのバリデーションを行い」に対応する。
     * 必須フィールドの存在確認と型チェックを行う。
     *
     * @param jsonString バリデーション対象のJSON文字列
     * @return ValidationResult バリデーション結果
     */
    private fun validateSettingsJson(jsonString: String): ValidationResult {
        return try {
            // まず有効なJSONかどうか確認する
            val jsonElement = JsonParser.parseString(jsonString)

            // JSONオブジェクトであることを確認する
            if (!jsonElement.isJsonObject) {
                return ValidationResult(false, "JSONオブジェクトではありません")
            }

            val jsonObject = jsonElement.asJsonObject

            // 必須フィールドの存在確認（trackA, trackBセクション）
            if (!jsonObject.has("trackA")) {
                return ValidationResult(false, "trackAセクションがありません")
            }
            if (!jsonObject.has("trackB")) {
                return ValidationResult(false, "trackBセクションがありません")
            }

            // trackAセクションの必須フィールドを確認する
            val trackA = jsonObject.getAsJsonObject("trackA")
            if (!trackA.has("volume")) {
                return ValidationResult(false, "trackA.volumeフィールドがありません")
            }

            // trackBセクションの必須フィールドを確認する
            val trackB = jsonObject.getAsJsonObject("trackB")
            if (!trackB.has("volume")) {
                return ValidationResult(false, "trackB.volumeフィールドがありません")
            }

            // 音量の値が0.0〜1.0の範囲内かどうか確認する
            val volumeA = trackA.get("volume").asDouble
            if (volumeA < 0.0 || volumeA > 1.0) {
                return ValidationResult(false, "trackA.volumeの値が範囲外です（0.0〜1.0）")
            }

            val volumeB = trackB.get("volume").asDouble
            if (volumeB < 0.0 || volumeB > 1.0) {
                return ValidationResult(false, "trackB.volumeの値が範囲外です（0.0〜1.0）")
            }

            // 再生速度フィールドがある場合は範囲チェックする
            if (trackA.has("playbackSpeed")) {
                val speed = trackA.get("playbackSpeed").asDouble
                if (speed < 0.5 || speed > 2.0) {
                    return ValidationResult(false, "playbackSpeedの値が範囲外です（0.5〜2.0）")
                }
            }

            // バリデーション通過
            ValidationResult(true, "")

        } catch (e: Exception) {
            // JSON解析そのものが失敗した場合
            ValidationResult(false, "JSONの解析に失敗しました: ${e.message}")
        }
    }

    /**
     * applySettingsFromJson - バリデーション済みのJSONから設定を適用するプライベートメソッド
     *
     * @param jsonString バリデーション済みのJSON文字列
     */
    private suspend fun applySettingsFromJson(jsonString: String) {
        val jsonObject = JsonParser.parseString(jsonString).asJsonObject

        // トラックA設定を適用する
        val trackA = jsonObject.getAsJsonObject("trackA")
        if (trackA.has("folderPath")) {
            settingsStore.saveTrackAFolder(trackA.get("folderPath").asString)
        }
        if (trackA.has("volume")) {
            settingsStore.saveTrackAVolume(trackA.get("volume").asFloat)
        }
        if (trackA.has("playbackSpeed")) {
            settingsStore.savePlaybackSpeed(trackA.get("playbackSpeed").asFloat)
        }

        // トラックB設定を適用する
        val trackB = jsonObject.getAsJsonObject("trackB")
        if (trackB.has("folderPath")) {
            settingsStore.saveTrackBFolder(trackB.get("folderPath").asString)
        }
        if (trackB.has("volume")) {
            settingsStore.saveTrackBVolume(trackB.get("volume").asFloat)
        }
        if (trackB.has("linkedToTrackA")) {
            settingsStore.saveTrackBLinkedToA(trackB.get("linkedToTrackA").asBoolean)
        }
        if (trackB.has("linkedRatio")) {
            settingsStore.saveLinkedRatio(trackB.get("linkedRatio").asFloat)
        }

        // フェード設定を適用する
        if (jsonObject.has("fade")) {
            val fade = jsonObject.getAsJsonObject("fade")
            if (fade.has("fadeInSeconds")) {
                settingsStore.saveFadeInSeconds(fade.get("fadeInSeconds").asFloat)
            }
            if (fade.has("fadeOutSeconds")) {
                settingsStore.saveFadeOutSeconds(fade.get("fadeOutSeconds").asFloat)
            }
            if (fade.has("fadeOutBeforeEndSeconds")) {
                settingsStore.saveFadeOutBeforeEndSeconds(fade.get("fadeOutBeforeEndSeconds").asFloat)
            }
        }

        Log.i(TAG, "JSONから設定を適用しました")
    }

    /**
     * buildImportSummary - インポート結果のサマリー文字列を生成するプライベートメソッド
     *
     * @param jsonString インポートしたJSON文字列
     * @return 人間が読みやすいサマリー文字列
     */
    private fun buildImportSummary(jsonString: String): String {
        val jsonObject = JsonParser.parseString(jsonString).asJsonObject
        val items = mutableListOf<String>()

        // 設定された項目を列挙してサマリーを作成する
        if (jsonObject.has("trackA")) {
            val trackA = jsonObject.getAsJsonObject("trackA")
            val folderPath = trackA.get("folderPath")?.asString ?: ""
            if (folderPath.isNotBlank()) {
                items.add("トラックAフォルダ")
            }
            items.add("トラックA音量: ${trackA.get("volume")?.asFloat}")
        }

        if (jsonObject.has("trackB")) {
            val trackB = jsonObject.getAsJsonObject("trackB")
            val folderPath = trackB.get("folderPath")?.asString ?: ""
            if (folderPath.isNotBlank()) {
                items.add("トラックBフォルダ")
            }
            items.add("トラックB音量: ${trackB.get("volume")?.asFloat}")
        }

        if (jsonObject.has("fade")) {
            items.add("フェード設定")
        }

        if (jsonObject.has("schedule")) {
            items.add("スケジュール設定")
        }

        // 項目が1件もない場合のフォールバック
        return if (items.isEmpty()) {
            "設定をインポートしました"
        } else {
            "インポート完了: ${items.joinToString("・")}"
        }
    }
}
