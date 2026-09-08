package com.rtbishop.look4sat.feature.settings

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbManager
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
    Text("${settings.usbModelId}: ${models.find { it.first == settings.usbModelId }?.second ?: "IC-910"}")
    OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
        label = { Text(stringResource(R.string.usb_model_search)) }, modifier = Modifier.fillMaxWidth())
    if (search.isNotBlank()) {
        models.filter { it.second.contains(search, true) || it.first.toString() == search }.take(15).forEach { (id, label) ->
            FilterChip(selected = settings.usbModelId == id, label = { Text("$id $label") },
                onClick = { onChange(settings.copy(usbModelId = id, civAddress = "")); search = "" })
        }
    }
    Text(stringResource(R.string.usb_device))
    if (devices.isEmpty()) Text(stringResource(R.string.usb_no_device))
    devices.forEach { device ->
        val label = "${device.productName ?: "USB"} ${device.vendorId.toString(16)}:${device.productId.toString(16)} ${device.deviceName}"
        FilterChip(selected = device.deviceName == settings.usbDeviceName, label = { Text(label) },
            onClick = { onChange(settings.copy(usbDeviceName = device.deviceName, usbPort = 0)) })
    }
    OutlinedButton(enabled = devices.any { it.deviceName == settings.usbDeviceName } && !permitted,
        onClick = {
            manager.deviceList[settings.usbDeviceName]?.let { device ->
                val intent = Intent("${context.packageName}.USB_PERMISSION").setPackage(context.packageName)
                manager.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }) { Text(stringResource(if (permitted) R.string.usb_authorized else R.string.usb_authorize)) }
    UsbNumberField(stringResource(R.string.usb_port), settings.usbPort) { if (it in 0..15) onChange(settings.copy(usbPort = it)) }
    UsbNumberField(stringResource(R.string.usb_baud), settings.usbBaud) { if (it in 1..921600) onChange(settings.copy(usbBaud = it)) }
    UsbOptions("Data bits", listOf(7, 8), settings.usbDataBits) { onChange(settings.copy(usbDataBits = it)) }
    UsbOptions("Stop bits", listOf(1, 2), settings.usbStopBits) { onChange(settings.copy(usbStopBits = it)) }
    Text("Parity: 0=None, 1=Odd, 2=Even")
    UsbOptions("Parity", listOf(0, 1, 2), settings.usbParity) { onChange(settings.copy(usbParity = it)) }
    Text(stringResource(R.string.usb_lines_warning))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DTR")
        Switch(checked = settings.usbDtr, onCheckedChange = { onChange(settings.copy(usbDtr = it)) })
        Text("RTS")
        Switch(checked = settings.usbRts, onCheckedChange = { onChange(settings.copy(usbRts = it)) })
    }
    OutlinedTextField(value = settings.civAddress, onValueChange = {
        if (it.length <= 2 && it.all { c -> c.digitToIntOrNull(16) != null }) onChange(settings.copy(civAddress = it))
    }, label = { Text(stringResource(R.string.usb_civ)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun UsbNumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = text, onValueChange = { text = it; it.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun UsbOptions(label: String, values: List<Int>, selected: Int, onChange: (Int) -> Unit) {
    Text(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { value -> FilterChip(selected = selected == value,
            onClick = { onChange(value) }, label = { Text(value.toString()) }) }
    }
}
