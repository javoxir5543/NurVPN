#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NurVPN — C5 (Kill-switch) + H4 (Biometric) + H5 (HWID switch) tuzatishlari.

═══════════════════════════════════════════════════════════════════════════
C5 — KILL-SWITCH: HAQIQIY HOLATNI TEKSHIRISH
═══════════════════════════════════════════════════════════════════════════
MUAMMO (audit tasdiqladi):
  • `android:always_on_vpn_app`  = null   ← tizimda YOQILMAGAN
  • `always_on_vpn_lockdown`     = 0      ← KILL-SWITCH O'CHIQ
  • Ilova "kill_switch" pref ni YOZADI (SettingsFragment:738), lekin
    hech qanday HIMOYA bermaydi — faqat UI ko'rsatish uchun o'qiladi (:334).
  • Foydalanuvchi switch'ni yoqadi → dialog → "Sozlamalarga o'tish" →
    u yerda hech narsa qilmasa qaytib keladi → switch "Yoqilgan" ko'rinadi
    → LEKIN lockdown = 0 → IP OSHKOR.

YECHIM:
  `KillSwitch` obyekti qo'shamiz — tizimning HAQIQIY holatini o'qiydi:
     Settings.Secure.getInt(cr, "always_on_vpn_lockdown", 0) == 1
     Settings.Secure.getString(cr, "always_on_vpn_app")  == packageName
  SettingsFragment switch holatini SHU yerdan oladi (pref emas).
  Dialogdan qaytgach (onResume) holat QAYTA tekshiriladi.

═══════════════════════════════════════════════════════════════════════════
H4 — BIOMETRIC BYPASS
═══════════════════════════════════════════════════════════════════════════
MUAMMO: BiometricAuthHelper.kt:70-81 — ekran qulfi yo'q qurilmada
  `onSuccess()` SHARTSIZ chaqiriladi → himoya butunlay yo'q.

YECHIM:
  Ekran qulfi yo'q bo'lsa — SO'RAMAYMIZ. `onUnavailable()` chaqirib,
  chaqiruvchiga "himoya o'rnatilmagan" deb xabar beramiz. Chaqiruvchi
  (masalan, ilova qulfi) o'zi qaror qiladi.

═══════════════════════════════════════════════════════════════════════════
H5 — HWID SWITCH ISHLAMAYDI
═══════════════════════════════════════════════════════════════════════════
MUAMMO: HwidHelper.fromContext (HwidHelper.kt:67-80) `HwidStore.isEnabled`
  ni UMUMAN o'qimaydi → HWID switch hech narsani to'xtatmaydi,
  `x-hwid` yuborilishda davom etadi.

YECHIM: `fromContext` ichida `HwidStore.isEnabled` ni tekshirish.

Ishlatish:
    python3 tools/fix_killswitch_biometric_hwid.py --check
    python3 tools/fix_killswitch_biometric_hwid.py --dry-run
    python3 tools/fix_killswitch_biometric_hwid.py --self-test
    python3 tools/fix_killswitch_biometric_hwid.py
"""

import io
import os
import re
import shutil
import sys
import time

# ═══════════════════════════════════════════════════════════════════════
# YO'LLAR
# ═══════════════════════════════════════════════════════════════════════
SRC = "app/src/main/java/com/nurvpn/app"
KILL_SWITCH_FILE = SRC + "/util/KillSwitch.kt"
SETTINGS = SRC + "/ui/settings/SettingsFragment.kt"
BIOMETRIC = SRC + "/util/BiometricAuthHelper.kt"
BACKUP_DIR = ".fix-backups"


# ═══════════════════════════════════════════════════════════════════════
# YANGI FAYL: KillSwitch.kt
# ═══════════════════════════════════════════════════════════════════════
KILL_SWITCH_KT = '''package com.nurvpn.app.util

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
     * Bu ilova always-on VPN sifatida tanlanganmi?
     * Android 10+ da boshqa ilova bo'lsa `false` (ko'rinmaydi).
     */
    fun isAlwaysOn(ctx: Context): Boolean =
        try {
            val app = Settings.Secure.getString(ctx.contentResolver, KEY_APP)
            !app.isNullOrEmpty() && app == ctx.packageName
        } catch (t: Throwable) {
            // Android 12+ da ba'zi qurilmalarda SecurityException bo'lishi mumkin
            false
        }

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

    /** Ikkalasi ham yoqilganmi — to'liq himoya. */
    fun isFullyEnabled(ctx: Context): Boolean =
        isAlwaysOn(ctx) && isLockdown(ctx)

    /**
     * Foydalanuvchiga ko'rsatish uchun holat.
     * @return OFF (hech narsa), PARTIAL (faqat always-on), ON (to'liq)
     */
    fun state(ctx: Context): State = when {
        isFullyEnabled(ctx) -> State.ON
        isAlwaysOn(ctx) -> State.PARTIAL
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
'''


# ═══════════════════════════════════════════════════════════════════════
# C5: SettingsFragment — tizim holatidan o'qish
# ═══════════════════════════════════════════════════════════════════════
SETTINGS_OLD_INIT = '''        killSwitch?.isChecked = a.prefs.getBoolean("kill_switch", false)'''
SETTINGS_NEW_INIT = '''        // ★ C5 TUZATISH: tizimning HAQIQIY holatidan o'qiymiz (pref emas).
        //   Ilgari pref ko'rsatilardi — foydalanuvchi "Yoqilgan" deb o'ylab,
        //   aslida lockdown=0 bo'lib real IP oshkor bo'lardi.
        syncKillSwitchState()'''

SETTINGS_OLD_BTN = '''                try {
                    val intent = android.content.Intent("android.net.vpn.SETTINGS")
                    startActivity(intent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(c,
                        getString(R.string.toast_error_prefix, e.message),
                        android.widget.Toast.LENGTH_SHORT).show()
                }'''
SETTINGS_NEW_BTN = '''                // ★ C5 TUZATISH: holatni pref'ga YOZMAYMIZ — tizimdan o'qiymiz.
                //   Dialogdan qaytgach `onResume` -> `syncKillSwitchState()`
                //   haqiqiy holatni ko'rsatadi.
                if (!com.nurvpn.app.util.KillSwitch.openSettings(c)) {
                    android.widget.Toast.makeText(c,
                        getString(R.string.toast_error_prefix, "VPN sozlamalari ochilmadi"),
                        android.widget.Toast.LENGTH_SHORT).show()
                }'''

# onResume qo'shish (mavjud bo'lmasa)
SETTINGS_ONRESUME = '''
    override fun onResume() {
        super.onResume()
        // ★ C5: foydalanuvchi tizim sozlamalaridan qaytgach holatni yangilaymiz
        syncKillSwitchState()
    }

    /**
     * ★ C5 TUZATISH: switch holatini TIZIMDAN o'qiydi.
     * Lockdown yoqilmagan bo'lsa — switch o'chiq ko'rinadi va
     * foydalanuvchi himoya yo'qligini biladi.
     */
    private fun syncKillSwitchState() {
        val ctx = context ?: return
        val ks = killSwitch ?: return
        binding = true
        try {
            ks.isChecked = com.nurvpn.app.util.KillSwitch.isFullyEnabled(ctx)
            // Qisman holat (always-on bor, lockdown yo'q) — ogohlantiramiz.
            // MaterialSwitch'da `summary` yo'q (bu ListItem usuli), shuning uchun Toast.
            if (com.nurvpn.app.util.KillSwitch.isAlwaysOn(ctx) &&
                !com.nurvpn.app.util.KillSwitch.isLockdown(ctx)) {
                android.widget.Toast.makeText(ctx,
                    getString(R.string.kill_switch_partial_warning),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        } finally {
            binding = false
        }
    }
'''


# ═══════════════════════════════════════════════════════════════════════
# H4: BiometricAuthHelper — ekran qulfi yo'q bo'lsa so'ramaymiz
# ═══════════════════════════════════════════════════════════════════════
BIOMETRIC_OLD = """        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // Agar telefonda parol, PIN yoki barmoq izi umuman bo'lmasa:
            // Foydalanuvchini bloklab qo'ymaymiz, faqat ogohlantiramiz.
            android.widget.Toast.makeText(
                activity,
                activity.getString(com.nurvpn.app.R.string.bio_no_lock),
                android.widget.Toast.LENGTH_LONG
            ).show()
            
            onSuccess() // Ruxsat beramiz
            return
        }"""

BIOMETRIC_NEW = """        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // ★ H4 TUZATISH (audit): qurilmada ekran qulfi ham, barmoq izi ham YO'Q.
            //
            //   ILGARI: `onSuccess()` chaqirilardi ("Ruxsat beramiz") — ya'ni
            //   himoya UMUMAN ishlamasdi: qulfni olib tashlagan foydalanuvchi
            //   (yoki uni o'rnatmagan) ilova qulfini bir bosishda ochardi.
            //
            //   ENDI: `onUnavailable()` — soxta "muvaffaqiyat" bermaymiz.
            //   Chaqiruvchi o'zi qaror qiladi (ogohlantiradi yoki ochiq qoldiradi),
            //   lekin xavfsizlik "soxta yoqilgan" ko'rinmaydi.
            android.widget.Toast.makeText(
                activity,
                activity.getString(com.nurvpn.app.R.string.bio_no_lock),
                android.widget.Toast.LENGTH_LONG
            ).show()

            android.util.Log.w("NurVPN-Bio",
                "Qurilmada ekran qulfi ham, biometrika ham yo'q — autentifikatsiya " +
                "o'tkazilmadi (onError)")
            onError(activity.getString(com.nurvpn.app.R.string.bio_no_lock))
            return
        }"""

# ═══════════════════════════════════════════════════════════════════════
# H5: HwidHelper — switch tekshiruvi
# ═══════════════════════════════════════════════════════════════════════
# ═══════════════════════════════════════════════════════════════════════
# H5: HWID — QAROR: O'ZGARTIRILMAYDI
# ═══════════════════════════════════════════════════════════════════════
# `HwidHelper.fromContext` (HwidHelper.kt:59-66) da muallif ATaylab yozgan:
#     "DIQQAT: qiymat `HwidStore` sozlamasiga BOG'LIQ EMAS ... Xatti-harakatni
#      o'zgartirmaslik uchun shu holat saqlandi."
# Foydalanuvchi qarori (2026-11-15): hech narsa qilmaslik.
# Shuning uchun H5 tuzatilmadi — bu ataylab qoldirilgan xatti-harakat.


# ═══════════════════════════════════════════════════════════════════════
# MATNLAR (4 til)
# ═══════════════════════════════════════════════════════════════════════
STRINGS = {
    "values": {
        "kill_switch_partial_warning":
            'Always-on yoqilgan, lekin "VPN\\\'siz ulanishni bloklash" YOQILMAGAN \u2014 '
            'VPN uzilsa IP oshkor bo\\\'ladi. Sozlamalarda yoqing.',
    },
    "values-uz": {
        "kill_switch_partial_warning":
            'Always-on yoqilgan, lekin "VPN\\\'siz ulanishni bloklash" YOQILMAGAN \u2014 '
            'VPN uzilsa IP oshkor bo\\\'ladi. Sozlamalarda yoqing.',
    },
    "values-ru": {
        "kill_switch_partial_warning":
            'Always-on \u0432\u043a\u043b\u044e\u0447\u0451\u043d, \u043d\u043e '
            '"\u0411\u043b\u043e\u043a\u0438\u0440\u043e\u0432\u0430\u0442\u044c '
            '\u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u044f '
            '\u0431\u0435\u0437 VPN" \u041d\u0415 '
            '\u0432\u043a\u043b\u044e\u0447\u0435\u043d\u043e \u2014 '
            '\u043f\u0440\u0438 \u043e\u0431\u0440\u044b\u0432\u0435 VPN '
            '\u0432\u0430\u0448 IP \u0431\u0443\u0434\u0435\u0442 \u0432\u0438\u0434\u0435\u043d. '
            '\u0412\u043a\u043b\u044e\u0447\u0438\u0442\u0435 \u0432 '
            '\u043d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0430\u0445.',
    },
    "values-en": {
        "kill_switch_partial_warning":
            'Always-on is enabled, but "Block connections without VPN" is NOT \u2014 '
            'your IP will leak if the VPN drops. Enable it in settings.',
    },
}


def backup(path, root):
    d = os.path.join(root, BACKUP_DIR)
    os.makedirs(d, exist_ok=True)
    dst = os.path.join(d, "%s.c5h4h5.%s.bak" %
                       (os.path.basename(path), time.strftime("%Y%m%d_%H%M%S")))
    shutil.copy2(path, dst)
    return os.path.relpath(dst, root)


def patch(path, replacements, root, dry, label):
    """Bir nechta almashtirishni bajaradi. `(eski, yangi)` juftliklar ro'yxati."""
    full = os.path.join(root, path)
    if not os.path.exists(full):
        print("    ✘ %s topilmadi" % path)
        return False
    t = io.open(full, encoding="utf-8").read()
    orig = t
    for old, new in replacements:
        if new.strip() and new in t and old not in t:
            print("    ⏭  %s — allaqachon qo'llangan" % label)
            continue
        if old not in t:
            print("    ✘ %s — anchor topilmadi: %s" % (label, old.strip()[:60]))
            return False
        t = t.replace(old, new, 1)
    if t == orig:
        print("    ⏭  %s — o'zgarish yo'q" % label)
        return True
    if dry:
        print("    • %s — o'zgaradi" % label)
        return True
    bk = backup(full, root)
    io.open(full, "w", encoding="utf-8").write(t)
    print("    ✔ %s" % label)
    return True


def add_strings(root, dry):
    """4 ta strings.xml ga yangi kalit qo'shadi."""
    n = 0
    for loc, kv in STRINGS.items():
        p = os.path.join(root, "app/src/main/res/%s/strings.xml" % loc)
        if not os.path.exists(p):
            print("    ✘ %s topilmadi" % p)
            continue
        t = io.open(p, encoding="utf-8").read()
        added = False
        for k, v in kv.items():
            if 'name="%s"' % k in t:
                continue
            t = t.replace("</resources>",
                          '    <string name="%s">%s</string>\n</resources>' % (k, v), 1)
            added = True
            n += 1
        if added and not dry:
            io.open(p, "w", encoding="utf-8").write(t)
    # 4 tilda bir xil bo'lishini tekshirish
    def keys(loc):
        p = os.path.join(root, "app/src/main/res/%s/strings.xml" % loc)
        if not os.path.exists(p):
            return set()
        return set(re.findall(r'name="([^"]+)"', io.open(p, encoding="utf-8").read()))
    allk = [keys(l) for l in STRINGS]
    if allk and all(k == allk[0] for k in allk):
        print("    ✔ 4 tilda kalitlar bir xil")
    else:
        print("    ⚠️  4 tildagi kalitlar FARQ qiladi")
    print("    ✔ %d ta string qo'shildi" % n)
    return True


def apply(root, dry):
    ok = True

    # ── 1. KillSwitch.kt (yangi fayl) ──
    print("  ── KillSwitch.kt ──")
    ksp = os.path.join(root, KILL_SWITCH_FILE)
    if os.path.exists(ksp):
        print("    ⏭  allaqachon mavjud")
    elif dry:
        print("    • KillSwitch.kt yaratiladi")
    else:
        io.open(ksp, "w", encoding="utf-8").write(KILL_SWITCH_KT)
        print("    ✔ KillSwitch.kt yaratildi")

    # ── 2. SettingsFragment.kt ──
    print("  ── SettingsFragment.kt ──")
    ok &= patch(SETTINGS, [
        (SETTINGS_OLD_INIT, SETTINGS_NEW_INIT),
        (SETTINGS_OLD_BTN, SETTINGS_NEW_BTN),
    ], root, dry, "switch holati tizimdan")

    # onResume + syncKillSwitchState qo'shish
    full = os.path.join(root, SETTINGS)
    t = io.open(full, encoding="utf-8").read()
    # ⚠️ MUHIM: chaqiruv (337) va funksiya ta'rifi BOSHQA narsa!
    # Faqat funksiya TA'RIFI yo'qligini tekshiramiz.
    if "private fun syncKillSwitchState" not in t:
        # fayl oxiridagi `}` dan oldin qo'shamiz (sinf yopilishi)
        idx = t.rstrip().rfind("}")
        if idx > 0:
            t2 = t[:idx] + SETTINGS_ONRESUME + t[idx:]
            if not dry:
                io.open(full, "w", encoding="utf-8").write(t2)
            print("    ✔ onResume + syncKillSwitchState qo'shildi")
        else:
            print("    ✘ sinf yopilishi topilmadi")
            ok = False
    else:
        print("    ⏭  syncKillSwitchState allaqachon bor")

    # ── 3. Matnlar ──
    print("  ── strings.xml (4 til) ──")
    add_strings(root, dry)

    # ── 4. H4 Biometric ──
    print("  ── BiometricAuthHelper.kt (H4) ──")
    ok &= patch(BIOMETRIC, [(BIOMETRIC_OLD, BIOMETRIC_NEW)], root, dry, "biometric bypass")

    return 0 if ok else 1


def check(root):
    print("  ══ CHECK ══\n")
    ok = True

    def has(path, needle, label):
        nonlocal ok
        p = os.path.join(root, path)
        g = os.path.exists(p) and needle in io.open(p, encoding="utf-8").read()
        ok = ok and g
        print("    %s %s" % ("✔" if g else "❌", label))
        return g

    has(KILL_SWITCH_FILE, "object KillSwitch", "KillSwitch.kt mavjud")
    has(KILL_SWITCH_FILE, "always_on_vpn_lockdown", "lockdown o'qish")
    has(KILL_SWITCH_FILE, "always_on_vpn_app", "always-on app o'qish")
    has(SETTINGS, "syncKillSwitchState", "SettingsFragment sinxronizatsiya")
    has(SETTINGS, "KillSwitch.isFullyEnabled", "tizim holatidan o'qish")
    has(SETTINGS, "override fun onResume", "onResume yangilash")
    has(BIOMETRIC, "onError(activity.getString", "H4: onError chaqirilishi")
    has(BIOMETRIC, "H4 TUZATISH", "H4: tuzatish belgisi")

    # 4 tilda kalit borligi
    for loc in STRINGS:
        p = os.path.join(root, "app/src/main/res/%s/strings.xml" % loc)
        g = os.path.exists(p) and 'name="kill_switch_partial_warning"' in io.open(p, encoding="utf-8").read()
        ok = ok and g
        print("    %s %s matni" % ("✔" if g else "❌", loc))

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def self_test():
    print("  ══ SELF-TEST ══\n")
    ok = True

    def t(label, cond):
        nonlocal ok
        ok = ok and cond
        print("    %s %s" % ("✔" if cond else "✘", label))

    # KillSwitch.kt
    t("KillSwitch.kt: object", "object KillSwitch" in KILL_SWITCH_KT)
    t("KillSwitch.kt: always_on_vpn_app satri", '"always_on_vpn_app"' in KILL_SWITCH_KT)
    t("KillSwitch.kt: lockdown satri", '"always_on_vpn_lockdown"' in KILL_SWITCH_KT)
    t("KillSwitch.kt: Settings.Secure ishlatiladi", "Settings.Secure.get" in KILL_SWITCH_KT)
    t("KillSwitch.kt: isFullyEnabled", "fun isFullyEnabled" in KILL_SWITCH_KT)
    t("KillSwitch.kt: State enum", "enum class State" in KILL_SWITCH_KT)
    t("KillSwitch.kt: openSettings", "fun openSettings" in KILL_SWITCH_KT)
    t("KillSwitch.kt: try/catch himoyasi", KILL_SWITCH_KT.count("catch (t: Throwable)") >= 3)
    # Qavslar muvozanati
    t("KillSwitch.kt: {} muvozanat", KILL_SWITCH_KT.count("{") == KILL_SWITCH_KT.count("}"))
    t("KillSwitch.kt: () muvozanat", KILL_SWITCH_KT.count("(") == KILL_SWITCH_KT.count(")"))

    # Settings
    t("C5: pref YOZILMAYDI", "putBoolean(\"kill_switch\"" not in SETTINGS_NEW_BTN)
    t("C5: tizimdan o'qiladi",
      "syncKillSwitchState" in SETTINGS_NEW_INIT
      and "KillSwitch.isFullyEnabled" in SETTINGS_ONRESUME)
    t("C5: onResume", "override fun onResume" in SETTINGS_ONRESUME)
    t("C5: qisman holat ogohlantirishi", "isAlwaysOn" in SETTINGS_ONRESUME and "isLockdown" in SETTINGS_ONRESUME)
    t("C5: {} muvozanat", SETTINGS_ONRESUME.count("{") == SETTINGS_ONRESUME.count("}"))

    # H4
    t("H4: onSuccess BYPASS olib tashlandi", "onSuccess() // Ruxsat beramiz" not in BIOMETRIC_NEW)
    t("H4: onError chaqiriladi", "onError(" in BIOMETRIC_NEW)
    t("H4: ogohlantirish saqlanadi", "bio_no_lock" in BIOMETRIC_NEW)
    # DEVICE_CREDENTIAL yuqoridagi canAuthenticate() da ishlatiladi (65-68),
    # fragment ichida emas — shuning uchun tekshirmaymiz.
    t("H4: {} muvozanat", BIOMETRIC_NEW.count("{") == BIOMETRIC_NEW.count("}"))


    # Matnlar
    t("4 til matni", len(STRINGS) == 4)
    t("matnlarda kalit bir xil",
      all("kill_switch_partial_warning" in v for v in STRINGS.values()))
    # XML escape tekshiruvi
    for loc, kv in STRINGS.items():
        for k, v in kv.items():
            bad = re.search(r'[<>&](?!amp;|lt;|gt;|quot;|apos;|#)', v)
            t("%s: XML escape to'g'ri" % loc, bad is None)

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


def main():
    args = sys.argv[1:]
    if "--self-test" in args:
        return self_test()
    root = next((a for a in args if not a.startswith("-")), ".")
    root = os.path.abspath(root)
    if "--check" in args:
        return check(root)
    dry = "--dry-run" in args
    print("  ══ C5 (Kill-switch) + H4 (Biometric) ══\n")
    rc = apply(root, dry)
    if rc == 0:
        print("\n  Keyingi qadam:\n    ./gradlew assembleDebug --offline")
    return rc


if __name__ == "__main__":
    sys.exit(main())
