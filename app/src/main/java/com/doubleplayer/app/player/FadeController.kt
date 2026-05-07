package com.doubleplayer.app.player

// コルーチン関連のインポート
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
// Hilt依存注入のインポート
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FadeController - トラックBのフェードイン/アウトを制御するクラス
 *
 * 仕様書セクション5「フェード制御」に基づいて実装する。
 * トラックAの状態に連動してトラックBの音量を滑らかに変化させる。
 *
 * フェード動作一覧：
 * - トラックA開始       → トラックBをフェードイン（fadeInSeconds秒）
 * - トラックA一時停止   → トラックBをフェードアウト（fadeOutSeconds秒）
 * - トラックA再開       → トラックBをフェードイン
 * - トラックA終了N秒前  → トラックBをフェードアウト開始（fadeOutBeforeEndSeconds）
 */
@Singleton
class FadeController @Inject constructor() {

    // フェードイン処理のジョブ（進行中のフェードをキャンセルするために保持する）
    private var fadeInJob: Job? = null

    // フェードアウト処理のジョブ（進行中のフェードをキャンセルするために保持する）
    private var fadeOutJob: Job? = null

    // フェード処理用のコルーチンスコープ（Dispatchers.Defaultで実行する）
    private val fadeScope = CoroutineScope(Dispatchers.Default)

    // 現在のトラックBの実際の音量（0.0f〜1.0f）
    private var currentVolume: Float = 0f

    // 音量更新コールバック（PlayerServiceから設定する）
    private var onVolumeChanged: ((Float) -> Unit)? = null

    /**
     * 音量更新コールバックを設定するメソッド
     * PlayerServiceがこのコールバックでExoPlayerの音量を更新する
     *
     * @param callback 音量が変化したときに呼ばれるコールバック
     */
    fun setOnVolumeChangedListener(callback: (Float) -> Unit) {
        // コールバックを保持する
        onVolumeChanged = callback
    }

    /**
     * フェードインを開始するメソッド
     * 現在の音量から目標音量まで指定した秒数かけて上げる
     *
     * @param targetVolume フェードイン後の目標音量（0.0f〜1.0f）
     * @param durationSeconds フェードインにかける秒数
     */
    fun fadeIn(targetVolume: Float, durationSeconds: Float) {
        // 進行中のフェード処理をすべてキャンセルする
        cancelAllFades()

        // フェードインジョブを起動する
        fadeInJob = fadeScope.launch {
            // フェードの更新間隔（ミリ秒）
            val intervalMs = 50L
            // 総ステップ数を計算する
            val totalSteps = (durationSeconds * 1000 / intervalMs).toInt().coerceAtLeast(1)
            // 開始音量を保存する
            val startVolume = currentVolume
            // 1ステップあたりの音量増加量を計算する
            val stepSize = (targetVolume - startVolume) / totalSteps

            // 指定ステップ数だけ繰り返す
            repeat(totalSteps) { step ->
                // コルーチンがキャンセルされていたら中断する
                if (!isActive) return@launch
                // 現在の音量を計算する（計算誤差を防ぐため直接計算する）
                val newVolume = (startVolume + stepSize * (step + 1)).coerceIn(0f, targetVolume)
                // 現在音量を更新する
                currentVolume = newVolume
                // コールバックで音量変化を通知する
                onVolumeChanged?.invoke(currentVolume)
                // 次のステップまで待機する
                delay(intervalMs)
            }
            // 最終的に目標音量に正確に合わせる
            currentVolume = targetVolume
            onVolumeChanged?.invoke(currentVolume)
        }
    }

    /**
     * フェードアウトを開始するメソッド
     * 現在の音量から0まで指定した秒数かけて下げる
     *
     * @param durationSeconds フェードアウトにかける秒数
     * @param onComplete フェードアウト完了後に呼ばれるコールバック（省略可）
     */
    fun fadeOut(durationSeconds: Float, onComplete: (() -> Unit)? = null) {
        // 進行中のフェード処理をすべてキャンセルする
        cancelAllFades()

        // すでに音量が0の場合はコールバックだけ呼んで終了する
        if (currentVolume <= 0f) {
            onComplete?.invoke()
            return
        }

        // フェードアウトジョブを起動する
        fadeOutJob = fadeScope.launch {
            // フェードの更新間隔（ミリ秒）
            val intervalMs = 50L
            // 総ステップ数を計算する
            val totalSteps = (durationSeconds * 1000 / intervalMs).toInt().coerceAtLeast(1)
            // 開始音量を保存する
            val startVolume = currentVolume
            // 1ステップあたりの音量減少量を計算する
            val stepSize = startVolume / totalSteps

            // 指定ステップ数だけ繰り返す
            repeat(totalSteps) { step ->
                // コルーチンがキャンセルされていたら中断する
                if (!isActive) return@launch
                // 現在の音量を計算する
                val newVolume = (startVolume - stepSize * (step + 1)).coerceIn(0f, startVolume)
                // 現在音量を更新する
                currentVolume = newVolume
                // コールバックで音量変化を通知する
                onVolumeChanged?.invoke(currentVolume)
                // 次のステップまで待機する
                delay(intervalMs)
            }
            // 最終的に音量を0に正確に合わせる
            currentVolume = 0f
            onVolumeChanged?.invoke(0f)
            // 完了コールバックを呼ぶ
            onComplete?.invoke()
        }
    }

    /**
     * 現在の音量を即座に設定するメソッド
     * フェードなしで音量を変更したい場合に使用する
     *
     * @param volume 設定する音量（0.0f〜1.0f）
     */
    fun setVolume(volume: Float) {
        // 進行中のフェードをキャンセルする
        cancelAllFades()
        // 音量を即座に設定する
        currentVolume = volume.coerceIn(0f, 1f)
        // コールバックで通知する
        onVolumeChanged?.invoke(currentVolume)
    }

    /**
     * 現在の音量を取得するメソッド
     *
     * @return 現在の音量（0.0f〜1.0f）
     */
    fun getCurrentVolume(): Float = currentVolume

    /**
     * 進行中のフェード処理をすべてキャンセルするメソッド
     * 新しいフェードを開始する前に呼ぶことで競合を防ぐ
     */
    fun cancelAllFades() {
        // フェードインジョブをキャンセルする
        fadeInJob?.cancel()
        fadeInJob = null
        // フェードアウトジョブをキャンセルする
        fadeOutJob?.cancel()
        fadeOutJob = null
    }

    /**
     * リソースを解放するメソッド
     * PlayerServiceのonDestroy()から呼ぶ
     */
    fun release() {
        // すべてのフェード処理を停止する
        cancelAllFades()
        // コルーチンスコープをキャンセルする
        fadeScope.cancel()
        // コールバックをクリアする
        onVolumeChanged = null
    }
}
