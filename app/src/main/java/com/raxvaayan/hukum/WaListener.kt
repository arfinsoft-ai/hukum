package com.raxvaayan.hukum

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Reads incoming WhatsApp notifications and replies through their inline Reply action. */
class WaListener : NotificationListenerService() {

    class Item(val sender: String) {
        val texts = ArrayList<String>()
        var action: Notification.Action? = null
        var time = 0L
    }

    companion object {
        @Volatile var connected = false
        private val items = LinkedHashMap<String, Item>()

        private class Any(val app: String, val title: String, val text: String, val time: Long)
        private val anyList = ArrayList<Any>()

        fun allSummary(app: String): String = synchronized(anyList) {
            if (!connected) return "NOT DONE: Notification access OFF hai — Settings → 3 se ON karwao."
            val l = anyList.filter { app.isBlank() || it.app.contains(app, true) }.takeLast(25).reversed()
            if (l.isEmpty()) return "Koi naya notification nahi."
            val f = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.ENGLISH)
            l.joinToString("\n") { "${f.format(java.util.Date(it.time))} [${it.app}] ${it.title}: ${it.text.take(120)}" }
        }

        fun summary(): String = synchronized(items) {
            if (!connected) return "Notification access OFF hai — Settings se ON karwao."
            val list = items.values.sortedByDescending { it.time }.take(8)
            if (list.isEmpty()) return "Koi naya WhatsApp message nahi."
            list.joinToString("\n") { "${it.sender}: ${it.texts.takeLast(3).joinToString(" | ")}" }
        }

        fun find(q: String): Item? = synchronized(items) {
            val v = items.values.sortedByDescending { it.time }
            v.firstOrNull { it.sender.equals(q, true) }
                ?: v.firstOrNull { it.sender.contains(q, true) }
                ?: v.firstOrNull { q.contains(it.sender, true) }
                ?: run {
                    val qt = Contacts.translit(q).lowercase()
                    val qw = qt.split(Regex("\\s+")).filter { it.length >= 3 }
                    v.firstOrNull { item -> val s = Contacts.translit(item.sender).lowercase(); qw.isNotEmpty() && qw.any { s.contains(it) } }
                }
        }

        fun reply(ctx: Context, item: Item, msg: String): String {
            val act = item.action ?: return "Is chat ka reply option available nahi. whatsapp_send use karo."
            val inputs = act.remoteInputs ?: return "Reply option nahi mila."
            val data = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, msg) } }
            val fill = Intent()
            RemoteInput.addResultsToIntent(inputs, fill, data)
            act.actionIntent.send(ctx, 0, fill)
            synchronized(items) { items.remove(item.sender) }
            return "Reply bhej diya ${item.sender} ko."
        }
    }

    private fun recordAny(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        synchronized(anyList) {
            anyList.removeAll { it.app == app && it.title == title && it.text == text }
            anyList.add(Any(app, title, text, System.currentTimeMillis()))
            while (anyList.size > 60) anyList.removeAt(0)
        }
    }

    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { recordAny(sbn) } catch (_: Exception) {}
        if (!sbn.packageName.startsWith("com.whatsapp")) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.equals("WhatsApp", true) || title.isEmpty()) return
        val sender = title.replace(Regex("""\s*\(\d+ (messages|new messages)\)$"""), "")
        val reply = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
            ?: Notification.WearableExtender(n).actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        synchronized(items) {
            val it = items.getOrPut(sender) { Item(sender) }
            if (text.isNotBlank() && it.texts.lastOrNull() != text) it.texts.add(text)
            while (it.texts.size > 6) it.texts.removeAt(0)
            if (reply != null) it.action = reply
            it.time = System.currentTimeMillis()
            if (items.size > 30) items.remove(items.values.minByOrNull { x -> x.time }!!.sender)
        }
    }
}
