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
        lastNear = emptyList()
        val list = query(ctx, q, isEmail = false) ?: return Resolved.Err(NO_PERM)
        return pick(q, list, "phone number")
    }

    fun email(ctx: Context, q: String): Resolved {
        if (q.contains("@")) return Resolved.One(Ct(q, q.trim()))
        lastNear = emptyList()
        val list = query(ctx, q, isEmail = true) ?: return Resolved.Err(NO_PERM)
        return pick(q, list, "email")
    }

    private fun pick(q: String, list: List<Ct>, what: String): Resolved {
        if (list.isEmpty()) {
            val near = lastNear; lastNear = emptyList()
            return Resolved.Err("Contacts mein '$q' ka $what nahi mila." +
                (if (near.isNotEmpty()) " Milte-julte naam: ${near.joinToString(", ")} — user se poochho inme se koi hai kya." else " User se saved naam ya number poochho."))
        }
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
        // 1. substring (raw or transliterated)
        val nqT = norm(translit(q))
        all.filter { val nn = norm(it.name); nn.contains(nq) || (nqT.length >= 3 && norm(translit(it.name)).contains(nqT)) }
            .let { if (it.isNotEmpty()) return it }
        // 2. relation aliases
        val group = ALIASES.firstOrNull { g -> g.any { norm(it) == nq } }
        if (group != null) {
            val terms = group.map { norm(it) }.filter { it.length >= 2 }
            all.filter { ct -> val nn = norm(ct.name); val words = ct.name.lowercase().split(Regex("\\s+")).map { norm(it) }
                terms.any { t -> if (t.length <= 3) words.contains(t) else nn.contains(t) } }
                .let { if (it.isNotEmpty()) return it }
        }
        // 3. word-by-word fuzzy score (handles "Dhruv Sabarmati" vs "Dhruv Patel Sabarmati", voice typos, Hindi script)
        val qw = words(q)
        if (qw.isEmpty()) return emptyList()
        val scored = all.map { it to score(qw, words(it.name)) }.filter { it.second > 0.0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        // need at least half the query words to match
        if (best < qw.size / 2.0 || best < 0.75) { lastNear = scored.sortedByDescending { it.second }.map { it.first.name }.distinct().take(4); return emptyList() }
        return scored.filter { it.second >= best - 0.01 }.map { it.first }
    }

    /** Close-but-not-enough names from the last failed search, to suggest to the user. */
    @Volatile var lastNear: List<String> = emptyList()

    private fun words(s: String) = translit(s).lowercase().split(Regex("[^\\p{L}\\p{N}]+")).map { norm(it) }.filter { it.length >= 2 }

    private fun wordMatch(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length >= 3 && (b.startsWith(a) || a.startsWith(b) && b.length >= 3)) return 0.9
        val m = maxOf(a.length, b.length)
        val d = dist(a, b)
        val tol = if (m >= 7) 2 else if (m >= 4) 1 else 0
        return if (d <= tol) 0.75 else 0.0
    }

    private fun score(qw: List<String>, cw: List<String>) = qw.sumOf { q -> cw.maxOfOrNull { wordMatch(q, it) } ?: 0.0 }

    private val DEVA = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऋ' to "ri",
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ृ' to "ri", 'ं' to "n", 'ँ' to "n",
        // Gujarati
        'અ' to "a", 'આ' to "a", 'ઇ' to "i", 'ઈ' to "i", 'ઉ' to "u", 'એ' to "e", 'ઓ' to "o",
        'ક' to "k", 'ખ' to "kh", 'ગ' to "g", 'ઘ' to "gh", 'ચ' to "ch", 'છ' to "chh", 'જ' to "j", 'ઝ' to "jh",
        'ટ' to "t", 'ઠ' to "th", 'ડ' to "d", 'ઢ' to "dh", 'ણ' to "n", 'ત' to "t", 'થ' to "th", 'દ' to "d", 'ધ' to "dh", 'ન' to "n",
        'પ' to "p", 'ફ' to "f", 'બ' to "b", 'ભ' to "bh", 'મ' to "m", 'ય' to "y", 'ર' to "r", 'લ' to "l", 'વ' to "v", 'શ' to "sh", 'ષ' to "sh", 'સ' to "s", 'હ' to "h", 'ળ' to "l",
        'ા' to "a", 'િ' to "i", 'ી' to "i", 'ુ' to "u", 'ૂ' to "u", 'ે' to "e", 'ૈ' to "ai", 'ો' to "o", 'ૌ' to "au", 'ં' to "n",
    )

    /** Rough Devanagari/Gujarati → Latin so "ध्रुव" matches "Dhruv". Inherent 'a' dropped (Dhruv, not Dhruva). */
    fun translit(s: String): String {
        if (s.none { it.code in 0x0900..0x0AFF }) return s
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '्' || ch == '્' || ch == '़' || ch == '઼' -> {}
                DEVA.containsKey(ch) -> sb.append(DEVA[ch])
                ch.code in 0x0900..0x0AFF -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
    }
}
