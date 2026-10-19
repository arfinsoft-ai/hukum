package com.raxvaayan.hukum

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/**
 * Blinkit add-to-cart via Accessibility. Runs on a background thread (blocks the caller).
 * Never checks out / pays — stops with items in cart.
 */
object Blinkit {
    const val PKG = "com.grofers.customerapp"

    data class Item(val name: String, val qty: Int)

    private const val STEP = 600L

    fun installed(svc: HukumAccess) = try { svc.packageManager.getPackageInfo(PKG, 0); true } catch (e: Exception) { false }

    fun addAll(svc: HukumAccess, items: List<Item>): String {
        if (!installed(svc)) return "Blinkit installed nahi hai."
        val out = ArrayList<String>()
        var failDump: String? = null
        for ((idx, it) in items.withIndex()) {
            val r = addOne(svc, it, first = idx == 0)
            out.add(r.first)
            if (!r.second && failDump == null) failDump = dump(svc)
        }
        failDump?.let { copy(svc, it) }
        return out.joinToString("\n") +
            (if (failDump != null) "\n(Debug: Blinkit screen ka structure clipboard mein copy hai — Claude ko paste karna.)" else "") +
            "\nCheckout/payment user khud Blinkit cart mein kare."
    }

    /** returns (message, success) */
    private fun addOne(svc: HukumAccess, item: Item, first: Boolean): Pair<String, Boolean> {
        val q = item.name.trim()
        openSearch(svc, q)
        sleep(if (first) 2500 else 1500)

        val deadline = System.currentTimeMillis() + 30_000
        var typedAt = 0L
        var entered = false
        var searchTaps = 0
        while (System.currentTimeMillis() < deadline) {
            val root = blinkitRoot(svc)
            if (root == null) { sleep(STEP); continue }
            val edit = findEdit(root)
            if (edit == null) {
                // Not on search screen: tap the search bar.
                val bar = findSearchBar(root)
                if (bar != null && searchTaps < 4) { click(bar); searchTaps++ }
                else if (searchTaps >= 4) svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                sleep(1000); continue
            }
            val cur = edit.text?.toString().orEmpty()
            if (!cur.contains(q, ignoreCase = true)) {
                setText(edit, q); typedAt = System.currentTimeMillis(); entered = false
                sleep(900); continue
            }
            if (!entered) { imeEnter(edit); entered = true; if (typedAt == 0L) typedAt = System.currentTimeMillis(); sleep(1200); continue }
            if (System.currentTimeMillis() - typedAt < 1500) { sleep(STEP); continue }

            val add = findAddButtons(root).firstOrNull()
            if (add == null) { sleep(STEP); continue }
            val title = productTitle(add) ?: q
            val box = Rect().also { add.getBoundsInScreen(it) }
            if (!click(add)) { sleep(STEP); continue }
            sleep(1200)
            var count = 1
            while (count < item.qty) {
                val r2 = blinkitRoot(svc) ?: break
                val plus = findPlusNear(r2, box) ?: break
                if (!click(plus)) break
                count++
                sleep(900)
            }
            val qtyMsg = if (count < item.qty) " (sirf $count add hua, ${item.qty} chahiye the — + button nahi mila)" else ""
            return "✅ $title × $count cart mein$qtyMsg" to (count >= item.qty)
        }
        return "❌ '$q' add nahi ho saka (search result/ADD button nahi mila)." to false
    }

    private fun openSearch(svc: HukumAccess, q: String) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://blinkit.com/s/?q=" + Uri.encode(q)))
            .setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { svc.startActivity(web); return } catch (_: Exception) {}
        svc.packageManager.getLaunchIntentForPackage(PKG)?.let { svc.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun blinkitRoot(svc: HukumAccess): AccessibilityNodeInfo? {
        svc.rootInActiveWindow?.let { if (it.packageName?.toString() == PKG) return it }
        return try { svc.windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == PKG } } catch (_: Exception) { null }
    }

    // ---------- finders ----------

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 40) return
        out.add(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    private fun all(root: AccessibilityNodeInfo) = ArrayList<AccessibilityNodeInfo>().also { walk(root, it) }

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    private fun findEdit(root: AccessibilityNodeInfo) =
        all(root).firstOrNull { it.isVisibleToUser && (it.isEditable || it.className?.toString()?.contains("EditText") == true) }

    private fun findSearchBar(root: AccessibilityNodeInfo) = all(root).firstOrNull {
        it.isVisibleToUser && label(it).contains("search", true)
    }

    private fun findAddButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = all(root).filter { it.isVisibleToUser && label(it).equals("ADD", true) }
        // top-most, left-most first = first search result
        return nodes.sortedWith(compareBy({ b(it).top / 40 }, { b(it).left }))
    }

    private fun findPlusNear(root: AccessibilityNodeInfo, near: Rect): AccessibilityNodeInfo? {
        val cands = all(root).filter {
            if (!it.isVisibleToUser) return@filter false
            val l = label(it).lowercase()
            l == "+" || l.contains("increase") || l.contains("increment") || l.contains("add one more") || l == "plus"
        }.ifEmpty {
            // Stepper with icons only: pick right-most clickable inside the old ADD area.
            all(root).filter { it.isVisibleToUser && it.isClickable && Rect.intersects(b(it), grow(near)) && label(it).isEmpty() }
                .sortedByDescending { b(it).right }.take(1)
        }
        return cands.minByOrNull { Math.abs(b(it).centerY() - near.centerY()) + Math.abs(b(it).centerX() - near.right) / 4 }
            ?.takeIf { Math.abs(b(it).centerY() - near.centerY()) < 250 }
    }

    private fun productTitle(add: AccessibilityNodeInfo): String? {
        var p = add.parent
        repeat(4) {
            val c = p ?: return null
            val texts = ArrayList<AccessibilityNodeInfo>().also { walk(c, it) }.map { label(it) }
                .filter { t -> t.length in 4..80 && !t.equals("ADD", true) && !t.startsWith("₹") && !t.matches(Regex(".*\\d+\\s*(MINS?|min).*", RegexOption.IGNORE_CASE)) && t.any { ch -> ch.isLetter() } }
            texts.maxByOrNull { it.length }?.let { if (it.length >= 6) return it }
            p = c.parent
        }
        return null
    }

    // ---------- actions ----------

    private fun b(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
    private fun grow(r: Rect) = Rect(r.left - 60, r.top - 40, r.right + 60, r.bottom + 40)

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        repeat(5) {
            val c = cur ?: return false
            if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = c.parent
        }
        return false
    }

    private fun setText(n: AccessibilityNodeInfo, t: String) {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) })
    }

    private fun imeEnter(n: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= 30) n.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    // ---------- debug ----------

    private fun dump(svc: HukumAccess): String {
        val root = blinkitRoot(svc) ?: return "no blinkit window"
        val sb = StringBuilder()
        fun rec(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 30 || sb.length > 12000) return
            val l = label(n)
            if (l.isNotEmpty() || n.isClickable || n.isEditable)
                sb.append(" ".repeat(d)).append(n.className?.toString()?.substringAfterLast('.')).append(" '").append(l.take(40))
                    .append("' ").append(n.viewIdResourceName?.substringAfter(":id/") ?: "")
                    .append(if (n.isClickable) " C" else "").append(if (n.isEditable) " E" else "").append('\n')
            for (i in 0 until n.childCount) rec(n.getChild(i), d + 1)
        }
        rec(root, 0)
        return sb.toString()
    }

    private fun copy(svc: HukumAccess, s: String) = try {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            svc.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("blinkit", s))
        }
    } catch (_: Exception) {}
}
