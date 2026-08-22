package space.linuxct.glyphworks.core

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

class AndroidRenderScheduler : RenderScheduler {

    private val thread = HandlerThread("compositor-worker").apply { start() }
    private val handler = Handler(thread.looper)
    private var ticker: Runnable? = null

    override fun setTicker(intervalMs: Long, tick: () -> Unit) {
        ticker?.let { handler.removeCallbacks(it) }
        val next = object : Runnable {
            override fun run() {
                if (ticker !== this) return
                tick()
                if (ticker === this) handler.postDelayed(this, intervalMs)
            }
        }
        ticker = next
        handler.post(next)
    }

    override fun clearTicker() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit): Cancelable {
        val scheduled = Runnable { action() }
        handler.postDelayed(scheduled, delayMs)
        return object : Cancelable {
            override fun cancel() {
                handler.removeCallbacks(scheduled)
            }
        }
    }

    override fun run(action: () -> Unit) {
        if (Looper.myLooper() == thread.looper) action() else handler.post(action)
    }
}
