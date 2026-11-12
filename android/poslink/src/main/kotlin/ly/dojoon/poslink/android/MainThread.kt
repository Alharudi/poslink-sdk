package ly.dojoon.poslink.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The main thread, where the Android transports keep their connection state (the USB and
 * Bluetooth broadcast receivers run there too). Delayed work is posted with a token, so
 * [cancelAll] in stop() removes only that work, never a start() or stop() that a caller on
 * another thread queued with [run].
 */
internal class MainThread {
    val handler = Handler(Looper.getMainLooper())
    private val token = Any()

    /** Runs [block] now on the main thread, otherwise queues it. Not removed by [cancelAll]. */
    fun run(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else handler.post { block() }
    }

    fun post(delayMs: Long, block: () -> Unit) {
        postRunnable(Runnable { block() }, delayMs)
    }

    fun postRunnable(r: Runnable, delayMs: Long) {
        handler.postAtTime(r, token, SystemClock.uptimeMillis() + delayMs)
    }

    /** Like [postRunnable], but first drops [r] if it is already waiting. */
    fun repost(r: Runnable, delayMs: Long) {
        handler.removeCallbacks(r, token)
        postRunnable(r, delayMs)
    }

    fun cancelAll() {
        handler.removeCallbacksAndMessages(token)
    }
}

/** Registers a receiver for system and own-package broadcasts only. */
internal fun Context.registerPosLinkReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(receiver, filter)
    }
}
