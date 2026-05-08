package com.doubleplayer.app

// Hiltの依存注入を有効にするアノテーション
import dagger.hilt.android.HiltAndroidApp
// Androidアプリケーションの基底クラス
import android.app.Application
// Hiltからの注入
import javax.inject.Inject
// デバッグログのインポート
import com.doubleplayer.app.debug.DebugLogger

/**
 * DoublePlayerアプリケーションクラス
 * Hiltの依存注入を有効にするため @HiltAndroidApp を付与する
 * AndroidManifest.xml の android:name にこのクラスを指定する
 */
@HiltAndroidApp
class DoublePlayerApplication : Application() {

    // DebugLoggerをHiltで注入する（Applicationクラスへの注入はHiltが対応）
    @Inject
    lateinit var debugLogger: DebugLogger

    override fun onCreate() {
        // 親クラスのonCreateを呼び出す
        super.onCreate()
        // ★ アプリ起動ログを記録する
        debugLogger.log(DebugLogger.Category.SERVICE, "DoublePlayer アプリ起動")
        // ★ UncaughtExceptionHandlerを設定してクラッシュ時に自動でログを保存する
        setupCrashHandler()
    }

    /**
     * クラッシュ時の自動ログ保存をセットアップするプライベートメソッド
     * 既存の UncaughtExceptionHandler（Android標準）を内部で呼び出してクラッシュを伝播させる
     */
    private fun setupCrashHandler() {
        // 既存のデフォルトハンドラーを保持しておく（標準のクラッシュダイアログを維持するため）
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // ★ クラッシュ直前にDebugLoggerに記録してファイルに保存する
            debugLogger.onUncaughtException(thread, throwable)
            // デフォルトハンドラーにも渡してOSのクラッシュ処理を続ける
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

