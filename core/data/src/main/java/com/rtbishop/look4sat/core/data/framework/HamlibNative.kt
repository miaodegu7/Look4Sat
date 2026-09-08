package com.rtbishop.look4sat.core.data.framework

import android.os.Build
import kotlinx.coroutines.sync.Mutex

/** Available on Android 9+ ARM devices; Bluetooth/TCP remain usable on older devices. */
internal object HamlibNative {
    val mutex = Mutex()
    val available: Boolean by lazy {
        if (Build.VERSION.SDK_INT < 28) false else try {
            System.loadLibrary("look4sat_hamlib")
            true
        } catch (_: UnsatisfiedLinkError) { false }
    }
    external fun models(): String
    external fun open(model: Int, port: Int, civAddress: String): Long
    external fun close(handle: Long)
    external fun command(handle: Long, operation: Int, value: Long, argument: String): Long
    external fun modeName(mode: Long): String
}
