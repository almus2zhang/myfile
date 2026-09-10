package com.example.myfile.ui.components

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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
    var biometricHint by remember { mutableStateOf<String?>(null) }

    // androidx.biometric 需要 FragmentActivity 才能显示系统生物识别弹窗
    val activity = context as? FragmentActivity

    /** 检查设备是否具备可用的生物识别能力 */
    fun biometricStatus(): Int {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    }

    fun triggerBiometric() {
        val act = activity ?: run {
            biometricHint = "无法启动生物识别（需要 Activity 上下文）"
            return
        }
        when (biometricStatus()) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                // 设备支持，正常拉起
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                biometricHint = "此设备不支持指纹/生物识别"
                return
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                biometricHint = "生物识别硬件暂不可用"
                return
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                biometricHint = "请先在系统设置中录入指纹或锁屏密码"
                return
            }
            else -> {
                biometricHint = "生物识别不可用"
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(act)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onUnlockSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                biometricHint = errString.toString()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                biometricHint = "验证失败，请重试"
            }
        }

        val prompt = BiometricPrompt(act, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁配置")
            .setSubtitle("验证指纹以访问「${account.name}」")
            .setNegativeButtonText("使用密码")
            .build()

        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            biometricHint = "启动指纹验证失败: ${e.message}"
        }
    }

    // 进入对话框时自动拉起一次指纹识别（若设备支持且已录入）
    LaunchedEffect(Unit) {
        if (activity != null && biometricStatus() == BiometricManager.BIOMETRIC_SUCCESS) {
            triggerBiometric()
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

                // 生物识别状态提示
                biometricHint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (biometricStatus() != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
                    OutlinedButton(
                        onClick = { triggerBiometric() },
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
