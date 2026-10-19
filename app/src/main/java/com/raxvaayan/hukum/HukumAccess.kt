package com.raxvaayan.hukum

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Taps the Send button in WhatsApp / Gmail, only right after HUKUM opened that screen. */
class HukumAccess : AccessibilityService() {

    companion object {
        @Volatile var inst: HukumAccess? = null
        @Volatile private var pendingPkg: String? = null
        @Volatile private var pendingAt = 0L

        fun isOn() = inst != null
        fun arm(pkg: String) { pendingPkg = pkg; pendingAt = System.currentTimeMillis() }
    }

    private val h = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { inst = this }
    override fun onDestroy() { inst = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val want = pendingPkg ?: return
        if (System.currentTimeMillis() - pendingAt > 25_000) { pendingPkg = null; return }
        val pkg = e?.packageName?.toString() ?: return
        if (pkg != want) return
        val root = rootInActiveWindow ?: return
        val node = findSend(root, pkg) ?: return
        if (click(node)) {
            pendingPkg = null
            h.postDelayed({ backToHukum() }, 1200)
        }
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        val ids = if (pkg.startsWith("com.whatsapp")) listOf("$pkg:id/send", "$pkg:id/send_btn")
        else listOf("$pkg:id/send")
        for (id in ids) root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser }?.let { return it }
        return root.findAccessibilityNodeInfosByText("Send")?.firstOrNull {
            it.isVisibleToUser && (it.contentDescription?.toString().equals("Send", true) || it.text?.toString().equals("Send", true))
        }
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        repeat(4) {
            val c = cur ?: return false
            if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = c.parent
        }
        return false
    }

    fun backToHukum() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}
