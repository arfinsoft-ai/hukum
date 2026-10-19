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

/** Opens camera (front/back) and presses the shutter via Accessibility. Background thread only. */
object Cam {
    private fun all(n: AccessibilityNodeInfo) = ArrayList<AccessibilityNodeInfo>().also { fun w(x: AccessibilityNodeInfo?, d: Int) { if (x == null || d > 40) return; it.add(x); for (i in 0 until x.childCount) w(x.getChild(i), d + 1) }; w(n, 0) }
    private fun desc(n: AccessibilityNodeInfo) = ((n.contentDescription ?: "").toString() + " " + (n.text ?: "") + " " + (n.viewIdResourceName ?: "")).lowercase()
    private fun click(n: AccessibilityNodeInfo): Boolean { var c: AccessibilityNodeInfo? = n; repeat(4) { val x = c ?: return false; if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK); c = x.parent }; return false }

    fun shoot(svc: HukumAccess, front: Boolean): String? {
        val i = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("android.intent.extras.CAMERA_FACING", if (front) 1 else 0)
            .putExtra("android.intent.extras.LENS_FACING_FRONT", if (front) 1 else 0)
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", front)
            .putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", front)
        try { svc.startActivity(i) } catch (e: Exception) { return "camera app nahi khula" }
        Thread.sleep(2500)
        val deadline = System.currentTimeMillis() + 10_000
        var switched = false
        while (System.currentTimeMillis() < deadline) {
            val root = svc.rootInActiveWindow
            if (root == null || root.packageName?.toString() == svc.packageName) { Thread.sleep(400); continue }
            val nodes = all(root).filter { it.isVisibleToUser }
            if (!switched) {
                val wrong = if (front) listOf("switch to front", "front camera", "selfie") else listOf("switch to rear", "switch to back", "rear camera", "back camera")
                val sw = nodes.firstOrNull { n -> val d = desc(n); wrong.any { d.contains(it) } }
                if (sw != null) { click(sw); switched = true; Thread.sleep(1500); continue }
                switched = true
            }
            val shutter = nodes.firstOrNull { n -> val d = desc(n); listOf("shutter", "take photo", "take picture", "capture", "photo button", "click photo").any { d.contains(it) } }
            if (shutter != null && click(shutter)) { Thread.sleep(1500); return null }
            Thread.sleep(500)
        }
        // Fallback: tap bottom-centre where every camera app keeps the shutter.
        val dm = svc.resources.displayMetrics
        val p = android.graphics.Path().apply { moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.86f) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 80)).build()
        return if (svc.dispatchGesture(g, null, null)) { Thread.sleep(1500); null } else "shutter button nahi mila"
    }
}

/** Generic screen helpers + YouTube play. Background thread only. */
object Screen {
    private fun all(n: AccessibilityNodeInfo) = ArrayList<AccessibilityNodeInfo>().also { fun w(x: AccessibilityNodeInfo?, d: Int) { if (x == null || d > 45) return; it.add(x); for (i in 0 until x.childCount) w(x.getChild(i), d + 1) }; w(n, 0) }
    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
    private fun click(n: AccessibilityNodeInfo): Boolean { var c: AccessibilityNodeInfo? = n; repeat(5) { val x = c ?: return false; if (x.isClickable && x.isEnabled) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK); c = x.parent }; return false }
    private fun root(svc: HukumAccess): AccessibilityNodeInfo? {
        val r = svc.rootInActiveWindow
        if (r != null && r.packageName?.toString() != svc.packageName) return r
        return try { svc.windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() != svc.packageName } } catch (_: Exception) { null }
    }

    fun read(svc: HukumAccess): String {
        val r = root(svc) ?: return "Screen nahi padh paya (HUKUM ke alawa koi app khuli nahi)."
        val seen = LinkedHashSet<String>()
        for (n in all(r)) if (n.isVisibleToUser) {
            val l = label(n).replace('\n', ' ').take(70)
            if (l.isNotEmpty()) seen.add((if (n.isEditable) "[input] " else if (n.isClickable || n.parent?.isClickable == true) "[btn] " else "") + l)
            if (seen.size > 60) break
        }
        return "App: ${r.packageName}\n" + seen.joinToString("\n")
    }

    fun tap(svc: HukumAccess, text: String, nth: Int = 1): String {
        val r = root(svc) ?: return "NOT DONE: screen nahi mili."
        val ns = all(r).filter { it.isVisibleToUser }
        val m = ns.filter { label(it).equals(text, true) }.ifEmpty { ns.filter { label(it).contains(text, true) } }
        val n = m.getOrNull(nth - 1) ?: return "NOT DONE: '$text' screen par nahi mila."
        return if (click(n)) { Thread.sleep(1200); "DONE: '${label(n).take(50)}' dabaya." } else "NOT DONE: '$text' clickable nahi."
    }

    fun type(svc: HukumAccess, text: String, enter: Boolean): String {
        val r = root(svc) ?: return "NOT DONE: screen nahi mili."
        val e = all(r).firstOrNull { it.isVisibleToUser && it.isEditable } ?: return "NOT DONE: type karne ki jagah nahi mili."
        e.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
        if (enter && android.os.Build.VERSION.SDK_INT >= 30) e.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        Thread.sleep(1200)
        return "DONE: '$text' type kiya."
    }

    private const val YT = "com.google.android.youtube"

    fun youtube(svc: HukumAccess, query: String): String {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query)))
            .setPackage(YT).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { svc.startActivity(i) } catch (e: Exception) { return "NOT DONE: YouTube app nahi khula." }
        Thread.sleep(3000)
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            val r = root(svc)
            if (r == null || r.packageName?.toString() != YT) { Thread.sleep(500); continue }
            val bad = listOf("sponsored", "ad ·", "· ad", "shorts", "go to channel", "search", "filter", "voice", "more options", "action menu", "people also")
            val dm = svc.resources.displayMetrics
            // video rows carry a long content description like "Title - 12 minutes - Channel - 1.2M views - 3 hours ago - play video"
            val vids = all(r).filter { n ->
                if (!n.isVisibleToUser) return@filter false
                val d = n.contentDescription?.toString()?.lowercase() ?: return@filter false
                d.length > 25 && (d.contains("views") || d.contains("watching") || d.contains("minute") || d.contains("second") || d.contains("live") || d.contains("play video")) &&
                    bad.none { d.startsWith(it) || d.contains(it) && d.length < 40 } && !d.contains("sponsored")
            }.sortedBy { android.graphics.Rect().also { b -> it.getBoundsInScreen(b) }.top }
                .filter { android.graphics.Rect().also { b -> it.getBoundsInScreen(b) }.top > dm.heightPixels / 10 }
            val v = vids.firstOrNull()
            if (v != null && click(v)) {
                val title = v.contentDescription.toString().substringBefore(" - ").take(70)
                Thread.sleep(1500)
                return "DONE: YouTube pe chala diya: $title"
            }
            Thread.sleep(700)
        }
        return "NOT DONE: YouTube search khul gaya ('$query') par video tap nahi ho paya. User list se chun le."
    }
}
