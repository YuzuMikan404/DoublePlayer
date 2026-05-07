package com.doubleplayer.app.ui

// Android Uriのインポート（インポート時に使用）
import android.net.Uri
// Android Intentのインポート（エクスポート時の共有に使用）
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// Hilt依存注入のインポート
import dagger.hilt.android.lifecycle.HiltViewModel
// コルーチン関連のインポート
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
// 自アプリのインポート
import com.doubleplayer.app.storage.SettingsStore
import com.doubleplayer.app.storage.FileScanner
import com.doubleplayer.app.schedule.ScheduleConfig
import com.doubleplayer.app.schedule.TriggerConfig
import com.doubleplayer.app.schedule.ActiveDaysConfig
import com.doubleplayer.app.backup.BackupManager
import javax.inject.Inject

/**
 * SettingsViewModel - 設定画面の状態管理クラス
 *
 * 仕様書セクション3「ui/SettingsViewModel.kt」に基づいて実装する。
 * SettingsStoreから設定値を読み込み、変更時にDataStoreへ保存する。
 *
 * このクラスが担当する責務：
 * - フォルダ設定（トラックA・トラックBのフォルダパス）の管理
 * - スケジュール設定（時間帯・曜日・祝日スキップ）の管理
 * - 再生設定（音量・速度・フェード・イコライザー）の管理
 * - 設定のエクスポート・インポート（BackupManager委譲）
 * - FileScannerを使ったフォルダ内ファイル数の表示
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    // 設定値の保存・読み込み
    private val settingsStore: SettingsStore,
    // ファイルスキャン（フォルダ選択後のファイル数確認）
    private val fileScanner: FileScanner,
    // 設定のバックアップ（エクスポート・インポート）
    private val backupManager: BackupManager
) : ViewModel() {

    // ========== フォルダ設定 ==========

    // トラックAのフォルダパス
    private val _trackAFolder = MutableStateFlow("")
    val trackAFolder: StateFlow<String> = _trackAFolder.asStateFlow()

    // トラックBのフォルダパス
    private val _trackBFolder = MutableStateFlow("")
    val trackBFolder: StateFlow<String> = _trackBFolder.asStateFlow()

    // トラックAのフォルダ内ファイル数（スキャン後に更新）
    private val _trackAFileCount = MutableStateFlow(0)
    val trackAFileCount: StateFlow<Int> = _trackAFileCount.asStateFlow()

    // トラックBのフォルダ内ファイル数（サブフォルダ含む）
    private val _trackBFileCount = MutableStateFlow(0)
    val trackBFileCount: StateFlow<Int> = _trackBFileCount.asStateFlow()

    // ========== 再生設定 ==========

    // トラックAの音量（0.0〜1.0）
    private val _trackAVolume = MutableStateFlow(0.8f)
    val trackAVolume: StateFlow<Float> = _trackAVolume.asStateFlow()

    // トラックBの音量（0.0〜1.0）
    private val _trackBVolume = MutableStateFlow(0.3f)
    val trackBVolume: StateFlow<Float> = _trackBVolume.asStateFlow()

    // トラックBをトラックAに連動するかどうか
    private val _trackBLinked = MutableStateFlow(true)
    val trackBLinked: StateFlow<Boolean> = _trackBLinked.asStateFlow()

    // トラックBの連動比率（0.0〜1.0）
    private val _trackBLinkedRatio = MutableStateFlow(0.4f)
    val trackBLinkedRatio: StateFlow<Float> = _trackBLinkedRatio.asStateFlow()

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

    // イコライザー低音レベル（-10〜10）
    private val _bassLevel = MutableStateFlow(0)
    val bassLevel: StateFlow<Int> = _bassLevel.asStateFlow()

    // イコライザー高音レベル（-10〜10）
    private val _trebleLevel = MutableStateFlow(0)
    val trebleLevel: StateFlow<Int> = _trebleLevel.asStateFlow()

    // ========== スケジュール設定 ==========

    // スケジュール設定全体
    private val _scheduleConfig = MutableStateFlow(ScheduleConfig())
    val scheduleConfig: StateFlow<ScheduleConfig> = _scheduleConfig.asStateFlow()

    // ========== UI操作フィードバック ==========

    // メッセージ（スナックバー表示用、空文字は非表示）
    private val _snackbarMessage = MutableStateFlow("")
    val snackbarMessage: StateFlow<String> = _snackbarMessage.asStateFlow()

    // エクスポート・インポートの処理中フラグ
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // ========== 初期化処理 ==========

    init {
        // DataStoreから全設定値を読み込む
        loadAllSettings()
    }

    /**
     * loadAllSettings - DataStoreから全設定を読み込む
     * ViewModelの初期化時に呼ばれる
     */
    private fun loadAllSettings() {
        // フォルダパスを読み込む
        viewModelScope.launch {
            settingsStore.trackAFolder.collectLatest { path ->
                _trackAFolder.value = path
                // フォルダパスが設定されていればスキャンしてファイル数を取得する
                if (path.isNotEmpty()) {
                    fileScanner.scanTrackAFolder(path)
                    _trackAFileCount.value = fileScanner.getTrackAFileCount()
                }
            }
        }
        viewModelScope.launch {
            settingsStore.trackBFolder.collectLatest { path ->
                _trackBFolder.value = path
                // トラックBはサブフォルダも含めてスキャンしてカウントする
                if (path.isNotEmpty()) {
                    fileScanner.scanTrackBFolder(path)
                    _trackBFileCount.value = fileScanner.getTrackBFileCount()
                }
            }
        }
        // 音量設定を読み込む
        viewModelScope.launch {
            settingsStore.trackAVolume.collectLatest { _trackAVolume.value = it }
        }
        viewModelScope.launch {
            settingsStore.trackBVolume.collectLatest { _trackBVolume.value = it }
        }
        // 連動設定を読み込む
        viewModelScope.launch {
            settingsStore.trackBLinked.collectLatest { _trackBLinked.value = it }
        }
        viewModelScope.launch {
            settingsStore.trackBLinkedRatio.collectLatest { _trackBLinkedRatio.value = it }
        }
        // 再生速度を読み込む
        viewModelScope.launch {
            settingsStore.playbackSpeed.collectLatest { _playbackSpeed.value = it }
        }
        // フェード設定を読み込む
        viewModelScope.launch {
            settingsStore.fadeInSeconds.collectLatest { _fadeInSeconds.value = it }
        }
        viewModelScope.launch {
            settingsStore.fadeOutSeconds.collectLatest { _fadeOutSeconds.value = it }
        }
        viewModelScope.launch {
            settingsStore.fadeOutBeforeEnd.collectLatest { _fadeOutBeforeEndSeconds.value = it }
        }
        // イコライザー設定を読み込む
        viewModelScope.launch {
            settingsStore.bassLevel.collectLatest { _bassLevel.value = it }
        }
        viewModelScope.launch {
            settingsStore.trebleLevel.collectLatest { _trebleLevel.value = it }
        }
        // スケジュール設定を読み込む
        viewModelScope.launch {
            val config = settingsStore.getScheduleConfig()
            _scheduleConfig.value = config
        }
    }

    // ========== フォルダ設定メソッド ==========

    /**
     * setTrackAFolder - トラックAのフォルダパスを設定する
     * フォルダ選択ダイアログから呼ばれる
     * @param path 選択されたフォルダパス
     */
    fun setTrackAFolder(path: String) {
        // UIの状態を更新する
        _trackAFolder.value = path
        // DataStoreに保存する
        viewModelScope.launch {
            settingsStore.saveTrackAFolder(path)
            // 新しいフォルダをスキャンしてファイル数を取得する
            fileScanner.scanTrackAFolder(path)
            _trackAFileCount.value = fileScanner.getTrackAFileCount()
            // スキャン完了メッセージを表示する
            showSnackbar("トラックA: ${_trackAFileCount.value}件のファイルが見つかりました")
        }
    }

    /**
     * setTrackBFolder - トラックBのフォルダパスを設定する
     * @param path 選択されたフォルダパス
     */
    fun setTrackBFolder(path: String) {
        // UIの状態を更新する
        _trackBFolder.value = path
        // DataStoreに保存する
        viewModelScope.launch {
            settingsStore.saveTrackBFolder(path)
            // サブフォルダ含めてスキャンしてファイル数を取得する
            fileScanner.scanTrackBFolder(path)
            _trackBFileCount.value = fileScanner.getTrackBFileCount()
            // スキャン完了メッセージを表示する
            showSnackbar("トラックB: ${_trackBFileCount.value}件のファイルが見つかりました（サブフォルダ含む）")
        }
    }

    // ========== 再生設定メソッド ==========

    /**
     * setTrackAVolume - トラックAの音量を設定する
     */
    fun setTrackAVolume(volume: Float) {
        _trackAVolume.value = volume
        viewModelScope.launch { settingsStore.saveTrackAVolume(volume) }
    }

    /**
     * setTrackBVolume - トラックBの音量を設定する
     */
    fun setTrackBVolume(volume: Float) {
        _trackBVolume.value = volume
        viewModelScope.launch { settingsStore.saveTrackBVolume(volume) }
    }

    /**
     * setTrackBLinked - トラックBの連動設定を変更する
     */
    fun setTrackBLinked(linked: Boolean) {
        _trackBLinked.value = linked
        viewModelScope.launch {
            settingsStore.saveTrackBLinkSettings(linked, _trackBLinkedRatio.value)
        }
    }

    /**
     * setTrackBLinkedRatio - トラックBの連動比率を変更する
     */
    fun setTrackBLinkedRatio(ratio: Float) {
        _trackBLinkedRatio.value = ratio
        viewModelScope.launch {
            settingsStore.saveTrackBLinkSettings(_trackBLinked.value, ratio)
        }
    }

    /**
     * setPlaybackSpeed - 再生速度を変更する
     */
    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        viewModelScope.launch { settingsStore.savePlaybackSpeed(speed) }
    }

    /**
     * setFadeInSeconds - フェードイン秒数を変更する
     */
    fun setFadeInSeconds(seconds: Float) {
        _fadeInSeconds.value = seconds
        viewModelScope.launch { settingsStore.saveFadeInSeconds(seconds) }
    }

    /**
     * setFadeOutSeconds - フェードアウト秒数を変更する
     */
    fun setFadeOutSeconds(seconds: Float) {
        _fadeOutSeconds.value = seconds
        viewModelScope.launch { settingsStore.saveFadeOutSeconds(seconds) }
    }

    /**
     * setFadeOutBeforeEndSeconds - 終了前フェードアウト秒数を変更する
     */
    fun setFadeOutBeforeEndSeconds(seconds: Float) {
        _fadeOutBeforeEndSeconds.value = seconds
        viewModelScope.launch { settingsStore.saveFadeOutBeforeEndSeconds(seconds) }
    }

    /**
     * setBassLevel - イコライザー低音レベルを変更する
     */
    fun setBassLevel(level: Int) {
        _bassLevel.value = level
        viewModelScope.launch { settingsStore.saveEqualizerSettings(level, _trebleLevel.value) }
    }

    /**
     * setTrebleLevel - イコライザー高音レベルを変更する
     */
    fun setTrebleLevel(level: Int) {
        _trebleLevel.value = level
        viewModelScope.launch { settingsStore.saveEqualizerSettings(_bassLevel.value, level) }
    }

    // ========== スケジュール設定メソッド ==========

    /**
     * updateScheduleConfig - スケジュール設定全体を更新する
     */
    fun updateScheduleConfig(config: ScheduleConfig) {
        _scheduleConfig.value = config
        viewModelScope.launch { settingsStore.saveScheduleConfig(config) }
    }

    /**
     * updateTrigger - 特定のトリガー設定を更新する
     * @param trigger 更新するトリガー設定
     */
    fun updateTrigger(trigger: TriggerConfig) {
        // 既存のトリガーリストを更新する
        val currentConfig = _scheduleConfig.value
        val updatedTriggers = currentConfig.triggers.map {
            // 同じIDのトリガーを置き換える
            if (it.id == trigger.id) trigger else it
        }
        // IDが存在しない場合は追加する
        val finalTriggers = if (updatedTriggers.any { it.id == trigger.id }) {
            updatedTriggers
        } else {
            updatedTriggers + trigger
        }
        // 設定全体を更新する
        updateScheduleConfig(currentConfig.copy(triggers = finalTriggers))
    }

    /**
     * setActiveDays - 曜日設定を更新する
     */
    fun setActiveDays(days: ActiveDaysConfig) {
        val updated = _scheduleConfig.value.copy(activeDays = days)
        updateScheduleConfig(updated)
    }

    /**
     * setSkipHolidays - 祝日スキップ設定を変更する
     */
    fun setSkipHolidays(skip: Boolean) {
        val updated = _scheduleConfig.value.copy(skipHolidays = skip)
        updateScheduleConfig(updated)
    }

    /**
     * setCountdownSeconds - カウントダウン秒数を変更する
     */
    fun setCountdownSeconds(seconds: Int) {
        val updated = _scheduleConfig.value.copy(countdownSeconds = seconds)
        updateScheduleConfig(updated)
    }

    // ========== バックアップ操作 ==========

    /**
     * exportSettings - 設定をJSONファイルにエクスポートする
     * BackupManagerに委譲する
     * @param onShareIntent エクスポート成功時にShareIntentを受け取るコールバック
     */
    fun exportSettings(onShareIntent: (Intent) -> Unit) {
        // 処理中フラグを立てる
        _isProcessing.value = true
        viewModelScope.launch {
            try {
                // BackupManagerでエクスポートを実行する
                val result = backupManager.exportSettings()
                // 結果に応じてSnackbarか共有シートを表示する
                when (result) {
                    is BackupManager.BackupResult.ExportSuccess -> {
                        // 共有インテントをUIに渡す
                        onShareIntent(result.shareIntent)
                    }
                    is BackupManager.BackupResult.Failure -> {
                        showSnackbar("エクスポートに失敗しました: ${result.errorMessage}")
                    }
                    else -> showSnackbar("エクスポートに失敗しました")
                }
            } finally {
                // 処理中フラグをリセットする
                _isProcessing.value = false
            }
        }
    }

    /**
     * importSettings - ファイルURIから設定をインポートする
     * @param uri インポートするJSONファイルのUri
     */
    fun importSettings(uri: Uri) {
        // 処理中フラグを立てる
        _isProcessing.value = true
        viewModelScope.launch {
            try {
                // BackupManagerでインポートを実行する
                val result = backupManager.importSettings(uri)
                when (result) {
                    is BackupManager.BackupResult.ImportSuccess -> {
                        // インポート成功後に設定を再読み込みする
                        loadAllSettings()
                        showSnackbar("設定をインポートしました: ${result.summary}")
                    }
                    is BackupManager.BackupResult.Failure -> {
                        showSnackbar("インポートに失敗しました: ${result.errorMessage}")
                    }
                    else -> showSnackbar("インポートに失敗しました")
                }
            } finally {
                // 処理中フラグをリセットする
                _isProcessing.value = false
            }
        }
    }

    // ========== ユーティリティメソッド ==========

    /**
     * showSnackbar - スナックバーメッセージを表示する
     * @param message 表示するメッセージ
     */
    private fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    /**
     * clearSnackbar - スナックバーメッセージをクリアする
     * メッセージが表示された後にUIから呼ばれる
     */
    fun clearSnackbar() {
        _snackbarMessage.value = ""
    }

    /**
     * rescanFolders - 両トラックのフォルダを再スキャンする
     * 設定画面を開いた時などに呼ばれる
     */
    fun rescanFolders() {
        viewModelScope.launch {
            // トラックAのフォルダをスキャンする
            val folderA = _trackAFolder.value
            if (folderA.isNotEmpty()) {
                fileScanner.scanTrackAFolder(folderA)
                _trackAFileCount.value = fileScanner.getTrackAFileCount()
            }
            // トラックBのフォルダをスキャンする（サブフォルダ含む）
            val folderB = _trackBFolder.value
            if (folderB.isNotEmpty()) {
                fileScanner.scanTrackBFolder(folderB)
                _trackBFileCount.value = fileScanner.getTrackBFileCount()
            }
        }
    }
}