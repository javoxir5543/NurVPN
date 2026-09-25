package com.nurvpn.app.storage
import com.nurvpn.app.R

import android.content.Context

/** Ochiq manbalardagi tayyor obuna (GitHub, bepul). */
data class OpenSourceSubscription(
    val id: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val url: String? = null,
    /** AWG uchun inline config ID (BuiltinAwgConfigs.byId). Null bo'lsa URL ishlatiladi. */
    val awgId: String? = null,
    val flag: String = "\uD83C\uDF10"
) {
    val isAwg: Boolean get() = awgId != null
    fun name(ctx: android.content.Context): String = ctx.getString(nameRes)
    fun description(ctx: android.content.Context): String = ctx.getString(descriptionRes)
}

object OpenSourceCatalog {

    val ALL: List<OpenSourceSubscription> = listOf(
        OpenSourceSubscription(
            id = "nikita29a",
            nameRes = R.string.os_nikita_name,
            descriptionRes = R.string.os_nikita_desc,
            url = "https://raw.githubusercontent.com/nikita29a/FreeProxyList/main/mirror/4.txt",
            flag = "\uD83C\uDDF7\uD83C\uDDFA"
        ),
        OpenSourceSubscription(
            id = "hidashimora",
            nameRes = R.string.os_hidashimora_name,
            descriptionRes = R.string.os_hidashimora_desc,
            url = "https://raw.githubusercontent.com/Hidashimora/free-vpn-anti-rkn/main/configs/1.txt",
            flag = "\uD83C\uDDF7\uD83C\uDDFA"
        ),
        OpenSourceSubscription(
            id = "igareck",
            nameRes = R.string.os_igareck_name,
            descriptionRes = R.string.os_igareck_desc,
            url = "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            flag = "\uD83C\uDDF7\uD83C\uDDFA"
        ),
        OpenSourceSubscription(
            id = "hidashimora2",
            nameRes = R.string.os_hidashimora2_name,
            descriptionRes = R.string.os_hidashimora2_desc,
            url = "https://raw.githubusercontent.com/Hidashimora/free-vpn-anti-rkn/main/configs/2.txt",
            flag = "\uD83C\uDDF7\uD83C\uDDFA"
        ),
        OpenSourceSubscription(
            id = "ebrasha",
            nameRes = R.string.os_ebrasha_name,
            descriptionRes = R.string.os_ebrasha_desc,
            url = "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/V2Ray-Config-By-EbraSha.txt",
            flag = "\uD83C\uDF10"
        ),
        OpenSourceSubscription(
            id = "ruk1ng",
            nameRes = R.string.os_ruk1ng_name,
            descriptionRes = R.string.os_ruk1ng_desc,
            url = "https://raw.githubusercontent.com/Ruk1ng001/freeSub/main/v2ray",
            flag = "\uD83C\uDF10"
        ),
        OpenSourceSubscription(
            id = "ermaozi",
            nameRes = R.string.os_ermaozi_name,
            descriptionRes = R.string.os_ermaozi_desc,
            url = "https://raw.githubusercontent.com/ermaozi/get_subscribe/main/subscribe/v2ray.txt",
            flag = "\uD83C\uDF10"
        ),
        OpenSourceSubscription(
            id = "warp_cf1",
            nameRes = R.string.os_warp1_name,
            descriptionRes = R.string.os_warp1_desc,
            awgId = "warp_cf1",
            flag = "\u2601\uFE0F"
        ),
        OpenSourceSubscription(
            id = "warp_cf2",
            nameRes = R.string.os_warp2_name,
            descriptionRes = R.string.os_warp2_desc,
            awgId = "warp_cf2",
            flag = "\u2601\uFE0F"
        ),
        OpenSourceSubscription(
            id = "warp_cf3",
            nameRes = R.string.os_warp3_name,
            descriptionRes = R.string.os_warp3_desc,
            awgId = "warp_cf3",
            flag = "\u2601\uFE0F"
        )
    )

    fun byId(id: String): OpenSourceSubscription? = ALL.firstOrNull { it.id == id }
}

/** Yoqilgan ochiq manba ID'larini saqlaydi (SharedPreferences). */
object OpenSourceStore {
    private const val PREFS = "nurvpn_open_sources"
    private const val KEY_ENABLED = "enabled_ids"
    private const val KEY_DELETED = "deleted_ids"

    fun getEnabled(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()
    }

    fun isEnabled(context: Context, id: String): Boolean =
        id in getEnabled(context)

    // ═══ O'chirilgan (doimiy yashirilgan) manbalar ═══
    fun getDeleted(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_DELETED, emptySet()) ?: emptySet()
    }

    fun markDeleted(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getDeleted(context).toMutableSet()
        current.add(id)
        // Enabled'dan ham olib tashlaymiz
        val enabled = getEnabled(context).toMutableSet()
        enabled.remove(id)
        prefs.edit()
            .putStringSet(KEY_DELETED, current)
            .putStringSet(KEY_ENABLED, enabled)
            .apply()
    }

    fun restore(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getDeleted(context).toMutableSet()
        current.remove(id)
        prefs.edit().putStringSet(KEY_DELETED, current).apply()
    }

    /**
     * Katalogda yo'q eski ID'larni tozalash (upgrade paytida).
     * Eski obunalar o'chirilgan/yangilangan bo'lsa, orphan yozuvlarni olib tashlaydi.
     */
    fun cleanupOrphans(context: Context) {
        val validIds = OpenSourceCatalog.ALL.map { it.id }.toSet()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // enabled — faqat katalogda mavjudlarni qoldiramiz
        val enabled = getEnabled(context).filter { it in validIds }.toSet()
        // deleted — faqat katalogda mavjudlarni qoldiramiz
        val deleted = getDeleted(context).filter { it in validIds }.toSet()

        prefs.edit()
            .putStringSet(KEY_ENABLED, enabled)
            .putStringSet(KEY_DELETED, deleted)
            .apply()
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getEnabled(context).toMutableSet()
        if (enabled) current.add(id) else current.remove(id)
        prefs.edit().putStringSet(KEY_ENABLED, current).apply()
    }

    /** "open:nikita29a" kabi prefiksli obuna ID'si. */
    fun subId(openId: String) = "open:$openId"

    /** Berilgan obuna ID'si ochiq manbaniki ekanligini tekshirish. */
    fun isOpenSource(subId: String?): Boolean =
        subId != null && subId.startsWith("open:")

    /** "open:nikita29a" → "nikita29a" */
    fun extractOpenId(subId: String?): String? =
        subId?.takeIf { it.startsWith("open:") }?.removePrefix("open:")
}
