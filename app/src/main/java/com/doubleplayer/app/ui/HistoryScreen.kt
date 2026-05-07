package com.doubleplayer.app.ui

// Compose UIのインポート
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// Hiltのインポート
import androidx.hilt.navigation.compose.hiltViewModel
// ライフサイクルのインポート
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// Hiltのインポート
import dagger.hilt.android.lifecycle.HiltViewModel
// コルーチンのインポート
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
// 自アプリのインポート
import com.doubleplayer.app.storage.PlayHistoryStore
import javax.inject.Inject

/**
 * HistoryViewModel - 再生履歴画面の状態管理クラス
 *
 * PlayHistoryStoreから履歴を読み込んでUIに提供する。
 * HistoryScreen.ktと同一ファイルに定義する（小規模なViewModelのため）。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    // 再生履歴の保存・読み込み
    private val playHistoryStore: PlayHistoryStore
) : ViewModel() {

    // ========== UIに公開する状態 ==========

    // 再生履歴リスト（最新順）
    private val _historyList = MutableStateFlow<List<PlayHistoryStore.PlayHistoryEntry>>(emptyList())
    val historyList: StateFlow<List<PlayHistoryStore.PlayHistoryEntry>> = _historyList.asStateFlow()

    // 読み込み中フラグ
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 削除確認ダイアログ表示フラグ
    private val _showClearDialog = MutableStateFlow(false)
    val showClearDialog: StateFlow<Boolean> = _showClearDialog.asStateFlow()

    // ========== 初期化処理 ==========

    init {
        // PlayHistoryStoreのFlowを監視して履歴リストを更新する
        viewModelScope.launch {
            playHistoryStore.historyFlow.collectLatest { list ->
                // 最新順（日付・時刻の降順）に並べ替える
                _historyList.value = list.sortedWith(
                    compareByDescending<PlayHistoryStore.PlayHistoryEntry> { it.date }
                        .thenByDescending { it.time }
                )
                // 読み込み完了
                _isLoading.value = false
            }
        }
    }

    // ========== 操作メソッド ==========

    /**
     * requestClear - 履歴削除確認ダイアログを表示する
     */
    fun requestClear() {
        _showClearDialog.value = true
    }

    /**
     * confirmClear - 履歴削除を確定する
     */
    fun confirmClear() {
        viewModelScope.launch {
            // PlayHistoryStoreの全履歴削除を呼ぶ
            playHistoryStore.clearHistory()
            // ダイアログを閉じる
            _showClearDialog.value = false
        }
    }

    /**
     * dismissClearDialog - 履歴削除ダイアログをキャンセルする
     */
    fun dismissClearDialog() {
        _showClearDialog.value = false
    }
}

/**
 * HistoryScreen - 再生履歴画面のCompose UI
 *
 * 仕様書セクション3「ui/HistoryScreen.kt」に基づいて実装する。
 * PlayHistoryStoreに保存されている最大100件の再生履歴を一覧表示する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    // 前の画面に戻るナビゲーションコールバック
    onNavigateBack: () -> Unit,
    // ViewModelはHiltで自動注入
    viewModel: HistoryViewModel = hiltViewModel()
) {
    // ========== 状態の収集 ==========

    val historyList by viewModel.historyList.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val showClearDialog by viewModel.showClearDialog.collectAsStateWithLifecycle()

    // ========== UI本体 ==========

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("再生履歴") },
                navigationIcon = {
                    // 戻るボタン
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る"
                        )
                    }
                },
                actions = {
                    // 履歴削除ボタン（履歴がある場合のみ表示）
                    if (historyList.isNotEmpty()) {
                        IconButton(onClick = { viewModel.requestClear() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "履歴を削除"
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                // 読み込み中
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // 履歴なし
                historyList.isEmpty() -> {
                    EmptyHistoryContent(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // 履歴あり
                else -> {
                    HistoryList(historyList = historyList)
                }
            }
        }
    }

    // ========== 削除確認ダイアログ ==========
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearDialog() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "警告"
                )
            },
            title = { Text("履歴を削除") },
            text = { Text("全ての再生履歴を削除します。この操作は取り消せません。") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmClear() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissClearDialog() }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

/**
 * EmptyHistoryContent - 履歴が0件の時に表示するコンテンツ
 */
@Composable
private fun EmptyHistoryContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 空の状態アイコン
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        // 空の状態メッセージ
        Text(
            text = "再生履歴がありません",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "再生が完了すると履歴に記録されます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/**
 * HistoryList - 再生履歴をリスト表示するコンポーネント
 */
@Composable
private fun HistoryList(
    historyList: List<PlayHistoryStore.PlayHistoryEntry>
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(historyList) { entry ->
            HistoryItem(entry = entry)
        }
    }
}

/**
 * HistoryItem - 再生履歴の1件分を表示するカードコンポーネント
 */
@Composable
private fun HistoryItem(
    entry: PlayHistoryStore.PlayHistoryEntry
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.completed)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 完了・未完了アイコン
            Icon(
                imageVector = if (entry.completed)
                    Icons.Default.CheckCircle
                else
                    Icons.Default.Cancel,
                contentDescription = if (entry.completed) "完了" else "未完了",
                tint = if (entry.completed)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 日付・時刻の表示
                Text(
                    text = "${entry.date}  ${entry.time}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(2.dp))

                // 再生したトラックAのファイル名
                Text(
                    text = entry.trackAFile.ifEmpty { "（ファイル名不明）" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // トリガー種別の表示（日本語に変換する）
                Text(
                    text = triggerDisplayName(entry.trigger),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * triggerDisplayName - トリガー種別を日本語表示名に変換するヘルパー関数
 * @param trigger トリガー種別文字列（例: "bluetooth_auto", "manual"）
 * @return 日本語の表示名
 */
private fun triggerDisplayName(trigger: String): String {
    return when (trigger) {
        // Bluetooth自動再生
        "bluetooth_auto" -> "Bluetooth自動再生"
        // 手動再生
        "manual" -> "手動再生"
        // MacroDroid経由の再生
        "macrodroid" -> "MacroDroid"
        // 不明なトリガー
        else -> trigger
    }
}