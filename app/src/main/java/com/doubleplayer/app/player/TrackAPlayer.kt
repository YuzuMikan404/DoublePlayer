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
// ファイル操作のインポート
import java.io.File

/**
 * TrackAPlayer - トラックA専用プレイヤー（メイン音声の再生を担当する）
 *
 * 仕様書セクション5「トラックA（メイン再生）」に基づいて実装する。
 * 指定フォルダ内の音声ファイルをファイル名昇順で順番に再生する。
 * 再生位置はPlayerServiceを通じてPlaybackStateStoreに保存する。
 *
 * 対応フォーマット：MP3, AAC, WAV, FLAC, OGG
 */
@Singleton
class TrackAPlayer @Inject constructor(
    @ApplicationContext private val context: Context  // アプリのコンテキスト
) {

    // ExoPlayerインスタンス（メインスレッドで初期化する必要がある）
    private var exoPlayer: ExoPlayer? = null

    // 再生対象のファイルリスト（ファイル名昇順で並べる）
    private var fileList: List<File> = emptyList()

    // 現在再生中のファイルインデックス（0始まり）
    private var currentIndex: Int = 0

    // トラックAの操作用コルーチンスコープ
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
    }

    /**
     * 再生フォルダを設定してファイルリストを構築するメソッド
     * フォルダ内の音声ファイルをファイル名昇順で並べる
     *
     * @param folderPath 再生対象フォルダのパス
     */
    fun setFolder(folderPath: String) {
        // フォルダをFileオブジェクトに変換する
        val folder = File(folderPath)
        // フォルダが存在しない場合はリストを空にする
        if (!folder.exists() || !folder.isDirectory) {
            fileList = emptyList()
            _remainingCount.value = 0
            return
        }
        // 対応する音声ファイルの拡張子リスト
        val supportedExtensions = setOf("mp3", "aac", "wav", "flac", "ogg", "m4a")
        // ファイルリストをファイル名昇順で取得する
        fileList = folder.listFiles()
            ?.filter { file ->
                // ファイルであることを確認する
                file.isFile &&
                // 拡張子が対応フォーマットであることを確認する
                file.extension.lowercase() in supportedExtensions
            }
            ?.sortedBy { it.name }  // ファイル名昇順に並べる
            ?: emptyList()
        // 残り曲数を更新する
        _remainingCount.value = fileList.size
    }

    /**
     * 指定したインデックスから再生を開始するメソッド
     * 最初の起動時または再開時に使用する
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
        // ExoPlayerを一時停止する
        exoPlayer?.pause()
        // 再生中フラグを更新する
        _isPlaying.value = false
    }

    /**
     * 一時停止中の再生を再開するメソッド
     */
    fun resume() {
        // ExoPlayerの再生を再開する
        exoPlayer?.play()
        // 再生中フラグを更新する
        _isPlaying.value = true
    }

    /**
     * 再生を停止するメソッド
     * 位置をリセットせずに停止する（resume()で続きから再生できる）
     */
    fun stop() {
        // ExoPlayerを停止する
        exoPlayer?.stop()
        // 再生中フラグを更新する
        _isPlaying.value = false
    }

    /**
     * 次のファイルへスキップするメソッド
     */
    fun skipToNext() {
        // 次のインデックスに移動する
        if (currentIndex < fileList.size - 1) {
            currentIndex++
            nearEndNotified = false
            loadCurrentFile(0L)
            exoPlayer?.play()
            _isPlaying.value = true
        } else {
            // 最後のファイルを超えた場合は全完了とする
            handleAllCompleted()
        }
    }

    /**
     * 前のファイルへ戻るメソッド
     */
    fun skipToPrevious() {
        // 前のインデックスに移動する（先頭なら先頭に留まる）
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
     * FadeControllerから直接呼ばれる
     *
     * @param volume 設定する音量（0.0f〜1.0f）
     */
    fun setVolume(volume: Float) {
        // ExoPlayerの音量を設定する
        exoPlayer?.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * ExoPlayerのオーディオセッションIDを取得するメソッド
     * EqualizerControllerの初期化に使用する
     *
     * @return オーディオセッションID（未初期化の場合は0）
     */
    fun getAudioSessionId(): Int = exoPlayer?.audioSessionId ?: 0

    /**
     * ExoPlayerインスタンスを取得するメソッド
     * SpeedControllerの初期化に使用する
     *
     * @return ExoPlayerインスタンス（未初期化の場合はnull）
     */
    fun getExoPlayer(): ExoPlayer? = exoPlayer

    /**
     * 現在のファイルインデックスを取得するメソッド
     *
     * @return 現在のファイルインデックス
     */
    fun getCurrentIndex(): Int = currentIndex

    /**
     * 現在の再生位置（ミリ秒）を取得するメソッド
     *
     * @return 現在の再生位置（ミリ秒）
     */
    fun getCurrentPositionMs(): Long = exoPlayer?.currentPosition ?: 0L

    /**
     * 現在再生中のファイルパスを取得するメソッド
     *
     * @return 現在のファイルパス（ファイルが存在しない場合は空文字）
     */
    fun getCurrentFilePath(): String = fileList.getOrNull(currentIndex)?.absolutePath ?: ""

    /**
     * ファイルリストのサイズを取得するメソッド
     *
     * @return ファイルリストのサイズ
     */
    fun getFileListSize(): Int = fileList.size

    /**
     * 現在のファイルを読み込んでExoPlayerにセットするプライベートメソッド
     *
     * @param positionMs 再生開始位置（ミリ秒）
     */
    private fun loadCurrentFile(positionMs: Long) {
        // 現在のファイルを取得する
        val currentFile = fileList.getOrNull(currentIndex) ?: return
        // ファイル名をUIに反映する
        _currentFileName.value = currentFile.name
        // 残り曲数を更新する（現在のファイルを含む残り数）
        _remainingCount.value = fileList.size - currentIndex
        // MediaItemを生成する
        val mediaItem = MediaItem.fromUri(Uri.fromFile(currentFile))
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
     * 再生終了・エラーなどのイベントを処理する
     *
     * @return Playerのリスナーオブジェクト
     */
    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            // 再生状態が変化したときに呼ばれる
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    // 再生が終了したとき
                    Player.STATE_ENDED -> {
                        // トラック終了コールバックを呼ぶ
                        onTrackEndCallback?.invoke()
                        // 次のファイルへ進む
                        handleTrackEnd()
                    }
                    // 再生準備完了のとき
                    Player.STATE_READY -> {
                        // 総再生時間を更新する
                        _durationMs.value = exoPlayer?.duration ?: 0L
                    }
                    else -> { /* その他の状態は無視する */ }
                }
            }

            // 再生エラーが発生したときに呼ばれる
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 再生エラー時は仕様書「再生エラー時は自動で次のファイルへスキップ」に従う
                handleTrackEnd()
            }
        }
    }

    /**
     * 1ファイルの再生終了時の処理を行うプライベートメソッド
     * 次のファイルへ進む、またはすべて完了の処理を行う
     */
    private fun handleTrackEnd() {
        // 次のファイルが存在するか確認する
        if (currentIndex < fileList.size - 1) {
            // 次のファイルへ進む
            currentIndex++
            nearEndNotified = false
            loadCurrentFile(0L)
            exoPlayer?.play()
        } else {
            // 全ファイルを再生し終えた
            handleAllCompleted()
        }
    }

    /**
     * 全ファイル再生完了時の処理を行うプライベートメソッド
     */
    private fun handleAllCompleted() {
        // 完了フラグを立てる
        _isCompleted.value = true
        // 再生中フラグをオフにする
        _isPlaying.value = false
        // 残り曲数を0にする
        _remainingCount.value = 0
        // 全完了コールバックを呼ぶ
        onAllCompletedCallback?.invoke()
    }

    /**
     * 再生位置を定期的に監視するコルーチンを開始するプライベートメソッド
     * UIへの再生位置更新とトラックA終了N秒前の検知を行う
     */
    private fun startPositionMonitor() {
        // 既存の監視ジョブをキャンセルする
        positionMonitorJob?.cancel()
        // 500msごとに再生位置を更新する
        positionMonitorJob = playerScope.launch {
            while (isActive) {
                // 再生中のみ処理する
                if (_isPlaying.value) {
                    val position = exoPlayer?.currentPosition ?: 0L
                    val duration = exoPlayer?.duration ?: 0L
                    // 再生位置を更新する
                    _currentPositionMs.value = position
                    // 終了前N秒になったか確認する（未通知の場合のみ）
                    if (!nearEndNotified && duration > 0L) {
                        val remainingMs = duration - position
                        val thresholdMs = (fadeOutBeforeEndSeconds * 1000).toLong()
                        if (remainingMs in 0L..thresholdMs) {
                            // 終了前通知フラグを立てる
                            nearEndNotified = true
                            // フェードアウト開始コールバックを呼ぶ
                            onNearEndCallback?.invoke()
                        }
                    }
                }
                // 500ms待機する
                delay(500L)
            }
        }
    }

    /**
     * リソースを解放するメソッド
     * PlayerServiceのonDestroy()から呼ぶ
     */
    fun release() {
        // 再生位置監視を停止する
        positionMonitorJob?.cancel()
        // コルーチンスコープをキャンセルする
        playerScope.cancel()
        // ExoPlayerを解放する
        exoPlayer?.release()
        exoPlayer = null
        // コールバックをクリアする
        onNearEndCallback = null
        onTrackEndCallback = null
        onAllCompletedCallback = null
    }
}
