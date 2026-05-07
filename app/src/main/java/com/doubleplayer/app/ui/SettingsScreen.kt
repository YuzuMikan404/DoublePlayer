package com.doubleplayer.app.ui

// Android ActivityResultのインポート（フォルダ選択・ファイルインポートに使用）
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
// Compose UIのインポート
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
// Hiltナビゲーションのインポート
import androidx.hilt.navigation.compose.hiltViewModel
// ライフサイクルのインポート
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// 自アプリのインポート
import com.doubleplayer.app.schedule.ActiveDaysConfig
import com.doubleplayer.app.schedule.TriggerConfig
import kotlin.math.roundToInt

/**
 * SettingsScreen - 設定画面のCompose UI
 *
 * 仕様書セクション2「設定画面」に基づいて実装する。
 * 3つのタブで設定を分類する：
 * - フォルダ設定タブ：トラックA/BのフォルダパスとBluetooth設定
 * - スケジュール設定タブ：時間帯・曜日・祝日スキップ
 * - 再生設定タブ：音量・速度・フェード・イコライザー・バックアップ
 */
@Composable
fun SettingsScreen(
    // ViewModelはHiltで自動注入
    viewModel: SettingsViewModel = hiltViewModel()
) {
    // ========== 状態の収集 ==========

    // 各設定値をStateとして収集する
    val trackAFolder by viewModel.trackAFolder.collectAsStateWithLifecycle()
    val trackBFolder by viewModel.trackBFolder.collectAsStateWithLifecycle()
    val trackAFileCount by viewModel.trackAFileCount.collectAsStateWithLifecycle()
    val trackBFileCount by viewModel.trackBFileCount.collectAsStateWithLifecycle()
    val trackAVolume by viewModel.trackAVolume.collectAsStateWithLifecycle()
    val trackBVolume by viewModel.trackBVolume.collectAsStateWithLifecycle()
    val trackBLinked by viewModel.trackBLinked.collectAsStateWithLifecycle()
    val trackBLinkedRatio by viewModel.trackBLinkedRatio.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val fadeInSeconds by viewModel.fadeInSeconds.collectAsStateWithLifecycle()
    val fadeOutSeconds by viewModel.fadeOutSeconds.collectAsStateWithLifecycle()
    val fadeOutBeforeEndSeconds by viewModel.fadeOutBeforeEndSeconds.collectAsStateWithLifecycle()
    val bassLevel by viewModel.bassLevel.collectAsStateWithLifecycle()
    val trebleLevel by viewModel.trebleLevel.collectAsStateWithLifecycle()
    val scheduleConfig by viewModel.scheduleConfig.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()

    // 現在選択中のタブインデックス（0=フォルダ, 1=スケジュール, 2=再生設定）
    var selectedTab by remember { mutableIntStateOf(0) }

    // Snackbarの状態管理
    val snackbarHostState = remember { SnackbarHostState() }

    // SnackbarMessageが来たら表示する
    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage.isNotEmpty()) {
            snackbarHostState.showSnackbar(snackbarMessage)
            viewModel.clearSnackbar()
        }
    }

    val context = LocalContext.current

    // ========== ファイルピッカーランチャー（インポート用）==========

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        // uriがnullでなければインポートを実行する
        uri?.let { viewModel.importSettings(it) }
    }

    // ========== UI本体 ==========

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ========== タブ行 ==========
            TabRow(selectedTabIndex = selectedTab) {
                // フォルダ設定タブ
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "フォルダ設定"
                        )
                    },
                    text = { Text("フォルダ") }
                )
                // スケジュール設定タブ
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "スケジュール設定"
                        )
                    },
                    text = { Text("スケジュール") }
                )
                // 再生設定タブ
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "再生設定"
                        )
                    },
                    text = { Text("再生設定") }
                )
            }

            // ========== タブのコンテンツ ==========
            when (selectedTab) {
                // フォルダ設定タブ
                0 -> FolderSettingsTab(
                    trackAFolder = trackAFolder,
                    trackBFolder = trackBFolder,
                    trackAFileCount = trackAFileCount,
                    trackBFileCount = trackBFileCount,
                    onTrackAFolderChange = { viewModel.setTrackAFolder(it) },
                    onTrackBFolderChange = { viewModel.setTrackBFolder(it) }
                )
                // スケジュール設定タブ
                1 -> ScheduleSettingsTab(
                    scheduleConfig = scheduleConfig,
                    onTriggerUpdate = { viewModel.updateTrigger(it) },
                    onActiveDaysChange = { viewModel.setActiveDays(it) },
                    onSkipHolidaysChange = { viewModel.setSkipHolidays(it) },
                    onCountdownSecondsChange = { viewModel.setCountdownSeconds(it) }
                )
                // 再生設定タブ
                2 -> PlaybackSettingsTab(
                    trackAVolume = trackAVolume,
                    trackBVolume = trackBVolume,
                    trackBLinked = trackBLinked,
                    trackBLinkedRatio = trackBLinkedRatio,
                    playbackSpeed = playbackSpeed,
                    fadeInSeconds = fadeInSeconds,
                    fadeOutSeconds = fadeOutSeconds,
                    fadeOutBeforeEndSeconds = fadeOutBeforeEndSeconds,
                    bassLevel = bassLevel,
                    trebleLevel = trebleLevel,
                    isProcessing = isProcessing,
                    onTrackAVolumeChange = { viewModel.setTrackAVolume(it) },
                    onTrackBVolumeChange = { viewModel.setTrackBVolume(it) },
                    onTrackBLinkedChange = { viewModel.setTrackBLinked(it) },
                    onTrackBLinkedRatioChange = { viewModel.setTrackBLinkedRatio(it) },
                    onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                    onFadeInChange = { viewModel.setFadeInSeconds(it) },
                    onFadeOutChange = { viewModel.setFadeOutSeconds(it) },
                    onFadeOutBeforeEndChange = { viewModel.setFadeOutBeforeEndSeconds(it) },
                    onBassChange = { viewModel.setBassLevel(it) },
                    onTrebleChange = { viewModel.setTrebleLevel(it) },
                    onExport = {
                        viewModel.exportSettings { intent ->
                            // エクスポート成功後に共有シートを表示する
                            context.startActivity(Intent.createChooser(intent, "設定をエクスポート"))
                        }
                    },
                    onImport = {
                        // JSONファイルを選択するピッカーを起動する
                        // */* を指定することでJSONファイルを確実に選択できるようにする
                        importLauncher.launch("*/*")
                    }
                )
            }
        }
    }
}

/**
 * フォルダURIから表示用のパス文字列を生成するヘルパー関数
 * SAFのURIをユーザーが読みやすい形式に変換する
 */
private fun uriToDisplayPath(uri: Uri): String {
    // content://com.android.externalstorage.documents/tree/primary%3AMusic 形式を
    // /storage/emulated/0/Music のような形式に変換する
    val path = uri.lastPathSegment ?: return uri.toString()
    return when {
        path.startsWith("primary:") -> {
            "/storage/emulated/0/" + path.removePrefix("primary:")
        }
        path.contains(":") -> {
            val parts = path.split(":", limit = 2)
            "/storage/${parts[0]}/${parts[1]}"
        }
        else -> path
    }
}

/**
 * FolderSettingsTab - フォルダ設定タブのコンテンツ
 * SAF（Storage Access Framework）のフォルダピッカーを使用して
 * 外部ストレージデバイスのフォルダも選択できるようにする
 */
@Composable
private fun FolderSettingsTab(
    trackAFolder: String,
    trackBFolder: String,
    trackAFileCount: Int,
    trackBFileCount: Int,
    onTrackAFolderChange: (String) -> Unit,
    onTrackBFolderChange: (String) -> Unit
) {
    val context = LocalContext.current

    // ========== SAFフォルダピッカーランチャー（トラックA）==========
    val trackAPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 永続的なアクセス権限を取得する（アプリ再起動後もアクセス可能にする）
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // URIをString化してViewModelに渡す
            onTrackAFolderChange(uri.toString())
        }
    }

    // ========== SAFフォルダピッカーランチャー（トラックB）==========
    val trackBPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 永続的なアクセス権限を取得する（アプリ再起動後もアクセス可能にする）
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // URIをString化してViewModelに渡す
            onTrackBFolderChange(uri.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ========== トラックA フォルダ設定 ==========
        SettingsSection(title = "トラックA フォルダ") {
            // 現在設定されているパスを表示する（URIを読みやすいパスに変換）
            val displayPathA = if (trackAFolder.isEmpty()) {
                "フォルダ未設定"
            } else {
                try {
                    uriToDisplayPath(Uri.parse(trackAFolder))
                } catch (e: Exception) {
                    trackAFolder
                }
            }
            Text(
                text = displayPathA,
                style = MaterialTheme.typography.bodySmall,
                color = if (trackAFolder.isEmpty())
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (trackAFolder.isNotEmpty()) {
                // ファイル数表示
                Text(
                    text = "音声ファイル: ${trackAFileCount}件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // SAFフォルダピッカーを起動するボタン
            OutlinedButton(
                onClick = {
                    // 現在設定済みのURIがあれば初期表示フォルダとして渡す
                    val initialUri = if (trackAFolder.isNotEmpty()) {
                        Uri.parse(trackAFolder)
                    } else null
                    trackAPickerLauncher.launch(initialUri)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "フォルダを選択",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("フォルダを選択")
            }
            // 説明テキスト
            Text(
                text = "外部ストレージ（SDカード・USBドライブ）も選択できます",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        // ========== トラックB フォルダ設定 ==========
        SettingsSection(title = "トラックB フォルダ（BGM）") {
            val displayPathB = if (trackBFolder.isEmpty()) {
                "フォルダ未設定"
            } else {
                try {
                    uriToDisplayPath(Uri.parse(trackBFolder))
                } catch (e: Exception) {
                    trackBFolder
                }
            }
            Text(
                text = displayPathB,
                style = MaterialTheme.typography.bodySmall,
                color = if (trackBFolder.isEmpty())
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (trackBFolder.isNotEmpty()) {
                Text(
                    text = "音声ファイル: ${trackBFileCount}件（サブフォルダ含む）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val initialUri = if (trackBFolder.isNotEmpty()) {
                        Uri.parse(trackBFolder)
                    } else null
                    trackBPickerLauncher.launch(initialUri)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "フォルダを選択",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("フォルダを選択")
            }
            Text(
                text = "外部ストレージ（SDカード・USBドライブ）も選択できます\nサブフォルダ内のファイルも自動検索されます",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * ScheduleSettingsTab - スケジュール設定タブのコンテンツ
 */
@Composable
private fun ScheduleSettingsTab(
    scheduleConfig: com.doubleplayer.app.schedule.ScheduleConfig,
    onTriggerUpdate: (TriggerConfig) -> Unit,
    onActiveDaysChange: (ActiveDaysConfig) -> Unit,
    onSkipHolidaysChange: (Boolean) -> Unit,
    onCountdownSecondsChange: (Int) -> Unit
) {
    // トリガー編集ダイアログの表示フラグと対象トリガー
    var editingTrigger by remember { mutableStateOf<TriggerConfig?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ========== 時間帯トリガー ==========
        SettingsSection(title = "自動再生 時間帯") {
            // トリガーが1件もない場合の案内
            if (scheduleConfig.triggers.isEmpty()) {
                Text(
                    text = "時間帯が設定されていません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 各トリガーを表示する
            scheduleConfig.triggers.forEach { trigger ->
                TriggerRow(
                    trigger = trigger,
                    onEditClick = { editingTrigger = trigger }
                )
            }
        }

        // ========== 曜日設定 ==========
        SettingsSection(title = "有効な曜日") {
            val days = scheduleConfig.activeDays
            // 曜日ごとのチェックボックスを横並びで表示する
            DayOfWeekSelector(
                activeDays = days,
                onDaysChange = onActiveDaysChange
            )
        }

        // ========== 祝日スキップ設定 ==========
        SettingsSection(title = "祝日の扱い") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "祝日はスキップする",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "日本の祝日カレンダーを使用します",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = scheduleConfig.skipHolidays,
                    onCheckedChange = onSkipHolidaysChange
                )
            }
        }

        // ========== カウントダウン設定 ==========
        SettingsSection(title = "再生開始カウントダウン") {
            Text(
                text = "再生開始前に何秒カウントダウンするか",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = scheduleConfig.countdownSeconds.toFloat(),
                    onValueChange = { onCountdownSecondsChange(it.roundToInt()) },
                    valueRange = 0f..30f,
                    steps = 29,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${scheduleConfig.countdownSeconds}秒",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    // ========== トリガー編集ダイアログ ==========
    editingTrigger?.let { trigger ->
        TriggerEditDialog(
            trigger = trigger,
            onConfirm = { updated ->
                onTriggerUpdate(updated)
                editingTrigger = null
            },
            onDismiss = { editingTrigger = null }
        )
    }
}

/**
 * TriggerRow - 1件のトリガー設定を表示する行コンポーネント
 */
@Composable
private fun TriggerRow(
    trigger: TriggerConfig,
    onEditClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 時間帯の表示
            Text(
                text = "%02d:%02d〜%02d:%02d".format(
                    trigger.startHour, trigger.startMinute,
                    trigger.endHour, trigger.endMinute
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            // 有効・無効の状態表示
            Text(
                text = if (trigger.enabled) "有効" else "無効",
                style = MaterialTheme.typography.labelSmall,
                color = if (trigger.enabled)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 編集ボタン
        IconButton(onClick = onEditClick) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "編集",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * TriggerEditDialog - トリガーの時間帯を編集するダイアログ
 */
@Composable
private fun TriggerEditDialog(
    trigger: TriggerConfig,
    onConfirm: (TriggerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    // 編集中の値を管理する
    var startHour by remember { mutableIntStateOf(trigger.startHour) }
    var startMin by remember { mutableIntStateOf(trigger.startMinute) }
    var endHour by remember { mutableIntStateOf(trigger.endHour) }
    var endMin by remember { mutableIntStateOf(trigger.endMinute) }
    var enabled by remember { mutableStateOf(trigger.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("時間帯を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 有効スイッチ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("有効にする")
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                // 開始時刻（時・分のスライダー）
                Text("開始時刻: %02d:%02d".format(startHour, startMin),
                    style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = startHour.toFloat(),
                    onValueChange = { startHour = it.roundToInt() },
                    valueRange = 0f..23f, steps = 22
                )
                Slider(
                    value = startMin.toFloat(),
                    onValueChange = { startMin = it.roundToInt() },
                    valueRange = 0f..59f, steps = 58
                )
                // 終了時刻（時・分のスライダー）
                Text("終了時刻: %02d:%02d".format(endHour, endMin),
                    style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = endHour.toFloat(),
                    onValueChange = { endHour = it.roundToInt() },
                    valueRange = 0f..23f, steps = 22
                )
                Slider(
                    value = endMin.toFloat(),
                    onValueChange = { endMin = it.roundToInt() },
                    valueRange = 0f..59f, steps = 58
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(trigger.copy(
                    startHour = startHour,
                    startMinute = startMin,
                    endHour = endHour,
                    endMinute = endMin,
                    enabled = enabled
                ))
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

/**
 * DayOfWeekSelector - 曜日チェックボックスコンポーネント
 */
@Composable
private fun DayOfWeekSelector(
    activeDays: ActiveDaysConfig,
    onDaysChange: (ActiveDaysConfig) -> Unit
) {
    // 曜日名と対応するActiveDaysConfigの変更関数をリスト化する
    val dayLabels = listOf("日", "月", "火", "水", "木", "金", "土")
    val dayValues = listOf(
        activeDays.sunday,
        activeDays.monday,
        activeDays.tuesday,
        activeDays.wednesday,
        activeDays.thursday,
        activeDays.friday,
        activeDays.saturday
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // 各曜日のチェックボックスを表示する
        dayLabels.forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 曜日ラベル
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall
                )
                // チェックボックス
                Checkbox(
                    checked = dayValues[index],
                    onCheckedChange = { checked ->
                        // 変更された曜日だけ更新した新しいActiveDaysConfigを作る
                        val updated = when (index) {
                            0 -> activeDays.copy(sunday = checked)
                            1 -> activeDays.copy(monday = checked)
                            2 -> activeDays.copy(tuesday = checked)
                            3 -> activeDays.copy(wednesday = checked)
                            4 -> activeDays.copy(thursday = checked)
                            5 -> activeDays.copy(friday = checked)
                            6 -> activeDays.copy(saturday = checked)
                            else -> activeDays
                        }
                        onDaysChange(updated)
                    }
                )
            }
        }
    }
}

/**
 * PlaybackSettingsTab - 再生設定タブのコンテンツ
 */
@Composable
private fun PlaybackSettingsTab(
    trackAVolume: Float,
    trackBVolume: Float,
    trackBLinked: Boolean,
    trackBLinkedRatio: Float,
    playbackSpeed: Float,
    fadeInSeconds: Float,
    fadeOutSeconds: Float,
    fadeOutBeforeEndSeconds: Float,
    bassLevel: Int,
    trebleLevel: Int,
    isProcessing: Boolean,
    onTrackAVolumeChange: (Float) -> Unit,
    onTrackBVolumeChange: (Float) -> Unit,
    onTrackBLinkedChange: (Boolean) -> Unit,
    onTrackBLinkedRatioChange: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFadeInChange: (Float) -> Unit,
    onFadeOutChange: (Float) -> Unit,
    onFadeOutBeforeEndChange: (Float) -> Unit,
    onBassChange: (Int) -> Unit,
    onTrebleChange: (Int) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ========== 音量設定 ==========
        SettingsSection(title = "音量設定") {
            // トラックA音量
            LabeledSlider(
                label = "トラックA音量",
                value = trackAVolume,
                valueRange = 0f..1f,
                displayText = "${(trackAVolume * 100).roundToInt()}%",
                onValueChange = onTrackAVolumeChange
            )
            Spacer(modifier = Modifier.height(4.dp))
            // トラックB音量
            LabeledSlider(
                label = "トラックB音量",
                value = trackBVolume,
                valueRange = 0f..1f,
                displayText = "${(trackBVolume * 100).roundToInt()}%",
                onValueChange = onTrackBVolumeChange
            )
            Spacer(modifier = Modifier.height(8.dp))
            // トラックB連動スイッチ
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("トラックAに連動", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "AのBGMを自動調整します",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = trackBLinked, onCheckedChange = onTrackBLinkedChange)
            }
            // 連動比率スライダー（連動ONの時のみ表示）
            if (trackBLinked) {
                Spacer(modifier = Modifier.height(4.dp))
                LabeledSlider(
                    label = "連動比率",
                    value = trackBLinkedRatio,
                    valueRange = 0f..1f,
                    displayText = "${"%.1f".format(trackBLinkedRatio)}",
                    onValueChange = onTrackBLinkedRatioChange
                )
            }
        }

        // ========== 再生速度 ==========
        SettingsSection(title = "再生速度") {
            LabeledSlider(
                label = "",
                value = playbackSpeed,
                valueRange = 0.5f..2.0f,
                displayText = "×${"%.2f".format(playbackSpeed)}",
                onValueChange = { speed ->
                    // 0.25刻みにスナップする
                    val snapped = (speed / 0.25f).roundToInt() * 0.25f
                    onSpeedChange(snapped.coerceIn(0.5f, 2.0f))
                }
            )
        }

        // ========== フェード設定 ==========
        SettingsSection(title = "フェード設定") {
            LabeledSlider(
                label = "フェードイン",
                value = fadeInSeconds,
                valueRange = 0f..10f,
                displayText = "${fadeInSeconds.roundToInt()}秒",
                onValueChange = onFadeInChange
            )
            Spacer(modifier = Modifier.height(4.dp))
            LabeledSlider(
                label = "フェードアウト",
                value = fadeOutSeconds,
                valueRange = 0f..10f,
                displayText = "${fadeOutSeconds.roundToInt()}秒",
                onValueChange = onFadeOutChange
            )
            Spacer(modifier = Modifier.height(4.dp))
            LabeledSlider(
                label = "A終了前B停止",
                value = fadeOutBeforeEndSeconds,
                valueRange = 0f..30f,
                displayText = "${fadeOutBeforeEndSeconds.roundToInt()}秒前",
                onValueChange = onFadeOutBeforeEndChange
            )
        }

        // ========== イコライザー設定 ==========
        SettingsSection(title = "イコライザー") {
            LabeledSlider(
                label = "低音（ベース）",
                value = bassLevel.toFloat(),
                valueRange = -10f..10f,
                steps = 19,
                displayText = if (bassLevel >= 0) "+$bassLevel" else "$bassLevel",
                onValueChange = { onBassChange(it.roundToInt()) }
            )
            Spacer(modifier = Modifier.height(4.dp))
            LabeledSlider(
                label = "高音（トレブル）",
                value = trebleLevel.toFloat(),
                valueRange = -10f..10f,
                steps = 19,
                displayText = if (trebleLevel >= 0) "+$trebleLevel" else "$trebleLevel",
                onValueChange = { onTrebleChange(it.roundToInt()) }
            )
        }

        // ========== バックアップ ==========
        SettingsSection(title = "設定のバックアップ") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // エクスポートボタン
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing
                ) {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = "エクスポート",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("エクスポート")
                }
                // インポートボタン
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "インポート",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("インポート")
                }
            }
            // 処理中インジケーター
            if (isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * LabeledSlider - ラベル付きスライダーコンポーネント（設定画面用）
 */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    displayText: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // ラベルと現在値を横並びで表示する
        if (label.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        // スライダー本体
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * SettingsSection - 設定項目をカード内にまとめるコンテナコンポーネント
 */
@Composable
private fun SettingsSection(
    title: String,       // セクションタイトル
    content: @Composable ColumnScope.() -> Unit  // コンテンツ
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // タイトル
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            // コンテンツ
            content()
        }
    }
}
