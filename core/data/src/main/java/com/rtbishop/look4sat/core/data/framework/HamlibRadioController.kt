package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.math.roundToInt

/** rigctld default (non --vfo) mode, extended replies terminated by RPRT. */
class HamlibRadioController(private val settings: RadioControlSettings) : IRadioController {
    private val mutex = Mutex()
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var output: OutputStream? = null
    @Volatile override var isConnected = false
        private set
    var lastError: String? = null
        private set

    override suspend fun connect(): Boolean = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            close()
            try {
                require(settings.hamlibHost.isNotBlank() && settings.hamlibPort in 1..65535)
                socket = Socket().apply {
                    soTimeout = 4000
                    tcpNoDelay = true
                }
                socket!!.connect(InetSocketAddress(settings.hamlibHost, settings.hamlibPort), 4000)
                reader = socket!!.getInputStream().bufferedReader(Charsets.US_ASCII)
                output = socket!!.getOutputStream()
                // Explicitly reject --vfo mode; it changes every command's argument layout.
                val vfo = exchange("chk_vfo")?.get("CHKVFO")
                check(vfo == "0") { "Start rigctld without --vfo (-o)" }
                check(exchange("get_freq")?.get("Frequency")?.toLongOrNull() != null) {
                    lastError ?: "Cannot read radio frequency"
                }
                isConnected = true
                true
            } catch (e: Exception) {
                lastError = e.message ?: "Hamlib connection failed"
                close()
                false
            }
        }
    }

    override suspend fun disconnect() = mutex.withLock { runInterruptible(Dispatchers.IO) { close() } }

    private fun close() {
        try { socket?.close() } catch (_: IOException) { }
        socket = null
        reader = null
        output = null
        isConnected = false
    }

    private fun exchange(command: String): Map<String, String>? {
        output!!.write(("+\\$command\n").toByteArray(Charsets.US_ASCII))
        output!!.flush()
        val result = mutableMapOf<String, String>()
        // Bound both reply size and read duration; never reuse a desynchronized socket.
        repeat(64) {
            val line = buildString {
                while (true) {
                    val c = reader!!.read()
                    if (c == -1) throw IOException("Hamlib disconnected")
                    if (c == 10) break
                    if (length >= 4096) throw IOException("Hamlib reply too long")
                    if (c != 13) append(c.toChar())
                }
            }
            if (line.startsWith("RPRT ")) {
                val code = line.removePrefix("RPRT ").trim().toIntOrNull()
                    ?: throw IOException("Invalid Hamlib status")
                lastError = if (code == 0) null else "$command: RPRT $code"
                return if (code == 0) result else null
            }
            val colon = line.indexOf(':')
            if (colon >= 0) result[line.substring(0, colon)] = line.substring(colon + 1).trim()
        }
        throw IOException("Incomplete Hamlib response")
    }

    private suspend fun command(command: String): Map<String, String>? = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            if (!isConnected) return@runInterruptible null
            try { exchange(command) } catch (e: IOException) {
                lastError = e.message
                close()
                null
            }
        }
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean =
        frequencyHz > 0 && command("set_freq $frequencyHz") != null
    override suspend fun setMode(mode: String): Boolean {
        val value = mode.uppercase(Locale.ROOT)
        return value.matches(Regex("[A-Z0-9]+")) && command("set_mode $value 0") != null
    }
    override suspend fun setVfo(vfoA: Boolean): Boolean {
        val vfo = if (vfoA) settings.hamlibRxVfo else settings.hamlibTxVfo
        return vfo in RadioControlSettings.HAMLIB_VFOS && command("set_vfo $vfo") != null
    }
    override suspend fun setSplitMode(enabled: Boolean): Boolean =
        settings.hamlibTxVfo in RadioControlSettings.HAMLIB_VFOS &&
            command("set_split_vfo ${if (enabled) 1 else 0} ${settings.hamlibTxVfo}") != null
    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean =
        frequencyHz > 0 && command("set_split_freq $frequencyHz") != null
    suspend fun setTxMode(mode: String): Boolean {
        val value = mode.uppercase(Locale.ROOT)
        return value.matches(Regex("[A-Z0-9]+")) && command("set_split_mode $value 0") != null
    }
    override suspend fun readWorkingFrequency(): Long? = command("get_freq")?.get("Frequency")?.toLongOrNull()
    override suspend fun readTxVfoFrequency(): Long? = command("get_split_freq")?.get("TX Frequency")?.toLongOrNull()
    override suspend fun readFrequencyAndMode(): Pair<Long, String>? {
        val frequency = readWorkingFrequency() ?: return null
        val mode = command("get_mode")?.get("Mode") ?: return null
        return frequency to mode
    }
    override suspend fun setCtcssMode(enabled: Boolean): Boolean = command("set_func TONE ${if (enabled) 1 else 0}") != null
    override suspend fun setCtcssTone(toneHz: Double): Boolean =
        toneHz.isFinite() && toneHz > 0 && command("set_ctcss_tone ${(toneHz * 10).roundToInt()}") != null
    // Frequency tracking never keys the transmitter. Keep PTT on the radio.
    override suspend fun pttOn(): Boolean = false
    override suspend fun pttOff(): Boolean = command("set_ptt 0") != null
}
