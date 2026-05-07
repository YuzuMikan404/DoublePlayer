package com.doubleplayer.app.ui.theme

// Material3のカラースキーム関連のインポート
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
// システムのダークモード設定を取得するためのインポート
import androidx.compose.foundation.isSystemInDarkTheme
// Composable関連のインポート
import androidx.compose.runtime.Composable
// Android 12以上の判定
import android.os.Build
import androidx.compose.ui.platform.LocalContext

/**
 * ライトテーマのカラースキーム定義
 */
private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    secondary = Secondary,
    secondaryContainer = SecondaryContainer,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onBackground = OnBackground,
    onSurface = OnSurface,
    error = Error,
    errorContainer = ErrorContainer
)

/**
 * ダークテーマのカラースキーム定義
 */
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    secondary = SecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = OnBackgroundDark,
    onSurface = OnSurfaceDark,
    error = Error,
    errorContainer = ErrorContainer
)

/**
 * DoublePlayerアプリのテーマ設定
 * システムのダークモード設定に自動的に追従する
 *
 * @param darkTheme ダークモードを強制する場合はtrue（デフォルトはシステム設定）
 * @param dynamicColor Android 12以上のダイナミックカラーを使用するかどうか
 * @param content テーマを適用するComposableコンテンツ
 */
@Composable
fun DoublePlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // システムのダークモード設定に従う
    dynamicColor: Boolean = false,              // ダイナミックカラーはデフォルト無効
    content: @Composable () -> Unit
) {
    // カラースキームを選択する
    val colorScheme = when {
        // Android 12以上かつダイナミックカラーが有効な場合
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // ダークモードの場合
        darkTheme -> DarkColorScheme
        // ライトモードの場合（デフォルト）
        else -> LightColorScheme
    }

    // Material3テーマを適用する
    MaterialTheme(
        colorScheme = colorScheme,  // カラースキームを設定
        typography = AppTypography, // タイポグラフィを設定
        content = content           // コンテンツを適用
    )
}
