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
import kotlinx.coroutines.cancel
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
 * TrackBPlayer - トラックB専用プレイヤー（BGMのシャッフル再生を担当する）
 *
 * 仕様書セクション5「トラックB（バックグラウンド再生）」に基づいて実装する。
 * 指定フォルダ＋サブフォルダ内の全音声ファイルをシャッフルで再生する。
 * シャッフルリストとインデックスは当日セッション中は保持する。
 *
 * 音量はFadeControllerが制御するため、直接setVolume()を外部から呼ばない。
 */
@Singleton
class TrackBPlayer @Inject constructor(
    @ApplicationContext private val context: Context  // アプリのコンテキスト
) {

    // ExoPlayerインスタンス
    private var exoPlayer: ExoPlayer? = null

    // シャッフル済みのファイルリスト（セッション中は順番を保持する）
    private var shuffledFileList: MutableList<File> = mutableListOf()

    // 現在再生中のファイルインデックス
    private var currentIndex: Int = 0

    // トラックBの操作用コルーチンスコープ
    private val playerScope = CoroutineScope(Dispatchers.Main)

    // 現在再生中のファイル名（UIに表示するため StateFlow で公開する）
    private val _currentFileName = MutableStateFlow("")
    val currentFileName: StateFlow<String> = _currentFileName.asStateFlow()

    // 残り曲数（シャッフルリストの残り）
    private val _remainingCount = MutableStateFlow(0)
    val remainingCount: StateFlow<Int> = _remainingCount.asStateFlow()

    // 再生中かどうかのフラグ
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // シャッフルリストのファイル名リスト（UI表示用）
    private val _shuffleListNames = MutableStateFlow<List<String>>(emptyList())
    val shuffleListNames: StateFlow<List<String>> = _shuffleListNames.asStateFlow()

    /**
     * ExoPlayerを初期化するメソッド
     * PlayerService.onCreate()から呼ぶ（メインスレッドで実行する必要がある）
     */
    fun initialize() {
        // ExoPlayerを生成する
        exoPlayer = ExoPlayer.Builder(context).build()
        // プレイヤーのリスナーを設定する
        exoPlayer?.addListener(createPlayerListener())
    }

    /**
     * 再生フォルダを設定してシャッフルリストを構築するメソッド
     * サブフォルダも含めて全音声ファイルを収集してシャッフルする
     *
     * @param folderPath 再生対象フォルダのパス
     * @param savedShuffleList 保存済みのシャッフルリスト（nullの場合は新規生成する）
     * @param savedIndex 保存済みのインデックス（デフォルトは0）
     */
    fun setFolder(
        folderPath: String,
        savedShuffleList: List<String>? = null,
        savedIndex: Int = 0
    ) {
        // フォルダをFileオブジェクトに変換する
        val folder = File(folderPath)
        // フォルダが存在しない場合はリストを空にする
        if (!folder.exists() || !folder.isDirectory) {
            shuffledFileList = mutableListOf()
            _remainingCount.value = 0
            _shuffleListNames.value = emptyList()
            return
        }
        // 保存済みシャッフルリストがある場合はそれを復元する
        if (savedShuffleList != null && savedShuffleList.isNotEmpty()) {
            // 保存されたパスからFileオブジェクトを生成する
            shuffledFileList = savedShuffleList
                .map { File(it) }
                .filter { it.exists() }  // 存在するファイルのみ使用する
                .toMutableList()
            currentIndex = savedIndex.coerceIn(0, shuffledFileList.size - 1)
        } else {
            // 新しいシャッフルリストを生成する
            val allFiles = collectAllAudioFiles(folder)
            shuffledFileList = allFiles.shuffled().toMutableList()
            currentIndex = 0
        }
        // UIにシャッフルリストのファイル名を反映する
        _shuffleListNames.value = shuffledFileList.map { it.name }
        // 残り曲数を更新する
        _remainingCount.value = shuffledFileList.size - currentIndex
    }

    /**
     * 再生を開始するメソッド
     * フェードインはFadeControllerが制御するため、ここでは音量を設定しない
     */
    fun play() {
        // ファイルリストが空の場合は再生しない
        if (shuffledFileList.isEmpty()) return
        // 現在のファイルを読み込む
        loadCurrentFile()
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
     * 再生を停止するメソッド
     */
    fun stop() {
        // ExoPlayerを停止する
        exoPlayer?.stop()
        // 再生中フラグを更新する
        _isPlaying.value = false
    }

    /**
     * 音量を設定するメソッド
     * FadeControllerのコールバックから呼ばれる
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
     * 現在のシャッフルリストをJSON用のパスリストで取得するメソッド
     * PlaybackStateStoreへの保存に使用する
     *
     * @return ファイルパスの文字列リスト
     */
    fun getShuffleListPaths(): List<String> = shuffledFileList.map { it.absolutePath }

    /**
     * 現在のインデックスを取得するメソッド
     *
     * @return 現在のインデックス
     */
    fun getCurrentIndex(): Int = currentIndex

    /**
     * 現在再生中のファイルパスを取得するメソッド
     *
     * @return 現在のファイルパス（ファイルが存在しない場合は空文字）
     */
    fun getCurrentFilePath(): String = shuffledFileList.getOrNull(currentIndex)?.absolutePath ?: ""

    /**
     * フォルダ内のサブフォルダを含めた全音声ファイルを収集するプライベートメソッド
     *
     * @param folder 収集対象のルートフォルダ
     * @return 収集した音声ファイルのリスト
     */
    private fun collectAllAudioFiles(folder: File): List<File> {
        // 対応する音声ファイルの拡張子リスト
        val supportedExtensions = setOf("mp3", "aac", "wav", "flac", "ogg", "m4a")
        // 再帰的にファイルを収集するリスト
        val result = mutableListOf<File>()
        // フォルダ内のすべてのファイルとフォルダを処理する
        folder.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.lowercase() in supportedExtensions) {
                // 対応形式の音声ファイルをリストに追加する
                result.add(file)
            }
        }
        return result
    }

    /**
     * 現在のファイルを読み込んでExoPlayerにセットするプライベートメソッド
     */
    private fun loadCurrentFile() {
        // 現在のファイルを取得する
        val currentFile = shuffledFileList.getOrNull(currentIndex) ?: return
        // ファイル名をUIに反映する
        _currentFileName.value = currentFile.name
        // 残り曲数を更新する
        _remainingCount.value = shuffledFileList.size - currentIndex
        // MediaItemを生成する
        val mediaItem = MediaItem.fromUri(Uri.fromFile(currentFile))
        // ExoPlayerにMediaItemをセットする
        exoPlayer?.setMediaItem(mediaItem)
        // 再生の準備をする
        exoPlayer?.prepare()
    }

    /**
     * ExoPlayerのリスナーを作成するプライベートメソッド
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
                        // 次のシャッフルファイルへ進む
                        handleTrackEnd()
                    }
                    else -> { /* その他の状態は無視する */ }
                }
            }

            // 再生エラーが発生したときに呼ばれる
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 再生エラー時は次のファイルへスキップする
                handleTrackEnd()
            }
        }
    }

    /**
     * 1ファイルの再生終了時の処理を行うプライベートメソッド
     * シャッフルリストの次のファイルへ進む
     */
    private fun handleTrackEnd() {
        if (currentIndex < shuffledFileList.size - 1) {
            // 次のファイルへ進む
            currentIndex++
            loadCurrentFile()
            exoPlayer?.play()
        } else {
            // シャッフルリストを最初から再生し直す（ループ再生）
            currentIndex = 0
            // 新しいシャッフル順を生成する
            shuffledFileList.shuffle()
            // UIのシャッフルリストを更新する
            _shuffleListNames.value = shuffledFileList.map { it.name }
            loadCurrentFile()
            exoPlayer?.play()
        }
        // 残り曲数を更新する
        _remainingCount.value = shuffledFileList.size - currentIndex
    }

    /**
     * リソースを解放するメソッド
     * PlayerServiceのonDestroy()から呼ぶ
     */
    fun release() {
        // コルーチンスコープをキャンセルする
        playerScope.cancel()
        // ExoPlayerを解放する
        exoPlayer?.release()
        exoPlayer = null
    }
}
