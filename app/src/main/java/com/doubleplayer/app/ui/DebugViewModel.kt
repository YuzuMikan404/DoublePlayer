package com.doubleplayer.app.ui

// Android基本のインポート
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
// ViewModelのインポート
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// Hiltのインポート
import dagger.hilt.android.lifecycle.HiltViewModel
// コルーチン関連のインポート
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
// 自アプリのインポート
import com.doubleplayer.app.debug.DebugLogger
import javax.inject.Inject

/**
 * DebugViewModel - デバッグ画面の状態管理クラス
 *
 * DebugLoggerのStateFlowを購読してUIに反映する。
 * カテゴリフィルターの状態管理も担当する。
 */
@HiltViewModel
class DebugViewModel @Inject constructor(
    // DebugLoggerをHiltから注入する
    private val debugLogger: DebugLogger
) : ViewModel() {

    // ========== フィルター状態 ==========

    // 現在選択中のカテゴリフィルター（nullはすべて表示）
    private val _selectedFilter = MutableStateFlow<DebugLogger.Category?>(null)
    val selectedFilter: StateFlow<DebugLogger.Category?> = _selectedFilter.asStateFlow()

    // 自動スクロール有効フラグ（デフォルトON）
    private val _autoScroll = MutableStateFlow(true)
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    // ========== フィルター済みログ ==========

    // フィルター適用後のログ一覧（UIに表示する）
    private val _filteredLogs = MutableStateFlow<List<DebugLogger.DebugEntry>>(emptyList())
    val filteredLogs: StateFlow<List<DebugLogger.DebugEntry>> = _filteredLogs.asStateFlow()

    // ========== 初期化 ==========

    init {
        // ログとフィルターの両方が変化したときにフィルター済みリストを更新する
        viewModelScope.launch {
            combine(
                debugLogger.logs,
                _selectedFilter
            ) { logs, filter ->
                // フィルターがnullなら全件、そうでなければカテゴリで絞り込む
                if (filter == null) logs else logs.filter { it.category == filter }
            }.collectLatest { filtered ->
                _filteredLogs.value = filtered
            }
        }
    }

    // ========== ユーザー操作メソッド ==========

    /**
     * カテゴリフィルターを変更するメソッド
     * @param category null=すべて表示、それ以外=指定カテゴリのみ
     */
    fun setFilter(category: DebugLogger.Category?) {
        _selectedFilter.value = category
    }

    /**
     * 自動スクロールの有効・無効を切り替えるメソッド
     * @param enabled trueで有効
     */
    fun setAutoScroll(enabled: Boolean) {
        _autoScroll.value = enabled
    }

    /**
     * ログを全件クリアするメソッド
     * 「クリア」ボタンから呼ばれる
     */
    fun clearLogs() {
        debugLogger.clearLogs()
    }

    /**
     * ログ全文をクリップボードにコピーするメソッド
     * 「コピー」ボタンから呼ばれる
     * @param context クリップボードサービスの取得に使用
     */
    fun copyLogsToClipboard(context: Context) {
        // ログテキストを取得する
        val text = debugLogger.exportAsText()
        // クリップボードサービスを取得する
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // クリップボードにセットする
        val clip = ClipData.newPlainText("DoublePlayer デバッグログ", text)
        clipboard.setPrimaryClip(clip)
        // コピーログを自分自身にも記録する
        debugLogger.log(DebugLogger.Category.INFO, "ログをクリップボードにコピーしました (${_filteredLogs.value.size}件)")
    }

    /**
     * ログをファイルに保存してシェアシートで共有するメソッド
     * 「共有」ボタンから呼ばれる
     * @param context シェアシートの起動に使用
     */
    fun shareLog(context: Context) {
        debugLogger.log(DebugLogger.Category.INFO, "ログをファイルに保存して共有します")
        debugLogger.shareLog(context)
    }
}
