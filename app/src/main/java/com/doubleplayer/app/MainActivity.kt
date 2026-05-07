package com.doubleplayer.app

// Android基本のインポート
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
// ActivityのインポートとCompose連携
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
// ナビゲーションのインポート
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
// Hiltのインポート
import dagger.hilt.android.AndroidEntryPoint
// 自アプリのインポート
import com.doubleplayer.app.ui.PlayerScreen
import com.doubleplayer.app.ui.SettingsScreen
import com.doubleplayer.app.ui.HistoryScreen
import com.doubleplayer.app.ui.theme.DoublePlayerTheme

/**
 * MainActivity - アプリのエントリーポイント
 *
 * 仕様書セクション2「画面構成」に基づいて実装する。
 * Jetpack Composeのナビゲーションで以下の画面を管理する：
 * - プレイヤー画面（PlayerScreen）
 * - 設定画面（SettingsScreen）
 * - 再生履歴画面（HistoryScreen）
 *
 * 起動時に必要なパーミッションのリクエストも行う。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // ========== ナビゲーションの画面定義 ==========

    // BottomNavigationBarに表示するナビゲーションアイテム
    sealed class BottomNavItem(
        val route: String,     // ルートパス
        val label: String      // タブラベル
    ) {
        // プレイヤー画面
        object Player : BottomNavItem("player", "プレイヤー")
        // 設定画面
        object Settings : BottomNavItem("settings", "設定")
    }

    // ========== パーミッションリクエストランチャー ==========

    // 複数パーミッションをまとめてリクエストするランチャー
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // パーミッション結果のログ（現時点では自動的に再試行はしない）
        results.forEach { (permission, granted) ->
            android.util.Log.d("MainActivity", "パーミッション $permission: $granted")
        }
    }

    // ========== ライフサイクルメソッド ==========

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必要なパーミッションをリクエストする
        requestRequiredPermissions()
        // Composeの画面を設定する
        setContent {
            DoublePlayerTheme {
                DoublePlayerApp()
            }
        }
    }

    /**
     * requestRequiredPermissions - アプリに必要なパーミッションをリクエストする
     * 仕様書セクション11のパーミッション一覧に基づく
     */
    private fun requestRequiredPermissions() {
        // リクエストするパーミッションのリスト
        val permissionsToRequest = mutableListOf<String>()

        // 音声ファイル読み込みパーミッション（Android 13以上）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isPermissionGranted(Manifest.permission.READ_MEDIA_AUDIO)) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            // Android 12以下は READ_EXTERNAL_STORAGE を使用する
            if (!isPermissionGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // 通知表示パーミッション（Android 13以上）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Bluetooth接続確認パーミッション（Android 12以上）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!isPermissionGranted(Manifest.permission.BLUETOOTH_CONNECT)) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // パーミッションリクエストを実行する（リストが空でなければ）
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    /**
     * isPermissionGranted - パーミッションが付与されているか確認するヘルパー関数
     * @param permission 確認するパーミッション名
     * @return true = 付与済み, false = 未付与
     */
    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/**
 * DoublePlayerApp - アプリ全体のナビゲーション構成
 * BottomNavigationBarと画面のNavHostを組み合わせる
 */
@Composable
fun DoublePlayerApp() {
    // NavControllerを作成する
    val navController = rememberNavController()

    // 現在のバックスタックエントリを監視する
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // BottomNavigationBarを表示する画面のルートリスト（履歴画面では非表示）
    val bottomNavRoutes = listOf("player", "settings")

    // BottomNavigationBarを表示するかどうか
    val showBottomBar = currentDestination?.route in bottomNavRoutes

    Scaffold(
        bottomBar = {
            // BottomNavigationBarはプレイヤー画面と設定画面のみ表示する
            if (showBottomBar) {
                NavigationBar {
                    // プレイヤータブ
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any {
                            it.route == MainActivity.BottomNavItem.Player.route
                        } == true,
                        onClick = {
                            navController.navigate(MainActivity.BottomNavItem.Player.route) {
                                // バックスタックを溜めずに画面を切り替える
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "プレイヤー"
                            )
                        },
                        label = { Text(MainActivity.BottomNavItem.Player.label) }
                    )
                    // 設定タブ
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any {
                            it.route == MainActivity.BottomNavItem.Settings.route
                        } == true,
                        onClick = {
                            navController.navigate(MainActivity.BottomNavItem.Settings.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "設定"
                            )
                        },
                        label = { Text(MainActivity.BottomNavItem.Settings.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        // NavHost - 各画面のルーティングを定義する
        NavHost(
            navController = navController,
            startDestination = MainActivity.BottomNavItem.Player.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // プレイヤー画面
            composable(MainActivity.BottomNavItem.Player.route) {
                PlayerScreen(navController = navController)
            }
            // 設定画面
            composable(MainActivity.BottomNavItem.Settings.route) {
                SettingsScreen()
            }
            // 再生履歴画面（BottomNavBarなし）
            composable("history") {
                HistoryScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
