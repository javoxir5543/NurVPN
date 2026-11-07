package com.nurvpn.app.core

class Subscription(
    @JvmField var id: String,
    @JvmField var url: String,
    @JvmField var name: String
) {
    @JvmField var lastUpdated: Long = 0
    /** Tartib — pastdan yuqoriga (0 = eng yuqori). */
    @JvmField var order: Int = 0
    /** Saralash rejimi: default, ping_asc, ping_desc, name_asc, name_desc */
    @JvmField var sortMode: String = "default"
    @JvmField var updateIntervalHours: Int = 24
    @JvmField var expireAt: Long = 0
    @JvmField var trafficUsed: Long = 0
    @JvmField var trafficTotal: Long = 0
    @JvmField var serverCount: Int = 0

    // ═══ ★ FIX149-SUBINFO: obuna HTTP header'laridan keladigan metama'lumot
    //
    //   Bular HECH QAYERDA saqlanmasdi — shuning uchun karta qayta
    //   chizilganda (fragment qayta yaratilganda, tab almashganda)
    //   ma'lumot YO'QOLARDI. Endi diskda saqlanadi.

    /** `profile-update-interval` — yangilanish intervali (kun). 0 = noma'lum. */
    @JvmField var updateIntervalDays: Int = 0

    /** `profile-web-page-url` — provayder sayti. */
    @JvmField var webPage: String = ""

    /** `support-url` — qo'llab-quvvatlash havolasi. */
    @JvmField var supportUrl: String = ""

    /** `announce` — provayder e'loni. */
    @JvmField var announce: String = ""

    /** Karta uchun qulay ko'rinish: "domen.com" (sxemasiz). */
    fun webPageShort(): String = shortUrl(webPage)

    /** Karta uchun qulay ko'rinish: support domeni yoki "Support". */
    fun supportShort(): String = shortUrl(supportUrl)

    private fun shortUrl(u: String): String {
        if (u.isBlank()) return ""
        var s = u.trim()
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.removePrefix("t.me/").removePrefix("telegram.me/")
        s = s.trimEnd('/')
        if (s.length > 26) s = s.take(25) + "…"
        return s
    }

    /** Asosiy (Home) ekranda ko'rsatilmasin — faqat Serverlar bo'limida ko'rinadi. */
    @JvmField var hiddenOnHome: Boolean = false

    /** Crypt1 shifrlangan linkdan kelgan — QR/copy bloklanadi. */
    @JvmField var encrypted: Boolean = false

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expireAt > 0 && now > expireAt

    fun daysLeft(now: Long = System.currentTimeMillis()): Int {
        if (expireAt <= 0) return -1
        return ((expireAt - now) / 86400_000L).toInt()
    }

    fun trafficPercent(): Int {
        if (trafficTotal <= 0) return -1
        return ((trafficUsed * 100) / trafficTotal).toInt()
    }
}
