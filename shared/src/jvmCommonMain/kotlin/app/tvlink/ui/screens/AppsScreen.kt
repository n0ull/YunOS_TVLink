package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tvlink.device.RpmService
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.theme.TvColors

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
fun AppsScreen(vm: AppViewModel) {
    var installUrl by remember { mutableStateOf("") }
    var installPkg by remember { mutableStateOf("") }
    var showInstall by remember { mutableStateOf(false) }
    var uninstallTarget by remember { mutableStateOf<RpmService.TvApp?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.navBack() }) { Text("‹ 返回") }
            Spacer(Modifier.weight(1f))
            Text("应用管理", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.refreshApps() }) { Text("刷新") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("共 ${vm.tvApps.size} 个应用", color = TvColors.TextSecondary)
            TextButton(onClick = { showInstall = true }) { Text("按 URL 安装") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.tvApps, key = { it.packageName }) { app ->
                Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.appName.ifEmpty { app.packageName }, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${app.packageName}  ${app.versionName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TvColors.TextSecondary,
                            )
                        }
                        TextButton(onClick = { vm.rpm.openApp(app.packageName) }) { Text("打开") }
                        TextButton(onClick = { uninstallTarget = app }) { Text("卸载", color = TvColors.Red) }
                    }
                }
            }
        }
    }

    if (showInstall) {
        AlertDialog(
            onDismissRequest = { showInstall = false },
            title = { Text("按 URL 安装应用") },
            text = {
                Column {
                    OutlinedTextField(
                        value = installPkg,
                        onValueChange = { installPkg = it },
                        label = { Text("包名") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = installUrl,
                        onValueChange = { installUrl = it },
                        label = { Text("APK 下载地址 (http/https)") },
                        singleLine = true,
                    )
                    Text(
                        "电视将自行下载并安装该 APK",
                        style = MaterialTheme.typography.bodySmall,
                        color = TvColors.TextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (installPkg.isNotBlank() && installUrl.isNotBlank()) {
                            vm.rpm.installByUrl(installPkg.trim(), installUrl.trim())
                        }
                        showInstall = false
                    },
                ) { Text("安装") }
            },
            dismissButton = { TextButton(onClick = { showInstall = false }) { Text("取消") } },
        )
    }

    uninstallTarget?.let { app ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("卸载应用") },
            text = { Text("确定从电视上卸载 ${app.appName.ifEmpty { app.packageName }} 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rpm.uninstall(app.packageName)
                        uninstallTarget = null
                    },
                ) { Text("卸载", color = TvColors.Red) }
            },
            dismissButton = { TextButton(onClick = { uninstallTarget = null }) { Text("取消") } },
        )
    }
}
