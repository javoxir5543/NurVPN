package com.nurvpn.app.util

object CountryLookup {
    val MAP = mapOf(
        "us" to "🇺🇸 AQSH", "de" to "🇩🇪 Germaniya", "nl" to "🇳🇱 Niderlandiya",
        "fr" to "🇫🇷 Fransiya", "gb" to "🇬🇧 Buyuk Britaniya", "uk" to "🇬🇧 Buyuk Britaniya",
        "ru" to "🇷🇺 Rossiya", "kz" to "🇰🇿 Qozog'iston", "tr" to "🇹🇷 Turkiya",
        "ae" to "🇦🇪 BAA", "jp" to "🇯🇵 Yaponiya", "kr" to "🇰🇷 Koreya",
        "sg" to "🇸🇬 Singapur", "in" to "🇮🇳 Hindiston", "fi" to "🇫🇮 Finlyandiya",
        "se" to "🇸🇪 Shvetsiya", "no" to "🇳🇴 Norvegiya", "ch" to "🇨🇭 Shveytsariya",
        "pl" to "🇵🇱 Polsha", "ua" to "🇺🇦 Ukraina", "ca" to "🇨🇦 Kanada",
        "au" to "🇦🇺 Avstraliya", "br" to "🇧🇷 Braziliya", "hk" to "🇭🇰 Gonkong"
    )

    fun lookup(host: String?): Array<String> {
        if (host.isNullOrEmpty()) return arrayOf("", "🌍 Noma'lum")
        val h = host.lowercase()
        val tld = h.substringAfterLast('.', "")
        if (tld.length == 2 && tld.all { it.isLetter() }) {
            val name = MAP[tld] ?: "🌍 " + tld.uppercase()
            return arrayOf(tld.uppercase(), name)
        }
        if (h.contains("germany")) return arrayOf("DE", "🇩🇪 Germaniya")
        if (h.contains("turk")) return arrayOf("TR", "🇹🇷 Turkiya")
        if (h.contains("america")) return arrayOf("US", "🇺🇸 AQSH")
        if (h.contains("london")) return arrayOf("GB", "🇬🇧 Buyuk Britaniya")
        return arrayOf("", "🌍 " + h)
    }

    /** Remark dan emoji bayroqni ajratib olish (🇨🇦, 🇩🇪 va h.k.). */
    fun flagFromRemark(remark: String?): String {
        if (remark.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < remark.length) {
            val cp = remark.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) {
                sb.appendCodePoint(cp)
                i += Character.charCount(cp)
            } else if (sb.isNotEmpty()) break
            else i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Remark dan davlat kodini olish (🇨🇦 → CA). */
    fun ccFromRemark(remark: String?): String {
        if (remark.isNullOrEmpty()) return ""
        var i = 0
        val codes = mutableListOf<Int>()
        while (i < remark.length && codes.size < 2) {
            val cp = remark.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) {
                codes.add(cp - 0x1F1E6)
                i += Character.charCount(cp)
            } else if (codes.isEmpty()) {
                i += Character.charCount(cp)
            } else break
        }
        if (codes.size != 2) return ""
        return "${('A' + codes[0])}${('A' + codes[1])}"
    }
}
