package com.doubleplayer.app

// Hiltの依存注入を有効にするアノテーション
import dagger.hilt.android.HiltAndroidApp
// Androidアプリケーションの基底クラス
import android.app.Application

/**
 * DoublePlayerアプリケーションクラス
 * Hiltの依存注入を有効にするため @HiltAndroidApp を付与する
 * AndroidManifest.xml の android:name にこのクラスを指定する
 */
@HiltAndroidApp
class DoublePlayerApplication : Application() {

    override fun onCreate() {
        // 親クラスのonCreateを呼び出す
        super.onCreate()
        // アプリ起動時の初期化処理（必要に応じて追加）
    }
}
