// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.icons.AppIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var propKey by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = { vm.navBack() }) {
                    Icon(AppIcons.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    GroupHeader("当前连接")
                    SettingItem(AppIcons.Tv, "设备", vm.connectedName)
                    SettingItem(AppIcons.Wifi, "IP", vm.connectedIp)
                    SettingItem(AppIcons.Memory, "IB 版本", vm.connectedIbVer.ifEmpty { "未探测（手动连接）" })
                    SettingItem(AppIcons.Link, "IB sid", vm.connectedIbSid.ifEmpty { "未探测（手动连接）" })
                    SettingItem(AppIcons.Wifi, "媒体服务", vm.mediaServerUrl.ifEmpty { "未启动" })
                    SettingItem(
                        AppIcons.Link,
                        "IB 快速通道",
                        if (vm.rc.ibReady.value) "已连接" else "未连接（使用回退通道）",
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    GroupHeader("系统属性")
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = propKey,
                            onValueChange = { propKey = it },
                            label = { Text("prop key,如 ro.product.model") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { vm.querySysProp(propKey) },
                            enabled = propKey.isNotBlank() && !vm.sysPropBusy,
                        ) {
                            Text(if (vm.sysPropBusy) "查询中…" else "查询")
                        }
                    }
                    if (vm.sysPropResult.isNotEmpty()) {
                        Text(
                            vm.sysPropResult,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                        )
                    } else {
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    GroupHeader("关于")
                    SettingItem(AppIcons.Info, "TVLink 1.0", "开源的 YunOS 电视局域网控制客户端")
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingItem(
    icon: ImageVector,
    label: String,
    value: String,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
