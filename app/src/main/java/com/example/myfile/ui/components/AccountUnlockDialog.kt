package com.example.myfile.ui.components

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myfile.model.WebDavAccount

@Composable
fun AccountUnlockDialog(
    account: WebDavAccount,
    onUnlockSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var enteredPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun triggerBiometricPrompt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val cancellationSignal = CancellationSignal()
                val prompt = android.hardware.biometrics.BiometricPrompt.Builder(context)
                    .setTitle("解锁配置")
                    .setSubtitle("验证指纹以访问「${account.name}」")
                    .setNegativeButton("使用密码", context.mainExecutor) { _, _ ->
                        cancellationSignal.cancel()
                    }
                    .build()

                prompt.authenticate(
                    cancellationSignal,
                    context.mainExecutor,
                    object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                            super.onAuthenticationSucceeded(result)
                            onUnlockSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            super.onAuthenticationError(errorCode, errString)
                            // 取消或错误
                        }
                    }
                )
            } catch (_: Exception) {
            }
        }
    }

    // 进入对话框时尝试自动拉起一次指纹识别（若系统支持）
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            triggerBiometricPrompt()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text("配置已加密", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "切换到「${account.name}」需要输入解锁密码或指纹验证",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = enteredPassword,
                    onValueChange = {
                        enteredPassword = it
                        errorMessage = null
                    },
                    label = { Text("解锁密码") },
                    singleLine = true,
                    maxLines = 1,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    OutlinedButton(
                        onClick = { triggerBiometricPrompt() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Fingerprint, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("使用指纹解锁")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetPwd = account.unlockPassword()
                    if (enteredPassword == targetPwd) {
                        onUnlockSuccess()
                    } else {
                        errorMessage = "密码错误，请重新输入"
                    }
                }
            ) {
                Text("解锁")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
