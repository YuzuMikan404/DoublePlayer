package com.doubleplayer.app.ui

// Android関連のインポート
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
// ViewModel関連のインポート
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// Hilt依存注入のインポート
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
// コルーチン関連のインポート
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
// 自アプリのインポート
import com.doubleplayer.app.player.PlayerService
import com.doubleplayer.app.storage.SettingsStore
import javax.inject.Inject

/**
 * PlayerViewModel - プレイヤー画面の状態管理クラス
 *
 * 仕様書セクション3「ui/PlayerViewModel.kt」に基づいて実装する。
 * PlayerServiceにバインドしてUIの状態を管理する。
 *
 * このクラスが担当する責務：
 * - PlayerServiceへのバインド・アンバインド
 * - 再生状態のUIへの公開（StateFlow）
 * - 再生操作（再生・停止・次へ・前へ）の委譲
 * - 音量・速度・フェード設定の委譲と保存
 * - 設定値の読み込み（SettingsStore経由）
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    // アプリケーションコンテキスト（サービスバインドに使用）
    @ApplicationContext private val context: Context,
    // 設定値の保存・読み込み
    private val settingsStore: SettingsStore
) : ViewModel() {

    // ========== PlayerServiceへのバインド状態 ==========

    // バインドしているPlayerServiceのインスタンス（nullはバインドされていない状態）
    private var playerService: PlayerService? = null

    // サービスがバインドされているかどうかのフラグ
    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    // ========== UIに公開する再生状態（StateFlow）==========

    // 再生中かどうかのフラグ
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // トラックAの現在ファイル名
    private val _trackAFileName = MutableStateFlow("")
    val trackAFileName: StateFlow<String> = _trackAFileName.asStateFlow()

    // トラックAの現在再生位置（ミリ秒）
    private val _trackAPositionMs = MutableStateFlow(0L)
    val trackAPositionMs: StateFlow<Long> = _trackAPositionMs.asStateFlow()

    // トラックAの総再生時間（ミリ秒）
    private val _trackADurationMs = MutableStateFlow(0L)
    val trackADurationMs: StateFlow<Long> = _trackADurationMs.asStateFlow()

    // トラックAの残り曲数
    private val _trackARemainingCount = MutableStateFlow(0)
    val trackARemainingCount: StateFlow<Int> = _trackARemainingCount.asStateFlow()

    // トラックBの現在ファイル名
    private val _trackBFileName = MutableStateFlow("")
    val trackBFileName: StateFlow<String> = _trackBFileName.asStateFlow()

    // トラックBの残り曲数
    private val _trackBRemainingCount = MutableStateFlow(0)
    val trackBRemainingCount: StateFlow<Int> = _trackBRemainingCount.asStateFlow()

    // トラックBのシャッフルリスト（ファイル名のリスト）
    private val _trackBShuffleList = MutableStateFlow<List<String>>(emptyList())
    val trackBShuffleList: StateFlow<List<String>> = _trackBShuffleList.asStateFlow()

    // ========== UIに公開する設定値（StateFlow）==========

    // トラックAの音量（0.0〜1.0）
    private val _trackAVolume = MutableStateFlow(0.8f)
    val trackAVolume: StateFlow<Float> = _trackAVolume.asStateFlow()

    // トラックBの音量（0.0〜1.0）
    private val _trackBVolume = MutableStateFlow(0.3f)
    val trackBVolume: StateFlow<Float> = _trackBVolume.asStateFlow()

    // 再生速度（0.5〜2.0）
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // フェードイン秒数
    private val _fadeInSeconds = MutableStateFlow(3.0f)
    val fadeInSeconds: StateFlow<Float> = _fadeInSeconds.asStateFlow()

    // フェードアウト秒数
    private val _fadeOutSeconds = MutableStateFlow(3.0f)
    val fadeOutSeconds: StateFlow<Float> = _fadeOutSeconds.asStateFlow()

    // トラックA終了前フェードアウト開始秒数
    private val _fadeOutBeforeEndSeconds = MutableStateFlow(10.0f)
    val fadeOutBeforeEndSeconds: StateFlow<Float> = _fadeOutBeforeEndSeconds.asStateFlow()

    // ========== ServiceConnection（サービスバインド用コールバック）==========

    private val serviceConnection = object : ServiceConnection {
        /**
         * サービスへの接続が確立した時のコールバック
         * PlayerServiceのBinderからPlayerServiceインスタンスを取得してStateFlowを監視する
         */
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // PlayerService.PlayerBinderにキャストしてPlayerServiceを取得する
            val binder = service as? PlayerService.PlayerBinder
            playerService = binder?.getService()
            // バインド成功フラグを立てる
            _isServiceBound.value = true
            // サービスのStateFlowを監視してUIに反映する
            observeServiceState()
        }

        /**
         * サービスとの接続が切断した時のコールバック
         * 通常はサービスがクラッシュした時などに呼ばれる
         */
        override fun onServiceDisconnected(name: ComponentName?) {
            // PlayerServiceへの参照をクリアする
            playerService = null
            // バインドフラグをリセットする
            _isServiceBound.value = false
        }
    }

    // ========== 初期化処理 ==========

    init {
        // 起動時に保存済み設定値をロードしてUIの初期値を設定する
        loadSettingsFromStore()
    }

    /**
     * loadSettingsFromStore - SettingsStoreから設定値を読み込む
     * ViewModelの初期化時に呼ばれる
     */
    private fun loadSettingsFromStore() {
        // 音量・速度・フェード設定をDataStoreから読み込む
        viewModelScope.launch {
            settingsStore.trackAVolume.collectLatest { _trackAVolume.value = it }
        }
        viewModelScope.launch {
            settingsStore.trackBVolume.collectLatest { _trackBVolume.value = it }
        }
        viewModelScope.launch {
            settingsStore.playbackSpeed.collectLatest { _playbackSpeed.value = it }
        }
        viewModelScope.launch {
            settingsStore.fadeInSeconds.collectLatest { _fadeInSeconds.value = it }
        }
        viewModelScope.launch {
            settingsStore.fadeOutSeconds.collectLatest { _fadeOutSeconds.value = it }
        }
        viewModelScope.launch {
            settingsStore.fadeOutBeforeEnd.collectLatest { _fadeOutBeforeEndSeconds.value = it }
        }
    }

    /**
     * observeServiceState - PlayerServiceのStateFlowをViewModelのStateFlowに反映する
     * サービスにバインドした後に呼ばれる
     */
    private fun observeServiceState() {
        val service = playerService ?: return
        // 再生状態（isPlaying）を監視する
        viewModelScope.launch {
            service.trackAPlayer.isPlaying.collectLatest { _isPlaying.value = it }
        }
        // トラックAのファイル名を監視する
        viewModelScope.launch {
            service.trackAPlayer.currentFileName.collectLatest { _trackAFileName.value = it }
        }
        // トラックAの再生位置を監視する
        viewModelScope.launch {
            service.trackAPlayer.currentPositionMs.collectLatest { _trackAPositionMs.value = it }
        }
        // トラックAの再生時間を監視する
        viewModelScope.launch {
            service.trackAPlayer.durationMs.collectLatest { _trackADurationMs.value = it }
        }
        // トラックAの残り曲数を監視する
        viewModelScope.launch {
            service.trackAPlayer.remainingCount.collectLatest { _trackARemainingCount.value = it }
        }
        // トラックBのファイル名を監視する
        viewModelScope.launch {
            service.trackBPlayer.currentFileName.collectLatest { _trackBFileName.value = it }
        }
        // トラックBの残り曲数を監視する
        viewModelScope.launch {
            service.trackBPlayer.remainingCount.collectLatest { _trackBRemainingCount.value = it }
        }
        // トラックBのシャッフルリストを監視する
        viewModelScope.launch {
            service.trackBPlayer.shuffleListNames.collectLatest { _trackBShuffleList.value = it }
        }
    }

    // ========== サービスのバインド・アンバインド ==========

    /**
     * bindService - PlayerServiceにバインドする
     * PlayerScreenが表示される時に呼ばれる
     */
    fun bindService() {
        // PlayerServiceへのバインドインテントを作成する
        val intent = Intent(context, PlayerService::class.java)
        // バインドを開始する（BIND_AUTO_CREATEでサービスが起動していなければ起動する）
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * unbindService - PlayerServiceのバインドを解除する
     * PlayerScreenが非表示になる時に呼ばれる
     */
    fun unbindService() {
        // バインドされていない場合は何もしない
        if (!_isServiceBound.value) return
        // バインドを解除する
        context.unbindService(serviceConnection)
        // バインドフラグをリセットする
        _isServiceBound.value = false
        // PlayerServiceへの参照をクリアする
        playerService = null
    }

    // ========== 再生操作メソッド ==========

    /**
     * startPlayback - 手動で再生を開始する
     * プレイヤー画面の再生ボタンから呼ばれる
     * SettingsStoreからフォルダパスを取得してからPlayerServiceに渡す
     */
    fun startPlayback() {
        val service = playerService ?: return
        // SettingsStoreからフォルダパスを取得してから再生を開始する
        viewModelScope.launch {
            // DataStoreから現在のフォルダパスを1回だけ読み取る
            val folderA = settingsStore.trackAFolder.first()
            val folderB = settingsStore.trackBFolder.first()
            val linkedToA = settingsStore.trackBLinked.first()
            val linkedRatio = settingsStore.trackBLinkedRatio.first()
            // PlayerServiceのstartPlaybackを呼び出す（手動トリガー）
            service.startPlayback(
                trackAFolderPath = folderA,
                trackBFolderPath = folderB,
                trackAVolume = _trackAVolume.value,
                trackBVolume = _trackBVolume.value,
                trackBLinkedToA = linkedToA,
                trackBLinkedRatio = linkedRatio,
                fadeInSeconds = _fadeInSeconds.value,
                fadeOutSeconds = _fadeOutSeconds.value,
                fadeOutBeforeEndSeconds = _fadeOutBeforeEndSeconds.value,
                speed = _playbackSpeed.value
            )
        }
    }

    /**
     * resumePlayback - 一時停止から再開する
     */
    fun resumePlayback() {
        // PlayerServiceの再開メソッドを呼ぶ
        playerService?.resumePlayback()
    }

    /**
     * pausePlayback - 再生を一時停止する
     */
    fun pausePlayback() {
        // PlayerServiceの一時停止メソッドを呼ぶ
        playerService?.pausePlayback()
    }

    /**
     * stopPlayback - 再生を完全停止する
     */
    fun stopPlayback() {
        // PlayerServiceの停止メソッドを呼ぶ
        playerService?.stopPlayback()
    }

    /**
     * skipToNext - トラックAを次のファイルへスキップする
     */
    fun skipToNext() {
        // PlayerServiceのスキップメソッドを呼ぶ
        playerService?.skipToNextTrackA()
    }

    /**
     * skipToPrevious - トラックAを前のファイルへ戻す
     */
    fun skipToPrevious() {
        // PlayerServiceの前へメソッドを呼ぶ
        playerService?.skipToPreviousTrackA()
    }

    // ========== 設定値変更メソッド（サービスに反映 + DataStoreに保存）==========

    /**
     * setTrackAVolume - トラックAの音量を変更する
     * @param volume 0.0〜1.0
     */
    fun setTrackAVolume(volume: Float) {
        // UIの状態を更新する
        _trackAVolume.value = volume
        // PlayerServiceに反映する
        playerService?.setTrackAVolume(volume)
        // DataStoreに保存する
        viewModelScope.launch {
            settingsStore.saveTrackAVolume(volume)
        }
    }

    /**
     * setTrackBVolume - トラックBの音量を変更する
     * @param volume 0.0〜1.0
     */
    fun setTrackBVolume(volume: Float) {
        // UIの状態を更新する
        _trackBVolume.value = volume
        // PlayerServiceに反映する
        playerService?.setTrackBVolume(volume)
        // DataStoreに保存する
        viewModelScope.launch {
            settingsStore.saveTrackBVolume(volume)
        }
    }

    /**
     * setPlaybackSpeed - 再生速度を変更する
     * @param speed 0.5〜2.0
     */
    fun setPlaybackSpeed(speed: Float) {
        // UIの状態を更新する
        _playbackSpeed.value = speed
        // PlayerServiceに反映する
        playerService?.setSpeed(speed)
        // DataStoreに保存する
        viewModelScope.launch {
            settingsStore.savePlaybackSpeed(speed)
        }
    }

    /**
     * setFadeInSeconds - フェードイン秒数を変更する
     * @param seconds フェードイン秒数
     */
    fun setFadeInSeconds(seconds: Float) {
        // UIの状態を更新する
        _fadeInSeconds.value = seconds
        // PlayerServiceに反映する
        playerService?.setFadeInSeconds(seconds)
        // DataStoreに保存する（フェード設定は3つまとめて保存する）
        viewModelScope.launch {
            settingsStore.saveFadeInSeconds(seconds)
        }
    }

    /**
     * setFadeOutSeconds - フェードアウト秒数を変更する
     * @param seconds フェードアウト秒数
     */
    fun setFadeOutSeconds(seconds: Float) {
        // UIの状態を更新する
        _fadeOutSeconds.value = seconds
        // PlayerServiceに反映する
        playerService?.setFadeOutSeconds(seconds)
        // DataStoreに保存する
        viewModelScope.launch {
            settingsStore.saveFadeOutSeconds(seconds)
        }
    }

    /**
     * setFadeOutBeforeEndSeconds - トラックA終了前フェードアウト開始秒数を変更する
     * @param seconds 終了前フェードアウト開始秒数
     */
    fun setFadeOutBeforeEndSeconds(seconds: Float) {
        // UIの状態を更新する
        _fadeOutBeforeEndSeconds.value = seconds
        // PlayerServiceに反映する
        playerService?.setFadeOutBeforeEndSeconds(seconds)
        // DataStoreに保存する
        viewModelScope.launch {
            settingsStore.saveFadeOutBeforeEndSeconds(seconds)
        }
    }

    /**
     * exitApp - アプリを終了する（サービスごと止める）
     */
    fun exitApp() {
        // PlayerServiceのexitAppメソッドを呼ぶ
        playerService?.exitApp()
    }

    // ========== ライフサイクル管理 ==========

    /**
     * onCleared - ViewModelが破棄される時の後始末
     * バインドされていれば解除する
     */
    override fun onCleared() {
        super.onCleared()
        // バインドされていればアンバインドする
        unbindService()
    }
}
