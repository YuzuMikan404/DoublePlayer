package com.doubleplayer.app.bluetooth

// Android基本のインポート
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
// Hilt依存注入のインポート（BroadcastReceiverへのHilt注入）
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
// 設定ストアのインポート
import com.doubleplayer.app.storage.SettingsStore
// TriggerManagerのインポート
import com.doubleplayer.app.schedule.TriggerManager
// PlayerServiceのインポート
import com.doubleplayer.app.player.PlayerService
// コルーチン関連のインポート
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BluetoothReceiver - Bluetooth接続/切断を検知してPlayerServiceを制御するクラス
 *
 * 仕様書セクション3「bluetooth/BluetoothReceiver.kt」に基づいて実装する。
 * 仕様書セクション7「MacroDroid連携（BroadcastIntent）」も同ファイルで担当する。
 *
 * 受け取るBroadcastアクション一覧：
 * ＜Bluetooth系＞
 * - ACL_CONNECTED：Bluetoothデバイス接続（A2DPプロファイルを優先確認）
 * - ACL_DISCONNECTED：Bluetoothデバイス切断
 * - A2DP_CONNECTION_STATE_CHANGED：A2DP接続状態変化（スピーカー・ヘッドフォン用）
 *
 * ＜MacroDroid連携系＞
 * - ACTION_PLAY：再生開始（extrasにfolder_pathで上書き可）
 * - ACTION_PAUSE：一時停止
 * - ACTION_STOP：完全停止
 * - ACTION_NEXT：次のファイルへ
 * - ACTION_PREV：前のファイルへ
 *
 * AndroidManifest.xmlで両方のintent-filterが登録されている。
 */
@AndroidEntryPoint
class BluetoothReceiver : BroadcastReceiver() {

    // 設定ストア（フォルダパス・スケジュール設定の取得に使う）
    @Inject lateinit var settingsStore: SettingsStore

    // トリガー管理クラス（スケジュールチェックと再生開始判断に使う）
    @Inject lateinit var triggerManager: TriggerManager

    // コルーチンスコープ（BroadcastReceiverは非同期処理に制限があるのでgoAsync()を使う）
    private val receiverScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Broadcastを受信したときに呼ばれるメソッド
     *
     * @param context Androidコンテキスト
     * @param intent 受信したIntentオブジェクト
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        // コンテキストがnullの場合は何もしない
        if (context == null || intent == null) return

        // アクションに応じた処理を分岐する
        when (intent.action) {

            // ===== Bluetooth接続イベント =====

            // Bluetoothデバイスの接続（ACLレベル）
            "android.bluetooth.device.action.ACL_CONNECTED" -> {
                // Bluetooth接続時のロジックを実行する
                handleBluetoothConnected(context)
            }

            // A2DPプロファイルの接続状態変化（スピーカー・ヘッドフォン向け）
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                // 接続状態を取得する（2 = STATE_CONNECTED）
                val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                if (state == 2) {
                    // A2DP接続時のロジックを実行する
                    handleBluetoothConnected(context)
                }
            }

            // ===== Bluetooth切断イベント =====

            // Bluetoothデバイスの切断（ACLレベル）
            "android.bluetooth.device.action.ACL_DISCONNECTED" -> {
                // Bluetooth切断時のロジックを実行する
                handleBluetoothDisconnected(context)
            }

            // ===== MacroDroid連携アクション =====

            // 再生開始（extrasにfolder_pathで上書き可）
            "com.doubleplayer.ACTION_PLAY" -> {
                // folder_pathがあればフォルダを変更して再生する
                val folderPath = intent.getStringExtra("folder_path")
                handleMacroDroidPlay(context, folderPath)
            }

            // 一時停止
            "com.doubleplayer.ACTION_PAUSE" -> {
                // PlayerServiceに一時停止を指示する
                sendCommandToService(context, "com.doubleplayer.ACTION_PAUSE")
            }

            // 完全停止
            "com.doubleplayer.ACTION_STOP" -> {
                // PlayerServiceに停止を指示する
                sendCommandToService(context, "com.doubleplayer.ACTION_STOP")
            }

            // 次のファイルへ
            "com.doubleplayer.ACTION_NEXT" -> {
                // PlayerServiceに次へスキップを指示する
                sendCommandToService(context, "com.doubleplayer.ACTION_NEXT")
            }

            // 前のファイルへ
            "com.doubleplayer.ACTION_PREV" -> {
                // PlayerServiceに前へ戻るを指示する
                sendCommandToService(context, "com.doubleplayer.ACTION_PREV")
            }
        }
    }

    // ========== プライベートメソッド ==========

    /**
     * Bluetooth接続時の処理をするプライベートメソッド
     * TriggerManagerにイベントを通知し、再生条件を確認してもらう
     *
     * @param context Androidコンテキスト
     */
    private fun handleBluetoothConnected(context: Context) {
        receiverScope.launch {
            // 設定ストアからスケジュール設定を読み込む
            val scheduleConfig = settingsStore.getScheduleConfig()
            // TriggerManagerにスケジュール設定をセットする
            triggerManager.setScheduleConfig(scheduleConfig)

            // TriggerManagerにBluetooth接続イベントを通知する
            triggerManager.onBluetoothConnected(
                onStartPlayback = { resumeFromSaved ->
                    // 再生開始の指示をPlayerServiceに送る
                    startPlayerService(context, resumeFromSaved)
                },
                onCountdown = { remainingSeconds ->
                    // カウントダウン中（UIへの通知はPlayerViewModelが行う）
                    // ここではログだけ（必要であれば通知で表示することも可能）
                },
                onSkipped = { reason ->
                    // 再生条件を満たさなかったのでスキップ（ログだけ）
                }
            )
        }
    }

    /**
     * Bluetooth切断時の処理をするプライベートメソッド
     * カウントダウン中であればキャンセルする（再生中の音楽は止めない）
     *
     * @param context Androidコンテキスト
     */
    private fun handleBluetoothDisconnected(context: Context) {
        // TriggerManagerにBluetooth切断イベントを通知する（カウントダウンキャンセル）
        triggerManager.onBluetoothDisconnected()
    }

    /**
     * MacroDroidからの再生指示を処理するプライベートメソッド
     * folder_pathが指定された場合はPlayerServiceにフォルダを変更して再生させる
     *
     * @param context Androidコンテキスト
     * @param folderPath 上書きするフォルダパス（nullの場合は変更なし）
     */
    private fun handleMacroDroidPlay(context: Context, folderPath: String?) {
        // PlayerServiceにACTION_PLAYを送る
        val intent = Intent("com.doubleplayer.ACTION_PLAY").apply {
            // folder_pathが指定されていれば追加する
            if (folderPath != null) {
                putExtra("folder_path", folderPath)
            }
        }
        // PlayerServiceにBroadcastを送る（PlayerServiceの通知Receiverが処理する）
        context.sendBroadcast(intent)
    }

    /**
     * PlayerServiceを起動して再生を開始するプライベートメソッド
     *
     * @param context Androidコンテキスト
     * @param resumeFromSaved 保存された状態から続きを再生するかどうか
     */
    private fun startPlayerService(context: Context, resumeFromSaved: Boolean) {
        // PlayerServiceを起動するIntentを作成する
        val intent = Intent(context, PlayerService::class.java).apply {
            // 続きから再生かどうかをIntentに追加する
            putExtra("resume_from_saved", resumeFromSaved)
            // 自動起動（Bluetooth）であることを伝える
            putExtra("trigger_type", "bluetooth_auto")
        }
        // フォアグラウンドサービスとして起動する
        context.startForegroundService(intent)
    }

    /**
     * PlayerServiceにコマンドをBroadcastで送るプライベートメソッド
     * MacroDroidからの各種操作コマンドの転送に使う
     *
     * @param context Androidコンテキスト
     * @param action 送るアクション文字列
     */
    private fun sendCommandToService(context: Context, action: String) {
        // コマンドをBroadcastとして送る（PlayerServiceが内部Receiverで受け取る）
        val intent = Intent(action)
        context.sendBroadcast(intent)
    }
}
