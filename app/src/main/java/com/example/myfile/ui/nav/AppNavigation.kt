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
    // 从本地存储切换到「已加密」配置时，需要先解锁
    var pendingUnlockAccount by remember { mutableStateOf<com.example.myfile.model.WebDavAccount?>(null) }
    // 编辑「已加密」配置前需要先解锁
    var pendingUnlockForEdit by remember { mutableStateOf<com.example.myfile.model.WebDavAccount?>(null) }
    // 编辑配置对话框
    var editingAccount by remember { mutableStateOf<com.example.myfile.model.WebDavAccount?>(null) }
    // 删除配置确认
    var deletingAccount by remember { mutableStateOf<com.example.myfile.model.WebDavAccount?>(null) }

    if (showLocal) {
        com.example.myfile.ui.local.LocalScreen(
            onBack = { showLocal = false },
            accounts = state.accounts,
            onSwitchToWebDav = { acc ->
                // 与 WebDavScreen 内切换账户保持一致：加密配置需先解锁
                if (acc.isEncrypted && acc.id != state.currentAccount?.id) {
                    pendingUnlockAccount = acc
                } else {
                    vm.selectAccount(acc)
                    showLocal = false
                }
            },
            onEditWebDav = { acc ->
                // 加密配置需先解锁才能编辑
                if (acc.isEncrypted) {
                    pendingUnlockForEdit = acc
                } else {
                    editingAccount = acc
                }
            },
            onDeleteWebDav = { acc -> deletingAccount = acc }
        )
    } else {
        com.example.myfile.ui.webdav.WebDavScreen(
            vm = vm,
            onNavigateToLocal = { showLocal = true }
        )
    }

    // 切换到加密配置前的解锁弹窗（密码 / 指纹）
    pendingUnlockAccount?.let { accToUnlock ->
        com.example.myfile.ui.components.AccountUnlockDialog(
            account = accToUnlock,
            onUnlockSuccess = {
                pendingUnlockAccount = null
                vm.selectAccount(accToUnlock)
                showLocal = false
            },
            onDismiss = { pendingUnlockAccount = null }
        )
    }

    // 编辑加密配置前的解锁弹窗
    pendingUnlockForEdit?.let { accToUnlock ->
        com.example.myfile.ui.components.AccountUnlockDialog(
            account = accToUnlock,
            onUnlockSuccess = {
                pendingUnlockForEdit = null
                editingAccount = accToUnlock
            },
            onDismiss = { pendingUnlockForEdit = null }
        )
    }

    // 编辑配置对话框
    editingAccount?.let { acc ->
        com.example.myfile.ui.webdav.WebDavAccountDialog(
            initial = acc,
            onDismiss = { editingAccount = null },
            onSave = { edited ->
                vm.saveAccount(edited)
                editingAccount = null
            }
        )
    }

    // 删除配置确认对话框
    deletingAccount?.let { acc ->
        com.example.myfile.ui.components.DeleteConfirmDialog(
            title = "删除配置",
            message = "确定要删除配置「${acc.name}」吗？此操作不会删除远端服务器上的文件。",
            onDismiss = { deletingAccount = null },
            onConfirm = {
                vm.deleteAccount(acc)
                deletingAccount = null
            }
        )
    }
}
