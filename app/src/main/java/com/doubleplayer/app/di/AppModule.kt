package com.doubleplayer.app.di

// Hilt関連のインポート
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
// Androidコンテキスト
import android.content.Context
// Gson（JSON処理）
import com.google.gson.Gson
import com.google.gson.GsonBuilder
// DataStore（設定保存）
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
// シングルトンアノテーション
import javax.inject.Singleton
import javax.inject.Qualifier

// 設定用DataStoreの拡張プロパティ（シングルトン）
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// 再生状態用DataStoreの拡張プロパティ（シングルトン）
private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_state")

// 再生履歴用DataStoreの拡張プロパティ（シングルトン）
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "play_history")

// DataStoreを区別するためのQualifierアノテーション
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SettingsStore
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PlaybackStore
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class HistoryStore

/**
 * Hiltモジュール：アプリ全体で使用する依存関係を定義する
 * SingletonComponentに設置することでアプリ全体でシングルトンとして管理される
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Gsonインスタンスを提供するメソッド
     * JSONの読み書きに使用する
     */
    @Provides
    @Singleton
    fun provideGson(): Gson {
        // 日本語を含むUnicodeをそのまま出力するGsonを生成する
        return GsonBuilder()
            .disableHtmlEscaping() // HTMLエスケープを無効化（日本語が文字化けしないように）
            .setPrettyPrinting()   // 見やすいJSON形式で出力する
            .create()
    }

    /**
     * 設定用DataStoreを提供するメソッド
     * ユーザー設定の永続化に使用する
     */
    @Provides
    @Singleton
    @SettingsStore
    fun provideSettingsDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.settingsDataStore

    /**
     * 再生状態用DataStoreを提供するメソッド
     * 再生位置・当日フラグなどの永続化に使用する
     */
    @Provides
    @Singleton
    @PlaybackStore
    fun providePlaybackDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.playbackDataStore

    /**
     * 再生履歴用DataStoreを提供するメソッド
     * 再生履歴の永続化に使用する
     */
    @Provides
    @Singleton
    @HistoryStore
    fun provideHistoryDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.historyDataStore
}
