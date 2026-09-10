package com.example.myfile.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfile.ui.webdav.WebDavViewModel

@Composable
fun AppNavigation() {
    val vm: WebDavViewModel = viewModel()
    val state by vm.state.collectAsState()
    // 默认进入本地存储；可通过顶部下拉切换到任意 WebDAV 账户
    var showLocal by remember { mutableStateOf(true) }

    if (showLocal) {
        com.example.myfile.ui.local.LocalScreen(
            onBack = { showLocal = false },
            accounts = state.accounts,
            onSwitchToWebDav = { acc ->
                vm.selectAccount(acc)
                showLocal = false
            }
        )
    } else {
        com.example.myfile.ui.webdav.WebDavScreen(
            vm = vm,
            onNavigateToLocal = { showLocal = true }
        )
    }
}
