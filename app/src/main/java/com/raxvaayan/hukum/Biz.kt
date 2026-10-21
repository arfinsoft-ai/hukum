package com.raxvaayan.hukum

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Business + personal helpers: dues follow-up, expenses (+bank SMS import), monthly bills/EMI, document vault,
 * birthday wishes, screen-time report + app limits, shortcuts, contact save from visiting cards.
 * Daily alarm: 08:30 (birthdays/bills/dues), every 30 min (app limits), 21:30 (screen-time report).
 */
object Biz {
    private const val CH = "hukum_biz"
    private const val RC = 777_300
    private fun sp(c: Context) = c.getSharedPreferences("hukum_biz", Context.MODE_PRIVATE)
    private fun arr(c: Context, k: String) = try { JSONArray(sp(c).getString(k, "[]")) } catch (e: Exception) { JSONArray() }
    private fun list(c: Context, k: String) = arr(c, k).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun save(c: Context, k: String, l: List<JSONObject>) = sp(c).edit().putString(k, JSONArray(l).toString()).apply()
    private fun inr(v: Double) = "₹" + "%,.0f".format(v)
    private val D = SimpleDateFormat("d MMM", Locale.ENGLISH)
    private fun dayKey(t: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date(t))
    private fun startOfDay(t: Long = System.currentTimeMillis()) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Last photo attached in chat (set by MainActivity). */
    @Volatile var lastImage: File? = null

    fun usageReportOn(c: Context) = sp(c).getBoolean("usageReport", false)
    fun setUsageReport(c: Context, on: Boolean) { sp(c).edit().putBoolean("usageReport", on).apply(); schedule(c) }

    // ======================= DUES (lena baaki) =======================
    fun dues(c: Context) = list(c, "dues")

    private fun dueAdd(h: ToolHost, a: JSONObject): String {
        val c = h.ctx; val name = a.getString("name").trim(); val amt = a.getDouble("amount")
        val every = a.optInt("every_days", 7).coerceIn(1, 60)
        val first = Extra.parseTime(a.optString("first_date", "").let { if (it.length == 10) "$it 08:30" else it })
            ?: (startOfDay() + every * 86400000L + 8 * 3600000L + 30 * 60000L)
        val phone = (Contacts.phone(c, name) as? Resolved.One)?.ct
        val l = dues(c).filterNot { it.getString("name").equals(name, true) }.toMutableList()
        l.add(JSONObject().put("id", System.currentTimeMillis()).put("name", phone?.name ?: name).put("phone", phone?.value ?: "")
            .put("amount", amt).put("note", a.optString("note")).put("every", every).put("next", first))
        save(c, "dues", l); schedule(c)
        return "DONE: ${phone?.name ?: name} se ${inr(amt)} lena hai — ${D.format(Date(first))} se har $every din yaad dilaunga" +
            (if (phone == null) " (contact nahi mila, WhatsApp button nahi aayega)." else ", WhatsApp reminder ready karke.")
    }

    private fun duePaid(c: Context, name: String, amount: Double?): String {
        val l = dues(c).toMutableList(); val o = l.firstOrNull { it.getString("name").contains(name.trim(), true) } ?: return "'$name' ka koi baaki nahi mila."
        val left = if (amount == null) 0.0 else o.getDouble("amount") - amount
        if (left <= 0.5) { l.remove(o); save(c, "dues", l); return "DONE: ${o.getString("name")} ka hisaab clear ✅" }
        o.put("amount", left); save(c, "dues", l); return "DONE: ${o.getString("name")} se ab ${inr(left)} baaki."
    }

    fun dueText(o: JSONObject) = "Namaste ${o.getString("name").split(" ").first()} ji, ${inr(o.getDouble("amount"))} ka payment pending hai" +
        (o.optString("note").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ". Kripya jaldi clear kar dijiye. Dhanyavaad 🙏 — ${Prefs.userName}"

    // ======================= EXPENSES =======================
    fun expenses(c: Context) = list(c, "exp")

    private fun expAdd(c: Context, amt: Double, cat: String, note: String, t: Long = System.currentTimeMillis(), src: String = ""): JSONObject {
        val o = JSONObject().put("t", t).put("amt", amt).put("cat", cat.ifBlank { "other" }.lowercase()).put("note", note).put("src", src)
        val l = expenses(c).toMutableList(); l.add(o); save(c, "exp", l.takeLast(3000)); return o
    }

    private fun range(period: String): Pair<Long, Long> {
        val now = System.currentTimeMillis(); val c = Calendar.getInstance()
        return when (period.lowercase()) {
            "today", "aaj" -> startOfDay() to now
            "yesterday", "kal" -> startOfDay() - 86400000L to startOfDay()
            "week", "hafta" -> startOfDay() - 6 * 86400000L to now
            "last_month" -> { c.set(Calendar.DAY_OF_MONTH, 1); val end = startOfDay(c.timeInMillis); c.add(Calendar.MONTH, -1); startOfDay(c.timeInMillis) to end }
            else -> { c.set(Calendar.DAY_OF_MONTH, 1); startOfDay(c.timeInMillis) to now }
        }
    }

    fun monthTotal(c: Context) = range("month").let { (s, e) -> expenses(c).filter { it.getLong("t") in s..e }.sumOf { it.getDouble("amt") } }

    private fun expReport(c: Context, period: String, cat: String): String {
        val (s, e) = range(period)
        val l = expenses(c).filter { it.getLong("t") in s..e && (cat.isBlank() || it.getString("cat").contains(cat.lowercase())) }
        if (l.isEmpty()) return "Is period mein koi kharcha nahi."
        val by = l.groupBy { it.getString("cat") }.mapValues { it.value.sumOf { x -> x.getDouble("amt") } }.toList().sortedByDescending { it.second }
        return "💸 Kharcha (${period}): ${inr(l.sumOf { it.getDouble("amt") })} — ${l.size} entries\n" +
            by.joinToString("\n") { "• ${it.first}: ${inr(it.second)}" } +
            "\nRecent: " + l.takeLast(5).reversed().joinToString(" · ") { "${inr(it.getDouble("amt"))} ${it.optString("note").ifBlank { it.getString("cat") }}" }
    }

    private val DEBIT = Regex("(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)

    private fun expFromSms(c: Context, days: Int): String {
        if (!has(c, Manifest.permission.READ_SMS)) return "NOT DONE: SMS permission do."
        val since = System.currentTimeMillis() - days.coerceIn(1, 60) * 86400000L
        val seen = (sp(c).getStringSet("smsSeen", emptySet()) ?: emptySet()).toMutableSet(); val added = ArrayList<String>()
        c.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id", "address", "body", "date"), "date>?", arrayOf(since.toString()), "date ASC")?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(0); val body = cur.getString(2) ?: continue; val lb = body.lowercase()
                if (id in seen) continue
                val isDebit = (lb.contains("debited") || lb.contains("spent") || lb.contains("paid") || lb.contains("sent rs") || lb.contains("withdrawn")) &&
                    !lb.contains("credited to your") && !lb.contains("otp") && !lb.contains("will be debited") && !lb.contains("due")
                if (!isDebit) continue
                val amt = DEBIT.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
                if (amt <= 0 || amt > 5_000_000) continue
                val to = Regex("(?:to|at|towards)\\s+([A-Za-z0-9 .&@_-]{3,30})", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim() ?: cur.getString(1)
                val cat = when { lb.contains("upi") -> "upi"; lb.contains("atm") || lb.contains("withdrawn") -> "cash"; lb.contains("card") -> "card"; else -> "bank" }
                expAdd(c, amt, cat, to.take(30), cur.getLong(3), "sms"); seen.add(id); added.add("${inr(amt)} $to")
            }
        }
        sp(c).edit().putStringSet("smsSeen", seen.toList().takeLast(2000).toSet()).apply()
        return if (added.isEmpty()) "Pichhle $days din ke SMS mein naya debit nahi mila." else "DONE: ${added.size} kharche SMS se jode:\n" + added.takeLast(12).joinToString("\n")
    }

    // ======================= BILLS / EMI =======================
    fun bills(c: Context) = list(c, "bills")
    private fun paidKey(o: JSONObject) = "paid_" + o.getString("name").lowercase() + "_" + SimpleDateFormat("yyyyMM", Locale.ENGLISH).format(Date())
    fun billPaid(c: Context, o: JSONObject) = sp(c).getBoolean(paidKey(o), false)

    fun nextBillDate(o: JSONObject): Long {
        val cal = Calendar.getInstance(); val d = o.getInt("day")
        cal.set(Calendar.DAY_OF_MONTH, minOf(d, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        if (startOfDay(cal.timeInMillis) < startOfDay()) { cal.add(Calendar.MONTH, 1); cal.set(Calendar.DAY_OF_MONTH, minOf(d, cal.getActualMaximum(Calendar.DAY_OF_MONTH))) }
        return startOfDay(cal.timeInMillis)
    }

    // ======================= VAULT =======================
    private fun vaultDir(c: Context) = File(c.filesDir, "vault").apply { mkdirs() }
    fun vault(c: Context) = list(c, "vault")

    private fun vaultSave(c: Context, label: String): String {
        val src = lastImage ?: return "NOT DONE: pehle 📎 se photo bhejo, phir bolo 'isko PAN card naam se save karo'."
        val f = File(vaultDir(c), label.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(40) + "_" + System.currentTimeMillis() % 100000 + ".jpg")
        src.copyTo(f, overwrite = true)
        val l = vault(c).filterNot { it.getString("label").equals(label, true) }.toMutableList()
        l.add(JSONObject().put("label", label.trim()).put("file", f.name).put("t", System.currentTimeMillis()))
        save(c, "vault", l)
        return "DONE: '$label' vault mein save (sirf phone mein)."
    }

    private fun vaultSend(h: ToolHost, label: String, contact: String): String {
        val c = h.ctx
        val o = vault(c).firstOrNull { it.getString("label").contains(label.trim(), true) || label.contains(it.getString("label"), true) }
        val file = if (o != null) File(vaultDir(c), o.getString("file")) else if (label.contains("photo", true) || label.contains("last", true)) lastImage else null
        if (file == null || !file.exists()) return "NOT DONE: vault mein '$label' nahi mila. Saved: ${vault(c).joinToString { it.getString("label") }.ifEmpty { "kuch nahi" }}"
        val uri = Files.uri(c, file)
        val i = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        var who = "share"
        if (contact.isNotBlank()) {
            val r = Contacts.phone(c, contact); if (r is Resolved.Err) return r.msg
            val ct = (r as Resolved.One).ct
            if (!Prefs.autoConfirm && !h.confirm("📎 Document bhejun?", "'${o?.getString("label") ?: "photo"}' → ${ct.name} (WhatsApp)")) return "User ne cancel kiya."
            i.setPackage(Prefs.waPkg).putExtra("jid", Contacts.intl(ct.value) + "@s.whatsapp.net"); who = ct.name
            if (HukumAccess.isOn()) HukumAccess.arm(Prefs.waPkg)
        }
        h.onUi { try { c.startActivity(i) } catch (e: Exception) { c.startActivity(Intent.createChooser(i.setPackage(null), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; "" }
        return "DONE: '${o?.getString("label") ?: "photo"}' $who ko bhejne ke liye WhatsApp khola" + (if (HukumAccess.isOn() && who != "share") " (auto-send)." else ".")
    }

    // ======================= BIRTHDAYS =======================
    class Bday(val name: String, val phone: String?, val date: Long)

    /** One query: contact_id → number and lowercase name → number. */
    private fun phoneMaps(c: Context): Pair<Map<Long, String>, Map<String, String>> {
        val byId = HashMap<Long, String>(); val byName = HashMap<String, String>()
        try { c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { cur ->
            while (cur.moveToNext()) { val n = cur.getString(1) ?: continue
                byId.putIfAbsent(cur.getLong(0), n); cur.getString(2)?.lowercase()?.trim()?.let { byName.putIfAbsent(it, n) } } } } catch (_: Exception) {}
        return byId to byName
    }

    fun birthdays(c: Context, days: Int): List<Bday> {
        val out = ArrayList<Bday>(); val today = startOfDay()
        val (byId, byName) = if (has(c, Manifest.permission.READ_CONTACTS)) phoneMaps(c) else (emptyMap<Long, String>() to emptyMap<String, String>())
        if (has(c, Manifest.permission.READ_CONTACTS)) try {
            c.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE, ContactsContract.Data.CONTACT_ID),
                "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Event.TYPE}=${ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY}",
                arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE), null)?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue; val s = cur.getString(1) ?: continue
                    val md = Regex("(\\d{1,2})-(\\d{1,2})$").find(s.trim())?.groupValues ?: continue
                    val cal = Calendar.getInstance().apply { timeInMillis = today; set(Calendar.MONTH, md[1].toInt() - 1); set(Calendar.DAY_OF_MONTH, md[2].toInt()) }
                    if (cal.timeInMillis < today) cal.add(Calendar.YEAR, 1)
                    if (cal.timeInMillis <= today + days * 86400000L) out.add(Bday(name, byId[cur.getLong(2)] ?: byName[name.lowercase().trim()], cal.timeInMillis))
                }
            }
        } catch (_: Exception) {}
        if (has(c, Manifest.permission.READ_CALENDAR)) try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, today - 86400000L); android.content.ContentUris.appendId(it, today + (days + 1) * 86400000L) }.build()
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val title = cur.getString(0) ?: continue
                    if (!title.contains("birthday", true) && !title.contains("bday", true) && !title.contains("janmdin", true)) continue
                    var t = cur.getLong(1)
                    if (cur.getInt(2) == 1) { val u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = t }
                        t = Calendar.getInstance().apply { clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH)) }.timeInMillis }
                    if (t < today) continue
                    val who = title.replace(Regex("(?i)'?s?\\s*(birthday|bday|janmdin|ka|ki|nu|no)"), " ").replace(Regex("\\s+"), " ").trim()
                    if (out.any { it.name.contains(who, true) || who.contains(it.name.split(" ").first(), true) }) continue
                    val digits = who.filter { it.isDigit() }
                    val ph = if (digits.length >= 10 && who.count { it.isLetter() } == 0) who
                        else byName[who.lowercase()] ?: byName.entries.firstOrNull { e -> who.length >= 4 && e.key.startsWith(who.lowercase()) }?.value
                    out.add(Bday(who.ifBlank { title }, ph, startOfDay(t)))
                }
            }
        } catch (_: Exception) {}
        return out.sortedBy { it.date }
    }

    fun wish(name: String) = "Happy Birthday ${name.split(" ").first()} 🎂🎉 Bhagwan aapko khoob khushiyan, achhi sehat aur safalta de. Aapka din shubh ho! — ${Prefs.userName}"

    // ======================= SCREEN TIME =======================
    fun usageAllowed(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usage(c: Context, from: Long, to: Long): List<Pair<String, Long>> {
        val um = c.getSystemService(UsageStatsManager::class.java)
        val pm = c.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
        return um.queryAndAggregateUsageStats(from, to).values
            .filter { it.totalTimeInForeground > 60000 && it.packageName !in launchers && it.packageName != "com.android.systemui" }
            .map { s -> (try { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString() } catch (e: Exception) { s.packageName }) to s.totalTimeInForeground }
            .groupBy { it.first }.map { it.key to it.value.sumOf { x -> x.second } }.sortedByDescending { it.second }
    }

    private fun hm(ms: Long) = (ms / 60000).let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }

    fun screenTime(c: Context, day: String): String {
        if (!usageAllowed(c)) return "NOT DONE: Usage access chahiye — Settings ⚙️ → 5. Usage access → HUKUM ON."
        val (s, e) = if (day.startsWith("y") || day == "kal") startOfDay() - 86400000L to startOfDay() else startOfDay() to System.currentTimeMillis()
        val u = usage(c, s, e)
        if (u.isEmpty()) return "Koi usage data nahi."
        return "📱 Screen time (${if (s < startOfDay()) "kal" else "aaj"}): ${hm(u.sumOf { it.second })}\n" + u.take(8).joinToString("\n") { "• ${it.first}: ${hm(it.second)}" } +
            limits(c).takeIf { it.isNotEmpty() }?.let { "\nLimits: " + it.joinToString { l -> "${l.getString("app")} ${l.getInt("min")}m" } }.orEmpty()
    }

    fun limits(c: Context) = list(c, "limits")

    // ======================= SHORTCUTS =======================
    private val DEFAULT_SC = linkedMapOf(
        "office mode" to listOf("phone vibrate pe karo", "Dhan app kholo", "aaj ki meetings batao", "market brief open batao"),
        "ghar mode" to listOf("phone sound pe karo", "aaj kitne steps hue aur kitna paani piya batao"),
        "sone ka mode" to listOf("DND on karo", "kal subah 6:30 ka alarm lagao", "kal ke reminders aur meetings batao"))

    fun shortcuts(c: Context): Map<String, List<String>> {
        if (!sp(c).contains("sc")) sp(c).edit().putString("sc", JSONObject(DEFAULT_SC.mapValues { JSONArray(it.value) } as Map<*, *>).toString()).apply()
        val o = try { JSONObject(sp(c).getString("sc", "{}")) } catch (e: Exception) { JSONObject() }
        return o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }
    }

    private fun scSave(c: Context, name: String, steps: List<String>?) {
        val m = shortcuts(c).toMutableMap(); if (steps == null) m.remove(name.lowercase().trim()) else m[name.lowercase().trim()] = steps
        sp(c).edit().putString("sc", JSONObject(m.mapValues { JSONArray(it.value) } as Map<*, *>).toString()).apply()
    }

    // ======================= CONTACT SAVE =======================
    private fun contactSave(h: ToolHost, a: JSONObject): String {
        val c = h.ctx; val name = a.getString("name").trim(); val phone = a.optString("phone").trim()
        val detail = listOf(name, phone, a.optString("company"), a.optString("email"), a.optString("title")).filter { it.isNotBlank() }.joinToString("\n")
        if (!Prefs.autoConfirm && !h.confirm("👤 Contact save karun?", detail)) return "User ne cancel kiya."
        if (!has(c, Manifest.permission.WRITE_CONTACTS)) {
            val i = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, name).putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                .putExtra(ContactsContract.Intents.Insert.EMAIL, a.optString("email")).putExtra(ContactsContract.Intents.Insert.COMPANY, a.optString("company"))
                .putExtra(ContactsContract.Intents.Insert.JOB_TITLE, a.optString("title")).putExtra(ContactsContract.Intents.Insert.NOTES, a.optString("note"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            h.onUi { c.startActivity(i); "" }; return "Contact form bhar ke khola — user Save dabaye."
        }
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
        fun row(mime: String, vararg kv: Pair<String, Any?>) { ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, mime)
            .apply { kv.forEach { withValue(it.first, it.second) } }.build()) }
        row(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to name)
        if (phone.isNotBlank()) row(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.NUMBER to phone,
            ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
        if (a.optString("email").isNotBlank()) row(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.ADDRESS to a.getString("email"),
            ContactsContract.CommonDataKinds.Email.TYPE to ContactsContract.CommonDataKinds.Email.TYPE_WORK)
        if (a.optString("company").isNotBlank() || a.optString("title").isNotBlank()) row(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.COMPANY to a.optString("company"), ContactsContract.CommonDataKinds.Organization.TITLE to a.optString("title"))
        if (a.optString("note").isNotBlank()) row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Note.NOTE to a.getString("note"))
        return try { c.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops); "DONE: '$name' contact save ho gaya." } catch (e: Exception) { "NOT DONE: ${e.message}" }
    }

    // ======================= DAILY ALARM =======================
    private fun at(h: Int, m: Int, plusDays: Int = 0) = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, plusDays); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, RC, Intent(c, Alarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        val now = System.currentTimeMillis()
        val cands = mutableListOf(at(8, 30), at(8, 30, 1))
        if (usageReportOn(c)) { cands.add(at(21, 30)); cands.add(at(21, 30, 1)) }
        if (limits(c).isNotEmpty()) { val n = Calendar.getInstance(); val m = n.get(Calendar.MINUTE); cands.add(now + ((30 - m % 30) * 60000L)) }
        val t = cands.filter { it > now + 20000 }.minOrNull() ?: return
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) }
    }

    private fun tick(c: Context) {
        val n = Calendar.getInstance(); val m = n.get(Calendar.HOUR_OF_DAY) * 60 + n.get(Calendar.MINUTE)
        if (m in (8 * 60 + 29)..(8 * 60 + 35) && sp(c).getString("dailyDone", "") != dayKey()) {
            sp(c).edit().putString("dailyDone", dayKey()).apply()
            birthdays(c, 0).forEachIndexed { i, b -> notify(c, 100 + i, "🎂 Aaj ${b.name} ka birthday", wish(b.name), b.phone?.let { wa(c, it, wish(b.name), 300 + i) }, "🎂 Wish bhejo") }
            bills(c).forEachIndexed { i, o ->
                val d = nextBillDate(o); val before = o.optInt("before", 1); val left = ((d - startOfDay()) / 86400000L).toInt()
                if (!billPaid(c, o) && (left == before || left == 0)) notify(c, 200 + i, "🧾 ${o.getString("name")} ${if (left == 0) "aaj" else "kal"} due",
                    (if (o.optDouble("amount", 0.0) > 0) inr(o.getDouble("amount")) + " · " else "") + "Har mahine ${o.getInt("day")} tarikh. Bhar diya ho to bolo \"${o.getString("name")} paid\".", null, null)
            }
            val l = dues(c).toMutableList(); var changed = false
            l.forEachIndexed { i, o -> if (o.getLong("next") <= at(23, 59)) {
                notify(c, 400 + i, "💰 ${o.getString("name")} se ${inr(o.getDouble("amount"))} lena hai", o.optString("note").ifBlank { "Payment follow-up ka din" },
                    o.optString("phone").takeIf { it.isNotBlank() }?.let { wa(c, it, dueText(o), 500 + i) }, "💬 Reminder bhejo")
                o.put("next", startOfDay() + o.getInt("every") * 86400000L + 8 * 3600000L + 30 * 60000L); changed = true } }
            if (changed) save(c, "dues", l)
        }
        if (usageReportOn(c) && m in (21 * 60 + 29)..(21 * 60 + 35) && usageAllowed(c)) notify(c, 1, "📱 Aaj ka screen time", screenTime(c, "today").substringAfter("\n", ""), null, null)
        if (limits(c).isNotEmpty() && usageAllowed(c)) {
            val u = usage(c, startOfDay(), System.currentTimeMillis()).toMap()
            limits(c).forEachIndexed { i, l -> val used = u.entries.firstOrNull { it.key.contains(l.getString("app"), true) }?.value ?: 0L
                val k = "lim_${dayKey()}_${l.getString("app")}"
                if (used >= l.getInt("min") * 60000L && !sp(c).getBoolean(k, false)) { sp(c).edit().putBoolean(k, true).apply()
                    notify(c, 600 + i, "⏳ ${l.getString("app")} limit poori", "Aaj ${hm(used)} ho gaye (limit ${l.getInt("min")}m). Thoda break lo.", null, null); Speaker.say(c, "${l.getString("app")} ki limit poori ho gayi") } }
        }
    }

    private fun wa(c: Context, phone: String, text: String, rc: Int): PendingIntent =
        PendingIntent.getActivity(c, rc, Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=${Contacts.intl(phone)}&text=" + Uri.encode(text)))
            .setPackage(Prefs.waPkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notify(c: Context, id: Int, title: String, text: String, action: PendingIntent?, label: String?) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Business & personal", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 9000 + id, Intent(c, TasksActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_mono).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open)
        if (action != null && label != null) b.addAction(Notification.Action.Builder(null, label, action).build())
        nm.notify(9000 + id, b.build())
    }

    class Alarm : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pr = goAsync()
            Thread { try { Prefs.init(c); tick(c) } catch (_: Exception) {} finally { schedule(c); pr.finish() } }.start()
        }
    }

    // ======================= LLM TOOLS =======================
    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    private fun fn(name: String, desc: String, props: JSONObject, req: List<String> = listOf()) = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun strArr(d: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", d)

    fun defs(a: JSONArray) {
        a.put(fn("due_add", "Kisi se paisa lena baaki (payment follow-up). Har every_days din subah notification + WhatsApp reminder button.",
            JSONObject().put("name", p("string", "party/contact naam")).put("amount", p("number", "₹")).put("note", p("string", "invoice/kaam, optional"))
                .put("every_days", p("integer", "kitne din mein yaad, default 7")).put("first_date", p("string", "yyyy-MM-dd pehli baar, optional")), listOf("name", "amount")))
        a.put(fn("due_paid", "Party ne payment diya — poora ya kuch amount.", JSONObject().put("name", p("string", "naam")).put("amount", p("number", "kitna mila, khali = poora")), listOf("name")))
        a.put(fn("due_list", "Kis-kis se kitna lena baaki hai.", JSONObject()))
        a.put(fn("due_remind_now", "Abhi us party ko WhatsApp payment reminder bhejo (confirm card ke saath).", JSONObject().put("name", p("string", "naam")), listOf("name")))
        a.put(fn("expense_add", "Kharcha likho. category: petrol, food, grocery, bills, shopping, travel, medical, business, family, other.",
            JSONObject().put("amount", p("number", "₹")).put("category", p("string", "category")).put("note", p("string", "kis cheez ka")).put("date", p("string", "yyyy-MM-dd, default aaj")), listOf("amount")))
        a.put(fn("expense_report", "Kharche ka hisaab. period: today | yesterday | week | month | last_month.",
            JSONObject().put("period", p("string", "default month")).put("category", p("string", "optional"))))
        a.put(fn("expense_from_sms", "Bank/UPI debit SMS padh ke kharche apne aap jodo.", JSONObject().put("days", p("integer", "kitne din pichhe, default 7"))))
        a.put(fn("expense_delete_last", "Aakhri kharcha entry hatao (galti se juda ho to).", JSONObject()))
        a.put(fn("bill_add", "Har mahine aane wala bill/EMI (bijli, mobile, rent, EMI, SIP). day = mahine ki tarikh.",
            JSONObject().put("name", p("string", "bill naam")).put("day", p("integer", "1-31")).put("amount", p("number", "optional")).put("before", p("integer", "kitne din pehle yaad, default 1")), listOf("name", "day")))
        a.put(fn("bill_paid", "Is mahine ka bill bhar diya.", JSONObject().put("name", p("string", "bill naam")), listOf("name")))
        a.put(fn("bill_list", "Bills/EMI list + is mahine paid ya nahi.", JSONObject()))
        a.put(fn("bill_remove", "Bill hatao.", JSONObject().put("name", p("string", "bill naam")), listOf("name")))
        a.put(fn("vault_save", "Abhi bheji gayi photo ko document vault mein label ke saath save karo (PAN, Aadhaar, GST certificate, RC...).",
            JSONObject().put("label", p("string", "document naam")), listOf("label")))
        a.put(fn("vault_list", "Vault mein saved documents.", JSONObject()))
        a.put(fn("vault_send", "Vault document (ya 'last photo') kisi contact ko WhatsApp karo; contact khali = share menu.",
            JSONObject().put("label", p("string", "document naam")).put("contact", p("string", "naam/number, optional")), listOf("label")))
        a.put(fn("vault_delete", "Vault se document hatao.", JSONObject().put("label", p("string", "naam")), listOf("label")))
        a.put(fn("birthdays", "Aane wale birthdays (contacts + calendar).", JSONObject().put("days", p("integer", "kitne din aage, default 7"))))
        a.put(fn("birthday_wish", "Kisi ko birthday wish WhatsApp karo (custom message optional).",
            JSONObject().put("name", p("string", "naam")).put("message", p("string", "optional")), listOf("name")))
        a.put(fn("screen_time", "Phone screen time / kaunsi app pe kitna time. day: today | yesterday.", JSONObject().put("day", p("string", "default today"))))
        a.put(fn("app_limit", "Kisi app ki roz ki limit set/hatao (minutes=0 hatao). report=true/false raat 9:30 screen-time report.",
            JSONObject().put("app", p("string", "app naam")).put("minutes", p("integer", "minutes/din")).put("report", p("boolean", "daily report"))))
        a.put(fn("shortcut_run", "Saved shortcut/mode chalao (jaise 'office mode'). Result mein steps aayenge — unhe tools se ek-ek karke karo.",
            JSONObject().put("name", p("string", "shortcut naam")), listOf("name")))
        a.put(fn("shortcut_save", "Naya shortcut banao/badlo: naam + steps (har step ek chhota hukum).",
            JSONObject().put("name", p("string", "naam")).put("steps", strArr("hukum list")), listOf("name", "steps")))
        a.put(fn("shortcut_list", "Saved shortcuts.", JSONObject()))
        a.put(fn("shortcut_delete", "Shortcut hatao.", JSONObject().put("name", p("string", "naam")), listOf("name")))
        a.put(fn("contact_save", "Phone mein naya contact save karo (visiting card se).",
            JSONObject().put("name", p("string", "naam")).put("phone", p("string", "number")).put("company", p("string", "company"))
                .put("title", p("string", "designation")).put("email", p("string", "email")).put("note", p("string", "address/GSTIN/website")), listOf("name")))
    }

    fun run(h: ToolHost, name: String, a: JSONObject): String? {
        val c = h.ctx
        return when (name) {
            "due_add" -> dueAdd(h, a)
            "due_paid" -> duePaid(c, a.getString("name"), if (a.has("amount")) a.getDouble("amount") else null)
            "due_list" -> dues(c).let { l -> if (l.isEmpty()) "Kisi se kuch baaki nahi." else "💰 Lena baaki: ${inr(l.sumOf { it.getDouble("amount") })}\n" +
                l.sortedByDescending { it.getDouble("amount") }.joinToString("\n") { "• ${it.getString("name")}: ${inr(it.getDouble("amount"))}${it.optString("note").takeIf { n -> n.isNotBlank() }?.let { n -> " ($n)" } ?: ""} · agla reminder ${D.format(Date(it.getLong("next")))}" } }
            "due_remind_now" -> {
                val o = dues(c).firstOrNull { it.getString("name").contains(a.getString("name").trim(), true) } ?: return "'${a.getString("name")}' ka koi baaki nahi."
                Tools.run(h, "whatsapp_send", JSONObject().put("contact", o.optString("phone").ifBlank { o.getString("name") }).put("message", dueText(o)))
            }
            "expense_add" -> { val t = a.optString("date").takeIf { it.length == 10 }?.let { Extra.parseTime("$it 12:00") } ?: System.currentTimeMillis()
                expAdd(c, a.getDouble("amount"), a.optString("category", "other"), a.optString("note"), t); "DONE: ${inr(a.getDouble("amount"))} ${a.optString("category", "other")} likha. Is mahine total ${inr(monthTotal(c))}." }
            "expense_report" -> expReport(c, a.optString("period", "month"), a.optString("category"))
            "expense_from_sms" -> expFromSms(c, a.optInt("days", 7))
            "expense_delete_last" -> expenses(c).let { l -> if (l.isEmpty()) "Koi entry nahi." else { save(c, "exp", l.dropLast(1)); "DONE: ${inr(l.last().getDouble("amt"))} ${l.last().optString("note")} hataya." } }
            "bill_add" -> { val l = bills(c).filterNot { it.getString("name").equals(a.getString("name"), true) }.toMutableList()
                val o = JSONObject().put("name", a.getString("name").trim()).put("day", a.getInt("day").coerceIn(1, 31)).put("amount", a.optDouble("amount", 0.0)).put("before", a.optInt("before", 1))
                l.add(o); save(c, "bills", l); schedule(c); "DONE: ${o.getString("name")} — har mahine ${o.getInt("day")} tarikh, ${o.getInt("before")} din pehle + usi din subah 8:30 yaad. Agla: ${D.format(Date(nextBillDate(o)))}." }
            "bill_paid" -> bills(c).firstOrNull { it.getString("name").contains(a.getString("name").trim(), true) }?.let { sp(c).edit().putBoolean(paidKey(it), true).apply(); "DONE: ${it.getString("name")} is mahine paid ✅" } ?: "Wo bill nahi mila."
            "bill_list" -> bills(c).let { l -> if (l.isEmpty()) "Koi bill set nahi." else l.sortedBy { nextBillDate(it) }.joinToString("\n") {
                "🧾 ${it.getString("name")} — ${it.getInt("day")} tarikh${if (it.optDouble("amount", 0.0) > 0) " · " + inr(it.getDouble("amount")) else ""} · ${if (billPaid(c, it)) "is mahine paid ✅" else "agla ${D.format(Date(nextBillDate(it)))}"}" } }
            "bill_remove" -> bills(c).let { l -> val k = l.filterNot { it.getString("name").contains(a.getString("name").trim(), true) }; save(c, "bills", k); if (k.size < l.size) "DONE: hataya." else "Nahi mila." }
            "vault_save" -> vaultSave(c, a.getString("label"))
            "vault_list" -> vault(c).let { l -> if (l.isEmpty()) "Vault khaali. 📎 se photo bhejo aur bolo 'PAN card naam se save karo'." else "🔐 Vault: " + l.joinToString(", ") { it.getString("label") } }
            "vault_send" -> vaultSend(h, a.getString("label"), a.optString("contact"))
            "vault_delete" -> vault(c).let { l -> val hit = l.filter { it.getString("label").contains(a.getString("label").trim(), true) }
                hit.forEach { File(vaultDir(c), it.getString("file")).delete() }; save(c, "vault", l - hit.toSet()); if (hit.isEmpty()) "Nahi mila." else "DONE: hataya." }
            "birthdays" -> birthdays(c, a.optInt("days", 7)).let { l -> if (l.isEmpty()) "Agle ${a.optInt("days", 7)} din koi birthday nahi (contacts/calendar mein)." else
                l.joinToString("\n") { "🎂 ${it.name} — ${TasksActivity.dayLabel(it.date)}${if (it.phone == null) " (number nahi)" else ""}" } }
            "birthday_wish" -> Tools.run(h, "whatsapp_send", JSONObject().put("contact", a.getString("name")).put("message", a.optString("message").ifBlank { wish(a.getString("name")) }))
            "screen_time" -> screenTime(c, a.optString("day", "today"))
            "app_limit" -> {
                if (a.has("report")) setUsageReport(c, a.getBoolean("report"))
                if (a.optString("app").isNotBlank()) {
                    val l = limits(c).filterNot { it.getString("app").equals(a.getString("app"), true) }.toMutableList()
                    if (a.optInt("minutes", 0) > 0) l.add(JSONObject().put("app", a.getString("app").trim()).put("min", a.getInt("minutes")))
                    save(c, "limits", l); schedule(c)
                }
                (if (!usageAllowed(c)) "⚠️ Usage access OFF — Settings ⚙️ → 5 se ON karo, tab hi kaam karega.\n" else "") +
                    "DONE: limits: ${limits(c).joinToString { "${it.getString("app")} ${it.getInt("min")}m" }.ifEmpty { "koi nahi" }}; raat report ${if (usageReportOn(c)) "ON 9:30" else "OFF"}."
            }
            "shortcut_run" -> { val s = shortcuts(c); val k = s.keys.firstOrNull { a.getString("name").lowercase().contains(it) || it.contains(a.getString("name").lowercase().trim()) }
                if (k == null) "'${a.getString("name")}' shortcut nahi mila. Saved: ${s.keys.joinToString()}" else
                    "SHORTCUT '$k' — ab ye steps ek-ek karke sahi tools se karo, phir 1 line summary:\n" + s[k]!!.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n") }
            "shortcut_save" -> { val st = a.getJSONArray("steps").let { l -> (0 until l.length()).map { l.getString(it) } }; scSave(c, a.getString("name"), st)
                "DONE: shortcut '${a.getString("name").lowercase()}' — ${st.size} steps. Bolo \"${a.getString("name")}\" aur sab ho jayega." }
            "shortcut_list" -> shortcuts(c).entries.joinToString("\n") { "⚡ ${it.key}: ${it.value.joinToString(" → ")}" }
            "shortcut_delete" -> { scSave(c, a.getString("name"), null); "DONE: hataya." }
            "contact_save" -> contactSave(h, a)
            else -> null
        }
    }
}
