package com.raxvaayan.hukum

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/** Floating 👑 bubble over other apps → tap opens HUKUM. Only runs when user turns it on. */
class Bubble : Service() {
    private var wm: WindowManager? = null
    private var view: View? = null

    companion object {
        fun canDraw(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)
        fun start(ctx: Context) { if (canDraw(ctx)) ctx.startService(Intent(ctx, Bubble::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, Bubble::class.java)) }
        @Volatile var running = false
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!canDraw(this)) { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val btn = TextView(this).apply {
            text = "👑"; textSize = 26f
            background = round(C.CARD, dp(28).toFloat(), dp(2), C.GOLD)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(200) }

        var dx = 0f; var dy = 0f; var ix = 0; var iy = 0; var moved = false
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ix = lp.x; iy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ix + (e.rawX - dx).toInt(); lp.y = iy + (e.rawY - dy).toInt()
                    if (abs(e.rawX - dx) > dp(8) || abs(e.rawY - dy) > dp(8)) moved = true
                    wm?.updateViewLayout(btn, lp); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) startActivity(Intent(this, MainActivity::class.java)
                        .putExtra("wake", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    true
                }
                else -> false
            }
        }
        try { wm?.addView(btn, lp); view = btn; running = true } catch (e: Exception) { stopSelf() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}

/** Reschedule reminders after reboot. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Prefs.init(ctx)
        Sched.reschedulePending(ctx)
        More.scheduleBrief(ctx)
        Health.schedule(ctx)
        Market.schedule(ctx)
        Biz.schedule(ctx)
        if (Prefs.bubble) Bubble.start(ctx)
    }
}
