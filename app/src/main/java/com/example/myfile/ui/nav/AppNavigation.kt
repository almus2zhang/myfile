package com.example.myfile.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

object Routes {
    const val LOCAL = "local"
    const val WEBDAV = "webdav"
    const val TRANSFER = "transfer"
    const val SETTINGS = "settings"
}

data class BottomTab(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val tabs = listOf(
        BottomTab(Routes.LOCAL, "本地", Icons.Filled.PhoneAndroid),
        BottomTab(Routes.WEBDAV, "WebDAV", Icons.Filled.Cloud),
        BottomTab(Routes.TRANSFER, "传输", Icons.Filled.SwapVert),
        BottomTab(Routes.SETTINGS, "设置", Icons.Filled.Settings)
    )
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination

    androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.weight(1f)
        ) {
            NavHost(navController, startDestination = Routes.LOCAL) {
                composable(Routes.LOCAL) {
                    com.example.myfile.ui.local.LocalScreen()
                }
                composable(Routes.WEBDAV) {
                    com.example.myfile.ui.webdav.WebDavScreen()
                }
                composable(Routes.TRANSFER) {
                    com.example.myfile.ui.transfer.TransferScreen()
                }
                composable(Routes.SETTINGS) {
                    com.example.myfile.ui.settings.SettingsScreen()
                }
            }
        }
        NavigationBar {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = current?.hierarchy?.any { it.route == tab.route } == true,
                    onClick = {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) }
                )
            }
        }
    }
}
