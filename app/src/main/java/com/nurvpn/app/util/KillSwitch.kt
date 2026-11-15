package com.nurvpn.app.util

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Kill-switch (Always-on VPN + Lockdown) — TIZIMNING HAQIQIY holatini o'qiydi.
 *
 * ★ C5 TUZATISH: Ilgari "kill_switch" SharedPreferences kaliti yozilardi,
 *   lekin hech qanday himoya bermasdi — tizim sozlamasi o'zgarmasdan
 *   foydalanuvchi "Yoqilgan" deb o'ylardi va real IP oshkor bo'lardi.
 *
 * Bu obyekt tizimdan to'g'ridan-to'g'ri o'qiydi:
 *   • `always_on_vpn_app`      — qaysi ilova always-on qilingan
 *   • `always_on_vpn_lockdown` — 1 bo'lsa VPN'siz internet bloklanadi
 *
 * ⚠️ Android 10+ da ilova faqat O'ZIGA tegishli qiymatni ko'ra oladi
 *    (boshqa VPN ilovasi bo'lsa `null` qaytadi) — bu xavfsizlik cheklovi.
 *
 * Sozlash: Sozlamalar → Tarmoq → VPN → NurVPN → ⚙️ → "Always-on VPN" +
 *          "Block connections without VPN".
 */
object KillSwitch {

    // ⚠️ Android'da bu nomlar uchun PUBLIC konstanta YO'Q
    //    (`Settings.Secure.ALWAYS_ON_VPN_APP` mavjud emas) — satr sifatida.
    //    Manba: AOSP `Settings.Secure` — `always_on_vpn_app`,
    //    `always_on_vpn_lockdown`.
    private const val KEY_APP = "always_on_vpn_app"
    private const val KEY_LOCKDOWN = "always_on_vpn_lockdown"

    /**
     * `always_on_vpn_app` qiymati — BU ILOVA paketi.
     *
     * ⚠️ ISHONCHSIZ: Android 10+ da qiymat **ko'pincha `null`** qaytadi
     *    (hatto o'z paketimiz uchun ham). Shuning uchun bu funksiya
     *    YAKKA O'ZI himoyani bildirmaydi — `isLockdown()` bilan birga
     *    ishlatiladi. Faqat "boshqa VPN tanlanganmi" ni aniqlash uchun.
     */
    fun isThisAppSelected(ctx: Context): Boolean =
        try {
            val app = Settings.Secure.getString(ctx.contentResolver, KEY_APP)
            !app.isNullOrEmpty() && app == ctx.packageName
        } catch (t: Throwable) {
            false
        }

    /**
     * Boshqa ilova always-on VPN qilib tanlanganmi?
     * (Android 10+ da ko'pincha `null` — bu holda `false`).
     */
    fun isOtherAppSelected(ctx: Context): Boolean =
        try {
            val app = Settings.Secure.getString(ctx.contentResolver, KEY_APP)
            !app.isNullOrEmpty() && app != ctx.packageName
        } catch (t: Throwable) {
            false
        }

    /**
     * ⚠️ ESKI nom — moslik uchun qoldirilgan.
     * Android 13 da `always_on_vpn_app` null bo'lgani uchun
     * YOLG'ON `false` qaytarishi mumkin. Yangi kodda `isLockdown()`
     * yoki `isFullyEnabled()` ishlatilsin.
     */
    @Deprecated("Ishonchsiz — o'rniga isFullyEnabled() ishlatilsin", ReplaceWith("isFullyEnabled"))
    fun isAlwaysOn(ctx: Context): Boolean = isThisAppSelected(ctx)

    /**
     * "Block connections without VPN" (lockdown) yoqilganmi?
     * Bu — HAQIQIY kill-switch: VPN uzilsa internet butunlay bloklanadi.
     */
    fun isLockdown(ctx: Context): Boolean =
        try {
            Settings.Secure.getInt(ctx.contentResolver, KEY_LOCKDOWN, 0) == 1
        } catch (t: Throwable) {
            false
        }

    /**
     * HAQIQIY himoya bormi?
     *
     * ★ C5 MUHIM: `always_on_vpn_app` ga TAYANMAYMIZ.
     *   Qurilmada tasdiqlandi (Redmi Note 10S, Android 13):
     *       always_on_vpn_app      = null   ← Android BERMAYDI!
     *       always_on_vpn_lockdown = 1      ← himoya BOR
     *   Ya'ni `isAlwaysOn && isLockdown` → `false` bo'lib, foydalanuvchi
     *   "himoya yo'q" deb o'ylardi, aslida LOCKDOWN ISHLAYAPTI.
     *
     *   Android `lockdown` ni FAQAT always-on VPN tanlanganda yoqadi,
     *   shuning uchun `lockdown == 1` o'zi YETARLI dalil.
     */
    fun isFullyEnabled(ctx: Context): Boolean = isLockdown(ctx)

    /**
     * Qisman holat: always-on tanlangan, lekin lockdown yo'q
     * (`lockdown` faqat always-on + bloklash yoqilganda 1 bo'ladi,
     * shuning uchun amalda bu holat kamdan-kam uchraydi).
     */
    fun isPartial(ctx: Context): Boolean =
        isThisAppSelected(ctx) && !isLockdown(ctx)

    /**
     * Foydalanuvchiga ko'rsatish uchun holat.
     * @return OFF (hech narsa), PARTIAL (faqat always-on), ON (to'liq)
     */
    fun state(ctx: Context): State = when {
        isLockdown(ctx) -> State.ON
        isThisAppSelected(ctx) -> State.PARTIAL
        else -> State.OFF
    }

    enum class State { OFF, PARTIAL, ON }

    /**
     * VPN sozlamalari ekraniga o'tish.
     *
     * `Settings.ACTION_VPN_SETTINGS` (API 24+) ishlatiladi; u mavjud
     * bo'lmasa eski `"android.net.vpn.SETTINGS"` action'ga qaytamiz.
     *
     * @return `true` — ekran ochildi.
     */
    fun openSettings(ctx: Context): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 24) {
                add(android.content.Intent(Settings.ACTION_VPN_SETTINGS))
            }
            add(android.content.Intent("android.net.vpn.SETTINGS"))
        }
        for (i in candidates) {
            try {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return true
            } catch (t: Throwable) {
                // Keyingi variantni sinaymiz
            }
        }
        return false
    }
}
