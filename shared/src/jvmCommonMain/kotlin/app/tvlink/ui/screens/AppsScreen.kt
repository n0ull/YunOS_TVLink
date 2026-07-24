// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tvlink.device.RpmService
import app.tvlink.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: AppViewModel) {
    var installUrl by remember { mutableStateOf("") }
    var installPkg by remember { mutableStateOf("") }
    var showInstall by remember { mutableStateOf(false) }
    var uninstallTarget by remember { mutableStateOf<RpmService.TvApp?>(null) }

    // 进屏即拉列表:module 未就绪时该请求同时触发 R2 唤醒,模块上线后经挂起补发回填
    LaunchedEffect(Unit) { vm.refreshApps() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("应用管理") },
            navigationIcon = {
                IconButton(onClick = { vm.navBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = { vm.refreshApps() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = { showInstall = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "按 URL 安装")
                }
            },
        )

        Text(
            "共 ${vm.tvApps.size} 个应用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (vm.tvApps.isEmpty()) {
            Text(
                "列表为空——电视的应用管理模块(com.yunos.idc.appstore)未就绪；部分固件不提供该模块。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        LazyColumn(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.tvApps, key = { it.packageName }) { app ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(app.appName.ifEmpty { app.packageName })
                        },
                        supportingContent = {
                            Text("${app.packageName}  ${app.versionName}")
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { vm.rpm.openApp(app.packageName) }) {
                                    Icon(Icons.Filled.OpenInNew, contentDescription = "打开")
                                }
                                IconButton(onClick = { uninstallTarget = app }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "卸载",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showInstall) {
        InstallDialog(
            pkg = installPkg,
            url = installUrl,
            onPkgChange = { installPkg = it },
            onUrlChange = { installUrl = it },
            onInstall = {
                if (installPkg.isNotBlank() && installUrl.isNotBlank()) {
                    vm.rpm.installByUrl(installPkg.trim(), installUrl.trim())
                }
                showInstall = false
            },
            onDismiss = { showInstall = false },
        )
    }

    uninstallTarget?.let { app ->
        UninstallDialog(
            app = app,
            onConfirm = {
                vm.rpm.uninstall(app.packageName)
                uninstallTarget = null
            },
            onDismiss = { uninstallTarget = null },
        )
    }
}

@Composable
private fun InstallDialog(
    pkg: String,
    url: String,
    onPkgChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按 URL 安装应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = pkg,
                    onValueChange = onPkgChange,
                    label = { Text("包名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text("APK 下载地址 (http/https)") },
                    singleLine = true,
                )
                Text(
                    "电视将自行下载并安装该 APK",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onInstall) { Text("安装") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun UninstallDialog(
    app: RpmService.TvApp,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("卸载应用") },
        text = { Text("确定从电视上卸载 ${app.appName.ifEmpty { app.packageName }} 吗？") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("卸载", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
