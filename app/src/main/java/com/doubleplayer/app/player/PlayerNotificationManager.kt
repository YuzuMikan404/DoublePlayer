package com.doubleplayer.app.player

// Android通知関連のインポート
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
// Media3通知関連のインポート
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
// Material Iconsは通知では使えないためR.drawableを使用する
import com.doubleplayer.app.MainActivity
// Hilt依存注入のインポート
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PlayerNotificationManager - MediaStyle通知を管理するクラス
 *
 * 仕様書セクション2「通知（MediaStyle通知）」に基づいて実装する。
 * 通知に表示する内容：
 * - トラックA現在のファイル名
 * - トラックB現在のファイル名
 * - ボタン：前へ / 停止・再開 / 次へ / 終了
 *
 * ForegroundServiceの通知としても使用する。
 */
@Singleton
class PlayerNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context  // アプリのコンテキスト
) {

    // 通知チャンネルID（アプリ全体で固定する）
    val notificationChannelId = "doubleplayer_playback"

    // フォアグラウンドサービス用の通知ID（PlayerServiceと共有する）
    val notificationId = 1001

    // Androidシステムの通知マネージャー
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // BroadcastIntent用のアクション文字列（仕様書セクション7）
    companion object {
        // 再生ボタンのアクション
        const val ACTION_PLAY = "com.doubleplayer.ACTION_PLAY"
        // 一時停止ボタンのアクション
        const val ACTION_PAUSE = "com.doubleplayer.ACTION_PAUSE"
        // 停止ボタンのアクション
        const val ACTION_STOP = "com.doubleplayer.ACTION_STOP"
        // 次へボタンのアクション
        const val ACTION_NEXT = "com.doubleplayer.ACTION_NEXT"
        // 前へボタンのアクション
        const val ACTION_PREV = "com.doubleplayer.ACTION_PREV"
        // アプリ終了ボタンのアクション
        const val ACTION_EXIT = "com.doubleplayer.ACTION_EXIT"
    }

    /**
     * 通知チャンネルを作成するメソッド
     * アプリ起動時に一度だけ呼ぶ（Android 8.0以上で必要）
     */
    fun createNotificationChannel() {
        // Android 8.0以上でのみ通知チャンネルを作成する
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 通知チャンネルを定義する
            val channel = NotificationChannel(
                notificationChannelId,                   // チャンネルID
                "DoublePlayer 再生中",                    // ユーザーに表示するチャンネル名
                NotificationManager.IMPORTANCE_LOW       // 低優先度（音を出さない）
            ).apply {
                // 通知チャンネルの説明文
                description = "DoublePlayerの再生中に表示する通知"
                // 通知の際にサウンドを鳴らさない
                setSound(null, null)
                // 通知の際に振動しない
                enableVibration(false)
            }
            // 通知マネージャーにチャンネルを登録する
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 再生中の通知を構築して返すメソッド
     * ForegroundServiceのstartForeground()に渡す
     *
     * @param trackAFileName トラックAの現在のファイル名
     * @param trackBFileName トラックBの現在のファイル名
     * @param isPlaying 現在再生中かどうか
     * @return 構築した通知オブジェクト
     */
    fun buildNotification(
        trackAFileName: String,
        trackBFileName: String,
        isPlaying: Boolean
    ): Notification {
        // アプリを開くPendingIntentを生成する
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                // 既存のタスクに戻る設定
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 通知を構築して返す
        return NotificationCompat.Builder(context, notificationChannelId)
            // 通知のタイトル（トラックAのファイル名）
            .setContentTitle("A: $trackAFileName")
            // 通知のサブテキスト（トラックBのファイル名）
            .setContentText("B: $trackBFileName")
            // 通知アイコン（Material Iconsは通知で使えないためアプリアイコンを使用する）
            .setSmallIcon(android.R.drawable.ic_media_play)
            // 通知タップ時にアプリを開くIntentを設定する
            .setContentIntent(contentIntent)
            // 通知を消せないようにする（ForegroundService中は必須）
            .setOngoing(true)
            // 通知を常に表示する（ロック画面でも表示する）
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // 前へボタン
            .addAction(
                android.R.drawable.ic_media_previous,
                "前へ",
                buildActionPendingIntent(ACTION_PREV, 1)
            )
            // 停止・再開ボタン（再生状態によってアイコンを変える）
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "一時停止" else "再開",
                buildActionPendingIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY, 2)
            )
            // 次へボタン
            .addAction(
                android.R.drawable.ic_media_next,
                "次へ",
                buildActionPendingIntent(ACTION_NEXT, 3)
            )
            // 終了ボタン
            .addAction(
                android.R.drawable.ic_delete,
                "終了",
                buildActionPendingIntent(ACTION_EXIT, 4)
            )
            // MediaStyleを適用して音楽プレイヤー風の通知にする
            // （androidx.media:mediaライブラリ不要のシンプル実装）
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("A: $trackAFileName  B: $trackBFileName")
            )
            .build()
    }

    /**
     * ボタン押下時のPendingIntentを構築するプライベートメソッド
     *
     * @param action 送信するBroadcastアクション文字列
     * @param requestCode リクエストコード（ボタンごとに異なる値を使う）
     * @return PendingIntentオブジェクト
     */
    private fun buildActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        // BroadcastReceiverに送るIntentを生成する
        val intent = Intent(action).apply {
            // アプリのパッケージを指定して他アプリへ漏れないようにする
            setPackage(context.packageName)
        }
        // PendingIntentを生成して返す
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知を更新するメソッド
     * 再生状態が変化したときに呼ぶ
     *
     * @param trackAFileName トラックAの現在のファイル名
     * @param trackBFileName トラックBの現在のファイル名
     * @param isPlaying 現在再生中かどうか
     */
    fun updateNotification(
        trackAFileName: String,
        trackBFileName: String,
        isPlaying: Boolean
    ) {
        // 新しい通知を構築して通知マネージャーに通知する
        val notification = buildNotification(trackAFileName, trackBFileName, isPlaying)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * 通知を削除するメソッド
     * サービス停止時に呼ぶ
     */
    fun cancelNotification() {
        // 通知を削除する
        notificationManager.cancel(notificationId)
    }
}