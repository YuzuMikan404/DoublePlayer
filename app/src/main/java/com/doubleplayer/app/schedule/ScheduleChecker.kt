package com.doubleplayer.app.schedule

// 日付・時刻処理のインポート
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
// Hilt依存注入のインポート
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ScheduleChecker - 再生条件（時間帯・曜日・祝日）をチェックするクラス
 *
 * 仕様書セクション6「自動起動ロジック」に基づいて実装する。
 * Bluetooth接続検知後に以下の順番でチェックして再生可否を判定する：
 *
 * 1. 現在時刻がいずれかのトリガー時間帯に入っているか
 * 2. 今日の曜日がactiveDaysでONになっているか
 * 3. skipHolidays=trueの場合、今日は祝日か
 * 4. 今日すでに再生済みフラグが立っているか
 *
 * ScheduleCheckerは条件判定のみを担当し、実際の再生開始はTriggerManagerが行う。
 */
@Singleton
class ScheduleChecker @Inject constructor(
    // 祝日判定クラス（HolidayCheckerに処理を委譲する）
    private val holidayChecker: HolidayChecker
) {

    /**
     * 現在時刻が指定したトリガーの時間帯に含まれるかチェックするメソッド
     *
     * @param triggerConfig チェックするトリガーの設定（開始・終了時刻）
     * @param now 現在時刻（省略した場合はLocalTime.now()を使う）
     * @return true = 時間帯内、false = 時間帯外
     */
    fun isInTimeRange(triggerConfig: TriggerConfig, now: LocalTime = LocalTime.now()): Boolean {
        // トリガーが無効なら即falseを返す
        if (!triggerConfig.enabled) return false

        // 開始時刻と終了時刻をLocalTimeに変換する
        val startTime = LocalTime.of(triggerConfig.startHour, triggerConfig.startMinute)
        val endTime = LocalTime.of(triggerConfig.endHour, triggerConfig.endMinute)

        // 現在時刻が開始〜終了の範囲内かチェックする
        return now.isAfter(startTime) && now.isBefore(endTime) || now == startTime
    }

    /**
     * 今日の曜日が有効かどうかチェックするメソッド
     *
     * @param activeDays 曜日ごとの有効フラグ（DayConfigデータクラス）
     * @param today 今日の日付（省略した場合はLocalDate.now()を使う）
     * @return true = 今日は有効な曜日、false = 今日は無効な曜日
     */
    fun isActiveDayOfWeek(activeDays: ActiveDaysConfig, today: LocalDate = LocalDate.now()): Boolean {
        // 今日の曜日を取得する（Java DayOfWeek：MONDAY〜SUNDAY）
        return when (today.dayOfWeek) {
            DayOfWeek.SUNDAY    -> activeDays.sunday
            DayOfWeek.MONDAY    -> activeDays.monday
            DayOfWeek.TUESDAY   -> activeDays.tuesday
            DayOfWeek.WEDNESDAY -> activeDays.wednesday
            DayOfWeek.THURSDAY  -> activeDays.thursday
            DayOfWeek.FRIDAY    -> activeDays.friday
            DayOfWeek.SATURDAY  -> activeDays.saturday
        }
    }

    /**
     * 祝日スキップの判定をするメソッド
     * skipHolidays=trueかつ今日が祝日の場合はtrueを返す（スキップする）
     *
     * @param skipHolidays 祝日をスキップするかどうかの設定値
     * @param today 今日の日付（省略した場合はLocalDate.now()を使う）
     * @return true = 今日は再生をスキップすべき（祝日スキップが有効かつ祝日）
     */
    suspend fun shouldSkipForHoliday(
        skipHolidays: Boolean,
        today: LocalDate = LocalDate.now()
    ): Boolean {
        // 祝日スキップが無効なら即falseを返す（スキップしない）
        if (!skipHolidays) return false
        // 今日が祝日かどうかを確認する
        val isHoliday = holidayChecker.isHoliday(today)
        // 祝日ならtrue（スキップする）を返す
        return isHoliday
    }

    /**
     * 再生条件を総合的にチェックするメソッド
     * 仕様書セクション6の全チェック（時間帯・曜日・祝日）をまとめて実行する。
     * 「今日すでに再生済みか」はTriggerManagerが別途確認する。
     *
     * @param scheduleConfig スケジュール設定全体
     * @param now 現在時刻（テスト用に引数で受け取る）
     * @param today 今日の日付（テスト用に引数で受け取る）
     * @return ScheduleCheckResult（チェック結果と一致したトリガー）
     */
    suspend fun checkSchedule(
        scheduleConfig: ScheduleConfig,
        now: LocalTime = LocalTime.now(),
        today: LocalDate = LocalDate.now()
    ): ScheduleCheckResult {

        // ステップ1：有効なトリガーの中でいずれかが現在時刻に該当するか調べる
        val matchingTrigger = scheduleConfig.triggers.firstOrNull { trigger ->
            isInTimeRange(trigger, now)
        }

        // 該当するトリガーがなければ「時間帯外」として終了する
        if (matchingTrigger == null) {
            return ScheduleCheckResult(
                shouldPlay = false,
                reason = "現在時刻はどのトリガー時間帯にも該当しません",
                matchedTrigger = null
            )
        }

        // ステップ2：今日の曜日が有効かチェックする
        val isActiveDay = isActiveDayOfWeek(scheduleConfig.activeDays, today)
        if (!isActiveDay) {
            return ScheduleCheckResult(
                shouldPlay = false,
                reason = "今日（${today.dayOfWeek.name}）は再生対象の曜日ではありません",
                matchedTrigger = matchingTrigger
            )
        }

        // ステップ3：祝日スキップの判定をする
        val skipForHoliday = shouldSkipForHoliday(scheduleConfig.skipHolidays, today)
        if (skipForHoliday) {
            val holidayName = holidayChecker.getHolidayName(today) ?: "祝日"
            return ScheduleCheckResult(
                shouldPlay = false,
                reason = "今日は${holidayName}のためスキップします",
                matchedTrigger = matchingTrigger
            )
        }

        // 全チェック通過：再生可能と判定する
        return ScheduleCheckResult(
            shouldPlay = true,
            reason = "再生条件を満たしています（トリガー：${matchingTrigger.id}）",
            matchedTrigger = matchingTrigger
        )
    }
}

// ========== データクラス定義 ==========

/**
 * TriggerConfig - 1つの時間帯トリガーの設定データクラス
 * default_settings.jsonのtriggers配列の各要素に対応する
 */
data class TriggerConfig(
    // トリガーの識別ID（例："morning"）
    val id: String,
    // このトリガーが有効かどうか
    val enabled: Boolean,
    // 開始時刻の時（0〜23）
    val startHour: Int,
    // 開始時刻の分（0〜59）
    val startMinute: Int,
    // 終了時刻の時（0〜23）
    val endHour: Int,
    // 終了時刻の分（0〜59）
    val endMinute: Int
)

/**
 * ActiveDaysConfig - 曜日ごとの有効フラグをまとめたデータクラス
 * default_settings.jsonのactiveDaysオブジェクトに対応する
 */
data class ActiveDaysConfig(
    // 日曜日を有効にするか
    val sunday: Boolean = false,
    // 月曜日を有効にするか
    val monday: Boolean = true,
    // 火曜日を有効にするか
    val tuesday: Boolean = true,
    // 水曜日を有効にするか
    val wednesday: Boolean = true,
    // 木曜日を有効にするか
    val thursday: Boolean = true,
    // 金曜日を有効にするか
    val friday: Boolean = true,
    // 土曜日を有効にするか
    val saturday: Boolean = false
)

/**
 * ScheduleConfig - スケジュール設定全体をまとめたデータクラス
 * default_settings.jsonのscheduleオブジェクトに対応する
 */
data class ScheduleConfig(
    // 複数の時間帯トリガーのリスト
    val triggers: List<TriggerConfig> = emptyList(),
    // 曜日ごとの有効フラグ
    val activeDays: ActiveDaysConfig = ActiveDaysConfig(),
    // 祝日をスキップするかどうか
    val skipHolidays: Boolean = true,
    // 再生開始前のカウントダウン秒数（0の場合はカウントダウンなし）
    val countdownSeconds: Int = 0
)

/**
 * ScheduleCheckResult - スケジュールチェック結果をまとめたデータクラス
 * ScheduleChecker.checkSchedule()の戻り値として使用する
 */
data class ScheduleCheckResult(
    // 再生すべきかどうか（true = 再生可）
    val shouldPlay: Boolean,
    // チェック結果の理由（ログ・デバッグ用）
    val reason: String,
    // 一致したトリガー（shouldPlay=falseの時はnullの場合もある）
    val matchedTrigger: TriggerConfig?
)
