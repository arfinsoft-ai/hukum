package com.raxvaayan.hukum

import android.view.accessibility.AccessibilityNodeInfo

/** Turns OFF an alarm row in the phone's Clock app alarm list via Accessibility. Background thread only. */
object Clock {
    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, d: Int = 0) {
        if (n == null || d > 40) return
        out.add(n); for (i in 0 until n.childCount) walk(n.getChild(i), out, d + 1)
    }
    private fun all(n: AccessibilityNodeInfo) = ArrayList<AccessibilityNodeInfo>().also { walk(n, it) }
    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
    private fun isSwitch(n: AccessibilityNodeInfo) = n.isCheckable || n.className?.toString()?.let { it.contains("Switch") || it.contains("Toggle") } == true

    /** returns null on success, else reason */
    fun turnOff(svc: HukumAccess, hour: Int, minute: Int): String? {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val mm = "%02d".format(minute)
        val wants = setOf("%02d:%s".format(hour, mm), "$hour:$mm", "%02d:%s".format(h12, mm), "$h12:$mm")
        val pm = hour >= 12
        val deadline = System.currentTimeMillis() + 12_000
        var scrolls = 0
        Thread.sleep(1500)
        while (System.currentTimeMillis() < deadline) {
            val root = svc.rootInActiveWindow
            if (root == null || root.packageName?.toString() == svc.packageName) { Thread.sleep(500); continue }
            val nodes = all(root)
            val hits = nodes.filter { it.isVisibleToUser && label(it).let { l -> wants.any { w -> l == w || l.startsWith("$w ") } } }
            for (t in hits) {
                // climb to the row that also holds a switch
                var row: AccessibilityNodeInfo? = t.parent
                repeat(5) {
                    val r = row ?: return@repeat
                    val rowNodes = all(r)
                    val sw = rowNodes.firstOrNull { isSwitch(it) }
                    if (sw != null) {
                        val txt = rowNodes.joinToString(" ") { label(it) }.lowercase()
                        val hasAmPm = txt.contains("am") || txt.contains("pm") || txt.contains("सुबह") || txt.contains("शाम")
                        if (hasAmPm && (txt.contains("pm") != pm) && hour != h12) return@repeat
                        if (!sw.isChecked) return null // already off
                        var c: AccessibilityNodeInfo? = sw
                        repeat(3) { val x = c ?: return@repeat; if (x.isClickable) { x.performAction(AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(700); return null }; c = x.parent }
                        r.performAction(AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(700); return null
                    }
                    row = r.parent
                }
            }
            val sc = nodes.firstOrNull { it.isScrollable }
            if (sc != null && scrolls < 6) { sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); scrolls++; Thread.sleep(600) }
            else Thread.sleep(600)
        }
        return "%02d:%s ka alarm list mein nahi mila".format(hour, mm)
    }
}
