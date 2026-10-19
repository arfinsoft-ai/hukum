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

    private const val NO_PERM = "Contacts permission nahi hai — user ko bolo ⚙️ Settings → step 1 Permissions allow kare."

    /** Relation words people use instead of the saved name. */
    private val ALIASES = listOf(
        listOf("papa", "pappa", "papaji", "pappaji", "dad", "daddy", "father", "pitaji", "bapuji", "પપ્પા", "पापा", "पिताजी", "bapu"),
        listOf("mummy", "mumma", "mumy", "mom", "mother", "maa", "mammi", "mammy", "મમ્મી", "मम्मी", "मां", "बा", "ba"),
        listOf("bhai", "bhaiya", "brother", "bro", "ભાઈ", "भाई"),
        listOf("didi", "behen", "ben", "sister", "દીદી", "बहन", "दीदी"),
        listOf("wife", "patni", "biwi", "mrs", "home", "ghar"),
        listOf("husband", "pati", "hubby"),
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun dist(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    fun phone(ctx: Context, q: String): Resolved {
        if (looksLikeNumber(q)) return Resolved.One(Ct(q, q))
        val list = query(ctx, q, isEmail = false) ?: return Resolved.Err(NO_PERM)
        return pick(q, list, "phone number")
    }

    fun email(ctx: Context, q: String): Resolved {
        if (q.contains("@")) return Resolved.One(Ct(q, q.trim()))
        val list = query(ctx, q, isEmail = true) ?: return Resolved.Err(NO_PERM)
        return pick(q, list, "email")
    }

    private fun pick(q: String, list: List<Ct>, what: String): Resolved {
        if (list.isEmpty()) return Resolved.Err("Contacts mein '$q' ka $what nahi mila. User se saved naam ya number poochho.")
        val names = list.map { it.name }.distinct()
        if (names.size == 1) return Resolved.One(list[0])
        val exact = list.filter { norm(it.name) == norm(q) }
        if (exact.isNotEmpty()) return Resolved.One(exact[0])
        return Resolved.Err("'$q' ke multiple contacts mile: ${names.take(6).joinToString(", ")}. User se poochho kaunsa.")
    }

    /** null = no permission. Exact/substring match first, then relation aliases, then typo-tolerant match. */
    private fun query(ctx: Context, q: String, isEmail: Boolean): List<Ct>? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val uri = if (isEmail) Email.CONTENT_URI else Phone.CONTENT_URI
        val nameCol = if (isEmail) Email.DISPLAY_NAME_PRIMARY else Phone.DISPLAY_NAME
        val valCol = if (isEmail) Email.ADDRESS else Phone.NUMBER
        val all = ArrayList<Ct>()
        val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(uri, arrayOf(nameCol, valCol), null, null, "$nameCol ASC")?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val v = c.getString(1) ?: continue
                    val key = n + "|" + (if (isEmail) v.lowercase() else digits(v).takeLast(10))
                    if (seen.add(key)) all.add(Ct(n, v))
                }
            }
        } catch (e: SecurityException) {
            return null
        }
        val nq = norm(q)
        if (nq.isEmpty()) return emptyList()
        // 1. substring
        all.filter { norm(it.name).contains(nq) }.let { if (it.isNotEmpty()) return it }
        // 2. relation aliases
        val group = ALIASES.firstOrNull { g -> g.any { norm(it) == nq } }
        if (group != null) {
            val terms = group.map { norm(it) }.filter { it.length >= 2 }
            all.filter { ct -> val nn = norm(ct.name); val words = ct.name.lowercase().split(Regex("\\s+")).map { norm(it) }
                terms.any { t -> if (t.length <= 3) words.contains(t) else nn.contains(t) } }
                .let { if (it.isNotEmpty()) return it }
        }
        // 3. typo tolerant per word
        val maxD = if (nq.length >= 7) 2 else if (nq.length >= 4) 1 else 0
        if (maxD == 0) return emptyList()
        return all.filter { ct -> ct.name.split(Regex("\\s+")).any { w -> val nw = norm(w); nw.isNotEmpty() && dist(nw, nq) <= maxD } }
    }
}
