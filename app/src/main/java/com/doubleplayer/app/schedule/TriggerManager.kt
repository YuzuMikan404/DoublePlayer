package com.doubleplayer.app.schedule

// Androidコンポーネントのインポート
import android.content.Context
import android.content.Intent
// コルーチン関連のインポート
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
// Hilt依存注入のインポート
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
// 再生状態ストアのインポート
import com.doubleplayer.app.storage.PlaybackStateStore
// 日付処理のインポート
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * TriggerManager - 複数の時間帯トリガーを管理するクラス
 *
 * 仕様書セクション3「schedule/TriggerManager.kt」に基づいて実装する。
 * BluetoothReceiverからBluetooth接続イベントを受け取り、
 * ScheduleCheckerを使って再生条件を確認し、PlayerServiceの起動判断をする。
 *
 * 仕様書セクション6の全フロー（ステップ1〜6）を実装する：
 * 1. 時間帯チェック（ScheduleCheckerに委譲）
 * 2. 曜日チェック（ScheduleCheckerに委譲）
 * 3. 祝日チェック（ScheduleCheckerに委譲）
 * 4. 当日再生済みフラグチェック（PlaybackStateStoreから読む）
 * 5. カウントダウン表示（countdownSeconds > 0の場合）
 * 6. 再生開始 + 再生済みフラグ保存
 *
 * 同日Bluetooth再接続時の処理：
 * - 当日の再生済みフラグあり かつ トラックAが完了していない → 続きから再生
 */
@Singleton
class TriggerManager @Inject constructor(
    // Androidコンテキスト
    @ApplicationContext private val context: Context,
    // スケジュールチェッカー
    private val scheduleChecker: ScheduleChecker,
    // 再生状態ストア（当日フラグの確認・保存に使う）
    private val playbackStateStore: PlaybackStateStore
) {

    // TriggerManagerのコルーチンスコープ
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // カウントダウンジョブ（進行中のカウントダウンをキャンセルするために保持する）
    private var countdownJob: Job? = null

    // カウントダウン残り秒数（UIに表示するためにStateFlowで公開する）
    private val _countdownSeconds = MutableStateFlow(0)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    // 現在のスケジュール設定（BluetoothReceiverからセットされる）
    private var currentScheduleConfig: ScheduleConfig = ScheduleConfig()

    // 日付フォーマッター（今日の日付をYYYY-MM-DD形式で取得するため）
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ========== 外部から呼ぶメソッド ==========

    /**
     * スケジュール設定をセットするメソッド
     * SettingsViewModelから設定変更時に呼ばれる
     *
     * @param config 新しいスケジュール設定
     */
    fun setScheduleConfig(config: ScheduleConfig) {
        // 設定を更新する
        currentScheduleConfig = config
    }

    /**
     * Bluetooth接続イベントを受け取るメソッド
     * BluetoothReceiverからBluetooth接続時に呼ばれる
     *
     * @param onStartPlayback 再生開始時に呼ばれるコールバック（続きから再生か最初からかを伝える）
     * @param onCountdown カウントダウン中に呼ばれるコールバック（残り秒数を伝える）
     * @param onSkipped 再生条件を満たさない場合に呼ばれるコールバック（理由を伝える）
     */
    fun onBluetoothConnected(
        onStartPlayback: (resumeFromSaved: Boolean) -> Unit,
        onCountdown: (remainingSeconds: Int) -> Unit = {},
        onSkipped: (reason: String) -> Unit = {}
    ) {
        managerScope.launch {
            // 今日の日付を取得する（YYYY-MM-DD形式）
            val todayString = LocalDate.now().format(dateFormatter)

            // ステップ1〜3：スケジュールチェック（時間帯・曜日・祝日）
            val checkResult = scheduleChecker.checkSchedule(currentScheduleConfig)

            // 再生条件を満たさない場合は同日再接続チェックに進む
            if (!checkResult.shouldPlay) {
                // ステップ4の前に：同日再接続での続き再生をチェックする
                val snapshot = playbackStateStore.getSnapshot()
                if (snapshot.todayPlayedFlag && !snapshot.todayCompleteFlag
                    && snapshot.lastPlayDate == todayString) {
                    // 当日の再生済みフラグあり かつ 完了していない → 続きから再生
                    onStartPlayback(true)
                    return@launch
                }
                // 再生条件を満たさないためスキップする
                onSkipped(checkResult.reason)
                return@launch
            }

            // ステップ4：今日すでに再生済みかチェックする
            val snapshot = playbackStateStore.getSnapshot()
            val isNewDay = snapshot.lastPlayDate != todayString

            // 日付が変わっていれば前日のフラグをリセットする
            if (isNewDay) {
                playbackStateStore.resetForNewDay(todayString)
            }

            // 当日再生済みフラグがあるかチェックする（リセット後の最新値を参照）
            val todayPlayedFlag = if (isNewDay) false else snapshot.todayPlayedFlag

            if (todayPlayedFlag) {
                // すでに再生済みの場合：完了していなければ続きから再生する
                if (!snapshot.todayCompleteFlag) {
                    // 続きから再生する（保存されたファイル・位置から再開）
                    onStartPlayback(true)
                } else {
                    // 全ファイル再生完了済みの場合はスキップする
                    onSkipped("当日の全ファイル再生は完了済みです")
                }
                return@launch
            }

            // ステップ5：カウントダウンの表示（countdownSeconds > 0の場合）
            val countdown = currentScheduleConfig.countdownSeconds
            if (countdown > 0) {
                // カウントダウンを開始する
                startCountdown(countdown, onCountdown) {
                    // カウントダウン完了後に再生を開始する
                    managerScope.launch {
                        // ステップ6：再生済みフラグを保存してから再生を開始する
                        playbackStateStore.markTodayAsPlayed(todayString)
                        onStartPlayback(false)
                    }
                }
            } else {
                // ステップ6：カウントダウンなしで即再生を開始する
                playbackStateStore.markTodayAsPlayed(todayString)
                onStartPlayback(false)
            }
        }
    }

    /**
     * Bluetooth切断イベントを受け取るメソッド
     * BluetoothReceiverからBluetooth切断時に呼ばれる
     * カウントダウン中なら中止する
     */
    fun onBluetoothDisconnected() {
        // 進行中のカウントダウンをキャンセルする
        cancelCountdown()
    }

    /**
     * 進行中のカウントダウンをキャンセルするメソッド
     * ユーザーが手動でキャンセルした場合にも呼ばれる
     */
    fun cancelCountdown() {
        // カウントダウンジョブをキャンセルする
        countdownJob?.cancel()
        countdownJob = null
        // カウントダウン表示をリセットする
        _countdownSeconds.value = 0
    }

    /**
     * TriggerManagerのリソースを解放するメソッド
     * アプリ終了時に呼ぶ
     */
    fun release() {
        // コルーチンスコープをキャンセルする
        managerScope.cancel()
    }

    // ========== プライベートメソッド ==========

    /**
     * カウントダウンを開始するプライベートメソッド
     *
     * @param seconds カウントダウン秒数
     * @param onTick 毎秒呼ばれるコールバック（残り秒数を渡す）
     * @param onComplete カウントダウン完了時に呼ばれるコールバック
     */
    private fun startCountdown(
        seconds: Int,
        onTick: (Int) -> Unit,
        onComplete: () -> Unit
    ) {
        // 進行中のカウントダウンがあればキャンセルする
        countdownJob?.cancel()
        // 新しいカウントダウンジョブを開始する
        countdownJob = managerScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                // 残り秒数を更新する
                _countdownSeconds.value = remaining
                onTick(remaining)
                // 1秒待つ
                delay(1000L)
                remaining--
            }
            // カウントダウン完了：残り秒数を0にする
            _countdownSeconds.value = 0
            // 完了コールバックを呼ぶ
            onComplete()
        }
    }
}
