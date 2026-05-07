package com.doubleplayer.app.player

// Media3のプレイヤー制御関連インポート
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
// Hilt依存注入のインポート
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SpeedController - 再生速度を制御するクラス
 *
 * 仕様書セクション2「プレイヤー画面」に基づいて実装する。
 * 再生速度は0.5x〜2.0xの範囲で調整できる。
 * トラックAとトラックBに同じ速度を適用する（BGMと音声の同期を保つため）。
 */
@Singleton
class SpeedController @Inject constructor() {

    // 再生速度の最小値（仕様書：0.5x）
    val minSpeed: Float = 0.5f

    // 再生速度の最大値（仕様書：2.0x）
    val maxSpeed: Float = 2.0f

    // デフォルトの再生速度（1.0x = 通常速度）
    val defaultSpeed: Float = 1.0f

    // 現在の再生速度（初期値は通常速度）
    private var currentSpeed: Float = defaultSpeed

    // トラックAのExoPlayerインスタンス（外部から設定する）
    private var trackAPlayer: ExoPlayer? = null

    // トラックBのExoPlayerインスタンス（外部から設定する）
    private var trackBPlayer: ExoPlayer? = null

    /**
     * トラックAのExoPlayerを設定するメソッド
     * TrackAPlayerを初期化した後に呼ぶ
     *
     * @param player トラックA用のExoPlayerインスタンス
     */
    fun setTrackAPlayer(player: ExoPlayer) {
        // プレイヤーを保持する
        trackAPlayer = player
        // 現在の速度を即座に適用する
        applySpeedToPlayer(player, currentSpeed)
    }

    /**
     * トラックBのExoPlayerを設定するメソッド
     * TrackBPlayerを初期化した後に呼ぶ
     *
     * @param player トラックB用のExoPlayerインスタンス
     */
    fun setTrackBPlayer(player: ExoPlayer) {
        // プレイヤーを保持する
        trackBPlayer = player
        // 現在の速度を即座に適用する
        applySpeedToPlayer(player, currentSpeed)
    }

    /**
     * 再生速度を設定するメソッド
     * トラックA・Bの両方に同時に適用する
     *
     * @param speed 設定する再生速度（0.5f〜2.0f）
     */
    fun setSpeed(speed: Float) {
        // 速度を有効範囲内にクランプする
        val clampedSpeed = speed.coerceIn(minSpeed, maxSpeed)
        // 現在速度を更新する
        currentSpeed = clampedSpeed
        // トラックAに速度を適用する
        trackAPlayer?.let { applySpeedToPlayer(it, clampedSpeed) }
        // トラックBに速度を適用する
        trackBPlayer?.let { applySpeedToPlayer(it, clampedSpeed) }
    }

    /**
     * 現在の再生速度を取得するメソッド
     *
     * @return 現在の再生速度（0.5f〜2.0f）
     */
    fun getCurrentSpeed(): Float = currentSpeed

    /**
     * 速度をデフォルト値（1.0x）にリセットするメソッド
     */
    fun resetToDefault() {
        // デフォルト速度を設定する
        setSpeed(defaultSpeed)
    }

    /**
     * 指定したExoPlayerに再生速度を適用するプライベートメソッド
     * PlaybackParametersオブジェクトを生成してExoPlayerに渡す
     *
     * @param player 速度を適用するExoPlayerインスタンス
     * @param speed 設定する再生速度
     */
    private fun applySpeedToPlayer(player: ExoPlayer, speed: Float) {
        // PlaybackParametersを生成して速度を設定する
        // pitchは1.0fのまま（速度変更でピッチが変わらないようにする）
        val params = PlaybackParameters(speed, 1.0f)
        // ExoPlayerに再生パラメータを適用する
        player.playbackParameters = params
    }

    /**
     * リソースを解放するメソッド
     * PlayerServiceのonDestroy()から呼ぶ
     */
    fun release() {
        // プレイヤーの参照をクリアする（ExoPlayer自体のreleaseはTrackPlayerが行う）
        trackAPlayer = null
        trackBPlayer = null
        // 速度をデフォルトに戻す
        currentSpeed = defaultSpeed
    }
}
