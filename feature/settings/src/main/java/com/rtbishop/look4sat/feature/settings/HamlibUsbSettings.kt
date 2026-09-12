package com.rtbishop.look4sat.feature.settings

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.presentation.R
import kotlinx.coroutines.delay

@Composable
internal fun HamlibUsbSettings(
    settings: RadioControlSettings,
    models: List<Pair<Int, String>>,
    onChange: (RadioControlSettings) -> Unit
) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(UsbManager::class.java) }
    var devices by remember { mutableStateOf(manager.deviceList.values.toList()) }
    var permitted by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var choosingModel by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    // Enumerating USB devices is cheap; poll only while the settings sheet is composed.
    LaunchedEffect(settings.usbDeviceName) {
        while (true) {
            devices = manager.deviceList.values.toList()
            permitted = devices.find { it.deviceName == settings.usbDeviceName }?.let(manager::hasPermission) == true
            delay(1000)
        }
    }
    Text(stringResource(R.string.usb_help))
    if (models.isEmpty()) Text(stringResource(R.string.usb_native_missing), color = MaterialTheme.colorScheme.error)
    Text(stringResource(R.string.usb_step_model), style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = { choosingModel = true }, enabled = models.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(models.find { it.first == settings.usbModelId }?.second ?: stringResource(R.string.usb_choose_model))
    }
    if (choosingModel) AlertDialog(
        onDismissRequest = { choosingModel = false },
        title = { Text(stringResource(R.string.usb_choose_model)) },
        confirmButton = { TextButton(onClick = { choosingModel = false }) { Text(stringResource(android.R.string.cancel)) } },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                    label = { Text(stringResource(R.string.usb_model_search)) }, modifier = Modifier.fillMaxWidth())
                val filtered = models.filter { it.second.contains(search.trim(), true) }
                if (filtered.isEmpty()) Text(stringResource(R.string.usb_no_models))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.first }) { (id, label) ->
                        TextButton(onClick = {
                            onChange(settings.copy(usbModelId = id, civAddress = ""))
                            choosingModel = false
                            search = ""
                        }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
        }
    )
    Text(stringResource(R.string.usb_device))
    if (devices.isEmpty()) Text(stringResource(R.string.usb_no_device))
    devices.forEach { device ->
        val label = "${runCatching { device.productName }.getOrNull() ?: "USB"} (${device.vendorId.toString(16)}:${device.productId.toString(16)})"
        FilterChip(selected = device.deviceName == settings.usbDeviceName, label = { Text(label) },
            onClick = { onChange(settings.copy(usbDeviceName = device.deviceName)) })
    }
    OutlinedButton(enabled = devices.any { it.deviceName == settings.usbDeviceName } && !permitted,
        onClick = {
            manager.deviceList[settings.usbDeviceName]?.let { device ->
                val intent = Intent("${context.packageName}.USB_PERMISSION").setPackage(context.packageName)
                manager.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }) { Text(stringResource(if (permitted) R.string.usb_authorized else R.string.usb_authorize)) }
    Text(stringResource(R.string.usb_auto_port))
    Text(stringResource(R.string.usb_step_serial), style = MaterialTheme.typography.titleMedium)
    UsbOptions(stringResource(R.string.usb_baud), listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200), settings.usbBaud) {
        onChange(settings.copy(usbBaud = it))
    }
    TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.usb_advanced)) }
    if (advanced) {
    UsbOptions(stringResource(R.string.usb_data_bits), listOf(7, 8), settings.usbDataBits) { onChange(settings.copy(usbDataBits = it)) }
    UsbOptions(stringResource(R.string.usb_stop_bits), listOf(1, 2), settings.usbStopBits) { onChange(settings.copy(usbStopBits = it)) }
    Text(stringResource(R.string.usb_parity))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(R.string.usb_parity_none, R.string.usb_parity_odd, R.string.usb_parity_even).forEachIndexed { index, label ->
            FilterChip(selected = settings.usbParity == index, onClick = { onChange(settings.copy(usbParity = index)) },
                label = { Text(stringResource(label)) })
        }
    }
    Text(stringResource(R.string.usb_lines_warning))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DTR")
        Switch(checked = settings.usbDtr, onCheckedChange = { onChange(settings.copy(usbDtr = it)) })
        Text("RTS")
        Switch(checked = settings.usbRts, onCheckedChange = { onChange(settings.copy(usbRts = it)) })
    }
    }
    OutlinedTextField(value = settings.civAddress, onValueChange = {
        if (it.length <= 2 && it.all { c -> c.digitToIntOrNull(16) != null }) onChange(settings.copy(civAddress = it))
    }, label = { Text(stringResource(R.string.usb_civ)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun UsbOptions(label: String, values: List<Int>, selected: Int, onChange: (Int) -> Unit) {
    Text(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { value -> FilterChip(selected = selected == value,
            onClick = { onChange(value) }, label = { Text(value.toString()) }) }
    }
}
