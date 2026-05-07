package com.doubleplayer.app.schedule

// ネットワーク通信のインポート
import android.content.Context
// コルーチン関連のインポート
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
// Hilt依存注入のインポート
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
// JSON処理のインポート
import org.json.JSONObject
// ネットワーク通信のインポート
import java.net.HttpURLConnection
import java.net.URL
// 日付処理のインポート
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * HolidayChecker - 日本の祝日を判定するクラス
 *
 * 仕様書セクション3「schedule/HolidayChecker.kt」に基づいて実装する。
 * 内閣府が公開している祝日APIを使用して祝日判定を行う。
 *
 * 使用するAPI：https://holidays-jp.github.io/api/v1/date.json
 * このAPIは日本の全祝日を{"YYYY-MM-DD": "祝日名"}形式のJSONで返す。
 *
 * ネットワーク不可時のフォールバック：
 * - 通信失敗した場合はキャッシュを使う
 * - キャッシュもない場合はfalse（祝日ではない）を返す（安全側に倒す）
 */
@Singleton
class HolidayChecker @Inject constructor(
    // Androidコンテキスト（ネットワーク状態確認に使用）
    @ApplicationContext private val context: Context
) {

    // 祝日データのキャッシュ（メモリ内に保持する）
    // キー：YYYY-MM-DD形式の日付文字列、値：祝日名
    private val holidayCache: MutableMap<String, String> = mutableMapOf()

    // 祝日データAPIのURL（内閣府公認の祝日データ）
    private val holidayApiUrl = "https://holidays-jp.github.io/api/v1/date.json"

    // APIの最終取得日時（1日1回だけ取得するための管理）
    private var lastFetchDate: String = ""

    // 日付フォーマッター（YYYY-MM-DD形式）
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 指定した日付が日本の祝日かどうかを判定するメソッド
     *
     * @param date 判定する日付（LocalDate）
     * @return true = 祝日、false = 祝日でない（または判定不可）
     */
    suspend fun isHoliday(date: LocalDate): Boolean {
        // 日付をYYYY-MM-DD形式に変換する
        val dateString = date.format(dateFormatter)
        // 今日まだ取得していない場合はAPIからデータを取得する
        if (lastFetchDate != dateString) {
            fetchHolidayData()
            // 取得後に今日の日付を記録する
            lastFetchDate = dateString
        }
        // キャッシュに日付が含まれていれば祝日と判定する
        return holidayCache.containsKey(dateString)
    }

    /**
     * 今日が祝日かどうかを判定するメソッド（LocalDateを自動で取得する）
     *
     * @return true = 今日は祝日、false = 祝日でない（または判定不可）
     */
    suspend fun isTodayHoliday(): Boolean {
        // 今日の日付を取得する
        val today = LocalDate.now()
        // 今日の日付で祝日チェックをする
        return isHoliday(today)
    }

    /**
     * 祝日データをAPIから取得してキャッシュに保存するプライベートメソッド
     * ネットワーク通信はIOスレッドで実行する
     */
    private suspend fun fetchHolidayData() {
        withContext(Dispatchers.IO) {
            try {
                // APIに接続する
                val url = URL(holidayApiUrl)
                val connection = url.openConnection() as HttpURLConnection
                // タイムアウトを設定する（5秒で諦める）
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"

                // レスポンスコードを確認する
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // レスポンスボディを読み込む
                    val responseBody = connection.inputStream.bufferedReader().readText()
                    // JSONをパースしてキャッシュに保存する
                    parseAndCacheHolidayData(responseBody)
                }
                // 接続を閉じる
                connection.disconnect()
            } catch (e: Exception) {
                // 通信失敗時はキャッシュをそのまま使う（何もしない）
                // キャッシュがない場合はisHolidayがfalseを返すので問題ない
            }
        }
    }

    /**
     * APIから取得したJSONをパースしてキャッシュに保存するプライベートメソッド
     *
     * @param jsonString APIから取得したJSON文字列
     * 形式：{"YYYY-MM-DD": "祝日名", "YYYY-MM-DD": "祝日名", ...}
     */
    private fun parseAndCacheHolidayData(jsonString: String) {
        try {
            // 既存のキャッシュをクリアして最新データで上書きする
            holidayCache.clear()
            // JSONオブジェクトとしてパースする
            val jsonObject = JSONObject(jsonString)
            // 全てのキー（日付）を取得してキャッシュに保存する
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val dateKey = keys.next()
                // 祝日名を取得する
                val holidayName = jsonObject.getString(dateKey)
                // キャッシュに保存する
                holidayCache[dateKey] = holidayName
            }
        } catch (e: Exception) {
            // JSONパース失敗時はキャッシュを変更しない
        }
    }

    /**
     * キャッシュされている祝日名を取得するメソッド（デバッグ・UI表示用）
     *
     * @param date 取得する日付（LocalDate）
     * @return 祝日名（祝日でない場合はnull）
     */
    fun getHolidayName(date: LocalDate): String? {
        // 日付をYYYY-MM-DD形式に変換する
        val dateString = date.format(dateFormatter)
        // キャッシュから祝日名を取得する
        return holidayCache[dateString]
    }

    /**
     * キャッシュを強制的に更新するメソッド（設定画面からの手動更新用）
     */
    suspend fun forceRefresh() {
        // 最終取得日を空にしてAPIを再取得させる
        lastFetchDate = ""
        // 今日の日付で強制的にAPIを叩く
        isTodayHoliday()
    }
}
