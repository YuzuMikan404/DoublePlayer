package com.doubleplayer.app.player

// Android基本のインポート
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
// コルーチン関連のインポート
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
// Hilt依存注入のインポート
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
// デバッグログのインポート
import com.doubleplayer.app.debug.DebugLogger

/**
 * PlayerService - 2トラック再生を管理するForegroundService（再生エンジン本体）
 *
 * 仕様書セクション3「player/PlayerService.kt」に基づいて実装する。
 * バックグラウンドでも継続再生できるようForegroundServiceとして動作する。
 *
 * このクラスが担当する責務：
 * - TrackAPlayerとTrackBPlayerの初期化・ライフサイクル管理
 * - FadeControllerを使ったトラックBのフェードイン/アウト制御
 * - SpeedControllerを使った2トラックの再生速度同期
 * - EqualizerControllerを使ったイコライザー設定の適用
 * - PlayerNotificationManagerによる通知の更新
 * - 通知ボタンのBroadcastReceiverの登録・解除
 * - UIから参照できるStateFlowの集約と公開
 */
@AndroidEntryPoint
class PlayerService : Service() {

    // ========== Hiltで注入する依存クラス ==========

    // トラックAプレイヤー（ファイル名昇順再生）
    @Inject lateinit var trackAPlayer: TrackAPlayer

    // トラックBプレイヤー（シャッフル再生）
    @Inject lateinit var trackBPlayer: TrackBPlayer

    // フェードイン/アウト制御
    @Inject lateinit var fadeController: FadeController

    // 再生速度制御
    @Inject lateinit var speedController: SpeedController

    // イコライザー制御
    @Inject lateinit var equalizerController: EqualizerController

    // 通知管理
    @Inject lateinit var notificationManager: PlayerNotificationManager

    // デバッグログ管理
    @Inject lateinit var debugLogger: DebugLogger

    // ========== Binder（UIからのサービス接続用）==========

    // サービスにバインドするためのBinderクラス
    inner class PlayerBinder : Binder() {
        // バインドしたActivityやViewModelからPlayerServiceを取得できる
        fun getService(): PlayerService = this@PlayerService
    }

    // Binderインスタンス
    private val binder = PlayerBinder()

    // ========== コルーチンスコープ ==========

    // サービスのライフサイクルに合わせたコルーチンスコープ
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ========== 設定値（PlayerViewModelから設定される）==========

    // トラックAのフォルダパス
    private var trackAFolderPath: String = ""

    // トラックBのフォルダパス
    private var trackBFolderPath: String = ""

    // トラックAの音量設定値（0.0f〜1.0f）
    private var trackAVolume: Float = 0.8f

    // トラックBの音量設定値（0.0f〜1.0f）
    private var trackBVolume: Float = 0.3f

    // トラックBがトラックAに音量連動するかどうか
    private var trackBLinkedToA: Boolean = true

    // 音量連動の比率
    private var trackBLinkedRatio: Float = 0.4f

    // フェードインにかける秒数
    private var fadeInSeconds: Float = 3.0f

    // フェードアウトにかける秒数
    private var fadeOutSeconds: Float = 3.0f

    // トラックA終了何秒前にフェードアウトを開始するか
    private var fadeOutBeforeEndSeconds: Float = 10.0f

    // ========== BluetoothReceiver自動起動フラグ ==========

    // BluetoothReceiverから自動起動された場合にtrueになるフラグ
    // PlayerViewModelがバインド後にこのフラグを確認して再生を開始する
    var pendingAutoStart: Boolean = false

    // 保存された状態から続き再生するかどうかのフラグ
    // trueの場合はPlaybackStateStoreから再生位置を復元して再開する
    var pendingResumeFromSaved: Boolean = false

    // ========== UIに公開するStateFlow ==========

    // 再生中かどうか
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // サービスが起動中かどうか
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    // ========== BroadcastReceiver（通知ボタン・MacroDroid連携）==========

    // 通知ボタンのBroadcastReceiverインスタンス
    private val notificationActionReceiver = object : BroadcastReceiver() {
        // Broadcastを受信したときに呼ばれる
        override fun onReceive(context: Context?, intent: Intent?) {
            // アクションを取得する
            when (intent?.action) {
                // 再生ボタン
                PlayerNotificationManager.ACTION_PLAY -> {
                    // extrasにfolder_pathがある場合はフォルダを変更して再生する（MacroDroid連携）
                    val folderPath = intent.getStringExtra("folder_path")
                    if (folderPath != null) {
                        trackAFolderPath = folderPath
                        trackAPlayer.setFolder(folderPath)
                    }
                    resumePlayback()
                }
                // 一時停止ボタン
                PlayerNotificationManager.ACTION_PAUSE -> pausePlayback()
                // 停止ボタン
                PlayerNotificationManager.ACTION_STOP -> stopPlayback()
                // 次へボタン
                PlayerNotificationManager.ACTION_NEXT -> skipToNextTrackA()
                // 前へボタン
                PlayerNotificationManager.ACTION_PREV -> skipToPreviousTrackA()
                // アプリ終了ボタン
                PlayerNotificationManager.ACTION_EXIT -> exitApp()
            }
        }
    }

    // ========== Serviceのライフサイクルメソッド ==========

    /**
     * サービス生成時に呼ばれるメソッド
     * 各コントローラーを初期化する
     */
    override fun onCreate() {
        super.onCreate()
        // ★ サービス起動ログを記録する
        debugLogger.log(DebugLogger.Category.SERVICE, "PlayerService.onCreate() 開始")
        // 通知チャンネルを作成する（Android 8.0以上）
        notificationManager.createNotificationChannel()
        // トラックAプレイヤーを初期化する（メインスレッドで実行が必要）
        trackAPlayer.initialize()
        // トラックBプレイヤーを初期化する（メインスレッドで実行が必要）
        trackBPlayer.initialize()
        // FadeControllerのコールバックを設定する（トラックBの音量を制御する）
        fadeController.setOnVolumeChangedListener { volume ->
            trackBPlayer.setVolume(volume)
        }
        // SpeedControllerにプレイヤーを登録する
        // ★ initialize()直後はExoPlayerが生成済みのため通常はnullにならないが、
        //    万が一nullだった場合はリスナー経由で遅延登録して速度制御の無効化を防ぐ
        val exoA = trackAPlayer.getExoPlayer()
        if (exoA != null) {
            speedController.setTrackAPlayer(exoA)
        } else {
            trackAPlayer.setOnReadyCallback { player -> speedController.setTrackAPlayer(player) }
        }
        val exoB = trackBPlayer.getExoPlayer()
        if (exoB != null) {
            speedController.setTrackBPlayer(exoB)
        } else {
            trackBPlayer.setOnReadyCallback { player -> speedController.setTrackBPlayer(player) }
        }
        // EqualizerControllerを初期化する
        // ★ getAudioSessionId()が0を返す場合（ExoPlayer準備前）はコールバックで遅延初期化し、
        //    イコライザーが audioSessionId=0 で誤動作するのを防ぐ
        val sessionA = trackAPlayer.getAudioSessionId()
        if (sessionA != 0) {
            equalizerController.initTrackA(sessionA)
        } else {
            trackAPlayer.setOnAudioSessionReadyCallback { id -> equalizerController.initTrackA(id) }
        }
        val sessionB = trackBPlayer.getAudioSessionId()
        if (sessionB != 0) {
            equalizerController.initTrackB(sessionB)
        } else {
            trackBPlayer.setOnAudioSessionReadyCallback { id -> equalizerController.initTrackB(id) }
        }
        // トラックAのコールバックを設定する
        setupTrackACallbacks()
        // BroadcastReceiverを登録する
        registerNotificationReceiver()
        // サービス起動フラグを立てる
        _isServiceRunning.value = true
    }

    /**
     * startService()が呼ばれるたびに実行されるメソッド
     * Intentで渡されたコマンドを処理する
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // フォアグラウンドサービスとして起動する（通知を表示しながら動作する）
        val notification = notificationManager.buildNotification(
            trackAFileName = trackAPlayer.currentFileName.value.ifEmpty { "停止中" },
            trackBFileName = trackBPlayer.currentFileName.value.ifEmpty { "停止中" },
            isPlaying = _isPlaying.value
        )
        // フォアグラウンドサービスを開始する
        startForeground(notificationManager.notificationId, notification)

        // BluetoothReceiverからの自動起動かどうかを確認する（仕様書セクション6）
        val triggerType = intent?.getStringExtra("trigger_type")
        if (triggerType == "bluetooth_auto") {
            // resume_from_savedがtrueの場合は保存された状態から続き再生する
            val resumeFromSaved = intent.getBooleanExtra("resume_from_saved", false)
            // 自動起動フラグを保持する（PlayerViewModelがバインド後に参照する）
            pendingAutoStart = true
            pendingResumeFromSaved = resumeFromSaved
        }

        // サービスが強制終了された場合は再起動しない
        return START_NOT_STICKY
    }

    /**
     * ActivityがbindService()を呼んだときに呼ばれるメソッド
     *
     * @return バインド用のBinderオブジェクト
     */
    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * サービス終了時に呼ばれるメソッド
     * すべてのリソースを解放する
     */
    override fun onDestroy() {
        super.onDestroy()
        // ★ サービス終了ログを記録する
        debugLogger.log(DebugLogger.Category.SERVICE, "PlayerService.onDestroy() 開始")
        // BroadcastReceiverを解除する
        unregisterReceiver(notificationActionReceiver)
        // フェードをすべてキャンセルする
        fadeController.release()
        // 各コントローラーのリソースを解放する
        speedController.release()
        equalizerController.release()
        // プレイヤーのリソースを解放する
        trackAPlayer.release()
        trackBPlayer.release()
        // コルーチンスコープをキャンセルする
        serviceScope.cancel()
        // 通知を削除する
        notificationManager.cancelNotification()
        // サービス起動フラグをオフにする
        _isServiceRunning.value = false
    }

    // ========== 外部から呼ぶ再生制御メソッド ==========

    /**
     * 設定を適用して再生を開始するメソッド
     * PlayerViewModelから最初の再生時に呼ぶ
     *
     * @param settings 再生に使用する設定値
     * @param trackAIndex 再生を開始するトラックAのインデックス（再開時に使う）
     * @param trackAPositionMs トラックAの再生開始位置（再開時に使う）
     * @param savedShuffleList 保存済みのトラックBシャッフルリスト
     * @param savedShuffleIndex 保存済みのトラックBシャッフルインデックス
     */
    fun startPlayback(
        trackAFolderPath: String,
        trackBFolderPath: String,
        trackAVolume: Float = 0.8f,
        trackBVolume: Float = 0.3f,
        trackBLinkedToA: Boolean = true,
        trackBLinkedRatio: Float = 0.4f,
        fadeInSeconds: Float = 3.0f,
        fadeOutSeconds: Float = 3.0f,
        fadeOutBeforeEndSeconds: Float = 10.0f,
        speed: Float = 1.0f,
        trackAIndex: Int = 0,
        trackAPositionMs: Long = 0L,
        savedShuffleList: List<String>? = null,
        savedShuffleIndex: Int = 0
    ) {
        // 設定値を保存する
        this.trackAFolderPath = trackAFolderPath
        this.trackBFolderPath = trackBFolderPath
        this.trackAVolume = trackAVolume
        this.trackBVolume = trackBVolume
        this.trackBLinkedToA = trackBLinkedToA
        this.trackBLinkedRatio = trackBLinkedRatio
        this.fadeInSeconds = fadeInSeconds
        this.fadeOutSeconds = fadeOutSeconds
        this.fadeOutBeforeEndSeconds = fadeOutBeforeEndSeconds
        // ★ 再生開始ログを記録する
        debugLogger.log(DebugLogger.Category.PLAYBACK,
            "startPlayback: A=${trackAFolderPath.takeLast(30)} B=${trackBFolderPath.takeLast(30)} " +
            "vol(A=$trackAVolume B=$trackBVolume) speed=$speed idx=$trackAIndex pos=${trackAPositionMs}ms"
        )
        // フェードアウト開始秒数をトラックAプレイヤーに設定する
        trackAPlayer.fadeOutBeforeEndSeconds = fadeOutBeforeEndSeconds
        // 再生速度を設定する
        speedController.setSpeed(speed)
        // トラックAのフォルダを設定する
        trackAPlayer.setFolder(trackAFolderPath)
        // トラックAの音量を設定する
        trackAPlayer.setVolume(trackAVolume)
        // ★【トラックA/Bスタンドアローン修正】
        // トラックAはトラックBのスキャン完了を待たずに即座に再生を開始する。
        // トラックBのフォルダスキャンは非同期（IOスレッド）で実行され、
        // 完了次第 onReady コールバックで再生を開始する。
        // これによりトラックBのスキャン中もトラックAが正常に再生される。

        // トラックAの再生を即座に開始する（トラックBのスキャン完了を待たない）
        trackAPlayer.play(trackAIndex, trackAPositionMs)
        // 再生中フラグを更新する（通知でボタン状態を正しく反映するため先に更新する）
        _isPlaying.value = true
        // 通知を更新する
        updateNotification()

        // トラックBは非同期スキャン完了後に再生開始する
        // ★ setFolder の onReady コールバックでトラックBの再生とフェードインを行う
        trackBPlayer.setFolder(trackBFolderPath, savedShuffleList, savedShuffleIndex) {
            // スキャン完了（Mainスレッドで呼ばれる）
            // トラックBの音量を0にする（フェードインで上げる）
            fadeController.setVolume(0f)
            // トラックBの再生を開始する
            trackBPlayer.play()
            // トラックBをフェードインする
            val targetVolumeB = calculateTrackBVolume()
            fadeController.fadeIn(targetVolumeB, fadeInSeconds)
        }
    }

    /**
     * 一時停止中の再生を再開するメソッド
     * ★【修正】旧実装は trackBPlayer.pause()→play() を呼んで loadCurrentFile() が走り
     *   setMediaItem()/prepare() が実行されてしまい、オーディオフォーカスの競合で
     *   トラックAが止まる問題があった。
     *   トラックBがすでに準備済み（IDLE でない）なら resume() だけ呼ぶように修正した。
     */
    fun resumePlayback() {
        // ★ 再開ログを記録する
        debugLogger.log(DebugLogger.Category.PLAYBACK, "resumePlayback: トラックA・B再開")
        // トラックAを再開する
        trackAPlayer.resume()
        // トラックBを再開する
        // ★ pause()→play()にすると内部でloadCurrentFile()が呼ばれsetMediaItem/prepareが
        //   走りトラックAのフォーカスを奪うため、resume()（play()のみ）で再開する
        trackBPlayer.resume()
        // トラックBをフェードインする
        val targetVolumeB = calculateTrackBVolume()
        fadeController.fadeIn(targetVolumeB, fadeInSeconds)
        // 再生中フラグを更新する
        _isPlaying.value = true
        // 通知を更新する
        updateNotification()
    }

    /**
     * 再生を一時停止するメソッド
     */
    fun pausePlayback() {
        // ★ 一時停止ログを記録する
        debugLogger.log(DebugLogger.Category.PLAYBACK, "pausePlayback: 一時停止（フェードアウト開始）")
        // トラックAを一時停止する
        trackAPlayer.pause()
        // トラックBをフェードアウトしてから一時停止する
        fadeController.fadeOut(fadeOutSeconds) {
            // フェードアウト完了後にトラックBを一時停止する
            serviceScope.launch {
                trackBPlayer.pause()
            }
        }
        // 再生中フラグを更新する
        _isPlaying.value = false
        // 通知を更新する
        updateNotification()
    }

    /**
     * 再生を完全に停止するメソッド
     */
    fun stopPlayback() {
        // ★ 完全停止ログを記録する
        debugLogger.log(DebugLogger.Category.PLAYBACK, "stopPlayback: 完全停止")
        // フェードをキャンセルする
        fadeController.cancelAllFades()
        // トラックA・Bを停止する
        trackAPlayer.stop()
        trackBPlayer.stop()
        // 音量を0にする
        fadeController.setVolume(0f)
        // 再生中フラグを更新する
        _isPlaying.value = false
        // 通知を更新する
        updateNotification()
    }

    /**
     * トラックAを次のファイルへスキップするメソッド
     */
    fun skipToNextTrackA() {
        // 終了前通知をリセットするために次のファイルへ進む
        trackAPlayer.skipToNext()
        // 通知を更新する
        updateNotification()
    }

    /**
     * トラックAを前のファイルへ戻すメソッド
     */
    fun skipToPreviousTrackA() {
        trackAPlayer.skipToPrevious()
        // 通知を更新する
        updateNotification()
    }

    /**
     * 再生速度を変更するメソッド
     *
     * @param speed 設定する再生速度（0.5f〜2.0f）
     */
    fun setSpeed(speed: Float) {
        speedController.setSpeed(speed)
    }

    /**
     * トラックAの音量を変更するメソッド
     *
     * @param volume 設定する音量（0.0f〜1.0f）
     */
    fun setTrackAVolume(volume: Float) {
        trackAVolume = volume.coerceIn(0f, 1f)
        // ★ 音量変更ログを記録する
        debugLogger.log(DebugLogger.Category.VOLUME, "TrackA 音量変更: $trackAVolume")
        // トラックAの音量を直接設定する
        trackAPlayer.setVolume(trackAVolume)
        // 連動モードの場合はトラックBの音量も更新する
        // ★【音量調節修正】フェード中断しないupdateTargetVolumeを使う
        if (trackBLinkedToA) {
            val targetVolumeB = calculateTrackBVolume()
            fadeController.updateTargetVolume(targetVolumeB)
        }
    }

    /**
     * トラックBの音量を変更するメソッド
     *
     * @param volume 設定する音量（0.0f〜1.0f）
     */
    fun setTrackBVolume(volume: Float) {
        trackBVolume = volume.coerceIn(0f, 1f)
        // ★ 音量変更ログを記録する
        debugLogger.log(DebugLogger.Category.VOLUME, "TrackB 音量変更: $trackBVolume → 実効値: ${calculateTrackBVolume()}")
        // ★【音量調節修正】フェード中断しないupdateTargetVolumeを使う
        val targetVolumeB = calculateTrackBVolume()
        fadeController.updateTargetVolume(targetVolumeB)
    }

    /**
     * フェードイン秒数を変更するメソッド
     *
     * @param seconds フェードイン秒数
     */
    fun setFadeInSeconds(seconds: Float) {
        fadeInSeconds = seconds.coerceAtLeast(0f)
    }

    /**
     * フェードアウト秒数を変更するメソッド
     *
     * @param seconds フェードアウト秒数
     */
    fun setFadeOutSeconds(seconds: Float) {
        fadeOutSeconds = seconds.coerceAtLeast(0f)
    }

    /**
     * トラックA終了前フェードアウト開始秒数を変更するメソッド
     *
     * @param seconds フェードアウトを開始する終了前の秒数
     */
    fun setFadeOutBeforeEndSeconds(seconds: Float) {
        fadeOutBeforeEndSeconds = seconds.coerceAtLeast(0f)
        // トラックAプレイヤーの設定も更新する
        trackAPlayer.fadeOutBeforeEndSeconds = fadeOutBeforeEndSeconds
    }

    /**
     * イコライザーの低音レベルを変更するメソッド
     *
     * @param level 低音レベル（-10〜10）
     */
    fun setBassLevel(level: Int) {
        equalizerController.setBassLevel(level)
    }

    /**
     * イコライザーの高音レベルを変更するメソッド
     *
     * @param level 高音レベル（-10〜10）
     */
    fun setTrebleLevel(level: Int) {
        equalizerController.setTrebleLevel(level)
    }

    /**
     * アプリを終了するメソッド
     * 通知の「終了」ボタンを押したときに呼ばれる
     */
    fun exitApp() {
        // 再生を停止する
        stopPlayback()
        // フォアグラウンドサービスを停止する
        stopForeground(STOP_FOREGROUND_REMOVE)
        // サービス自体を停止する
        stopSelf()
    }

    // ========== プライベートメソッド ==========

    /**
     * トラックAのコールバックをセットアップするプライベートメソッド
     * トラックA終了N秒前・全完了などのイベントを処理する
     */
    private fun setupTrackACallbacks() {
        // トラックA終了N秒前のコールバック（トラックBをフェードアウトする）
        trackAPlayer.onNearEndCallback = {
            serviceScope.launch {
                fadeController.fadeOut(fadeOutBeforeEndSeconds)
            }
        }
        // トラックA全ファイル再生完了のコールバック
        trackAPlayer.onAllCompletedCallback = {
            serviceScope.launch {
                // トラックBをフェードアウトして停止する
                fadeController.fadeOut(fadeOutSeconds) {
                    serviceScope.launch {
                        trackBPlayer.stop()
                    }
                }
                // 再生中フラグをオフにする
                _isPlaying.value = false
                // 通知を更新する
                updateNotification()
            }
        }
    }

    /**
     * 音量連動の計算をするプライベートメソッド
     * 仕様書セクション5「音量連動」の計算式を実装する
     *
     * @return 計算されたトラックBの目標音量
     */
    private fun calculateTrackBVolume(): Float {
        return if (trackBLinkedToA) {
            // 連動モード：トラックBの音量 = 設定音量 × (1 - トラックAの音量 × 比率)
            trackBVolume * (1f - trackAVolume * trackBLinkedRatio)
        } else {
            // 非連動モード：設定音量をそのまま使う
            trackBVolume
        }
    }

    /**
     * 通知を最新の状態に更新するプライベートメソッド
     */
    private fun updateNotification() {
        notificationManager.updateNotification(
            trackAFileName = trackAPlayer.currentFileName.value.ifEmpty { "停止中" },
            trackBFileName = trackBPlayer.currentFileName.value.ifEmpty { "停止中" },
            isPlaying = _isPlaying.value
        )
    }

    /**
     * 通知ボタンのBroadcastReceiverを登録するプライベートメソッド
     */
    private fun registerNotificationReceiver() {
        // 受け付けるアクションのフィルターを設定する
        val filter = IntentFilter().apply {
            addAction(PlayerNotificationManager.ACTION_PLAY)
            addAction(PlayerNotificationManager.ACTION_PAUSE)
            addAction(PlayerNotificationManager.ACTION_STOP)
            addAction(PlayerNotificationManager.ACTION_NEXT)
            addAction(PlayerNotificationManager.ACTION_PREV)
            addAction(PlayerNotificationManager.ACTION_EXIT)
        }
        // BroadcastReceiverを登録する（Android 14以上はEXPORTEDフラグが必要）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(notificationActionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notificationActionReceiver, filter)
        }
    }
}
