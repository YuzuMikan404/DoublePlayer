# DoublePlayer ProGuard / R8 難読化設定
# リリースビルド時にコードを最適化・難読化するためのルール

# ========== Hilt（依存注入）==========
# Hiltが生成するクラスを難読化対象から除外する
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keepclassmembers class * {
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <init>(...);
}

# ========== Kotlin ==========
# Kotlin関連のクラスを保持する
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class ** {
    kotlinx.coroutines.flow.Flow *;
}

# ========== Gson（JSON処理）==========
# GsonはリフレクションでJSONを処理するため、データクラスを保持する
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# 設定JSONに対応するデータクラスを保持する
-keep class com.doubleplayer.app.storage.PlayHistoryStore$PlayHistoryEntry { *; }
-keepclassmembers class ** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ========== AndroidX Media3（ExoPlayer）==========
# Media3のクラスを保持する
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }

# ========== DataStore ==========
# DataStore関連のクラスを保持する
-keep class androidx.datastore.** { *; }

# ========== Glance（ウィジェット）==========
# GlanceのActionCallbackを保持する（リフレクションで呼ばれる）
-keep class androidx.glance.** { *; }
-keep class * extends androidx.glance.appwidget.action.ActionCallback { *; }
# ウィジェット関連クラスを保持する
-keep class com.doubleplayer.app.widget.** { *; }

# ========== BroadcastReceiver ==========
# BroadcastReceiverはAndroidManifestから参照されるため保持する
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class com.doubleplayer.app.bluetooth.BluetoothReceiver { *; }

# ========== Service ==========
# ServiceはAndroidManifestから参照されるため保持する
-keep class * extends android.app.Service { *; }
-keep class com.doubleplayer.app.player.PlayerService { *; }
-keep class com.doubleplayer.app.player.PlayerService$PlayerBinder { *; }

# ========== ViewModel ==========
# ViewModelはHiltで管理されるため保持する
-keep class * extends androidx.lifecycle.ViewModel { *; }

# ========== スタックトレース（クラッシュレポート用）==========
# デバッグ時にスタックトレースを読みやすくするためにメソッド名を保持する
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
