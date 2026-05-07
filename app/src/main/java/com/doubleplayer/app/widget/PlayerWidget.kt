package com.doubleplayer.app.widget

// Android基本のインポート
import android.content.Context
import android.content.Intent
// Glance（Composeベースのウィジェット）のインポート
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
// Composeのインポート
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PlayerWidget - ホーム画面ウィジェット（簡易コントロール）
 *
 * 仕様書セクション3「widget/PlayerWidget.kt」に基づいて実装する。
 * Jetpack Glance（Composeベースのウィジェット）を使用して以下のボタンを表示する：
 * - 前へ（⏮）
 * - 再生・停止（▶/⏸）
 * - 次へ（⏭）
 * - 終了（✕）
 *
 * ウィジェットはBroadcastIntentでPlayerServiceを操作する。
 * Glanceはアプリプロセス外で動作するためHiltは直接使えない。
 * ActionCallbackを使ってIntentを送信する方式にする。
 */
class PlayerWidget : GlanceAppWidget() {

    /**
     * provideGlance - ウィジェットのUIを提供するメソッド
     * 再生状態はDataStoreから直接読み込む
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // ウィジェットのUIを構築する
            WidgetContent()
        }
    }
}

/**
 * WidgetContent - ウィジェットのCompose UIコンテンツ
 * Glance Compose APIを使用する（通常のComposeとは異なるAPIを使う）
 * 注意：GlanceのComposableは通常のJetpack Composeと異なるAPIを使う
 */
@Composable
private fun WidgetContent() {
    // ウィジェット全体の背景カラー（GlanceはColor.copy()をサポートしないため直接指定）
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xE61C1B1F)) // alpha=0.9相当の値を直接指定する
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        // ボタンを横一列に並べる
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            // ========== 前へボタン ==========
            WidgetButton(
                text = "⏮",
                onClick = actionRunCallback<PrevActionCallback>()
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            // ========== 再生・停止ボタン ==========
            WidgetButton(
                text = "▶",
                onClick = actionRunCallback<PlayPauseActionCallback>()
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            // ========== 次へボタン ==========
            WidgetButton(
                text = "⏭",
                onClick = actionRunCallback<NextActionCallback>()
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            // ========== 終了ボタン ==========
            WidgetButton(
                text = "✕",
                onClick = actionRunCallback<StopActionCallback>()
            )
        }
    }
}

/**
 * WidgetButton - ウィジェット用のボタンコンポーネント
 * Glance APIを使用する（通常のComposeとは異なるAPIを使う）
 */
@Composable
private fun WidgetButton(
    text: String,                             // ボタンのテキスト
    onClick: androidx.glance.action.Action    // クリック時のアクション
) {
    Box(
        modifier = GlanceModifier
            .size(48.dp)
            .background(Color(0xFF49454F))
            .clickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = ColorProvider(Color.White),
                fontSize = 20.sp
            )
        )
    }
}

// ========== ActionCallback定義 ==========
// 各ボタンのクリック時にPlayerServiceにBroadcastIntentを送信する

/**
 * PlayPauseActionCallback - 再生・一時停止ボタンのコールバック
 */
class PlayPauseActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // PlayerServiceに再生・一時停止のBroadcastを送信する
        // 現在の再生状態はPlayerServiceが判断する
        val intent = Intent(ACTION_TOGGLE_PLAY_PAUSE).apply {
            // PlayerServiceのBroadcastReceiverに届くようにパッケージを指定する
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}

/**
 * NextActionCallback - 次へボタンのコールバック
 */
class NextActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // PlayerServiceに次のトラックへのBroadcastを送信する
        val intent = Intent(ACTION_NEXT).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}

/**
 * PrevActionCallback - 前へボタンのコールバック
 */
class PrevActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // PlayerServiceに前のトラックへのBroadcastを送信する
        val intent = Intent(ACTION_PREV).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}

/**
 * StopActionCallback - 終了ボタンのコールバック
 */
class StopActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // PlayerServiceに完全停止のBroadcastを送信する
        val intent = Intent(ACTION_STOP).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}

/**
 * PlayerWidgetReceiver - ウィジェットのBroadcastReceiver
 * GlanceAppWidgetReceiverを継承することでウィジェットの更新・削除を処理する
 */
class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    // GlanceAppWidgetReceiverが使うウィジェットのインスタンス
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

// ========== BroadcastActionの文字列定数 ==========
// 仕様書セクション7「MacroDroid連携」と同じActionを使用する

/** 再生・一時停止の切り替えAction（ウィジェット専用） */
private const val ACTION_TOGGLE_PLAY_PAUSE = "com.doubleplayer.ACTION_TOGGLE"

/** 次のトラックへのAction */
private const val ACTION_NEXT = "com.doubleplayer.ACTION_NEXT"

/** 前のトラックへのAction */
private const val ACTION_PREV = "com.doubleplayer.ACTION_PREV"

/** 完全停止Action */
private const val ACTION_STOP = "com.doubleplayer.ACTION_STOP"
