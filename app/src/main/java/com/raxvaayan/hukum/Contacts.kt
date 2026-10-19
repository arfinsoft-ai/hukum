package com.raxvaayan.hukum

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone

data class Ct(val name: String, val value: String)

/** Result of resolving a contact: either one match or an error message for the LLM. */
sealed class Resolved {
    data class One(val ct: Ct) : Resolved()
    data class Err(val msg: String) : Resolved()
}

object Contacts {
    private fun digits(s: String) = s.filter { it.isDigit() }

    fun looksLikeNumber(q: String) = digits(q).length >= 6 && q.count { it.isLetter() } == 0

    /** Normalise to international digits for WhatsApp (assume India for 10-digit numbers). */
    fun intl(num: String): String {
        var d = digits(num)
        if (d.startsWith("00")) d = d.substring(2)
        if (d.length == 11 && d.startsWith("0")) d = d.substring(1)
        if (d.length == 10) d = "91$d"
        return d
    }

    fun phone(ctx: Context, q: String): Resolved {
        if (looksLikeNumber(q)) return Resolved.One(Ct(q, q))
        val list = query(ctx, q, isEmail = false)
        return pick(q, list, "phone number")
    }

    fun email(ctx: Context, q: String): Resolved {
        if (q.contains("@")) return Resolved.One(Ct(q, q.trim()))
        val list = query(ctx, q, isEmail = true)
        return pick(q, list, "email")
    }

    private fun pick(q: String, list: List<Ct>, what: String): Resolved {
        if (list.isEmpty()) return Resolved.Err("Contacts mein '$q' ka $what nahi mila. User se poochho.")
        val names = list.map { it.name }.distinct()
        if (names.size == 1) return Resolved.One(list[0])
        val exact = list.filter { it.name.equals(q, ignoreCase = true) }
        if (exact.isNotEmpty()) return Resolved.One(exact[0])
        return Resolved.Err("'$q' ke multiple contacts mile: ${names.take(6).joinToString(", ")}. User se poochho kaunsa.")
    }

    private fun query(ctx: Context, q: String, isEmail: Boolean): List<Ct> {
        val uri = if (isEmail) Email.CONTENT_URI else Phone.CONTENT_URI
        val nameCol = if (isEmail) Email.DISPLAY_NAME_PRIMARY else Phone.DISPLAY_NAME
        val valCol = if (isEmail) Email.ADDRESS else Phone.NUMBER
        val out = ArrayList<Ct>()
        val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(uri, arrayOf(nameCol, valCol), "$nameCol LIKE ?", arrayOf("%$q%"), "$nameCol ASC")
                ?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(0) ?: continue
                        val v = c.getString(1) ?: continue
                        val key = n + "|" + (if (isEmail) v.lowercase() else digits(v).takeLast(10))
                        if (seen.add(key)) out.add(Ct(n, v))
                    }
                }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }
}
