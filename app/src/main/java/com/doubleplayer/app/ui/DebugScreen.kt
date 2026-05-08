package com.doubleplayer.app.ui

// Android基本のインポート
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
// Compose UIのインポート
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// ライフサイクルのインポート
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// Hiltのインポート
import androidx.hilt.navigation.compose.hiltViewModel
// 自アプリのインポート
import com.doubleplayer.app.debug.DebugLogger
import kotlinx.coroutines.launch

/**
 * DebugScreen - デバッグ情報を表示するCompose画面
 *
 * 設定タブ内のデバッグタブとして表示する。
 * 以下の機能を提供する：
 * - リアルタイムログの表示（カテゴリ別に色分け）
 * - カテゴリフィルター（再生・音量・ファイル・エラーなど）
 * - 「コピー」でクリップボードにコピー（AIへのペースト用）
 * - 「ファイルに保存して共有」でシェアシートを開く
 * - 「クリア」でログを全消去
 * - 自動スクロール（新しいログが追加されたら末尾に移動）
 */
@Composable
fun DebugScreen(
    viewModel: DebugViewModel = hiltViewModel()
) {
    // ========== 状態の収集 ==========

    // ログ一覧をStateとして収集する
    val logs by viewModel.filteredLogs.collectAsStateWithLifecycle()
    // 選択中のカテゴリフィルター
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    // 自動スクロール有効フラグ
    val autoScroll by viewModel.autoScroll.collectAsStateWithLifecycle()

    // LazyColumnのスクロール状態
    val listState = rememberLazyListState()
    // コルーチンスコープ（スクロール操作に使う）
    val scope = rememberCoroutineScope()
    // Contextの取得
    val context = LocalContext.current

    // ========== 自動スクロール ==========

    // ログが追加されたら末尾にスクロールする
    LaunchedEffect(logs.size) {
        if (autoScroll && logs.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(logs.size - 1)
            }
        }
    }

    // ========== UI本体 ==========

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        // ========== ヘッダー（タイトル + 件数バッジ）==========
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // タイトルとログ件数
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = "デバッグ",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "デバッグログ",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                // ログ件数バッジ
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "${logs.size}件",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            // 自動スクロールトグル
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "自動スクロール",
                    style = MaterialTheme.typography.labelSmall
                )
                Switch(
                    checked = autoScroll,
                    onCheckedChange = { viewModel.setAutoScroll(it) },
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        // ========== カテゴリフィルターチップ ==========
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 「すべて」チップ
            FilterChip(
                selected = selectedFilter == null,
                onClick = { viewModel.setFilter(null) },
                label = { Text("すべて") }
            )
            // カテゴリごとのフィルターチップ
            DebugLogger.Category.entries.forEach { category ->
                FilterChip(
                    selected = selectedFilter == category,
                    onClick = { viewModel.setFilter(category) },
                    label = { Text("${category.emoji} ${category.label}") }
                )
            }
        }

        // ========== アクションボタン行 ==========
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 「コピー」ボタン（AIにそのまま貼り付け用）
            OutlinedButton(
                onClick = {
                    viewModel.copyLogsToClipboard(context)
                    Toast.makeText(context, "ログをコピーしました", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "コピー",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "コピー", style = MaterialTheme.typography.labelMedium)
            }
            // 「共有」ボタン（シェアシート経由でファイル共有）
            OutlinedButton(
                onClick = { viewModel.shareLog(context) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "共有",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "共有", style = MaterialTheme.typography.labelMedium)
            }
            // 「クリア」ボタン
            OutlinedButton(
                onClick = { viewModel.clearLogs() },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "クリア",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "クリア", style = MaterialTheme.typography.labelMedium)
            }
        }

        // ========== ログリスト ==========
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            color = Color(0xFF0D1117),  // ダークな端末ターミナル風の背景色
            shape = MaterialTheme.shapes.medium
        ) {
            if (logs.isEmpty()) {
                // ログが空の場合のプレースホルダー
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = Color(0xFF58A6FF),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ログがまだありません",
                            color = Color(0xFF8B949E),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "再生を開始するとここにログが表示されます",
                            color = Color(0xFF6E7681),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                // ログ一覧の表示
                LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(
                        items = logs,
                        key = { "${it.timestamp}_${it.message.hashCode()}" }
                    ) { entry ->
                        DebugLogItem(entry = entry)
                    }
                }
            }
        }
    }
}

/**
 * DebugLogItem - ログ1件を表示するComposable
 * カテゴリに応じて色を変える
 */
@Composable
private fun DebugLogItem(entry: DebugLogger.DebugEntry) {
    // カテゴリに対応したテキスト色を返すヘルパー
    val textColor = when (entry.category) {
        DebugLogger.Category.ERROR -> Color(0xFFFF7B72)      // 赤（エラー）
        DebugLogger.Category.PLAYBACK -> Color(0xFF79C0FF)   // 青（再生）
        DebugLogger.Category.VOLUME -> Color(0xFF56D364)     // 緑（音量）
        DebugLogger.Category.FILE -> Color(0xFFD2A8FF)       // 紫（ファイル）
        DebugLogger.Category.SERVICE -> Color(0xFFFFA657)    // オレンジ（サービス）
        DebugLogger.Category.TRIGGER -> Color(0xFFE3B341)    // 黄（トリガー）
        DebugLogger.Category.INFO -> Color(0xFF8B949E)       // グレー（一般）
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        // タイムスタンプ + カテゴリ + メッセージ
        Row(
            verticalAlignment = Alignment.Top
        ) {
            // タイムスタンプ
            Text(
                text = entry.timestamp,
                color = Color(0xFF6E7681),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(80.dp)
            )
            // カテゴリラベル
            Surface(
                color = textColor.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Text(
                    text = entry.category.label,
                    color = textColor,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            // メッセージ本文
            Text(
                text = entry.message,
                color = Color(0xFFE6EDF3),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        // スタックトレースがある場合は展開して表示する
        if (entry.stackTrace != null) {
            Text(
                text = entry.stackTrace,
                color = Color(0xFFFF7B72).copy(alpha = 0.8f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 80.dp, top = 2.dp)
            )
        }
    }
}
