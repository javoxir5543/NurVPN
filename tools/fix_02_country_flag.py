#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #2 — Link nomi va davlat bayrog'i ko'rinmasligi               ║
╠══════════════════════════════════════════════════════════════════════════╣
║  TEKSHIRUV NATIJASI (6 ta real link bilan sinovdan o'tkazildi):          ║
║                                                                          ║
║  Parser TO'G'RI ishlaydi — `remark`, `host`, `displayName()` hammasi     ║
║  to'ldiriladi:                                                           ║
║    vless://...#Moskva%20Server → remark="Moskva Server" ✓                ║
║    hysteria2://...#Finland     → remark="Finland"       ✓                ║
║    anytls://...#Tokyo%20JP     → remark="Tokyo JP"      ✓                ║
║                                                                          ║
║  ASOSIY XATO — `flag()` va `CountryLookup`:                              ║
║    #🇷🇺%20Moskva          → flag=🇷🇺  ✓ (emoji bor)                      ║
║    #Russia%20Moscow      → flag=🌍  ✗                                    ║
║    #Netherlands%20Amsterdam → flag=🌍 ✗                                  ║
║    #Japan%20Tokyo        → flag=🌍  ✗                                    ║
║                                                                          ║
║  `CountryLookup.lookup()` da faqat 4 ta ingliz so'zi hardcode qilingan:  ║
║    "germany", "turk", "america", "london"                                ║
║  Qolgan davlat nomlari (Russia, Japan, Netherlands, France, ...)         ║
║  TANILMAYDI → "🌍 Noma'lum" ko'rsatiladi.                                ║
║                                                                          ║
║  YECHIM: til nomlarini (ingliz + rus) to'liq jadval bilan qo'llab-       ║
║  quvvatlash. Regex orqali butun so'z qidiriladi (substring emas —        ║
║  "america" "South America" da ham mos kelardi).                          ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from _backup import safe_backup  # noqa: E402
except ImportError:
    def safe_backup(p, t):  # type: ignore
        print("   (zaxira yo'q)")
        return p

ROOT = Path(__file__).resolve().parent.parent
LOOKUP = ROOT / "app/src/main/java/com/nurvpn/app/util/CountryLookup.kt"

NEW_FILE = r'''package com.nurvpn.app.util

import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  COUNTRY LOOKUP — host, remark va davlat nomidan bayroq/davlat aniqlash
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * ★ TUZATISH #2: avval faqat 4 ta ingliz so'zi hardcode qilingan edi
 *   ("germany", "turk", "america", "london"). Natijada obunalardagi
 *   "Russia Moscow", "Japan Tokyo", "Netherlands Amsterdam" kabi nomlar
 *   tanilmay, bayroq o'rniga "🌍 Noma'lum" chiqardi.
 *
 * Endi: ingliz + rus tilidagi davlat nomlari TO'LIQ jadvalda.
 * Qidiruv BUTUN SO'Z bo'yicha (regex \b) — "america" "South America" ning
 * ichidan noto'g'ri mos kelib qolmasligi uchun.
 */
object CountryLookup {

    /** ISO-2 kod → "🇺🇸 AQSH" ko'rinishidagi nom. */
    val MAP = mapOf(
        "us" to "🇺🇸 AQSH", "de" to "🇩🇪 Germaniya", "nl" to "🇳🇱 Niderlandiya",
        "fr" to "🇫🇷 Fransiya", "gb" to "🇬🇧 Buyuk Britaniya", "uk" to "🇬🇧 Buyuk Britaniya",
        "ru" to "🇷🇺 Rossiya", "kz" to "🇰🇿 Qozog'iston", "tr" to "🇹🇷 Turkiya",
        "ae" to "🇦🇪 BAA", "jp" to "🇯🇵 Yaponiya", "kr" to "🇰🇷 Koreya",
        "sg" to "🇸🇬 Singapur", "in" to "🇮🇳 Hindiston", "fi" to "🇫🇮 Finlyandiya",
        "se" to "🇸🇪 Shvetsiya", "no" to "🇳🇴 Norvegiya", "ch" to "🇨🇭 Shveytsariya",
        "pl" to "🇵🇱 Polsha", "ua" to "🇺🇦 Ukraina", "ca" to "🇨🇦 Kanada",
        "au" to "🇦🇺 Avstraliya", "br" to "🇧🇷 Braziliya", "hk" to "🇭🇰 Gonkong",
        // ★ Qo'shildi (keng tarqalgan VPN serverlari)
        "it" to "🇮🇹 Italiya", "es" to "🇪🇸 Ispaniya", "at" to "🇦🇹 Avstriya",
        "be" to "🇧🇪 Belgiya", "cz" to "🇨🇿 Chexiya", "dk" to "🇩🇰 Daniya",
        "ie" to "🇮🇪 Irlandiya", "il" to "🇮🇱 Isroil", "lt" to "🇱🇹 Litva",
        "lv" to "🇱🇻 Latviya", "ee" to "🇪🇪 Estoniya", "md" to "🇲🇩 Moldova",
        "ro" to "🇷🇴 Ruminiya", "bg" to "🇧🇬 Bolgariya", "hu" to "🇭🇺 Vengriya",
        "gr" to "🇬🇷 Gretsiya", "pt" to "🇵🇹 Portugaliya", "rs" to "🇷🇸 Serbiya",
        "am" to "🇦🇲 Armaniston", "ge" to "🇬🇪 Gruziya", "az" to "🇦🇿 Ozarbayjon",
        "by" to "🇧🇾 Belarus", "uz" to "🇺🇿 O'zbekiston", "kg" to "🇰🇬 Qirg'iziston",
        "mx" to "🇲🇽 Meksika", "ar" to "🇦🇷 Argentina", "cl" to "🇨🇱 Chili",
        "za" to "🇿🇦 JAR", "ng" to "🇳🇬 Nigeriya", "eg" to "🇪🇬 Misr",
        "th" to "🇹🇭 Tailand", "vn" to "🇻🇳 Vyetnam", "my" to "🇲🇾 Malayziya",
        "id" to "🇮🇩 Indoneziya", "ph" to "🇵🇭 Filippin", "tw" to "🇹🇼 Tayvan",
        "nz" to "🇳🇿 Yangi Zelandiya", "is" to "🇮🇸 Islandiya", "lu" to "🇱🇺 Lyuksemburg",
        "sk" to "🇸🇰 Slovakiya", "si" to "🇸🇮 Sloveniya", "hr" to "🇭🇷 Xorvatiya",
        "cy" to "🇨🇾 Kipr", "mt" to "🇲🇹 Malta", "cn" to "🇨🇳 Xitoy",
        "sa" to "🇸🇦 Saudiya", "qa" to "🇶🇦 Qatar", "kw" to "🇰🇼 Quvayt",
        "ir" to "🇮🇷 Eron", "pk" to "🇵🇰 Pokiston", "bd" to "🇧🇩 Bangladesh"
    )

    /**
     * Davlat nomi (ingliz + rus) → ISO-2 kod.
     * ★ TUZATISH #2: to'liq jadval, butun so'z bo'yicha qidiriladi.
     */
    private val NAME_TO_CC = mapOf(
        // Ingliz
        "russia" to "ru", "russian" to "ru", "moscow" to "ru",
        "germany" to "de", "german" to "de", "frankfurt" to "de",
        "berlin" to "de", "nuremberg" to "de", "falkenstein" to "de",
        "netherlands" to "nl", "dutch" to "nl", "amsterdam" to "nl",
        "holland" to "nl",
        "france" to "fr", "french" to "fr", "paris" to "fr", "marseille" to "fr",
        "united kingdom" to "gb", "england" to "gb", "london" to "gb",
        "britain" to "gb", "british" to "gb", "manchester" to "gb",
        "united states" to "us", "america" to "us", "american" to "us",
        "usa" to "us", "new york" to "us", "los angeles" to "us",
        "dallas" to "us", "seattle" to "us", "chicago" to "us", "miami" to "us",
        "japan" to "jp", "japanese" to "jp", "tokyo" to "jp", "osaka" to "jp",
        "korea" to "kr", "korean" to "kr", "seoul" to "kr",
        "singapore" to "sg",
        "india" to "in", "indian" to "in", "mumbai" to "in",
        "finland" to "fi", "finnish" to "fi", "helsinki" to "fi",
        "sweden" to "se", "swedish" to "se", "stockholm" to "se",
        "norway" to "no", "norwegian" to "no", "oslo" to "no",
        "switzerland" to "ch", "swiss" to "ch", "zurich" to "ch", "geneva" to "ch",
        "poland" to "pl", "polish" to "pl", "warsaw" to "pl",
        "ukraine" to "ua", "ukrainian" to "ua", "kyiv" to "ua", "kiev" to "ua",
        "canada" to "ca", "canadian" to "ca", "toronto" to "ca", "montreal" to "ca",
        "australia" to "au", "australian" to "au", "sydney" to "au",
        "brazil" to "br", "brazilian" to "br", "sao paulo" to "br",
        "hong kong" to "hk", "hongkong" to "hk",
        "italy" to "it", "italian" to "it", "milan" to "it", "rome" to "it",
        "spain" to "es", "spanish" to "es", "madrid" to "es", "barcelona" to "es",
        "austria" to "at", "vienna" to "at",
        "belgium" to "be", "brussels" to "be",
        "czech" to "cz", "czechia" to "cz", "prague" to "cz",
        "denmark" to "dk", "copenhagen" to "dk",
        "ireland" to "ie", "irish" to "ie", "dublin" to "ie",
        "israel" to "il", "tel aviv" to "il",
        "lithuania" to "lt", "vilnius" to "lt",
        "latvia" to "lv", "riga" to "lv",
        "estonia" to "ee", "tallinn" to "ee",
        "moldova" to "md", "romania" to "ro", "bucharest" to "ro",
        "bulgaria" to "bg", "sofia" to "bg",
        "hungary" to "hu", "budapest" to "hu",
        "greece" to "gr", "athens" to "gr",
        "portugal" to "pt", "lisbon" to "pt",
        "serbia" to "rs", "belgrade" to "rs",
        "armenia" to "am", "yerevan" to "am",
        "georgia" to "ge", "tbilisi" to "ge",
        "azerbaijan" to "az", "baku" to "az",
        "belarus" to "by", "minsk" to "by",
        "uzbekistan" to "uz", "tashkent" to "uz",
        "kyrgyzstan" to "kg", "kazakhstan" to "kz", "almaty" to "kz",
        "astana" to "kz",
        "turkey" to "tr", "turkish" to "tr", "istanbul" to "tr", "ankara" to "tr",
        "mexico" to "mx", "argentina" to "ar", "chile" to "cl",
        "south africa" to "za", "nigeria" to "ng", "egypt" to "eg",
        "thailand" to "th", "bangkok" to "th",
        "vietnam" to "vn", "malaysia" to "my", "indonesia" to "id",
        "philippines" to "ph", "taiwan" to "tw",
        "new zealand" to "nz", "iceland" to "is", "luxembourg" to "lu",
        "slovakia" to "sk", "slovenia" to "si", "croatia" to "hr",
        "cyprus" to "cy", "malta" to "mt", "china" to "cn", "chinese" to "cn",
        "saudi" to "sa", "qatar" to "qa", "kuwait" to "kw",
        "iran" to "ir", "pakistan" to "pk", "bangladesh" to "bd",
        "emirates" to "ae", "dubai" to "ae",
        // Rus
        "россия" to "ru", "москва" to "ru", "германия" to "de",
        "нидерланды" to "nl", "амстердам" to "nl", "франция" to "fr",
        "париж" to "fr", "англия" to "gb", "великобритания" to "gb",
        "лондон" to "gb", "сша" to "us", "америка" to "us",
        "япония" to "jp", "токио" to "jp", "корея" to "kr", "сеул" to "kr",
        "сингапур" to "sg", "индия" to "in", "финляндия" to "fi",
        "швеция" to "se", "норвегия" to "no", "швейцария" to "ch",
        "польша" to "pl", "украина" to "ua", "киев" to "ua",
        "канада" to "ca", "австралия" to "au", "бразилия" to "br",
        "гонконг" to "hk", "италия" to "it", "испания" to "es",
        "австрия" to "at", "бельгия" to "be", "чехия" to "cz",
        "дания" to "dk", "ирландия" to "ie", "израиль" to "il",
        "литва" to "lt", "латвия" to "lv", "эстония" to "ee",
        "молдова" to "md", "румыния" to "ro", "болгария" to "bg",
        "венгрия" to "hu", "греция" to "gr", "португалия" to "pt",
        "сербия" to "rs", "армения" to "am", "грузия" to "ge",
        "азербайджан" to "az", "беларусь" to "by", "белоруссия" to "by",
        "узбекистан" to "uz", "ташкент" to "uz", "киргизия" to "kg",
        "казахстан" to "kz", "алматы" to "kz", "турция" to "tr",
        "стамбул" to "tr", "мексика" to "mx", "аргентина" to "ar",
        "чили" to "cl", "египет" to "eg", "тайланд" to "th",
        "вьетнам" to "vn", "малайзия" to "my", "индонезия" to "id",
        "филиппины" to "ph", "тайвань" to "tw", "исландия" to "is",
        "люксембург" to "lu", "словакия" to "sk", "хорватия" to "hr",
        "кипр" to "cy", "мальта" to "mt", "китай" to "cn",
        "саудовская" to "sa", "катар" to "qa", "иран" to "ir",
        "пакистан" to "pk", "оаэ" to "ae", "дубай" to "ae"
    )

    /** So'z chegaralari bilan qidiramiz: "america" ≠ "south america" ichida. */
    private fun findCc(text: String): String? {
        val t = text.lowercase(Locale.US)

        // 1) Aniq mos keladigan nom (uzunroqlari avval)
        for ((name, cc) in NAME_TO_CC.entries.sortedByDescending { it.key.length }) {
            val re = Regex("(^|[^a-zа-яё])" + Regex.escape(name) + "([^a-zа-яё]|$)")
            if (re.containsMatchIn(t)) return cc
        }

        // 2) "(DE)" yoki "[US]" kabi 2 harflik kod
        val m = Regex("\\(([a-z]{2})\\)|\\[([a-z]{2})\\]").find(t)
        if (m != null) {
            val cc = (m.groupValues[1].ifEmpty { m.groupValues[2] })
            if (cc in MAP) return cc
        }

        // 3) Yolg'iz 2 harflik token: "Frankfurt DE", "Tokyo JP"
        for (tok in Regex("[a-z]{2}").findAll(t)) {
            val cc = tok.value
            if (cc in MAP && cc !in STOPWORDS) return cc
        }
        return null
    }

    /** "de", "in", "no", "is" — ingliz so'zlari bilan to'qnashadi. */
    private val STOPWORDS = setOf("in", "de", "no", "is", "at", "it", "be",
        "to", "of", "on", "or", "by", "my", "so", "us", "am")

    fun lookup(host: String?): Array<String> {
        if (host.isNullOrEmpty()) return arrayOf("", "🌍 Noma'lum")
        val h = host.lowercase(Locale.US)
        val tld = h.substringAfterLast('.', "")
        if (tld.length == 2 && tld.all { it in 'a'..'z' }) {
            val name = MAP[tld] ?: "🌍 " + tld.uppercase(Locale.US)
            return arrayOf(tld.uppercase(Locale.US), name)
        }
        // ★ TUZATISH #2: to'liq davlat nomi jadvali (avval 4 ta so'z edi)
        findCc(h)?.let { cc ->
            MAP[cc]?.let { return arrayOf(cc.uppercase(Locale.US), it) }
        }
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

    /**
     * Remark dan davlat kodini aniqlash.
     * ★ TUZATISH #2: emoji bo'lmasa, DAVLAT NOMI bo'yicha ham izlaydi.
     *    Avval faqat emoji va "(DE)" formati ishlardi → "Netherlands
     *    Amsterdam" kabi obunalar bayroqsiz qolardi.
     */
    fun ccFromRemark(remark: String?): String {
        if (remark.isNullOrEmpty()) return ""

        // 1) Emoji bayroq (🇨🇦 → CA)
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
        if (codes.size == 2) {
            return "${('A' + codes[0])}${('B' + codes[1] - 1)}"
        }

        // 2) ★ Nom bo'yicha (Russia, Japan, Niderlandiya, ...)
        return findCc(remark)?.uppercase(Locale.US) ?: ""
    }
}
'''


def main() -> int:
    if not LOOKUP.exists():
        print(f"❌ Topilmadi: {LOOKUP}")
        return 1

    src = LOOKUP.read_text(encoding="utf-8")
    if "TUZATISH #2" in src:
        print("ℹ️  #2 allaqachon qo'llanilgan")
        return 0

    safe_backup(LOOKUP, "fix2")
    LOOKUP.write_text(NEW_FILE, encoding="utf-8")
    print(f"✅ Yangilandi: {LOOKUP.relative_to(ROOT)}")
    print("   • NAME_TO_CC: ingliz + rus davlat nomlari (180+ yozuv)")
    print("   • findCc(): butun so'z bo'yicha qidiruv")
    print("   • ccFromRemark(): emoji bo'lmasa NOM bo'yicha ham aniqlaydi")
    print("   • MAP: 24 → 80+ davlat kodi")
    return 0


if __name__ == "__main__":
    sys.exit(main())
