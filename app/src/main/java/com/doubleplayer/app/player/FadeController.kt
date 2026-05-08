package com.doubleplayer.app.player

// コルーチン関連のインポート
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
// Hilt依存注入のインポート
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FadeController - トラックBのフェードイン/アウトを制御するクラス
 *
 * 【修正】ExoPlayerはメインスレッドからしかアクセスできないため、
 * フェード計算はDefaultスレッドで行い、コールバック呼び出しは
 * withContext(Dispatchers.Main)でメインスレッドに切り替えて実行する。
 * これにより「Player is accessed on the wrong thread」クラッシュを防ぐ。
 */
@Singleton
class FadeController @Inject constructor() {

    // フェードイン処理のジョブ（進行中のフェードをキャンセルするために保持する）
    private var fadeInJob: Job? = null

    // フェードアウト処理のジョブ（進行中のフェードをキャンセルするために保持する）
    private var fadeOutJob: Job? = null

    // フェード計算用コルーチンスコープ（Defaultスレッドで時間計算する）
    private val fadeScope = CoroutineScope(Dispatchers.Default)

    // 現在のトラックBの音量（0.0f〜1.0f）
    private var currentVolume: Float = 0f

    // 音量更新コールバック（ExoPlayerへの反映はメインスレッドで行う必要がある）
    private var onVolumeChanged: ((Float) -> Unit)? = null

    /**
     * 音量更新コールバックを設定するメソッド
     * PlayerServiceがこのコールバックでExoPlayerの音量を更新する
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

        // Defaultスレッドで計算し、Mainスレッドでコールバックを呼ぶ
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
                // 現在の音量を計算する
                val newVolume = (startVolume + stepSize * (step + 1)).coerceIn(0f, targetVolume)
                // 現在音量を更新する
                currentVolume = newVolume
                // ★ ExoPlayerへの音量設定は必ずメインスレッドで行う
                withContext(Dispatchers.Main) {
                    onVolumeChanged?.invoke(currentVolume)
                }
                // 次のステップまで待機する
                delay(intervalMs)
            }
            // 最終的に目標音量に正確に合わせる
            currentVolume = targetVolume
            // ★ 最終値もメインスレッドで設定する
            withContext(Dispatchers.Main) {
                onVolumeChanged?.invoke(currentVolume)
            }
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

        // Defaultスレッドで計算し、Mainスレッドでコールバックを呼ぶ
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
                // ★ ExoPlayerへの音量設定は必ずメインスレッドで行う
                withContext(Dispatchers.Main) {
                    onVolumeChanged?.invoke(currentVolume)
                }
                // 次のステップまで待機する
                delay(intervalMs)
            }
            // 最終的に音量を0に正確に合わせる
            currentVolume = 0f
            // ★ 最終値もメインスレッドで設定する
            withContext(Dispatchers.Main) {
                onVolumeChanged?.invoke(0f)
            }
            // ★ 完了コールバックもメインスレッドで呼ぶ（PlayerServiceのBinder操作のため）
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    /**
     * 現在の音量を即座に設定するメソッド（フェード処理を中断する）
     * ★ このメソッドはメインスレッドから呼ぶこと（PlayerServiceから呼ぶため問題なし）
     *
     * @param volume 設定する音量（0.0f〜1.0f）
     */
    fun setVolume(volume: Float) {
        // 進行中のフェードをキャンセルする
        cancelAllFades()
        // 音量を即座に設定する
        currentVolume = volume.coerceIn(0f, 1f)
        // コールバックで通知する（呼び出し元がメインスレッドのため直接呼ぶ）
        onVolumeChanged?.invoke(currentVolume)
    }

    /**
     * フェード処理を中断せずに現在の目標音量だけを更新するメソッド
     * ★【音量調節修正】スライダー操作など、フェード中でも即座に音量レベルを変えたい場合に使う。
     *   setVolume()はフェードを中断してしまうが、このメソッドはフェードを継続しながら
     *   次のフェードの目標音量を変更する（現在音量も直ちに反映する）。
     *   再生中にスライダーを動かした場合は currentVolume を直接書き換えてコールバックを呼ぶ。
     *
     * @param volume 新しい目標音量（0.0f〜1.0f）
     */
    fun updateTargetVolume(volume: Float) {
        // フェードを中断せず現在音量だけ更新してコールバックで通知する
        currentVolume = volume.coerceIn(0f, 1f)
        onVolumeChanged?.invoke(currentVolume)
    }

    /**
     * 現在の音量を取得するメソッド
     */
    fun getCurrentVolume(): Float = currentVolume

    /**
     * 進行中のフェード処理をすべてキャンセルするメソッド
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
