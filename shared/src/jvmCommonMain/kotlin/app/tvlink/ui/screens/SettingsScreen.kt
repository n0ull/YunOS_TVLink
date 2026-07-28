// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TextButton
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
                    SettingItem(AppIcons.Memory, "型号", vm.connectedModel.ifEmpty { "未知" })
                    SettingItem(AppIcons.Wifi, "IP", vm.connectedIp)
                    SettingItem(AppIcons.Link, "MAC", vm.connectedMac.ifEmpty { "未获取（仅 mDNS 发现携带）" })
                    SettingItem(AppIcons.Memory, "IB 版本", vm.connectedIbVer.ifEmpty { "未探测（手动连接）" })
                    SettingItem(AppIcons.Link, "IB sid", vm.connectedIbSid.ifEmpty { "未探测（手动连接）" })
                    SettingItem(AppIcons.Wifi, "媒体服务", vm.mediaServerUrl.ifEmpty { "未启动" })
                    SettingItem(
                        AppIcons.Link,
                        "IB 快速通道",
                        if (vm.rc.ibReady.value) "已连接" else "未连接（使用回退通道）",
                    )
                    // 断开 = 结束本次会话；历史直连记录保留（原 App 语义），冷启动仍自动直连
                    FilledTonalButton(
                        onClick = { vm.disconnect() },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text("断开当前设备") }
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

            if (vm.dongleOnline) {
                DongleSettingsCard(vm)
                Spacer(Modifier.height(12.dp))
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    GroupHeader("关于")
                    SettingItem(AppIcons.Info, "TVLink 1.0", "开源的 YunOS 电视局域网控制客户端")
                    if (vm.connectedIbOnly) {
                        // IB-only 连接：无 cast 通道，展示 IB 版本供诊断
                        SettingItem(AppIcons.Link, "IB 通道", vm.connectedIbVer.ifEmpty { "未知" })
                        if (vm.connectedIbSid.isNotEmpty()) {
                            SettingItem(AppIcons.Info, "IB sid", vm.connectedIbSid)
                        }
                        SettingItem(AppIcons.Cast, "投屏", "不可用（IDC 未开放）")
                    } else {
                        // 投屏服务信息：连接后 GET /server-info 取一次（docs/re/04 §4）
                        vm.castServerInfo?.let { si ->
                            SettingItem(AppIcons.Cast, "投屏服务", si.serverVers.ifEmpty { "未知" })
                            SettingItem(AppIcons.Link, "投屏协议版本", si.protocolVers.ifEmpty { "未知" })
                            SettingItem(AppIcons.Tv, "投屏服务标识", si.displayName.ifEmpty { si.serverCode }.ifEmpty { "—" })
                            SettingItem(AppIcons.Memory, "投屏能力", si.features.ifEmpty { "—" })
                        }
                    }
                }
            }
        }
    }
}

/**
 * 魔投设置卡（仅当 dongle 设置模块在线时出现，docs/re/03 §B.3）：系统信息 + 分辨率切换
 * （列表下标即分辨率 id）+ 重启/恢复出厂（二次确认）/网络诊断/重新连网。
 */
@Composable
private fun DongleSettingsCard(vm: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val info = vm.dongleInfo
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column {
            GroupHeader("魔投设置")
            SettingItem(AppIcons.Memory, "固件版本", fieldValue(info?.firmware, "获取中…"))
            SettingItem(AppIcons.Wifi, "IP", fieldValue(info?.ip))
            SettingItem(AppIcons.Link, "MAC", fieldValue(info?.mac))
            SettingItem(AppIcons.Info, "SN", fieldValue(info?.sn))
            SettingItem(AppIcons.Tv, "UUID", fieldValue(info?.uuid))
            Box {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("分辨率") },
                    supportingContent = {
                        Text(fieldValue(info?.current, if (info == null) "获取中…" else "未知"))
                    },
                    modifier = Modifier.clickable(enabled = info != null) { expanded = true },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    info?.resolutions?.forEachIndexed { i, des ->
                        DropdownMenuItem(
                            text = { Text(des) },
                            onClick = {
                                expanded = false
                                val sent = vm.dongleSettings.adjustResolution(i)
                                vm.notice = if (sent) "已发送分辨率切换: $des" else "模块不在线"
                            },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { confirm = "reboot" }, Modifier.weight(1f)) { Text("重启") }
                TextButton(onClick = { confirm = "reset" }, Modifier.weight(1f)) { Text("恢复出厂") }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        vm.notice = if (vm.dongleSettings.netDiagnosis()) "已发送网络诊断指令" else "模块不在线"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("网络诊断") }
                TextButton(
                    onClick = {
                        vm.notice = if (vm.dongleSettings.reconnect()) "已发送重新连网指令" else "模块不在线"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("重新连网") }
            }
        }
    }
    confirm?.let { action -> DongleConfirmDialog(vm, action) { confirm = null } }
}

/** 空/未获取字段的占位显示。 */
private fun fieldValue(
    v: String?,
    fallback: String = "—",
): String = v?.takeIf { it.isNotEmpty() } ?: fallback

@Composable
private fun DongleConfirmDialog(
    vm: AppViewModel,
    action: String,
    onDismiss: () -> Unit,
) {
    val isReboot = action == "reboot"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isReboot) "重启魔投" else "恢复出厂设置") },
        text = { Text(if (isReboot) "确定重启已连接的魔投设备？" else "确定恢复出厂设置？设备配置将被清空。") },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    val sent = if (isReboot) vm.dongleSettings.reboot() else vm.dongleSettings.factoryReset()
                    vm.notice = if (sent) "指令已发送" else "模块不在线"
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
