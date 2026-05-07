package com.doubleplayer.app.storage

// DataStore関連のインポート
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
// コルーチン関連のインポート
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
// Hilt依存注入のインポート
import com.doubleplayer.app.di.SettingsStore as SettingsStoreDataStoreQualifier
import com.doubleplayer.app.schedule.ActiveDaysConfig
import com.doubleplayer.app.schedule.ScheduleConfig
import com.doubleplayer.app.schedule.TriggerConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SettingsStore - ユーザー設定の保存・読み込みを担当するクラス
 *
 * 仕様書セクション4「設定JSONファイルの仕様」に基づいて実装する。
 * DataStore（Preferences）を使用して設定値を永続化する。
 * Phase4で完全実装する予定だが、Phase3のBluetoothReceiverが必要とする
 * getScheduleConfig()のみ先行実装する。
 *
 * 保存する設定：
 * - トラックA：フォルダパス・音量・再生速度・トラック間ギャップ
 * - トラックB：フォルダパス・音量・連動設定・シャッフル設定
 * - フェード：フェードイン/アウト秒数・終了前秒数
 * - イコライザー：低音・高音レベル
 * - スケジュール：トリガー・曜日・祝日スキップ・カウントダウン
 */
@Singleton
class SettingsStore @Inject constructor(
    // Hilt Qualifierで区別した設定用DataStore
    @SettingsStoreDataStoreQualifier private val dataStore: DataStore<Preferences>
) {

    // ========== DataStoreのキー定義 ==========

    // トラックAのフォルダパスを保存するキー
    val KEY_TRACK_A_FOLDER = stringPreferencesKey("trackA_folderPath")

    // トラックBのフォルダパスを保存するキー
    val KEY_TRACK_B_FOLDER = stringPreferencesKey("trackB_folderPath")

    // トラックAの音量を保存するキー
    val KEY_TRACK_A_VOLUME = floatPreferencesKey("trackA_volume")

    // トラックBの音量を保存するキー
    val KEY_TRACK_B_VOLUME = floatPreferencesKey("trackB_volume")

    // トラックBをトラックAに連動するかどうかのキー
    val KEY_TRACK_B_LINKED = booleanPreferencesKey("trackB_linkedToTrackA")

    // トラックBの連動比率のキー
    val KEY_TRACK_B_LINKED_RATIO = floatPreferencesKey("trackB_linkedRatio")

    // 再生速度のキー
    val KEY_PLAYBACK_SPEED = floatPreferencesKey("playbackSpeed")

    // フェードイン秒数のキー
    val KEY_FADE_IN_SECONDS = floatPreferencesKey("fade_fadeInSeconds")

    // フェードアウト秒数のキー
    val KEY_FADE_OUT_SECONDS = floatPreferencesKey("fade_fadeOutSeconds")

    // トラックA終了前フェードアウト秒数のキー
    val KEY_FADE_OUT_BEFORE_END = floatPreferencesKey("fade_fadeOutBeforeEndSeconds")

    // イコライザー低音レベルのキー
    val KEY_BASS_LEVEL = intPreferencesKey("equalizer_bassLevel")

    // イコライザー高音レベルのキー
    val KEY_TREBLE_LEVEL = intPreferencesKey("equalizer_trebleLevel")

    // スケジュール：月〜日の有効フラグのキー
    val KEY_DAY_SUNDAY    = booleanPreferencesKey("schedule_sunday")
    val KEY_DAY_MONDAY    = booleanPreferencesKey("schedule_monday")
    val KEY_DAY_TUESDAY   = booleanPreferencesKey("schedule_tuesday")
    val KEY_DAY_WEDNESDAY = booleanPreferencesKey("schedule_wednesday")
    val KEY_DAY_THURSDAY  = booleanPreferencesKey("schedule_thursday")
    val KEY_DAY_FRIDAY    = booleanPreferencesKey("schedule_friday")
    val KEY_DAY_SATURDAY  = booleanPreferencesKey("schedule_saturday")

    // スケジュール：祝日スキップのキー
    val KEY_SKIP_HOLIDAYS = booleanPreferencesKey("schedule_skipHolidays")

    // スケジュール：カウントダウン秒数のキー
    val KEY_COUNTDOWN_SECONDS = intPreferencesKey("schedule_countdownSeconds")

    // スケジュール：トリガー（デフォルト1件）の開始・終了時刻のキー
    val KEY_TRIGGER_ENABLED     = booleanPreferencesKey("schedule_trigger_morning_enabled")
    val KEY_TRIGGER_START_HOUR  = intPreferencesKey("schedule_trigger_morning_startHour")
    val KEY_TRIGGER_START_MIN   = intPreferencesKey("schedule_trigger_morning_startMinute")
    val KEY_TRIGGER_END_HOUR    = intPreferencesKey("schedule_trigger_morning_endHour")
    val KEY_TRIGGER_END_MIN     = intPreferencesKey("schedule_trigger_morning_endMinute")

    // ========== 読み込みメソッド（Flowで返す）==========

    /** トラックAのフォルダパスを取得するFlow */
    val trackAFolder: Flow<String> = dataStore.data.map { it[KEY_TRACK_A_FOLDER] ?: "" }

    /** トラックBのフォルダパスを取得するFlow */
    val trackBFolder: Flow<String> = dataStore.data.map { it[KEY_TRACK_B_FOLDER] ?: "" }

    /** トラックAの音量を取得するFlow（デフォルト0.8） */
    val trackAVolume: Flow<Float> = dataStore.data.map { it[KEY_TRACK_A_VOLUME] ?: 0.8f }

    /** トラックBの音量を取得するFlow（デフォルト0.3） */
    val trackBVolume: Flow<Float> = dataStore.data.map { it[KEY_TRACK_B_VOLUME] ?: 0.3f }

    /** トラックBの連動フラグを取得するFlow（デフォルトtrue） */
    val trackBLinked: Flow<Boolean> = dataStore.data.map { it[KEY_TRACK_B_LINKED] ?: true }

    /** トラックBの連動比率を取得するFlow（デフォルト0.4） */
    val trackBLinkedRatio: Flow<Float> = dataStore.data.map { it[KEY_TRACK_B_LINKED_RATIO] ?: 0.4f }

    /** 再生速度を取得するFlow（デフォルト1.0） */
    val playbackSpeed: Flow<Float> = dataStore.data.map { it[KEY_PLAYBACK_SPEED] ?: 1.0f }

    /** フェードイン秒数を取得するFlow（デフォルト3.0） */
    val fadeInSeconds: Flow<Float> = dataStore.data.map { it[KEY_FADE_IN_SECONDS] ?: 3.0f }

    /** フェードアウト秒数を取得するFlow（デフォルト3.0） */
    val fadeOutSeconds: Flow<Float> = dataStore.data.map { it[KEY_FADE_OUT_SECONDS] ?: 3.0f }

    /** トラックA終了前フェードアウト秒数を取得するFlow（デフォルト10.0） */
    val fadeOutBeforeEnd: Flow<Float> = dataStore.data.map { it[KEY_FADE_OUT_BEFORE_END] ?: 10.0f }

    /** イコライザー低音レベルを取得するFlow（デフォルト0） */
    val bassLevel: Flow<Int> = dataStore.data.map { it[KEY_BASS_LEVEL] ?: 0 }

    /** イコライザー高音レベルを取得するFlow（デフォルト0） */
    val trebleLevel: Flow<Int> = dataStore.data.map { it[KEY_TREBLE_LEVEL] ?: 0 }

    // ========== スケジュール設定の取得メソッド ==========

    /**
     * スケジュール設定を一括取得するメソッド
     * BluetoothReceiverからScheduleConfigとして取得するために使う
     *
     * @return ScheduleConfig（スケジュール設定全体）
     */
    suspend fun getScheduleConfig(): ScheduleConfig {
        // DataStoreから設定を一度に読み込む
        val prefs = dataStore.data.first()

        // トリガー設定を組み立てる（現バージョンはmorningトリガー1件のみ）
        val morningTrigger = TriggerConfig(
            id = "morning",
            enabled = prefs[KEY_TRIGGER_ENABLED] ?: true,
            startHour = prefs[KEY_TRIGGER_START_HOUR] ?: 6,
            startMinute = prefs[KEY_TRIGGER_START_MIN] ?: 0,
            endHour = prefs[KEY_TRIGGER_END_HOUR] ?: 9,
            endMinute = prefs[KEY_TRIGGER_END_MIN] ?: 0
        )

        // 曜日設定を組み立てる
        val activeDays = ActiveDaysConfig(
            sunday    = prefs[KEY_DAY_SUNDAY]    ?: false,
            monday    = prefs[KEY_DAY_MONDAY]    ?: true,
            tuesday   = prefs[KEY_DAY_TUESDAY]   ?: true,
            wednesday = prefs[KEY_DAY_WEDNESDAY] ?: true,
            thursday  = prefs[KEY_DAY_THURSDAY]  ?: true,
            friday    = prefs[KEY_DAY_FRIDAY]    ?: true,
            saturday  = prefs[KEY_DAY_SATURDAY]  ?: false
        )

        // スケジュール設定全体を返す
        return ScheduleConfig(
            triggers = listOf(morningTrigger),
            activeDays = activeDays,
            skipHolidays = prefs[KEY_SKIP_HOLIDAYS] ?: true,
            countdownSeconds = prefs[KEY_COUNTDOWN_SECONDS] ?: 0
        )
    }

    // ========== 書き込みメソッド ==========

    /**
     * トラックAのフォルダパスを保存するメソッド
     *
     * @param path 保存するフォルダパス
     */
    suspend fun saveTrackAFolder(path: String) {
        dataStore.edit { it[KEY_TRACK_A_FOLDER] = path }
    }

    /**
     * トラックBのフォルダパスを保存するメソッド
     *
     * @param path 保存するフォルダパス
     */
    suspend fun saveTrackBFolder(path: String) {
        dataStore.edit { it[KEY_TRACK_B_FOLDER] = path }
    }

    /**
     * トラックAの音量を保存するメソッド
     *
     * @param volume 保存する音量（0.0f〜1.0f）
     */
    suspend fun saveTrackAVolume(volume: Float) {
        dataStore.edit { it[KEY_TRACK_A_VOLUME] = volume }
    }

    /**
     * トラックBの音量を保存するメソッド
     *
     * @param volume 保存する音量（0.0f〜1.0f）
     */
    suspend fun saveTrackBVolume(volume: Float) {
        dataStore.edit { it[KEY_TRACK_B_VOLUME] = volume }
    }

    /**
     * 再生速度を保存するメソッド
     *
     * @param speed 保存する再生速度（0.5f〜2.0f）
     */
    suspend fun savePlaybackSpeed(speed: Float) {
        dataStore.edit { it[KEY_PLAYBACK_SPEED] = speed }
    }

    /**
     * フェード設定を保存するメソッド（3つまとめて保存する）
     *
     * @param fadeIn フェードイン秒数
     * @param fadeOut フェードアウト秒数
     * @param fadeOutBeforeEnd 終了前フェードアウト秒数
     */
    suspend fun saveFadeSettings(fadeIn: Float, fadeOut: Float, fadeOutBeforeEnd: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_FADE_IN_SECONDS] = fadeIn
            prefs[KEY_FADE_OUT_SECONDS] = fadeOut
            prefs[KEY_FADE_OUT_BEFORE_END] = fadeOutBeforeEnd
        }
    }

    /**
     * スケジュール設定を保存するメソッド
     *
     * @param config 保存するスケジュール設定
     */
    suspend fun saveScheduleConfig(config: ScheduleConfig) {
        dataStore.edit { prefs ->
            // トリガー設定を保存する（現バージョンはmorningトリガー1件のみ）
            val trigger = config.triggers.firstOrNull()
            if (trigger != null) {
                prefs[KEY_TRIGGER_ENABLED]    = trigger.enabled
                prefs[KEY_TRIGGER_START_HOUR] = trigger.startHour
                prefs[KEY_TRIGGER_START_MIN]  = trigger.startMinute
                prefs[KEY_TRIGGER_END_HOUR]   = trigger.endHour
                prefs[KEY_TRIGGER_END_MIN]    = trigger.endMinute
            }
            // 曜日設定を保存する
            prefs[KEY_DAY_SUNDAY]    = config.activeDays.sunday
            prefs[KEY_DAY_MONDAY]    = config.activeDays.monday
            prefs[KEY_DAY_TUESDAY]   = config.activeDays.tuesday
            prefs[KEY_DAY_WEDNESDAY] = config.activeDays.wednesday
            prefs[KEY_DAY_THURSDAY]  = config.activeDays.thursday
            prefs[KEY_DAY_FRIDAY]    = config.activeDays.friday
            prefs[KEY_DAY_SATURDAY]  = config.activeDays.saturday
            // 祝日スキップを保存する
            prefs[KEY_SKIP_HOLIDAYS] = config.skipHolidays
            // カウントダウン秒数を保存する
            prefs[KEY_COUNTDOWN_SECONDS] = config.countdownSeconds
        }
    }

    /**
     * イコライザー設定を保存するメソッド
     *
     * @param bass 低音レベル（-10〜10）
     * @param treble 高音レベル（-10〜10）
     */
    suspend fun saveEqualizerSettings(bass: Int, treble: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_BASS_LEVEL] = bass
            prefs[KEY_TREBLE_LEVEL] = treble
        }
    }

    /**
     * トラックBの連動設定を保存するメソッド
     *
     * @param linked 連動するかどうか
     * @param ratio 連動比率（0.0f〜1.0f）
     */
    suspend fun saveTrackBLinkSettings(linked: Boolean, ratio: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_TRACK_B_LINKED] = linked
            prefs[KEY_TRACK_B_LINKED_RATIO] = ratio
        }
    }

    // ========== BackupManagerから呼ばれる個別保存メソッド ==========

    /**
     * フェードイン秒数を個別保存するメソッド（BackupManagerのインポート時に使用する）
     * @param seconds フェードイン秒数
     */
    suspend fun saveFadeInSeconds(seconds: Float) {
        // フェードイン秒数のみ更新する
        dataStore.edit { it[KEY_FADE_IN_SECONDS] = seconds }
    }

    /**
     * フェードアウト秒数を個別保存するメソッド（BackupManagerのインポート時に使用する）
     * @param seconds フェードアウト秒数
     */
    suspend fun saveFadeOutSeconds(seconds: Float) {
        // フェードアウト秒数のみ更新する
        dataStore.edit { it[KEY_FADE_OUT_SECONDS] = seconds }
    }

    /**
     * 終了前フェードアウト秒数を個別保存するメソッド（BackupManagerのインポート時に使用する）
     * @param seconds 終了前フェードアウト秒数
     */
    suspend fun saveFadeOutBeforeEndSeconds(seconds: Float) {
        // 終了前フェードアウト秒数のみ更新する
        dataStore.edit { it[KEY_FADE_OUT_BEFORE_END] = seconds }
    }

    /**
     * トラックBのトラックA連動フラグを個別保存するメソッド（BackupManagerのインポート時に使用する）
     * @param linked 連動するかどうか
     */
    suspend fun saveTrackBLinkedToA(linked: Boolean) {
        // 連動フラグのみ更新する
        dataStore.edit { it[KEY_TRACK_B_LINKED] = linked }
    }

    /**
     * トラックBの連動比率を個別保存するメソッド（BackupManagerのインポート時に使用する）
     * @param ratio 連動比率（0.0f〜1.0f）
     */
    suspend fun saveLinkedRatio(ratio: Float) {
        // 連動比率のみ更新する
        dataStore.edit { it[KEY_TRACK_B_LINKED_RATIO] = ratio }
    }
}