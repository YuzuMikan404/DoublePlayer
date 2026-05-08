package com.doubleplayer.app.player

// Android基本のインポート
import android.content.Context
import android.net.Uri
// DocumentFileのインポート（SAFアクセス用）
import androidx.documentfile.provider.DocumentFile
// Media3（ExoPlayer）のインポート
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
// コルーチン関連のインポート
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// Hilt依存注入のインポート
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TrackBPlayer - トラックB専用プレイヤー（BGMのシャッフル再生を担当する）
 *
 * 【修正】旧実装は File(path) でSDカードにアクセスしていたが、
 * Android 10以降は外部ストレージへの直接Fileアクセスが禁止されている。
 * SAF（Storage Access Framework）で取得した content:// URIを使い、
 * DocumentFileでサブフォルダを再帰的にスキャンし、
 * MediaItem.fromUri(uri) で直接再生する方式に変更した。
 *
 * シャッフルリストはURI文字列のリストとして保存・復元する。
 */
@Singleton
class TrackBPlayer @Inject constructor(
    @ApplicationContext private val context: Context  // アプリのコンテキスト
) {

    /**
     * AudioItem - 再生対象ファイルの情報（URI＋ファイル名）
     */
    data class AudioItem(
        val uri: Uri,       // SAF content:// URI
        val fileName: String // 表示用ファイル名
    )

    // ExoPlayerインスタンス
    private var exoPlayer: ExoPlayer? = null

    // シャッフル済みのリスト（セッション中は順番を保持する）
    private var shuffledList: MutableList<AudioItem> = mutableListOf()

    // 現在再生中のインデックス
    private var currentIndex: Int = 0

    // トラックBの操作用コルーチンスコープ
    private val playerScope = CoroutineScope(Dispatchers.Main)

    // 現在再生中のファイル名（UIに表示するため StateFlow で公開する）
    private val _currentFileName = MutableStateFlow("")
    val currentFileName: StateFlow<String> = _currentFileName.asStateFlow()

    // 残り曲数
    private val _remainingCount = MutableStateFlow(0)
    val remainingCount: StateFlow<Int> = _remainingCount.asStateFlow()

    // 再生中かどうかのフラグ
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // シャッフルリストのファイル名リスト（UI表示用）
    private val _shuffleListNames = MutableStateFlow<List<String>>(emptyList())
    val shuffleListNames: StateFlow<List<String>> = _shuffleListNames.asStateFlow()

    // 対応する音声ファイルの拡張子一覧
    private val SUPPORTED_EXTENSIONS = setOf("mp3", "aac", "wav", "flac", "ogg", "m4a")

    // ★ ExoPlayer生成直後に呼ばれるコールバック（SpeedController登録用）
    private var onReadyCallback: ((ExoPlayer) -> Unit)? = null

    // ★ オーディオセッションIDが確定した直後に呼ばれるコールバック（Equalizer初期化用）
    private var onAudioSessionReadyCallback: ((Int) -> Unit)? = null

    /**
     * ExoPlayerを初期化するメソッド
     * PlayerService.onCreate()から呼ぶ（メインスレッドで実行する必要がある）
     */
    fun initialize() {
        exoPlayer = ExoPlayer.Builder(context).build()
        exoPlayer?.addListener(createPlayerListener())
        // ★ ExoPlayer生成直後にコールバックを呼ぶ（SpeedController登録の遅延解消）
        exoPlayer?.let { player ->
            onReadyCallback?.invoke(player)
            onReadyCallback = null
        }
        // ★ audioSessionIdを確認してイコライザー初期化コールバックを呼ぶ
        val sessionId = exoPlayer?.audioSessionId ?: 0
        if (sessionId != 0) {
            onAudioSessionReadyCallback?.invoke(sessionId)
            onAudioSessionReadyCallback = null
        }
        // audioSessionIdが0の場合はSTATE_READYイベントで再試行する（リスナー内で処理）
    }

    /**
     * 再生フォルダを設定してシャッフルリストを構築するメソッド
     * サブフォルダも含めて全音声ファイルをSAF経由で収集してシャッフルする
     *
     * @param folderUriString SAFで取得したフォルダのURI文字列（content://形式）
     * @param savedShuffleList 保存済みのシャッフルリスト（URI文字列リスト、nullは新規生成）
     * @param savedIndex 保存済みのインデックス（デフォルトは0）
     */
    fun setFolder(
        folderUriString: String,
        savedShuffleList: List<String>? = null,
        savedIndex: Int = 0
    ) {
        // URIが空の場合はリストを空にする
        if (folderUriString.isBlank()) {
            shuffledList = mutableListOf()
            _remainingCount.value = 0
            _shuffleListNames.value = emptyList()
            return
        }
        try {
            // URI文字列をUriオブジェクトに変換する
            val folderUri = Uri.parse(folderUriString)
            // DocumentFileでSAFフォルダにアクセスする
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            // フォルダが存在しない・アクセスできない場合はリストを空にする
            if (documentFile == null || !documentFile.exists() || !documentFile.canRead()) {
                shuffledList = mutableListOf()
                _remainingCount.value = 0
                _shuffleListNames.value = emptyList()
                return
            }
            // 保存済みシャッフルリストがある場合はURI文字列から復元する
            if (savedShuffleList != null && savedShuffleList.isNotEmpty()) {
                shuffledList = savedShuffleList.mapNotNull { uriString ->
                    // URI文字列からAudioItemを復元する（存在確認は省略して高速化）
                    try {
                        val uri = Uri.parse(uriString)
                        val doc = DocumentFile.fromSingleUri(context, uri)
                        val name = doc?.name ?: uriString.substringAfterLast("/")
                        AudioItem(uri = uri, fileName = name)
                    } catch (e: Exception) { null }
                }.toMutableList()
                currentIndex = savedIndex.coerceIn(0, (shuffledList.size - 1).coerceAtLeast(0))
            } else {
                // 新しいシャッフルリストを生成する（サブフォルダを含めて再帰的に収集）
                val allItems = collectAllAudioFiles(documentFile)
                shuffledList = allItems.shuffled().toMutableList()
                currentIndex = 0
            }
            // UIにシャッフルリストのファイル名を反映する
            _shuffleListNames.value = shuffledList.map { it.fileName }
            // 残り曲数を更新する
            _remainingCount.value = (shuffledList.size - currentIndex).coerceAtLeast(0)
        } catch (e: Exception) {
            shuffledList = mutableListOf()
            _remainingCount.value = 0
            _shuffleListNames.value = emptyList()
        }
    }

    /**
     * 再生を開始するメソッド
     */
    fun play() {
        if (shuffledList.isEmpty()) return
        loadCurrentFile()
        exoPlayer?.play()
        _isPlaying.value = true
    }

    /**
     * 再生を一時停止するメソッド
     */
    fun pause() {
        exoPlayer?.pause()
        _isPlaying.value = false
    }

    /**
     * 再生を停止するメソッド
     */
    fun stop() {
        exoPlayer?.stop()
        _isPlaying.value = false
    }

    /**
     * 音量を設定するメソッド
     * FadeControllerのコールバックから呼ばれる
     * ★ メインスレッドから呼ぶこと（FadeControllerが withContext(Main) で呼ぶ）
     *
     * @param volume 設定する音量（0.0f〜1.0f）
     */
    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * ExoPlayerのオーディオセッションIDを取得するメソッド
     */
    fun getAudioSessionId(): Int = exoPlayer?.audioSessionId ?: 0

    /**
     * ExoPlayerインスタンスを取得するメソッド
     */
    fun getExoPlayer(): ExoPlayer? = exoPlayer

    /**
     * ExoPlayer生成直後に呼ばれるコールバックを登録するメソッド
     * ★ PlayerService.onCreate()でgetExoPlayer()がnullを返した場合の安全策
     */
    fun setOnReadyCallback(callback: (ExoPlayer) -> Unit) {
        val player = exoPlayer
        if (player != null) {
            callback(player)
        } else {
            onReadyCallback = callback
        }
    }

    /**
     * オーディオセッションIDが確定したときに呼ばれるコールバックを登録するメソッド
     * ★ PlayerService.onCreate()でgetAudioSessionId()が0を返した場合の安全策
     */
    fun setOnAudioSessionReadyCallback(callback: (Int) -> Unit) {
        val sessionId = exoPlayer?.audioSessionId ?: 0
        if (sessionId != 0) {
            callback(sessionId)
        } else {
            onAudioSessionReadyCallback = callback
        }
    }

    /**
     * 現在のシャッフルリストをURI文字列リストで取得するメソッド
     * PlaybackStateStoreへの保存に使用する
     */
    fun getShuffleListPaths(): List<String> = shuffledList.map { it.uri.toString() }

    /**
     * 現在のインデックスを取得するメソッド
     */
    fun getCurrentIndex(): Int = currentIndex

    /**
     * 現在再生中のファイルURIを文字列で取得するメソッド
     */
    fun getCurrentFilePath(): String = shuffledList.getOrNull(currentIndex)?.uri?.toString() ?: ""

    /**
     * DocumentFileフォルダ以下を再帰的にスキャンして音声ファイルを収集するメソッド
     * ★ SAF経由なのでSDカード・USBドライブのサブフォルダも取得できる
     *
     * @param folder スキャン対象のDocumentFile（フォルダ）
     * @return 収集した音声ファイルのAudioItemリスト
     */
    private fun collectAllAudioFiles(folder: DocumentFile): List<AudioItem> {
        val result = mutableListOf<AudioItem>()
        folder.listFiles().forEach { entry ->
            if (entry.isDirectory) {
                // サブフォルダは再帰的にスキャンする
                result.addAll(collectAllAudioFiles(entry))
            } else if (entry.isFile) {
                val name = entry.name ?: return@forEach
                val ext = name.substringAfterLast(".", "").lowercase()
                if (ext in SUPPORTED_EXTENSIONS) {
                    result.add(AudioItem(uri = entry.uri, fileName = name))
                }
            }
        }
        return result
    }

    /**
     * 現在のファイルを読み込んでExoPlayerにセットするプライベートメソッド
     * ★ content:// URIを直接使ってMediaItemを生成する（File()不使用）
     */
    private fun loadCurrentFile() {
        val item = shuffledList.getOrNull(currentIndex) ?: return
        _currentFileName.value = item.fileName
        _remainingCount.value = (shuffledList.size - currentIndex).coerceAtLeast(0)
        // ★ content:// URIを直接使ってMediaItemを生成する
        val mediaItem = MediaItem.fromUri(item.uri)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
    }

    /**
     * ExoPlayerのリスナーを作成するプライベートメソッド
     */
    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> handleTrackEnd()
                    // ★ STATE_READYでaudioSessionIdが確定する端末への対応
                    Player.STATE_READY -> {
                        val pendingCallback = onAudioSessionReadyCallback
                        if (pendingCallback != null) {
                            val sessionId = exoPlayer?.audioSessionId ?: 0
                            if (sessionId != 0) {
                                onAudioSessionReadyCallback = null
                                pendingCallback(sessionId)
                            }
                        }
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 再生エラー時は次のファイルへスキップする
                handleTrackEnd()
            }
        }
    }

    /**
     * 1ファイルの再生終了時の処理
     */
    private fun handleTrackEnd() {
        if (currentIndex < shuffledList.size - 1) {
            currentIndex++
            loadCurrentFile()
            exoPlayer?.play()
        } else {
            // リストを最初から再シャッフルして繰り返す
            currentIndex = 0
            shuffledList.shuffle()
            _shuffleListNames.value = shuffledList.map { it.fileName }
            loadCurrentFile()
            exoPlayer?.play()
        }
        _remainingCount.value = (shuffledList.size - currentIndex).coerceAtLeast(0)
    }

    /**
     * リソースを解放するメソッド
     */
    fun release() {
        playerScope.cancel()
        exoPlayer?.release()
        exoPlayer = null
        // ★ 遅延コールバックもクリアする
        onReadyCallback = null
        onAudioSessionReadyCallback = null
    }
}
