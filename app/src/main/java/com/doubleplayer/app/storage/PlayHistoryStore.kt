package com.doubleplayer.app.storage

// DataStore関連のインポート
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
// Gson（JSON処理）のインポート
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
// Hilt依存注入のインポート
import com.doubleplayer.app.di.HistoryStore
import javax.inject.Inject
import javax.inject.Singleton
// コルーチン関連のインポート
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
// Android関連のインポート
import android.util.Log

/**
 * PlayHistoryStore - 再生履歴を保存・読み込みするクラス
 *
 * 仕様書セクション13「再生履歴の保存仕様」に基づいて実装する。
 * DataStoreにJSON形式で最大100件まで履歴を保存する。
 *
 * 保存するデータ形式：
 * [
 *   {
 *     "date": "2026-05-05",
 *     "time": "07:00",
 *     "trackAFile": "0101_morning.mp3",
 *     "trigger": "bluetooth_auto",
 *     "completed": true
 *   }
 * ]
 */
@Singleton
class PlayHistoryStore @Inject constructor(
    // 再生履歴専用のDataStore（AppModuleのHistoryStoreで提供される）
    @HistoryStore private val dataStore: DataStore<Preferences>,
    // JSON変換用のGson（AppModuleで提供される）
    private val gson: Gson
) {

    // ログ出力用タグ
    private val TAG = "PlayHistoryStore"

    // 最大保存件数（仕様書セクション13より）
    private val MAX_HISTORY_SIZE = 100

    // 再生履歴リストを保存するDataStoreキー
    private val KEY_HISTORY_LIST = stringPreferencesKey("play_history_list")

    /**
     * PlayHistoryEntry - 再生履歴の1件分を表すデータクラス
     *
     * 仕様書セクション13のJSON構造に対応する。
     *
     * @param date        再生日（YYYY-MM-DD形式）
     * @param time        再生開始時刻（HH:mm形式）
     * @param trackAFile  トラックAで再生していたファイル名
     * @param trigger     再生のトリガー種別（"bluetooth_auto", "manual", "macroDroid"）
     * @param completed   全ファイル再生完了したかどうか
     */
    data class PlayHistoryEntry(
        val date: String,        // 再生日（YYYY-MM-DD形式）
        val time: String,        // 再生開始時刻（HH:mm形式）
        val trackAFile: String,  // トラックAのファイル名（表示用）
        val trigger: String,     // 再生トリガー種別
        val completed: Boolean   // 当日の再生が完了したかどうか
    )

    /**
     * 再生履歴リストをFlowで購読する
     *
     * UIがリアルタイムで履歴の変化を受け取れるようにFlowを返す。
     * 履歴がない場合は空リストを返す。
     */
    val historyFlow: Flow<List<PlayHistoryEntry>> = dataStore.data.map { preferences ->
        // JSON文字列が保存されている場合はデシリアライズして返す
        val json = preferences[KEY_HISTORY_LIST] ?: return@map emptyList()
        try {
            // Gsonを使ってJSON文字列をリストに変換する
            val type = object : TypeToken<List<PlayHistoryEntry>>() {}.type
            gson.fromJson<List<PlayHistoryEntry>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            // JSONの解析に失敗した場合はログに記録して空リストを返す
            Log.e(TAG, "再生履歴のJSONパースに失敗しました", e)
            emptyList()
        }
    }

    /**
     * addEntry - 再生履歴に1件追加するメソッド
     *
     * 最大100件を超えた場合は古いものから削除する（仕様書セクション13より）。
     *
     * @param entry 追加する履歴エントリ
     */
    suspend fun addEntry(entry: PlayHistoryEntry) {
        dataStore.edit { preferences ->
            // 現在の履歴リストを取得する
            val currentJson = preferences[KEY_HISTORY_LIST] ?: "[]"
            val currentList = try {
                val type = object : TypeToken<MutableList<PlayHistoryEntry>>() {}.type
                gson.fromJson<MutableList<PlayHistoryEntry>>(currentJson, type)
                    ?: mutableListOf()
            } catch (e: Exception) {
                // パース失敗時は空リストから始める
                Log.e(TAG, "既存履歴のJSONパースに失敗しました。履歴をリセットします", e)
                mutableListOf()
            }

            // 先頭に新しい履歴を追加する（最新が先頭になるようにする）
            currentList.add(0, entry)

            // 最大件数を超えた場合は末尾から削除する
            while (currentList.size > MAX_HISTORY_SIZE) {
                currentList.removeAt(currentList.size - 1)
            }

            // リストをJSON文字列に変換してDataStoreに保存する
            preferences[KEY_HISTORY_LIST] = gson.toJson(currentList)

            Log.d(TAG, "再生履歴を追加しました: ${entry.date} ${entry.time} - ${entry.trackAFile}")
        }
    }

    /**
     * updateLastEntryCompletion - 直前の履歴エントリの完了フラグを更新するメソッド
     *
     * 再生が途中で終わった場合はcompletedがfalseのままになっているため、
     * 全ファイル再生完了時にtrueに更新するために使用する。
     *
     * @param date      更新対象の日付（YYYY-MM-DD形式）
     * @param completed 設定する完了フラグの値
     */
    suspend fun updateLastEntryCompletion(date: String, completed: Boolean) {
        dataStore.edit { preferences ->
            // 現在の履歴リストを取得する
            val currentJson = preferences[KEY_HISTORY_LIST] ?: return@edit
            val currentList = try {
                val type = object : TypeToken<MutableList<PlayHistoryEntry>>() {}.type
                gson.fromJson<MutableList<PlayHistoryEntry>>(currentJson, type)
                    ?: return@edit
            } catch (e: Exception) {
                Log.e(TAG, "履歴更新時のJSONパースに失敗しました", e)
                return@edit
            }

            // 指定日付の最新エントリを探して完了フラグを更新する
            val index = currentList.indexOfFirst { it.date == date }
            if (index >= 0) {
                currentList[index] = currentList[index].copy(completed = completed)
                preferences[KEY_HISTORY_LIST] = gson.toJson(currentList)
                Log.d(TAG, "再生完了フラグを更新しました: $date completed=$completed")
            }
        }
    }

    /**
     * getHistoryList - 再生履歴リストを一度だけ取得するメソッド
     *
     * FlowではなくSuspend関数として使いたい場合に使用する。
     *
     * @return 再生履歴リスト（最新が先頭）
     */
    suspend fun getHistoryList(): List<PlayHistoryEntry> {
        return historyFlow.first()
    }

    /**
     * getHistoryForDate - 指定日付の履歴を取得するメソッド
     *
     * @param date 取得する日付（YYYY-MM-DD形式）
     * @return 指定日付の履歴エントリリスト
     */
    suspend fun getHistoryForDate(date: String): List<PlayHistoryEntry> {
        return getHistoryList().filter { it.date == date }
    }

    /**
     * clearHistory - 再生履歴を全件削除するメソッド
     *
     * 設定画面の「履歴をクリア」ボタンから呼ばれる想定。
     */
    suspend fun clearHistory() {
        dataStore.edit { preferences ->
            // 空のJSON配列を保存して履歴を全件削除する
            preferences[KEY_HISTORY_LIST] = "[]"
            Log.i(TAG, "再生履歴を全件削除しました")
        }
    }

    /**
     * getHistoryCount - 保存されている履歴件数を返すメソッド
     *
     * @return 現在の履歴件数
     */
    suspend fun getHistoryCount(): Int {
        return getHistoryList().size
    }

    /**
     * hasTodayHistory - 今日の再生履歴があるかどうか確認するメソッド
     *
     * @param today 今日の日付（YYYY-MM-DD形式）
     * @return 今日の履歴が1件以上あればtrue
     */
    suspend fun hasTodayHistory(today: String): Boolean {
        return getHistoryForDate(today).isNotEmpty()
    }
}
