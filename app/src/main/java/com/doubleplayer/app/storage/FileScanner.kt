package com.doubleplayer.app.storage

// Androidコンテキスト関連のインポート
import android.content.Context
import android.net.Uri
import android.util.Log
// Documentfileで外部ストレージアクセスするためのインポート
import androidx.documentfile.provider.DocumentFile
// Hilt依存注入のインポート
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
// コルーチン関連のインポート
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * FileScanner - 指定フォルダの音声ファイルを自動検知・リスト更新するクラス
 *
 * 仕様書セクション8「ファイル自動検知」に基づいて実装する。
 * - アプリ起動時・フォルダ設定変更時・定期スキャン（30分ごと）に呼ばれる
 * - 前回スキャン結果と差分があれば検知する
 * - 見つからないファイルがあれば警告リストを返す
 * - 再生エラー時は次のファイルへスキップ（PlayerServiceから呼ばれる）
 *
 * 対応フォーマット：MP3, AAC, WAV, FLAC, OGG
 */
@Singleton
class FileScanner @Inject constructor(
    // アプリケーションコンテキスト（ContentResolver使用のため必要）
    @ApplicationContext private val context: Context
) {

    // ログ出力用タグ
    private val TAG = "FileScanner"

    // 対応する音声ファイルの拡張子一覧（仕様書セクション5より）
    private val SUPPORTED_EXTENSIONS = setOf("mp3", "aac", "wav", "flac", "ogg", "m4a")

    // トラックAのファイルリストを保持するStateFlow（UIから購読可能）
    private val _trackAFiles = MutableStateFlow<List<AudioFile>>(emptyList())
    val trackAFiles: StateFlow<List<AudioFile>> = _trackAFiles

    // トラックBのファイルリストを保持するStateFlow（UIから購読可能）
    private val _trackBFiles = MutableStateFlow<List<AudioFile>>(emptyList())
    val trackBFiles: StateFlow<List<AudioFile>> = _trackBFiles

    // スキャンエラー情報を保持するStateFlow（見つからないファイルの通知用）
    private val _scanWarnings = MutableStateFlow<List<String>>(emptyList())
    val scanWarnings: StateFlow<List<String>> = _scanWarnings

    // スキャン中かどうかのフラグ（2重スキャン防止）
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    /**
     * AudioFile - 音声ファイルの情報を格納するデータクラス
     *
     * @param uri       ファイルのURI（ContentProvider経由でアクセスするため）
     * @param fileName  ファイル名（表示・ソート用）
     * @param filePath  ファイルの絶対パス（存在確認用）
     * @param fileSize  ファイルサイズ（バイト）
     */
    data class AudioFile(
        val uri: Uri,         // ファイルのURI（再生に使用）
        val fileName: String, // 拡張子を含むファイル名
        val filePath: String, // 表示用パス文字列
        val fileSize: Long    // ファイルサイズ（バイト）
    )

    /**
     * ScanResult - スキャン結果を格納するデータクラス
     *
     * @param files         見つかった音声ファイルのリスト
     * @param newFiles      前回から追加されたファイル名リスト
     * @param removedFiles  前回から削除されたファイル名リスト
     * @param errorFiles    アクセスエラーが発生したファイル名リスト
     */
    data class ScanResult(
        val files: List<AudioFile>,       // スキャンで見つかったファイルリスト
        val newFiles: List<String>,        // 前回スキャンから新規追加されたファイル名
        val removedFiles: List<String>,    // 前回スキャンから削除されたファイル名
        val errorFiles: List<String>       // アクセスエラーのファイル名
    )

    /**
     * scanTrackAFolder - トラックA用フォルダをスキャンしてファイルリストを更新する
     *
     * 仕様書セクション5「トラックA」：ファイル名昇順でリストを返す
     *
     * @param folderUriString フォルダのURI文字列（SAFで取得したURI）
     * @param previousFiles   前回スキャン結果（差分検知用）
     * @return ScanResult スキャン結果
     */
    suspend fun scanTrackAFolder(
        folderUriString: String,
        previousFiles: List<AudioFile> = _trackAFiles.value
    ): ScanResult = withContext(Dispatchers.IO) {
        // スキャン中フラグを立てる
        _isScanning.value = true

        try {
            // フォルダURIが空の場合は空のリストを返す
            if (folderUriString.isBlank()) {
                Log.w(TAG, "トラックAのフォルダURIが未設定です")
                _trackAFiles.value = emptyList()
                return@withContext ScanResult(emptyList(), emptyList(), emptyList(), emptyList())
            }

            // URIを解析してDocumentFileを取得する
            val folderUri = Uri.parse(folderUriString)
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)

            // フォルダが存在しない・アクセスできない場合は警告を返す
            if (documentFile == null || !documentFile.exists() || !documentFile.canRead()) {
                Log.e(TAG, "トラックAフォルダにアクセスできません: $folderUriString")
                val warning = "トラックAフォルダが見つかりません"
                _scanWarnings.value = listOf(warning)
                return@withContext ScanResult(emptyList(), emptyList(), emptyList(), listOf(warning))
            }

            // フォルダ内のファイルをスキャンして音声ファイルだけ抽出する
            val scannedFiles = scanFolderFiles(documentFile, recursive = false)

            // ファイル名昇順でソートする（仕様書セクション5「ファイル名昇順で再生」）
            val sortedFiles = scannedFiles.sortedBy { it.fileName.lowercase() }

            // 差分を計算する（新規追加・削除されたファイル名を取得）
            val diffResult = calculateDiff(previousFiles, sortedFiles)

            // StateFlowを更新して購読者に通知する
            _trackAFiles.value = sortedFiles

            Log.i(TAG, "トラックAスキャン完了: ${sortedFiles.size}件, 新規${diffResult.first.size}件, 削除${diffResult.second.size}件")

            // スキャン結果を返す
            ScanResult(
                files = sortedFiles,
                newFiles = diffResult.first,
                removedFiles = diffResult.second,
                errorFiles = emptyList()
            )
        } catch (e: Exception) {
            // 予期しないエラーが発生した場合はログに記録して空リストを返す
            Log.e(TAG, "トラックAスキャン中にエラーが発生しました", e)
            ScanResult(emptyList(), emptyList(), emptyList(), listOf(e.message ?: "不明なエラー"))
        } finally {
            // スキャン完了後にフラグをリセットする
            _isScanning.value = false
        }
    }

    /**
     * scanTrackBFolder - トラックB用フォルダをスキャンしてファイルリストを更新する
     *
     * 仕様書セクション5「トラックB」：サブフォルダも含めて全ファイルをスキャンする
     *
     * @param folderUriString フォルダのURI文字列（SAFで取得したURI）
     * @param previousFiles   前回スキャン結果（差分検知用）
     * @return ScanResult スキャン結果
     */
    suspend fun scanTrackBFolder(
        folderUriString: String,
        previousFiles: List<AudioFile> = _trackBFiles.value
    ): ScanResult = withContext(Dispatchers.IO) {
        // スキャン中フラグを立てる
        _isScanning.value = true

        try {
            // フォルダURIが空の場合は空のリストを返す
            if (folderUriString.isBlank()) {
                Log.w(TAG, "トラックBのフォルダURIが未設定です")
                _trackBFiles.value = emptyList()
                return@withContext ScanResult(emptyList(), emptyList(), emptyList(), emptyList())
            }

            // URIを解析してDocumentFileを取得する
            val folderUri = Uri.parse(folderUriString)
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)

            // フォルダが存在しない・アクセスできない場合は警告を返す
            if (documentFile == null || !documentFile.exists() || !documentFile.canRead()) {
                Log.e(TAG, "トラックBフォルダにアクセスできません: $folderUriString")
                val warning = "トラックBフォルダが見つかりません"
                _scanWarnings.value = listOf(warning)
                return@withContext ScanResult(emptyList(), emptyList(), emptyList(), listOf(warning))
            }

            // サブフォルダを含めて再帰的にスキャンする（仕様書セクション5「サブフォルダ内の全音声ファイル」）
            val scannedFiles = scanFolderFiles(documentFile, recursive = true)

            // 差分を計算する（新規追加・削除されたファイル名を取得）
            val diffResult = calculateDiff(previousFiles, scannedFiles)

            // StateFlowを更新して購読者に通知する
            _trackBFiles.value = scannedFiles

            Log.i(TAG, "トラックBスキャン完了: ${scannedFiles.size}件, 新規${diffResult.first.size}件, 削除${diffResult.second.size}件")

            // スキャン結果を返す
            ScanResult(
                files = scannedFiles,
                newFiles = diffResult.first,
                removedFiles = diffResult.second,
                errorFiles = emptyList()
            )
        } catch (e: Exception) {
            // 予期しないエラーが発生した場合はログに記録して空リストを返す
            Log.e(TAG, "トラックBスキャン中にエラーが発生しました", e)
            ScanResult(emptyList(), emptyList(), emptyList(), listOf(e.message ?: "不明なエラー"))
        } finally {
            // スキャン完了後にフラグをリセットする
            _isScanning.value = false
        }
    }

    /**
     * scanFolderFiles - DocumentFileのフォルダを走査して音声ファイルリストを返す内部メソッド
     *
     * @param folder    スキャン対象のDocumentFile（フォルダ）
     * @param recursive trueの場合はサブフォルダも再帰的にスキャンする
     * @return 見つかった音声ファイルのリスト
     */
    private fun scanFolderFiles(
        folder: DocumentFile,
        recursive: Boolean
    ): List<AudioFile> {
        // 結果を格納するリスト
        val result = mutableListOf<AudioFile>()

        // フォルダ内の全エントリを取得する
        val entries = folder.listFiles()

        // エントリが取得できない場合は空リストを返す
        if (entries.isEmpty()) {
            Log.w(TAG, "フォルダが空または取得できません: ${folder.uri}")
            return result
        }

        for (entry in entries) {
            if (entry.isDirectory && recursive) {
                // サブフォルダの場合は再帰的にスキャンする
                result.addAll(scanFolderFiles(entry, recursive = true))
            } else if (entry.isFile) {
                // ファイルの場合は音声ファイルかどうか確認する
                val fileName = entry.name ?: continue
                val extension = fileName.substringAfterLast(".", "").lowercase()

                // 対応する拡張子の場合のみリストに追加する
                if (extension in SUPPORTED_EXTENSIONS) {
                    val audioFile = AudioFile(
                        uri = entry.uri,
                        fileName = fileName,
                        filePath = entry.uri.toString(),
                        fileSize = entry.length()
                    )
                    result.add(audioFile)
                }
            }
        }

        return result
    }

    /**
     * calculateDiff - 前回と今回のスキャン結果の差分を計算するメソッド
     *
     * @param previous 前回のスキャン結果
     * @param current  今回のスキャン結果
     * @return Pair<新規追加ファイル名リスト, 削除されたファイル名リスト>
     */
    private fun calculateDiff(
        previous: List<AudioFile>,
        current: List<AudioFile>
    ): Pair<List<String>, List<String>> {
        // 前回のファイル名セットを作成する
        val previousNames = previous.map { it.fileName }.toSet()
        // 今回のファイル名セットを作成する
        val currentNames = current.map { it.fileName }.toSet()

        // 新規追加されたファイル（今回にあって前回にないファイル）
        val newFiles = currentNames.subtract(previousNames).toList()
        // 削除されたファイル（前回にあって今回にないファイル）
        val removedFiles = previousNames.subtract(currentNames).toList()

        return Pair(newFiles, removedFiles)
    }

    /**
     * getNextValidFile - 再生エラー時に次の有効なファイルを返すメソッド
     *
     * 仕様書セクション8「再生エラー時は自動で次のファイルへスキップ」に対応する。
     *
     * @param currentIndex 現在のインデックス
     * @param isTrackA     trueならトラックA、falseならトラックBのリストを使う
     * @return 次の有効なAudioFile。存在しない場合はnull
     */
    fun getNextValidFile(currentIndex: Int, isTrackA: Boolean): AudioFile? {
        // 対象のファイルリストを選択する
        val files = if (isTrackA) _trackAFiles.value else _trackBFiles.value

        // 現在インデックスの次から順番に有効なファイルを探す
        for (i in (currentIndex + 1) until files.size) {
            val file = files[i]
            // DocumentFileでアクセス可能かどうか確認する
            val docFile = DocumentFile.fromSingleUri(context, file.uri)
            if (docFile != null && docFile.exists() && docFile.canRead()) {
                return file
            }
            Log.w(TAG, "ファイルにアクセスできないためスキップします: ${file.fileName}")
        }

        // 有効なファイルが見つからない場合はnullを返す
        Log.w(TAG, "次の有効なファイルが見つかりません（currentIndex=$currentIndex）")
        return null
    }

    /**
     * clearWarnings - スキャン警告をクリアするメソッド
     *
     * 通知表示後に呼び出してStateFlowをリセットする。
     */
    fun clearWarnings() {
        // 警告リストを空にする
        _scanWarnings.value = emptyList()
    }

    /**
     * getTrackAFileAt - トラックAの指定インデックスのファイルを返すメソッド
     *
     * @param index インデックス（0始まり）
     * @return AudioFile。インデックスが範囲外の場合はnull
     */
    fun getTrackAFileAt(index: Int): AudioFile? {
        // インデックスが範囲内かどうか確認する
        val files = _trackAFiles.value
        return if (index in files.indices) files[index] else null
    }

    /**
     * getTrackBFileAt - トラックBの指定インデックスのファイルを返すメソッド
     *
     * @param index インデックス（0始まり）
     * @return AudioFile。インデックスが範囲外の場合はnull
     */
    fun getTrackBFileAt(index: Int): AudioFile? {
        // インデックスが範囲内かどうか確認する
        val files = _trackBFiles.value
        return if (index in files.indices) files[index] else null
    }

    /**
     * getTrackAFileCount - トラックAのファイル数を返すメソッド
     *
     * @return トラックAの総ファイル数
     */
    fun getTrackAFileCount(): Int = _trackAFiles.value.size

    /**
     * getTrackBFileCount - トラックBのファイル数を返すメソッド
     *
     * @return トラックBの総ファイル数
     */
    fun getTrackBFileCount(): Int = _trackBFiles.value.size
}
