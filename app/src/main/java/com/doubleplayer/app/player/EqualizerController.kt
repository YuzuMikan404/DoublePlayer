package com.doubleplayer.app.player

// AndroidのAudioSessionID取得に使用するインポート
import android.media.audiofx.Equalizer
// Hilt依存注入のインポート
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EqualizerController - イコライザー（低音・高音）を制御するクラス
 *
 * 仕様書セクション3のファイル構成に基づいて実装する。
 * Androidの標準イコライザー（android.media.audiofx.Equalizer）を使用する。
 * 低音（バス）と高音（トレブル）のバンドレベルを調整する。
 *
 * 注意：イコライザーはオーディオセッションIDごとに作成する必要がある。
 * トラックAとトラックBでそれぞれ別のEqualizerインスタンスを使用する。
 */
@Singleton
class EqualizerController @Inject constructor() {

    // トラックA用のイコライザーインスタンス
    private var equalizerA: Equalizer? = null

    // トラックB用のイコライザーインスタンス
    private var equalizerB: Equalizer? = null

    // 現在の低音レベル（-1000〜1000ミリデシベル、0がフラット）
    private var bassLevel: Int = 0

    // 現在の高音レベル（-1000〜1000ミリデシベル、0がフラット）
    private var trebleLevel: Int = 0

    // 低音バンドのインデックス（周波数が最も低いバンドを使用する）
    private var bassBandIndex: Short = 0

    // 高音バンドのインデックス（周波数が最も高いバンドを使用する）
    private var trebleBandIndex: Short = 0

    /**
     * トラックAのイコライザーを初期化するメソッド
     * ExoPlayerのオーディオセッションIDを使って初期化する
     *
     * @param audioSessionId ExoPlayerから取得したオーディオセッションID
     */
    fun initTrackA(audioSessionId: Int) {
        // 既存のイコライザーを解放する
        equalizerA?.release()
        // 新しいイコライザーを作成する（priority=0は一般アプリ用）
        equalizerA = createEqualizer(audioSessionId)
        // バンドインデックスを検出して設定する
        detectBandIndices(equalizerA)
        // 現在のレベルを適用する
        applyLevels(equalizerA)
    }

    /**
     * トラックBのイコライザーを初期化するメソッド
     * ExoPlayerのオーディオセッションIDを使って初期化する
     *
     * @param audioSessionId ExoPlayerから取得したオーディオセッションID
     */
    fun initTrackB(audioSessionId: Int) {
        // 既存のイコライザーを解放する
        equalizerB?.release()
        // 新しいイコライザーを作成する
        equalizerB = createEqualizer(audioSessionId)
        // 現在のレベルを適用する
        applyLevels(equalizerB)
    }

    /**
     * 低音レベルを設定するメソッド
     * トラックA・B両方に適用する
     *
     * @param level 低音レベル（-10〜10の整数、UIの値をミリdBに変換して使用する）
     */
    fun setBassLevel(level: Int) {
        // レベルをミリdBに変換する（UIの-10〜10を-1000〜1000に変換する）
        bassLevel = (level * 100).coerceIn(-1000, 1000)
        // トラックA・Bのイコライザーに適用する
        applyLevels(equalizerA)
        applyLevels(equalizerB)
    }

    /**
     * 高音レベルを設定するメソッド
     * トラックA・B両方に適用する
     *
     * @param level 高音レベル（-10〜10の整数）
     */
    fun setTrebleLevel(level: Int) {
        // レベルをミリdBに変換する
        trebleLevel = (level * 100).coerceIn(-1000, 1000)
        // トラックA・Bのイコライザーに適用する
        applyLevels(equalizerA)
        applyLevels(equalizerB)
    }

    /**
     * 現在の低音レベルをUI用の整数値で取得するメソッド
     *
     * @return 低音レベル（-10〜10）
     */
    fun getBassLevel(): Int = bassLevel / 100

    /**
     * 現在の高音レベルをUI用の整数値で取得するメソッド
     *
     * @return 高音レベル（-10〜10）
     */
    fun getTrebleLevel(): Int = trebleLevel / 100

    /**
     * イコライザーを作成するプライベートメソッド
     *
     * @param audioSessionId オーディオセッションID
     * @return 作成したEqualizerインスタンス（失敗時はnull）
     */
    private fun createEqualizer(audioSessionId: Int): Equalizer? {
        return try {
            // イコライザーを生成する
            val eq = Equalizer(0, audioSessionId)
            // イコライザーを有効にする
            eq.enabled = true
            eq
        } catch (e: Exception) {
            // イコライザーの生成に失敗した場合はnullを返す（端末非対応の場合あり）
            null
        }
    }

    /**
     * バンドインデックスを検出するプライベートメソッド
     * 最低周波数バンドを低音用、最高周波数バンドを高音用として使用する
     *
     * @param equalizer バンドを検出するEqualizerインスタンス
     */
    private fun detectBandIndices(equalizer: Equalizer?) {
        // イコライザーがnullの場合は何もしない
        equalizer ?: return
        // バンド数を取得する
        val numberOfBands = equalizer.numberOfBands
        if (numberOfBands < 2) return
        // 最初のバンドを低音用とする
        bassBandIndex = 0
        // 最後のバンドを高音用とする
        trebleBandIndex = (numberOfBands - 1).toShort()
    }

    /**
     * 現在の設定をイコライザーに適用するプライベートメソッド
     *
     * @param equalizer 設定を適用するEqualizerインスタンス
     */
    private fun applyLevels(equalizer: Equalizer?) {
        // イコライザーがnullの場合は何もしない
        equalizer ?: return
        try {
            // 低音バンドにレベルを設定する
            equalizer.setBandLevel(bassBandIndex, bassLevel.toShort())
            // 高音バンドにレベルを設定する
            equalizer.setBandLevel(trebleBandIndex, trebleLevel.toShort())
        } catch (e: Exception) {
            // バンドレベル設定に失敗した場合は無視する
        }
    }

    /**
     * リソースを解放するメソッド
     * PlayerServiceのonDestroy()から呼ぶ
     */
    fun release() {
        // トラックAのイコライザーを解放する
        equalizerA?.release()
        equalizerA = null
        // トラックBのイコライザーを解放する
        equalizerB?.release()
        equalizerB = null
    }
}
