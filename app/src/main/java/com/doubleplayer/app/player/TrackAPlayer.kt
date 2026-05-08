package com.doubleplayer.app.player

// Android基本のインポート
import android.content.Context
import android.net.Uri
// Media3（ExoPlayer）のインポート
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
// コルーチン関連のインポート
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// Hilt依存注入のインポート
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TrackAPlayer - トラックA専用プレイヤー（メイン音声の再生を担当する）
 *
 * 【修正】旧実装は File(path) でSDカードにアクセスしていたが、
 * Android 10以降は外部ストレージへの直接Fileアクセスが禁止されている。
 * SAF（Storage Access Framework）で取得した content:// URIを使い、
 * MediaItem.fromUri(uri) で直接再生する方式に変更した。
 * これにより内部ストレージ・SDカード・USBドライブを問わず再生できる。
 *
 * フォルダ内のファイル一覧はDocumentFileで取得し、ファイル名昇順でソートする。
 * 再生対象リストはUri+ファイル名のペア（AudioItem）で管理する。
 */
@Singleton
class TrackAPlayer @Inject constructor(
    @ApplicationContext private val context: Context  // アプリのコンテキスト
) {

    /**
     * AudioItem - 再生対象ファイルの情報（URI＋ファイル名）
     * File()の代わりにURIを持つことでSAFアクセスに対応する
     */
    data class AudioItem(
        val uri: Uri,       // SAF content:// URI（再生・アクセスに使用）
        val fileName: String // 表示・ソート用ファイル名
    )

    // ExoPlayerインスタンス（メインスレッドで初期化する必要がある）
    private var exoPlayer: ExoPlayer? = null

    // 再生対象のリスト（ファイル名昇順で並べたAudioItem）
    private var fileList: List<AudioItem> = emptyList()

    // 現在再生中のインデックス（0始まり）
    private var currentIndex: Int = 0

    // トラックAの操作用コルーチンスコープ（メインスレッドで動かす）
    private val playerScope = CoroutineScope(Dispatchers.Main)

    // 再生位置監視ジョブ
    private var positionMonitorJob: Job? = null

    // 現在再生中のファイル名（UIに表示するため StateFlow で公開する）
    private val _currentFileName = MutableStateFlow("")
    val currentFileName: StateFlow<String> = _currentFileName.asStateFlow()

    // 現在の再生位置（ミリ秒）
    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    // 現在のファイルの総再生時間（ミリ秒）
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    // 残り曲数（現在のファイル以降のファイル数）
    private val _remainingCount = MutableStateFlow(0)
    val remainingCount: StateFlow<Int> = _remainingCount.asStateFlow()

    // 再生中かどうかのフラグ
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // 全ファイル再生完了フラグ
    private val _isCompleted = MutableStateFlow(false)
    val isCompleted: StateFlow<Boolean> = _isCompleted.asStateFlow()

    // トラックA終了N秒前のコールバック（FadeControllerに通知するため）
    var onNearEndCallback: (() -> Unit)? = null

    // トラックA終了前に通知する秒数（PlayerServiceから設定する）
    var fadeOutBeforeEndSeconds: Float = 10f

    // 終了前通知済みフラグ（1ファイルにつき1回だけ通知する）
    private var nearEndNotified: Boolean = false

    // トラックが終了したときのコールバック
    var onTrackEndCallback: (() -> Unit)? = null

    // 全ファイル再生完了のコールバック
    var onAllCompletedCallback: (() -> Unit)? = null

    // ★ ExoPlayerが生成された直後に呼ばれるコールバック（SpeedController登録用）
    // PlayerService.onCreate()でgetExoPlayer()がnullを返した場合の安全策として使う
    private var onReadyCallback: ((ExoPlayer) -> Unit)? = null

    // ★ オーディオセッションIDが確定した直後に呼ばれるコールバック（Equalizer初期化用）
    // ExoPlayer準備前にaudioSessionId=0が返った場合の安全策として使う
    private var onAudioSessionReadyCallback: ((Int) -> Unit)? = null

    // 対応する音声ファイルの拡張子一覧
    private val SUPPORTED_EXTENSIONS = setOf("mp3", "aac", "wav", "flac", "ogg", "m4a")

    /**
     * ExoPlayerを初期化するメソッド
     * PlayerService.onCreate()から呼ぶ（メインスレッドで実行する必要がある）
     */
    fun initialize() {
        // ExoPlayerを生成する
        exoPlayer = ExoPlayer.Builder(context).build()
        // プレイヤーのリスナーを設定する
        exoPlayer?.addListener(createPlayerListener())
        // 再生位置の監視を開始する
        startPositionMonitor()
        // ★ ExoPlayer生成直後にコールバックを呼ぶ（SpeedController登録の遅延解消）
        exoPlayer?.let { player ->
            onReadyCallback?.invoke(player)
            onReadyCallback = null
        }
        // ★ audioSessionIdはExoPlayer生成直後から取得可能なためここで確認する
        val sessionId = exoPlayer?.audioSessionId ?: 0
        if (sessionId != 0) {
            onAudioSessionReadyCallback?.invoke(sessionId)
            onAudioSessionReadyCallback = null
        }
        // audioSessionIdが0の場合はSTATE_READYイベントで再試行する（リスナー内で処理）
    }

    /**
     * 再生フォルダを設定してファイルリストを構築するメソッド
     * SAF URI文字列を受け取りDocumentFileでファイル一覧を取得する
     *
     * @param folderUriString SAFで取得したフォルダのURI文字列（content://形式）
     */
    fun setFolder(folderUriString: String) {
        // URIが空の場合はリストを空にする
        if (folderUriString.isBlank()) {
            fileList = emptyList()
            _remainingCount.value = 0
            return
        }
        try {
            // URI文字列をUriオブジェクトに変換する
            val folderUri = Uri.parse(folderUriString)
            // DocumentFileでSAFフォルダにアクセスする
            val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
            // フォルダが存在しない・アクセスできない場合はリストを空にする
            if (documentFile == null || !documentFile.exists() || !documentFile.canRead()) {
                fileList = emptyList()
                _remainingCount.value = 0
                return
            }
            // フォルダ内の音声ファイルを収集してファイル名昇順でソートする
            val items = mutableListOf<AudioItem>()
            documentFile.listFiles().forEach { entry ->
                if (entry.isFile) {
                    val name = entry.name ?: return@forEach
                    val ext = name.substringAfterLast(".", "").lowercase()
                    // 対応フォーマットのみ追加する
                    if (ext in SUPPORTED_EXTENSIONS) {
                        items.add(AudioItem(uri = entry.uri, fileName = name))
                    }
                }
            }
            // ファイル名昇順でソートする（仕様書セクション5）
            fileList = items.sortedBy { it.fileName.lowercase() }
            // 残り曲数を更新する
            _remainingCount.value = fileList.size
        } catch (e: Exception) {
            // アクセスエラーの場合はリストを空にする
            fileList = emptyList()
            _remainingCount.value = 0
        }
    }

    /**
     * 指定したインデックスから再生を開始するメソッド
     *
     * @param index 再生を開始するファイルインデックス（デフォルトは0）
     * @param positionMs 再生開始位置（ミリ秒、デフォルトは0）
     */
    fun play(index: Int = 0, positionMs: Long = 0L) {
        // ファイルリストが空の場合は再生しない
        if (fileList.isEmpty()) return
        // インデックスを有効範囲内にクランプする
        currentIndex = index.coerceIn(0, fileList.size - 1)
        // 完了フラグをリセットする
        _isCompleted.value = false
        // 終了前通知フラグをリセットする
        nearEndNotified = false
        // 指定のファイルを読み込む
        loadCurrentFile(positionMs)
        // 再生を開始する
        exoPlayer?.play()
        // 再生中フラグを更新する
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
     * 一時停止中の再生を再開するメソッド
     */
    fun resume() {
        exoPlayer?.play()
        _isPlaying.value = true
    }

    /**
     * 再生を停止するメソッド
     */
    fun stop() {
        exoPlayer?.stop()
        _isPlaying.value = false
    }

    /**
     * 次のファイルへスキップするメソッド
     */
    fun skipToNext() {
        if (currentIndex < fileList.size - 1) {
            currentIndex++
            nearEndNotified = false
            loadCurrentFile(0L)
            exoPlayer?.play()
            _isPlaying.value = true
        } else {
            handleAllCompleted()
        }
    }

    /**
     * 前のファイルへ戻るメソッド
     */
    fun skipToPrevious() {
        if (currentIndex > 0) {
            currentIndex--
        }
        nearEndNotified = false
        loadCurrentFile(0L)
        exoPlayer?.play()
        _isPlaying.value = true
    }

    /**
     * 音量を設定するメソッド
     * ★ メインスレッドから呼ぶこと（ExoPlayerの制約）
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
     *
     * @param callback ExoPlayerインスタンスを受け取るコールバック
     */
    fun setOnReadyCallback(callback: (ExoPlayer) -> Unit) {
        // initialize()が呼ばれていれば即座に呼ぶ、まだなら保持する
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
     *
     * @param callback オーディオセッションIDを受け取るコールバック
     */
    fun setOnAudioSessionReadyCallback(callback: (Int) -> Unit) {
        val sessionId = exoPlayer?.audioSessionId ?: 0
        if (sessionId != 0) {
            // すでに取得可能なら即座に呼ぶ
            callback(sessionId)
        } else {
            onAudioSessionReadyCallback = callback
        }
    }

    /**
     * 現在のファイルインデックスを取得するメソッド
     */
    fun getCurrentIndex(): Int = currentIndex

    /**
     * 現在の再生位置（ミリ秒）を取得するメソッド
     */
    fun getCurrentPositionMs(): Long = exoPlayer?.currentPosition ?: 0L

    /**
     * 現在再生中のファイルURIを文字列で取得するメソッド
     * （旧API互換: PlaybackStateStoreへの保存用）
     */
    fun getCurrentFilePath(): String = fileList.getOrNull(currentIndex)?.uri?.toString() ?: ""

    /**
     * ファイルリストのサイズを取得するメソッド
     */
    fun getFileListSize(): Int = fileList.size

    /**
     * 現在のファイルを読み込んでExoPlayerにセットするプライベートメソッド
     * ★ content:// URIを直接MediaItemに渡すことでSAFアクセスに対応する
     *
     * @param positionMs 再生開始位置（ミリ秒）
     */
    private fun loadCurrentFile(positionMs: Long) {
        // 現在のAudioItemを取得する
        val item = fileList.getOrNull(currentIndex) ?: return
        // ファイル名をUIに反映する
        _currentFileName.value = item.fileName
        // 残り曲数を更新する
        _remainingCount.value = fileList.size - currentIndex
        // ★ content:// URIを直接使ってMediaItemを生成する（File()不使用）
        val mediaItem = MediaItem.fromUri(item.uri)
        // ExoPlayerにMediaItemをセットする
        exoPlayer?.setMediaItem(mediaItem)
        // 再生の準備をする
        exoPlayer?.prepare()
        // 指定した位置にシークする
        if (positionMs > 0L) {
            exoPlayer?.seekTo(positionMs)
        }
    }

    /**
     * ExoPlayerのリスナーを作成するプライベートメソッド
     */
    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    // 再生が終了したとき
                    Player.STATE_ENDED -> {
                        onTrackEndCallback?.invoke()
                        handleTrackEnd()
                    }
                    // 再生準備完了のとき
                    Player.STATE_READY -> {
                        _durationMs.value = exoPlayer?.duration ?: 0L
                        // ★ STATE_READYでaudioSessionIdが確定する端末への対応
                        // initialize()時にaudioSessionId=0だった場合、ここで再試行する
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
                // 再生エラー時は次のファイルへスキップする（仕様書セクション8）
                handleTrackEnd()
            }
        }
    }

    /**
     * 1ファイルの再生終了時の処理
     */
    private fun handleTrackEnd() {
        if (currentIndex < fileList.size - 1) {
            currentIndex++
            nearEndNotified = false
            loadCurrentFile(0L)
            exoPlayer?.play()
        } else {
            handleAllCompleted()
        }
    }

    /**
     * 全ファイル再生完了時の処理
     */
    private fun handleAllCompleted() {
        _isCompleted.value = true
        _isPlaying.value = false
        _remainingCount.value = 0
        onAllCompletedCallback?.invoke()
    }

    /**
     * 再生位置を定期的に監視するコルーチンを開始するプライベートメソッド
     */
    private fun startPositionMonitor() {
        positionMonitorJob?.cancel()
        positionMonitorJob = playerScope.launch {
            while (isActive) {
                if (_isPlaying.value) {
                    val position = exoPlayer?.currentPosition ?: 0L
                    val duration = exoPlayer?.duration ?: 0L
                    _currentPositionMs.value = position
                    // 終了前N秒になったか確認する
                    if (!nearEndNotified && duration > 0L) {
                        val remainingMs = duration - position
                        val thresholdMs = (fadeOutBeforeEndSeconds * 1000).toLong()
                        if (remainingMs in 0L..thresholdMs) {
                            nearEndNotified = true
                            onNearEndCallback?.invoke()
                        }
                    }
                }
                delay(500L)
            }
        }
    }

    /**
     * リソースを解放するメソッド
     */
    fun release() {
        positionMonitorJob?.cancel()
        playerScope.cancel()
        exoPlayer?.release()
        exoPlayer = null
        onNearEndCallback = null
        onTrackEndCallback = null
        onAllCompletedCallback = null
        // ★ 遅延コールバックもクリアする
        onReadyCallback = null
        onAudioSessionReadyCallback = null
    }
}
