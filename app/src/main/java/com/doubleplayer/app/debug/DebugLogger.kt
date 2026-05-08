package com.doubleplayer.app.debug

// Android基本のインポート
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
// ファイル書き込みのインポート
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
// コルーチンのインポート
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// Hiltのインポート
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DebugLogger - アプリ全体のデバッグログを収集・保存・共有するシングルトンクラス
 *
 * このクラスが担当する責務：
 * - カテゴリ別ログのリアルタイム収集（再生状態・音量・フェード・エラー）
 * - 最大500件のログをメモリに保持してUIにリアルタイム表示
 * - ログをテキストファイルとして内部ストレージに保存
 * - 保存したファイルをAIへの共有（シェアシート）に渡す
 * - ANR・クラッシュ時に自動でログを書き出す（UncaughtExceptionHandlerと連携）
 *
 * 使い方：
 *   DebugLogger.log(DebugLogger.Category.PLAYBACK, "トラックA再生開始: foo.mp3")
 *   DebugLogger.error(DebugLogger.Category.ERROR, "ExoPlayer例外", exception)
 */
@Singleton
class DebugLogger @Inject constructor(
    @ApplicationContext private val context: Context  // ファイル保存・共有に使用
) {

    /**
     * ログのカテゴリ定義
     * AIがバグ分析しやすいようにカテゴリで色分けして表示する
     */
    enum class Category(
        val label: String,  // ログに付けるラベル文字列
        val emoji: String   // UIでの色分け用絵文字
    ) {
        // 再生状態の変化（再生開始・停止・スキップなど）
        PLAYBACK("PLAY", "▶"),
        // 音量・フェードの数値変化
        VOLUME("VOL", "🔊"),
        // ファイルスキャン・ロードの結果
        FILE("FILE", "📁"),
        // サービスのライフサイクル（onCreate・onDestroy など）
        SERVICE("SVC", "⚙"),
        // エラー・例外（スタックトレースも記録する）
        ERROR("ERR", "❌"),
        // Bluetooth・スケジュールのトリガー
        TRIGGER("TRG", "🔔"),
        // 一般情報（カテゴリに当てはまらないもの）
        INFO("INFO", "ℹ")
    }

    /**
     * DebugEntry - ログの1件分のデータクラス
     */
    data class DebugEntry(
        val timestamp: String,  // HH:mm:ss.SSS 形式のタイムスタンプ
        val category: Category, // ログのカテゴリ
        val message: String,    // ログメッセージ本文
        val stackTrace: String? = null  // エラー時のスタックトレース（省略可）
    ) {
        /**
         * AIへの送信・ファイル保存用のテキスト形式に変換するメソッド
         */
        fun toLogLine(): String {
            return if (stackTrace != null) {
                "[$timestamp][${category.label}] $message\n  $stackTrace"
            } else {
                "[$timestamp][${category.label}] $message"
            }
        }
    }

    // ========== ログのメモリ保持 ==========

    // スレッドセーフなリストでログを保持する（UIスレッドと再生スレッドから同時書き込みあり）
    private val logList = CopyOnWriteArrayList<DebugEntry>()

    // ログの最大保持件数
    private val MAX_LOG_SIZE = 500

    // UIにリアルタイム表示するためのStateFlow
    private val _logs = MutableStateFlow<List<DebugEntry>>(emptyList())
    val logs: StateFlow<List<DebugEntry>> = _logs.asStateFlow()

    // ログが何件あるかのカウンター（バッジ表示用）
    private val _logCount = MutableStateFlow(0)
    val logCount: StateFlow<Int> = _logCount.asStateFlow()

    // ========== 時刻フォーマット ==========

    // タイムスタンプのフォーマッター
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN)

    // ファイル名用の日付フォーマッター
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN)

    // ========== ログ記録メソッド ==========

    /**
     * 通常ログを記録するメソッド
     * アプリ全体のあらゆる場所から呼ぶ
     *
     * @param category ログのカテゴリ（Category enum）
     * @param message ログメッセージ
     */
    fun log(category: Category, message: String) {
        // エントリを生成する
        val entry = DebugEntry(
            timestamp = timeFormat.format(Date()),
            category = category,
            message = message
        )
        // リストに追加してサイズ制限を適用する
        addEntry(entry)
        // Android LogcatにもTagつきで出力する（Android Studioでも見られるようにする）
        android.util.Log.d("DoublePlayer_${category.label}", message)
    }

    /**
     * エラーログを記録するメソッド
     * Throwableを受け取りスタックトレースも保存する
     *
     * @param category ログのカテゴリ（基本的にCategory.ERROR）
     * @param message エラーの説明メッセージ
     * @param throwable 例外オブジェクト（省略可）
     */
    fun error(category: Category, message: String, throwable: Throwable? = null) {
        // スタックトレースを文字列に変換する
        val stack = throwable?.let {
            "${it.javaClass.simpleName}: ${it.message}\n" +
            it.stackTrace.take(8).joinToString("\n") { frame -> "    at $frame" }
        }
        // エントリを生成する
        val entry = DebugEntry(
            timestamp = timeFormat.format(Date()),
            category = category,
            message = message,
            stackTrace = stack
        )
        // リストに追加する
        addEntry(entry)
        // Android LogcatにErrorレベルで出力する
        android.util.Log.e("DoublePlayer_${category.label}", message, throwable)
    }

    /**
     * ログをすべてクリアするメソッド
     * デバッグ画面の「クリア」ボタンから呼ぶ
     */
    fun clearLogs() {
        logList.clear()
        _logs.value = emptyList()
        _logCount.value = 0
    }

    // ========== ログのエクスポートメソッド ==========

    /**
     * 全ログをテキスト文字列として取得するメソッド
     * AIへのコピペ用
     *
     * @return ログ全文の文字列（ヘッダー情報付き）
     */
    fun exportAsText(): String {
        val sb = StringBuilder()
        // ヘッダー情報（端末・OSバージョン・アプリ情報）を付ける
        sb.appendLine("=== DoublePlayer デバッグログ ===")
        sb.appendLine("日時: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())}")
        sb.appendLine("端末: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ログ件数: ${logList.size}")
        sb.appendLine("=================================")
        sb.appendLine()
        // ログ本体を古い順に出力する
        logList.forEach { entry ->
            sb.appendLine(entry.toLogLine())
        }
        return sb.toString()
    }

    /**
     * ログをファイルとして内部ストレージに保存するメソッド
     * 保存したファイルのUriを返す（シェアシートに渡す）
     *
     * @return 保存したファイルのUri（FileProvider経由）、失敗時はnull
     */
    fun saveToFile(): Uri? {
        return try {
            // ファイル名にタイムスタンプを付ける（上書きしないように）
            val fileName = "doubleplayer_debug_${fileNameFormat.format(Date())}.txt"
            // 内部ストレージの cache/debug_logs ディレクトリに保存する
            val logDir = File(context.cacheDir, "debug_logs")
            logDir.mkdirs()
            val logFile = File(logDir, fileName)
            // ログテキストをファイルに書き込む
            logFile.writeText(exportAsText(), Charsets.UTF_8)
            // FileProvider経由でUriを取得する（Android 7.0以降の要件）
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )
        } catch (e: Exception) {
            error(Category.ERROR, "ログファイルの保存に失敗しました: ${e.message}", e)
            null
        }
    }

    /**
     * ログをシェアシートで共有するメソッド
     * 「AIに送る」ボタンから呼ぶ
     *
     * @param context シェアシートを表示するActivityのContext
     */
    fun shareLog(context: Context) {
        val uri = saveToFile() ?: return
        // シェアインテントを作成する
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DoublePlayer デバッグログ")
            putExtra(Intent.EXTRA_TEXT, "DoublePlayer のデバッグログです。バグ報告に添付します。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // シェアシートとして起動する
        context.startActivity(Intent.createChooser(shareIntent, "ログを共有").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * ANR・クラッシュ時にログを自動保存するメソッド
     * Application.onCreate() の UncaughtExceptionHandler から呼ぶ
     *
     * @param thread クラッシュしたスレッド
     * @param throwable クラッシュの原因
     */
    fun onUncaughtException(thread: Thread, throwable: Throwable) {
        // クラッシュ直前のログをエラーとして追加する
        error(Category.ERROR, "【クラッシュ検知】スレッド: ${thread.name}", throwable)
        // ファイルに保存する（クラッシュ後もログが残るように）
        saveToFile()
    }

    // ========== プライベートヘルパーメソッド ==========

    /**
     * エントリをリストに追加してStateFlowを更新するプライベートメソッド
     * MAX_LOG_SIZE を超えたら古いエントリを削除する
     *
     * @param entry 追加するDebugEntry
     */
    private fun addEntry(entry: DebugEntry) {
        // サイズ制限を超えたら先頭（最も古い）エントリを削除する
        if (logList.size >= MAX_LOG_SIZE) {
            logList.removeAt(0)
        }
        // 末尾に追加する
        logList.add(entry)
        // StateFlowを更新してUIに反映する
        // ★ CopyOnWriteArrayListはtoList()でスナップショットを作る（スレッド安全）
        _logs.value = logList.toList()
        _logCount.value = logList.size
    }
}
