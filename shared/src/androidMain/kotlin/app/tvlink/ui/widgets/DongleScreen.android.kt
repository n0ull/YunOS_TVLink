package app.tvlink.ui.widgets

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tvlink.dongle.DongleBlePairer
import app.tvlink.dongle.currentSsid
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.icons.AppIcons

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase；expect/actual 及各调用点均依赖此名
// BLUETOOTH_CONNECT/SCAN 已在进入本页时经 permLauncher 请求，设备列表仅在授权后扫描填充，d.name 调用安全
@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun DongleScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val pairer = remember { DongleBlePairer(context) }
    var phase by remember { mutableStateOf(DongleBlePairer.Phase.IDLE) }
    var phaseMsg by remember { mutableStateOf("") }
    val devices = remember { mutableStateListOf<BluetoothDevice>() }
    var ssid by remember { mutableStateOf(currentSsid(context)) }
    var password by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<BluetoothDevice?>(null) }

    val permissions =
        remember {
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    val permLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            if (grants.values.all { it }) pairer.startScan()
        }

    DisposableEffect(Unit) {
        pairer.onPhase = { p, m ->
            phase = p
            phaseMsg = m
        }
        pairer.onFound = { d ->
            if (devices.none { it.address == d.address }) devices.add(d)
        }
        permLauncher.launch(permissions)
        onDispose { pairer.close() }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("魔投配网") },
            navigationIcon = {
                IconButton(onClick = { vm.navBack() }) {
                    Icon(AppIcons.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        devices.clear()
                        pairer.startScan()
                    },
                ) { Icon(AppIcons.Refresh, contentDescription = "扫描") }
            },
        )

        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                phaseMsg,
                color =
                    when (phase) {
                        DongleBlePairer.Phase.FAILED -> MaterialTheme.colorScheme.error
                        DongleBlePairer.Phase.SUCCESS -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.address }) { d ->
                    val isSelected = selected?.address == d.address
                    ElevatedCard(Modifier.fillMaxWidth().clickable { selected = d }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(Modifier.padding(6.dp))
                            Column {
                                Text(d.name ?: "未知设备", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    d.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("Wi-Fi 名称 (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Wi-Fi 密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { selected?.let { pairer.pair(it, ssid.trim(), password) } },
                enabled = selected != null && ssid.isNotBlank() && phase != DongleBlePairer.Phase.WRITING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (phase == DongleBlePairer.Phase.WRITING) "配网中…" else "开始配网")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
