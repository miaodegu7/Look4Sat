package com.rtbishop.look4sat.core.data.framework

import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.roundToInt

/** Bundled Hamlib backend connected to an Android USB Host serial device. */
class HamlibUsbController(
    private val appScope: kotlinx.coroutines.CoroutineScope,
    private val usbManager: UsbManager,
    private val settings: RadioControlSettings
) : IRadioController {
    private var handle = 0L
    private var bridge: UsbSerialBridge? = null
    override var lastError: String? = null
        private set
    override val isConnected: Boolean get() = handle != 0L

    override suspend fun connect(): Boolean = HamlibNative.mutex.withLock {
        withContext(Dispatchers.IO) {
            disconnectLocked()
            try {
                check(HamlibNative.available) { "Bundled Hamlib requires Android 9+ and a supported ARM CPU" }
                val device = usbManager.deviceList[settings.usbDeviceName]
                    ?: error("Selected USB device is not attached")
                check(usbManager.hasPermission(device)) { "USB permission is required; reopen Radio Control settings" }
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: error("Unsupported USB serial adapter")
                val port = driver.ports.getOrNull(settings.usbPort)
                    ?: error("USB serial port ${settings.usbPort} is unavailable")
                val connection = usbManager.openDevice(device) ?: error("Could not open USB device")
                val usbBridge = UsbSerialBridge(port, connection, settings)
                usbBridge.start()
                bridge = usbBridge
                val civ = settings.civAddress.trim().removePrefix("0x")
                if (civ.isNotEmpty()) require(civ.toInt(16) in 0..255) { "CI-V address must be hexadecimal 00-FF" }
                handle = HamlibNative.open(settings.usbModelId, usbBridge.port, civ)
                check(handle != 0L) { "Hamlib could not open the radio" }
                lastError = null
                true
            } catch (e: Exception) {
                lastError = e.message ?: "Hamlib USB connection failed"
                disconnectLocked()
                false
            }
        }
    }

    override suspend fun disconnect() = HamlibNative.mutex.withLock {
        withContext(Dispatchers.IO) { disconnectLocked() }
    }
    private fun disconnectLocked() {
        if (handle != 0L) try { HamlibNative.close(handle) } catch (_: Exception) { }
        handle = 0
        bridge?.close()
        bridge = null
    }
    private suspend fun call(op: Int, value: Long = 0, argument: String = ""): Long? =
        HamlibNative.mutex.withLock {
            withContext(Dispatchers.IO) {
                if (handle == 0L) return@withContext null
                try { HamlibNative.command(handle, op, value, argument).also { lastError = null } }
                catch (e: Exception) {
                    lastError = e.message
                    disconnectLocked()
                    null
                }
            }
        }
    override suspend fun setFrequency(frequencyHz: Long) = frequencyHz > 0 && call(0, frequencyHz) != null
    override suspend fun setWorkingFrequency(frequencyHz: Long) = setFrequency(frequencyHz)
    override suspend fun readWorkingFrequency(): Long? = call(1)?.takeIf { it > 0 }
    override suspend fun setMode(mode: String) = call(2, argument = mode) != null
    override suspend fun setVfo(vfoA: Boolean) = call(3, argument = if (vfoA) settings.hamlibRxVfo else settings.hamlibTxVfo) != null
    override suspend fun setSplitMode(enabled: Boolean) = call(4, if (enabled) 1 else 0, settings.hamlibTxVfo) != null
    override suspend fun setTxVfoFrequency(frequencyHz: Long) = frequencyHz > 0 && call(5, frequencyHz) != null
    override suspend fun readTxVfoFrequency(): Long? = call(6)?.takeIf { it > 0 }
    override suspend fun setTxMode(mode: String) = call(7, argument = mode) != null
    override suspend fun setCtcssTone(toneHz: Double) = toneHz > 0 && call(8, (toneHz * 10).roundToInt().toLong()) != null
    override suspend fun setCtcssMode(enabled: Boolean) = call(9, if (enabled) 1 else 0) != null
    override suspend fun pttOn() = false
    override suspend fun pttOff() = call(10) != null
    override suspend fun readFrequencyAndMode(): Pair<Long, String>? {
        val frequency = readWorkingFrequency() ?: return null
        val mode = call(11)?.let { HamlibNative.modeName(it) } ?: return null
        return frequency to mode
    }
}

private class UsbSerialBridge(
    private val serial: UsbSerialPort,
    private val connection: android.hardware.usb.UsbDeviceConnection,
    private val settings: RadioControlSettings
) {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    private var client: Socket? = null
    private var job: Job? = null
    fun start() {
        serial.open(connection)
        serial.setParameters(settings.usbBaud, settings.usbDataBits, settings.usbStopBits, settings.usbParity)
        serial.dtr = settings.usbDtr
        serial.rts = settings.usbRts
        job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            client = runInterruptible { server.accept() }
            val socket = client ?: return@launch
            val networkInput = socket.getInputStream()
            val networkOutput = socket.getOutputStream()
            while (currentCoroutineContext().isActive) {
                var moved = false
                if (networkInput.available() > 0) {
                    val data = ByteArray(minOf(networkInput.available(), 4096))
                    val count = networkInput.read(data)
                    if (count < 0) break
                    serial.write(data.copyOf(count), 1000)
                    moved = true
                }
                val data = ByteArray(4096)
                val count = try { serial.read(data, 20) } catch (e: IOException) { break }
                if (count > 0) { networkOutput.write(data, 0, count); networkOutput.flush(); moved = true }
                if (!moved) delay(5)
            }
        }
    }
    fun close() {
        job?.cancel()
        try { client?.close() } catch (_: Exception) { }
        try { server.close() } catch (_: Exception) { }
        try { serial.close() } catch (_: Exception) { }
        connection.close()
    }
}
