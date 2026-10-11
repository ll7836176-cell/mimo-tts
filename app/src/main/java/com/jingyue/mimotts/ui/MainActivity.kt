package com.jingyue.mimotts.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jingyue.mimotts.MiMoKeepAliveService
import com.jingyue.mimotts.R
import com.jingyue.mimotts.data.PreferencesStore
import com.jingyue.mimotts.data.SecretStore
import com.jingyue.mimotts.data.SettingsSavePolicy
import com.jingyue.mimotts.provider.mimo.MiMoModelIds
import com.jingyue.mimotts.system.PowerOptimizationHelper
import kotlinx.coroutines.launch

private sealed interface SaveOutcome {
    data class Success(
        val apiKeyConfigured: Boolean,
        val usedSoftwareFallback: Boolean,
    ) : SaveOutcome
    data class Failure(val message: String) : SaveOutcome
}

class MainActivity : ComponentActivity() {
    private lateinit var preferences: PreferencesStore
    private lateinit var secrets: SecretStore
    private val batteryOptimizationIgnored = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = PreferencesStore(this)
        secrets = SecretStore(this)
        startMiMoKeepAliveService()
        batteryOptimizationIgnored.value =
            PowerOptimizationHelper.isIgnoringBatteryOptimizations(this)

        val readableApiKey = secrets.getApiKey()
        val hasStoredApiKey = secrets.hasStoredApiKey()
        setContent {
            MaterialTheme {
                MiMoDashboard(
                    initialEndpoint = preferences.endpoint,
                    initialModel = preferences.modelId,
                    initialApiKeyConfigured = readableApiKey != null,
                    initialApiKeyNeedsRecovery = hasStoredApiKey && readableApiKey == null,
                    initialBatteryOptimizationIgnored = batteryOptimizationIgnored.value,
                    onSave = ::saveConfiguration,
                    onClearApiKey = { secrets.clear() },
                    onReadApiKey = { secrets.getApiKey() },
                    onOpenTtsSettings = {
                        startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                    },
                    onOpenBatterySettings = ::openBatterySettings,
                )
            }
        }
    }

    private fun startMiMoKeepAliveService() {
        runCatching {
            startForegroundService(Intent(this, MiMoKeepAliveService::class.java))
        }.onFailure { error ->
            // A failed promotion must not prevent the settings screen from opening.
            android.util.Log.e("MainActivity", "Unable to start MiMo keep-alive service", error)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::secrets.isInitialized) {
            batteryOptimizationIgnored.value =
                PowerOptimizationHelper.isIgnoringBatteryOptimizations(this)
        }
    }

    private fun openBatterySettings(): Boolean {
        return runCatching {
            startActivity(PowerOptimizationHelper.createRequestIntent(this))
        }.isSuccess || runCatching {
            startActivity(PowerOptimizationHelper.createSettingsListIntent())
        }.isSuccess
    }

    private fun saveConfiguration(
        endpoint: String,
        model: String,
        apiKeyInput: String,
    ): SaveOutcome = try {
        val normalizedModel = model.trim().ifBlank { PreferencesStore.DEFAULT_MODEL }
        check(normalizedModel == MiMoModelIds.BUILT_IN) { "model_not_supported" }
        check(preferences.save(endpoint, model)) { "配置没有成功写入，请重试" }
        val existingApiKey = secrets.getApiKey()
        val resolvedApiKey = SettingsSavePolicy.resolveApiKey(apiKeyInput, existingApiKey)
        if (resolvedApiKey != existingApiKey && !resolvedApiKey.isNullOrBlank()) {
            check(secrets.putApiKey(resolvedApiKey)) { "API Key 没有成功写入，请重试" }
        }
        val storedApiKey = secrets.getApiKey()
        check(storedApiKey == resolvedApiKey) {
            if (resolvedApiKey == null) {
                "API Key 读取失败，请重新输入并保存"
            } else {
                "API Key 没有成功写入，请重试"
            }
        }
        SaveOutcome.Success(
            apiKeyConfigured = storedApiKey != null,
            usedSoftwareFallback = secrets.isUsingSoftwareFallback(),
        )
    } catch (error: IllegalArgumentException) {
        val message = if (error.message == "endpoint_must_use_https") {
            "接口地址必须使用 HTTPS"
        } else if (error.message == "model_not_supported") {
            "当前 Android 合成入口仅支持模型：${MiMoModelIds.BUILT_IN}"
        } else {
            "输入内容无效，请检查后重试"
        }
        SaveOutcome.Failure(message)
    } catch (_: Throwable) {
        SaveOutcome.Failure("保存失败，请重试")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiMoDashboard(
    initialEndpoint: String,
    initialModel: String,
    initialApiKeyConfigured: Boolean,
    initialApiKeyNeedsRecovery: Boolean,
    initialBatteryOptimizationIgnored: Boolean,
    onSave: (String, String, String) -> SaveOutcome,
    onClearApiKey: () -> Boolean,
    onReadApiKey: () -> String?,
    onOpenTtsSettings: () -> Unit,
    onOpenBatterySettings: () -> Boolean,
) {
    var endpoint by remember(initialEndpoint) { mutableStateOf(initialEndpoint) }
    var model by remember(initialModel) { mutableStateOf(initialModel) }
    // The real secret is deliberately not copied into Compose state. An empty field means keep.
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeyConfigured by remember { mutableStateOf(initialApiKeyConfigured) }
    var apiKeyNeedsRecovery by remember { mutableStateOf(initialApiKeyNeedsRecovery) }
    var showApiKey by remember { mutableStateOf(false) }
    var batteryPromptDismissed by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val endpointInvalid = endpoint.isNotBlank() && !endpoint.trim().startsWith("https://", ignoreCase = true)

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun toggleApiKeyVisibility() {
        if (showApiKey) {
            showApiKey = false
            return
        }

        if (apiKeyInput.isBlank()) {
            val storedApiKey = onReadApiKey()
            if (storedApiKey.isNullOrBlank()) {
                notify(if (apiKeyConfigured) "API Key 读取失败，请重新输入并保存" else "请先输入 API Key")
                return
            }
            apiKeyInput = storedApiKey
        }
        showApiKey = true
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MiMo TTS") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("小米 MiMo 语音合成", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "配置一次即可在系统 TTS 和阅读器中使用。设置会自动保存在本机。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("服务配置", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "填写 MiMo 接口和模型信息。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = endpoint,
                            onValueChange = { endpoint = it },
                            label = { Text("接口地址") },
                            placeholder = { Text("https://api.xiaomimimo.com/v1/chat/completions") },
                            supportingText = {
                                Text(if (endpointInvalid) "必须使用 HTTPS 地址" else "默认使用小米 MiMo 官方接口")
                            },
                            isError = endpointInvalid,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text("模型 ID") },
                            supportingText = { Text("当前 Android 入口支持：${MiMoModelIds.BUILT_IN}") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("访问凭证", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                apiKeyNeedsRecovery -> "检测到旧的 API Key，但当前系统无法解密。请重新输入并保存一次。"
                                apiKeyConfigured -> "API Key 已保存。留空并保存时会保留当前密钥。"
                                else -> "尚未配置 API Key，保存后才能开始合成。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (apiKeyNeedsRecovery) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("API Key") },
                            placeholder = {
                                Text(if (apiKeyConfigured) "已保存，留空表示不修改" else "请输入 API Key")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (showApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                TextButton(onClick = ::toggleApiKeyVisibility) {
                                    Text(if (showApiKey) "隐藏" else "显示")
                                }
                            },
                        )
                        if (apiKeyConfigured || apiKeyNeedsRecovery) {
                        TextButton(
                            onClick = {
                                if (onClearApiKey()) {
                                    apiKeyConfigured = false
                                    apiKeyNeedsRecovery = false
                                    apiKeyInput = ""
                                    showApiKey = false
                                    notify("API Key 已清除")
                                } else {
                                    notify("清除失败，请重试")
                                    }
                                },
                            ) {
                                Text("清除已保存的 API Key")
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("后台运行", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (initialBatteryOptimizationIgnored) {
                                "已允许 MiMo TTS 在后台运行，切换到阅读器后也能保持服务连接。"
                            } else {
                                "当前仍受系统电池管理限制，可能导致你打开阅读器后 MiMo TTS 被结束。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (initialBatteryOptimizationIgnored) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        StatusRow(
                            "后台权限",
                            if (initialBatteryOptimizationIgnored) "已允许" else "需要设置",
                        )
                        if (!initialBatteryOptimizationIgnored) {
                            OutlinedButton(
                                onClick = {
                                    batteryPromptDismissed = true
                                    if (!onOpenBatterySettings()) {
                                        notify("无法打开系统设置，请手动将 MiMo TTS 设置为允许后台运行")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("去设置后台运行权限")
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("当前状态", style = MaterialTheme.typography.titleMedium)
                        StatusRow("接口地址", if (endpointInvalid) "待修正" else "可用格式")
                        HorizontalDivider()
                        StatusRow("API Key", if (apiKeyConfigured) "已保存" else "未配置")
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            when (val result = onSave(endpoint, model, apiKeyInput)) {
                                is SaveOutcome.Success -> {
                                    apiKeyConfigured = result.apiKeyConfigured
                                    apiKeyNeedsRecovery = apiKeyNeedsRecovery && !result.apiKeyConfigured
                                    apiKeyInput = ""
                                    showApiKey = false
                                    notify(
                                        if (result.usedSoftwareFallback) {
                                            "保存成功（当前设备使用兼容加密存储），下次打开会自动恢复设置"
                                        } else {
                                            "保存成功，下次打开会自动恢复设置"
                                        },
                                    )
                                }
                                is SaveOutcome.Failure -> notify(result.message)
                            }
                        },
                        enabled = endpoint.isNotBlank() && !endpointInvalid,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("保存设置")
                    }
                    OutlinedButton(
                        onClick = onOpenTtsSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("打开系统 TTS 设置")
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("使用步骤", style = MaterialTheme.typography.titleMedium)
                        Text("1. 填写接口地址、模型 ID 和 API Key。", style = MaterialTheme.typography.bodyMedium)
                        Text("2. 点击“保存设置”，看到保存成功提示。", style = MaterialTheme.typography.bodyMedium)
                        Text("3. 在系统 TTS 设置中选择 MiMo TTS。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "API Key 优先使用 Android Keystore 加密；若设备不兼容，将使用随机软件密钥在本机应用私有空间加密保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (!initialBatteryOptimizationIgnored && !batteryPromptDismissed) {
            BatteryOptimizationDialog(
                onOpenSettings = {
                    batteryPromptDismissed = true
                    if (!onOpenBatterySettings()) {
                        notify("无法打开系统设置，请手动将 MiMo TTS 设置为允许后台运行")
                    }
                },
                onDismiss = {
                    batteryPromptDismissed = true
                    notify("已跳过后台权限设置，后台朗读可能会被系统中断")
                },
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
