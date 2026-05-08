package com.doubleplayer.app.storage

// DataStore関連のインポート
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
// コルーチン関連のインポート
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
// Hilt依存注入のインポート
import com.doubleplayer.app.di.PlaybackStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PlaybackStateStore - 再生状態を永続化するクラス
 *
 * 仕様書セクション9「再生状態の保存仕様」に基づいて実装する。
 * DataStoreを使って以下の情報をアプリ内部ストレージに保存する：
 * - 最後に再生した日付
 * - 当日の再生済みフラグ
 * - 当日の全ファイル完了フラグ
 * - トラックAの現在ファイルパス・再生位置・インデックス
 * - トラックBのシャッフルリスト・現在インデックス
 *
 * また、仕様書セクション6「自動起動ロジック」で参照される
 * 「今日すでに再生済みフラグ」も管理する。
 */
@Singleton
class PlaybackStateStore @Inject constructor(
    // Hilt Qualifierで区別した再生状態用DataStore
    @PlaybackStore private val dataStore: DataStore<Preferences>
) {

    // ========== DataStoreのキー定義 ==========

    // 最後に再生した日付を保存するキー（YYYY-MM-DD形式）
    private val KEY_LAST_PLAY_DATE = stringPreferencesKey("lastPlayDate")

    // 当日の再生済みフラグを保存するキー
    private val KEY_TODAY_PLAYED_FLAG = booleanPreferencesKey("todayPlayedFlag")

    // 当日の全ファイル再生完了フラグを保存するキー
    private val KEY_TODAY_COMPLETE_FLAG = booleanPreferencesKey("todayCompleteFlag")

    // トラックAの現在ファイルパスを保存するキー
    private val KEY_TRACK_A_CURRENT_FILE = stringPreferencesKey("trackACurrentFile")

    // トラックAの現在再生位置（ミリ秒）を保存するキー
    private val KEY_TRACK_A_POSITION_MS = longPreferencesKey("trackAPositionMs")

    // トラックAの現在インデックス（何番目のファイルか）を保存するキー
    private val KEY_TRACK_A_FILE_INDEX = intPreferencesKey("trackAFileIndex")

    // トラックBのシャッフルリストをJSON配列文字列で保存するキー
    private val KEY_TRACK_B_SHUFFLE_LIST = stringPreferencesKey("trackBShuffleList")

    // トラックBの現在インデックスを保存するキー
    private val KEY_TRACK_B_CURRENT_INDEX = intPreferencesKey("trackBCurrentIndex")

    // ========== 読み込みメソッド（Flowで返す）==========

    /**
     * 最後に再生した日付を取得するFlow
     * 値がなければ空文字を返す
     */
    val lastPlayDate: Flow<String> = dataStore.data.map { prefs ->
        // DataStoreから最後の再生日付を読み込む（なければ空文字）
        prefs[KEY_LAST_PLAY_DATE] ?: ""
    }

    /**
     * 当日の再生済みフラグを取得するFlow
     * 値がなければfalseを返す
     */
    val todayPlayedFlag: Flow<Boolean> = dataStore.data.map { prefs ->
        // DataStoreから再生済みフラグを読み込む（なければfalse）
        prefs[KEY_TODAY_PLAYED_FLAG] ?: false
    }

    /**
     * 当日の全ファイル完了フラグを取得するFlow
     * 値がなければfalseを返す
     */
    val todayCompleteFlag: Flow<Boolean> = dataStore.data.map { prefs ->
        // DataStoreから完了フラグを読み込む（なければfalse）
        prefs[KEY_TODAY_COMPLETE_FLAG] ?: false
    }

    /**
     * トラックAの現在ファイルパスを取得するFlow
     * 値がなければ空文字を返す
     */
    val trackACurrentFile: Flow<String> = dataStore.data.map { prefs ->
        // DataStoreからトラックAのファイルパスを読み込む（なければ空文字）
        prefs[KEY_TRACK_A_CURRENT_FILE] ?: ""
    }

    /**
     * トラックAの現在再生位置（ミリ秒）を取得するFlow
     * 値がなければ0Lを返す
     */
    val trackAPositionMs: Flow<Long> = dataStore.data.map { prefs ->
        // DataStoreからトラックAの再生位置を読み込む（なければ0ミリ秒）
        prefs[KEY_TRACK_A_POSITION_MS] ?: 0L
    }

    /**
     * トラックAの現在インデックスを取得するFlow
     * 値がなければ0を返す
     */
    val trackAFileIndex: Flow<Int> = dataStore.data.map { prefs ->
        // DataStoreからトラックAのインデックスを読み込む（なければ0）
        prefs[KEY_TRACK_A_FILE_INDEX] ?: 0
    }

    /**
     * トラックBのシャッフルリスト（JSON配列文字列）を取得するFlow
     * 値がなければ空文字を返す
     */
    val trackBShuffleList: Flow<String> = dataStore.data.map { prefs ->
        // DataStoreからトラックBのシャッフルリストを読み込む（なければ空文字）
        prefs[KEY_TRACK_B_SHUFFLE_LIST] ?: ""
    }

    /**
     * トラックBの現在インデックスを取得するFlow
     * 値がなければ0を返す
     */
    val trackBCurrentIndex: Flow<Int> = dataStore.data.map { prefs ->
        // DataStoreからトラックBの現在インデックスを読み込む（なければ0）
        prefs[KEY_TRACK_B_CURRENT_INDEX] ?: 0
    }

    // ========== 書き込みメソッド ==========

    /**
     * 当日の再生済みフラグと日付を同時に保存するメソッド
     * 仕様書セクション6「再生開始 + 今日の再生済みフラグを保存」に対応する
     *
     * @param date 今日の日付（YYYY-MM-DD形式）
     */
    suspend fun markTodayAsPlayed(date: String) {
        dataStore.edit { prefs ->
            // 最後の再生日付を保存する
            prefs[KEY_LAST_PLAY_DATE] = date
            // 当日の再生済みフラグをtrueにする
            prefs[KEY_TODAY_PLAYED_FLAG] = true
            // 完了フラグはまだfalseのまま（再生が完了した時にtrueにする）
            prefs[KEY_TODAY_COMPLETE_FLAG] = false
        }
    }

    /**
     * 当日の全ファイル再生完了フラグを保存するメソッド
     * TrackAPlayerの全再生完了コールバックから呼ばれる
     */
    suspend fun markTodayAsCompleted() {
        dataStore.edit { prefs ->
            // 全ファイル完了フラグをtrueにする
            prefs[KEY_TODAY_COMPLETE_FLAG] = true
        }
    }

    /**
     * トラックAの現在再生状態を保存するメソッド
     * 定期的に呼ばれて再生位置を記録する（再開時に続きから始めるため）
     *
     * @param filePath 現在のファイルパス
     * @param positionMs 現在の再生位置（ミリ秒）
     * @param fileIndex 現在のファイルインデックス
     */
    suspend fun saveTrackAState(filePath: String, positionMs: Long, fileIndex: Int) {
        dataStore.edit { prefs ->
            // ファイルパスを保存する
            prefs[KEY_TRACK_A_CURRENT_FILE] = filePath
            // 再生位置を保存する（ミリ秒単位）
            prefs[KEY_TRACK_A_POSITION_MS] = positionMs
            // ファイルインデックスを保存する
            prefs[KEY_TRACK_A_FILE_INDEX] = fileIndex
        }
    }

    /**
     * トラックBのシャッフル状態を保存するメソッド
     * シャッフルリストの順番と現在位置を当日セッション中は保持する
     *
     * @param shuffleListJson シャッフルリストのJSON配列文字列
     * @param currentIndex 現在再生中のインデックス
     */
    suspend fun saveTrackBState(shuffleListJson: String, currentIndex: Int) {
        dataStore.edit { prefs ->
            // シャッフルリストをJSON文字列で保存する
            prefs[KEY_TRACK_B_SHUFFLE_LIST] = shuffleListJson
            // 現在のインデックスを保存する
            prefs[KEY_TRACK_B_CURRENT_INDEX] = currentIndex
        }
    }

    /**
     * 日付が変わった時に当日フラグをリセットするメソッド
     * 毎朝最初のチェック時に前日のフラグをリセットする
     *
     * @param newDate 新しい日付（YYYY-MM-DD形式）
     */
    suspend fun resetForNewDay(newDate: String) {
        dataStore.edit { prefs ->
            // 新しい日付を保存する
            prefs[KEY_LAST_PLAY_DATE] = newDate
            // 再生済みフラグをリセットする
            prefs[KEY_TODAY_PLAYED_FLAG] = false
            // 完了フラグをリセットする
            prefs[KEY_TODAY_COMPLETE_FLAG] = false
            // トラックAの再生位置をリセットする（新しい日は最初から始める）
            prefs[KEY_TRACK_A_CURRENT_FILE] = ""
            prefs[KEY_TRACK_A_POSITION_MS] = 0L
            prefs[KEY_TRACK_A_FILE_INDEX] = 0
            // トラックBのシャッフルリストをリセットする（新しい日は再シャッフル）
            prefs[KEY_TRACK_B_SHUFFLE_LIST] = ""
            prefs[KEY_TRACK_B_CURRENT_INDEX] = 0
        }
    }

    /**
     * 全ての再生状態を一度に読み込むメソッド
     * Bluetooth接続時の再開判定に使う（一度にまとめて取得する）
     *
     * @return PlaybackSnapshot（現在の全再生状態をまとめたデータクラス）
     */
    suspend fun getSnapshot(): PlaybackSnapshot {
        // ★ dataStore.data.collect は Flow が完了するまでブロックし続けるため使えない。
        //    dataStore.data.first() で1回だけ読み込んで即リターンする。
        val prefs = dataStore.data.first()
        return PlaybackSnapshot(
            lastPlayDate      = prefs[KEY_LAST_PLAY_DATE] ?: "",
            todayPlayedFlag   = prefs[KEY_TODAY_PLAYED_FLAG] ?: false,
            todayCompleteFlag = prefs[KEY_TODAY_COMPLETE_FLAG] ?: false,
            trackACurrentFile = prefs[KEY_TRACK_A_CURRENT_FILE] ?: "",
            trackAPositionMs  = prefs[KEY_TRACK_A_POSITION_MS] ?: 0L,
            trackAFileIndex   = prefs[KEY_TRACK_A_FILE_INDEX] ?: 0,
            trackBShuffleList = prefs[KEY_TRACK_B_SHUFFLE_LIST] ?: "",
            trackBCurrentIndex = prefs[KEY_TRACK_B_CURRENT_INDEX] ?: 0
        )
    }
}

/**
 * PlaybackSnapshot - 再生状態の全データをまとめたデータクラス
 * Bluetooth接続時に全状態を一括取得するために使用する
 */
data class PlaybackSnapshot(
    // 最後に再生した日付（YYYY-MM-DD形式、空文字は未再生）
    val lastPlayDate: String = "",
    // 当日の再生済みフラグ
    val todayPlayedFlag: Boolean = false,
    // 当日の全ファイル完了フラグ
    val todayCompleteFlag: Boolean = false,
    // トラックAの現在ファイルパス（空文字は未設定）
    val trackACurrentFile: String = "",
    // トラックAの再生位置（ミリ秒）
    val trackAPositionMs: Long = 0L,
    // トラックAのファイルインデックス
    val trackAFileIndex: Int = 0,
    // トラックBのシャッフルリスト（JSON配列文字列）
    val trackBShuffleList: String = "",
    // トラックBの現在インデックス
    val trackBCurrentIndex: Int = 0
)
