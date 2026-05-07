package com.doubleplayer.app.ui

// Compose UIのインポート
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Hiltナビゲーションのインポート
import androidx.hilt.navigation.compose.hiltViewModel
// ナビゲーションのインポート
import androidx.navigation.NavController
// ライフサイクルのインポート
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// Android Composeのインポート
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
// Kotlinのインポート
import kotlin.math.roundToInt

/**
 * PlayerScreen - プレイヤー画面のCompose UI
 *
 * 仕様書セクション2「プレイヤー画面に表示する内容」に基づいて実装する。
 * 表示する項目：
 * - トラックA：ファイル名・再生位置バー・残り曲数
 * - トラックB：ファイル名・残り曲数・シャッフルリスト
 * - トラックA/B 音量スライダー
 * - 再生速度スライダー（0.5x〜2.0x）
 * - フェードイン/アウト時間スライダー
 * - トラックA終了何秒前にBをフェードアウト開始するか
 * - 手動再生・停止ボタン
 * - 再生履歴ボタン
 */
@Composable
fun PlayerScreen(
    // ナビゲーションコントローラー（履歴画面への遷移に使用）
    navController: NavController,
    // ViewModelはHiltで自動注入
    viewModel: PlayerViewModel = hiltViewModel()
) {
    // ========== StateFlowをCompose StateとしてCollectする ==========

    // 再生中かどうか
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    // トラックAのファイル名
    val trackAFileName by viewModel.trackAFileName.collectAsStateWithLifecycle()
    // トラックAの再生位置（ミリ秒）
    val trackAPositionMs by viewModel.trackAPositionMs.collectAsStateWithLifecycle()
    // トラックAの総再生時間（ミリ秒）
    val trackADurationMs by viewModel.trackADurationMs.collectAsStateWithLifecycle()
    // トラックAの残り曲数
    val trackARemainingCount by viewModel.trackARemainingCount.collectAsStateWithLifecycle()
    // トラックBのファイル名
    val trackBFileName by viewModel.trackBFileName.collectAsStateWithLifecycle()
    // トラックBの残り曲数
    val trackBRemainingCount by viewModel.trackBRemainingCount.collectAsStateWithLifecycle()
    // トラックBのシャッフルリスト
    val trackBShuffleList by viewModel.trackBShuffleList.collectAsStateWithLifecycle()
    // 音量・速度・フェード設定
    val trackAVolume by viewModel.trackAVolume.collectAsStateWithLifecycle()
    val trackBVolume by viewModel.trackBVolume.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val fadeInSeconds by viewModel.fadeInSeconds.collectAsStateWithLifecycle()
    val fadeOutSeconds by viewModel.fadeOutSeconds.collectAsStateWithLifecycle()
    val fadeOutBeforeEndSeconds by viewModel.fadeOutBeforeEndSeconds.collectAsStateWithLifecycle()

    // ========== サービスのバインド管理 ==========

    // 画面表示中はサービスにバインドし、非表示時にアンバインドする
    DisposableEffect(Unit) {
        viewModel.bindService()
        onDispose {
            viewModel.unbindService()
        }
    }

    // ========== UI本体 ==========

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // ========== トラックA セクション ==========
        item {
            TrackASection(
                fileName = trackAFileName,
                positionMs = trackAPositionMs,
                durationMs = trackADurationMs,
                remainingCount = trackARemainingCount,
                isPlaying = isPlaying,
                onPlayPause = {
                    if (isPlaying) viewModel.pausePlayback()
                    else if (trackAFileName.isEmpty()) viewModel.startPlayback()
                    else viewModel.resumePlayback()
                },
                onStop = { viewModel.stopPlayback() },
                onNext = { viewModel.skipToNext() },
                onPrevious = { viewModel.skipToPrevious() }
            )
        }

        // ========== トラックB セクション ==========
        item {
            TrackBSection(
                fileName = trackBFileName,
                remainingCount = trackBRemainingCount
            )
        }

        // ========== トラックBのシャッフルリスト ==========
        if (trackBShuffleList.isNotEmpty()) {
            item {
                Text(
                    text = "トラックB シャッフルリスト",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            itemsIndexed(trackBShuffleList) { index, fileName ->
                // 現在再生中のファイルを強調表示する
                val isCurrent = fileName == trackBFileName
                ShuffleListItem(
                    index = index + 1,
                    fileName = fileName,
                    isCurrent = isCurrent
                )
            }
        }

        // ========== 音量設定セクション ==========
        item {
            VolumeSection(
                trackAVolume = trackAVolume,
                trackBVolume = trackBVolume,
                onTrackAVolumeChange = { viewModel.setTrackAVolume(it) },
                onTrackBVolumeChange = { viewModel.setTrackBVolume(it) }
            )
        }

        // ========== 再生速度セクション ==========
        item {
            SpeedSection(
                speed = playbackSpeed,
                onSpeedChange = { viewModel.setPlaybackSpeed(it) }
            )
        }

        // ========== フェード設定セクション ==========
        item {
            FadeSection(
                fadeInSeconds = fadeInSeconds,
                fadeOutSeconds = fadeOutSeconds,
                fadeOutBeforeEndSeconds = fadeOutBeforeEndSeconds,
                onFadeInChange = { viewModel.setFadeInSeconds(it) },
                onFadeOutChange = { viewModel.setFadeOutSeconds(it) },
                onFadeOutBeforeEndChange = { viewModel.setFadeOutBeforeEndSeconds(it) }
            )
        }

        // ========== 再生履歴ボタン ==========
        item {
            OutlinedButton(
                onClick = { navController.navigate("history") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                // Material Iconsのhistoryアイコンを使用する
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "再生履歴",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "再生履歴を見る")
            }
        }
    }
}

/**
 * TrackASection - トラックAの表示・操作セクション
 */
@Composable
private fun TrackASection(
    fileName: String,         // 現在のファイル名
    positionMs: Long,         // 現在の再生位置（ミリ秒）
    durationMs: Long,         // 総再生時間（ミリ秒）
    remainingCount: Int,      // 残り曲数
    isPlaying: Boolean,       // 再生中かどうか
    onPlayPause: () -> Unit,  // 再生・停止ボタンのコールバック
    onStop: () -> Unit,       // 完全停止ボタンのコールバック
    onNext: () -> Unit,       // 次へボタンのコールバック
    onPrevious: () -> Unit    // 前へボタンのコールバック
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // セクションタイトル
            Text(
                text = "トラックA（メイン）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 現在のファイル名
            Text(
                text = if (fileName.isEmpty()) "ファイルが設定されていません" else fileName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 残り曲数表示
            Text(
                text = "残り ${remainingCount} 曲",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 再生位置バー
            if (durationMs > 0) {
                // 再生進行率を0.0〜1.0で計算する
                val progress = positionMs.toFloat() / durationMs.toFloat()
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                // 再生位置と総時間を表示する
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = formatTime(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            } else {
                // 時間情報がない場合はインジケーターを表示しない
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 操作ボタン行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 前へボタン
                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "前へ",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                // 再生・一時停止ボタン（状態によってアイコン切り替え）
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "一時停止" else "再生",
                        modifier = Modifier.size(32.dp)
                    )
                }
                // 次へボタン
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "次へ",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                // 完全停止ボタン
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * TrackBSection - トラックBの表示セクション
 */
@Composable
private fun TrackBSection(
    fileName: String,       // 現在のファイル名
    remainingCount: Int     // 残り曲数
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // セクションタイトル
            Text(
                text = "トラックB（BGM）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 現在のファイル名
            Text(
                text = if (fileName.isEmpty()) "再生停止中" else fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            // 残り曲数
            Text(
                text = "残り ${remainingCount} 曲（シャッフル）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * ShuffleListItem - シャッフルリストの1件分のアイテム
 */
@Composable
private fun ShuffleListItem(
    index: Int,         // リスト番号
    fileName: String,   // ファイル名
    isCurrent: Boolean  // 現在再生中かどうか
) {
    // 現在再生中のアイテムは強調表示する
    val backgroundColor = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 番号表示
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.End
            )
            Spacer(modifier = Modifier.width(8.dp))
            // 現在再生中アイコン
            if (isCurrent) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "再生中",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            // ファイル名表示
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

/**
 * VolumeSection - 音量スライダーセクション
 */
@Composable
private fun VolumeSection(
    trackAVolume: Float,              // トラックAの音量（0.0〜1.0）
    trackBVolume: Float,              // トラックBの音量（0.0〜1.0）
    onTrackAVolumeChange: (Float) -> Unit,  // トラックA音量変更コールバック
    onTrackBVolumeChange: (Float) -> Unit   // トラックB音量変更コールバック
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // セクションタイトル
            Text(
                text = "音量",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // トラックA音量スライダー
            SliderRow(
                label = "トラックA",
                value = trackAVolume,
                valueRange = 0f..1f,
                displayText = "${(trackAVolume * 100).roundToInt()}%",
                onValueChange = onTrackAVolumeChange
            )

            Spacer(modifier = Modifier.height(4.dp))

            // トラックB音量スライダー
            SliderRow(
                label = "トラックB",
                value = trackBVolume,
                valueRange = 0f..1f,
                displayText = "${(trackBVolume * 100).roundToInt()}%",
                onValueChange = onTrackBVolumeChange
            )
        }
    }
}

/**
 * SpeedSection - 再生速度スライダーセクション
 */
@Composable
private fun SpeedSection(
    speed: Float,                    // 現在の速度（0.5〜2.0）
    onSpeedChange: (Float) -> Unit   // 速度変更コールバック
) {
    // スライダーの内部状態（0.25刻みでスナップするため）
    var sliderValue by remember { mutableFloatStateOf(speed) }
    // 外部からspeedが変更された場合に内部状態を同期する
    LaunchedEffect(speed) { sliderValue = speed }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // セクションタイトル
            Text(
                text = "再生速度",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            SliderRow(
                label = "",
                value = sliderValue,
                valueRange = 0.5f..2.0f,
                steps = 5, // 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0の7段階（steps=5は中間点数）
                displayText = "×${"%.2f".format(sliderValue)}",
                onValueChange = { value ->
                    // 0.25刻みにスナップする
                    val snapped = (value / 0.25f).roundToInt() * 0.25f
                    sliderValue = snapped.coerceIn(0.5f, 2.0f)
                },
                onValueChangeFinished = { onSpeedChange(sliderValue) }
            )
        }
    }
}

/**
 * FadeSection - フェードイン/アウト設定セクション
 */
@Composable
private fun FadeSection(
    fadeInSeconds: Float,                     // フェードイン秒数
    fadeOutSeconds: Float,                    // フェードアウト秒数
    fadeOutBeforeEndSeconds: Float,           // 終了前フェードアウト秒数
    onFadeInChange: (Float) -> Unit,          // フェードイン変更コールバック
    onFadeOutChange: (Float) -> Unit,         // フェードアウト変更コールバック
    onFadeOutBeforeEndChange: (Float) -> Unit // 終了前フェードアウト変更コールバック
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // セクションタイトル
            Text(
                text = "フェード設定",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // フェードイン秒数スライダー
            SliderRow(
                label = "フェードイン",
                value = fadeInSeconds,
                valueRange = 0f..10f,
                displayText = "${fadeInSeconds.roundToInt()}秒",
                onValueChange = onFadeInChange
            )

            Spacer(modifier = Modifier.height(4.dp))

            // フェードアウト秒数スライダー
            SliderRow(
                label = "フェードアウト",
                value = fadeOutSeconds,
                valueRange = 0f..10f,
                displayText = "${fadeOutSeconds.roundToInt()}秒",
                onValueChange = onFadeOutChange
            )

            Spacer(modifier = Modifier.height(4.dp))

            // トラックA終了前フェードアウト開始秒数スライダー
            SliderRow(
                label = "A終了前B停止",
                value = fadeOutBeforeEndSeconds,
                valueRange = 0f..30f,
                displayText = "${fadeOutBeforeEndSeconds.roundToInt()}秒前",
                onValueChange = onFadeOutBeforeEndChange
            )
        }
    }
}

/**
 * SliderRow - ラベル・スライダー・値表示を横並びにしたコンポーネント
 */
@Composable
private fun SliderRow(
    label: String,                          // ラベルテキスト
    value: Float,                           // 現在の値
    valueRange: ClosedFloatingPointRange<Float>, // 値の範囲
    steps: Int = 0,                         // ステップ数（0は連続）
    displayText: String,                    // 表示テキスト（値の右に表示）
    onValueChange: (Float) -> Unit,         // 値変更コールバック
    onValueChangeFinished: (() -> Unit)? = null // 操作完了コールバック
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ラベル（あれば表示する）
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(80.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // スライダー（ラベルの幅分を引いた残り幅に配置する）
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        // 現在値のテキスト表示
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * formatTime - ミリ秒をmm:ss形式の文字列に変換するヘルパー関数
 * @param ms ミリ秒
 * @return "mm:ss"形式の文字列
 */
private fun formatTime(ms: Long): String {
    // ミリ秒を秒に変換する
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    // 分と秒に分解する
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    // ゼロパディングしてフォーマットする
    return "%02d:%02d".format(minutes, seconds)
}