#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NurVPN — C5 KILL-SWITCH (ilova o'zi, tizimsiz).

═══════════════════════════════════════════════════════════════════════════
FOYDALANUVCHI QARORI (2026-11-15)
═══════════════════════════════════════════════════════════════════════════
  • Dialog KERAK EMAS — switch faqat ON/OFF bo'lsin.
  • Ilova O'ZI bloklasin (tizim "Always-on VPN" sozlamasi kerak emas).

═══════════════════════════════════════════════════════════════════════════
MEXANIZM: BLACKHOLE TUN
═══════════════════════════════════════════════════════════════════════════
Kill-switch YOQILGAN va tunnel UZILGAN bo'lsa:
    → `Builder` bilan TUN ochamiz, lekin ROUTE QO'SHMAYMIZ
    → DNS server ham qo'ymaymiz
    → Natija: hech qanday paket chiqmaydi (qora tuynuk)
    → Foydalanuvchi ilovasini qayta ulaguncha internet YO'Q
Bu — haqiqiy kill-switch, tizim sozlamasisiz.

Tunnel QAYTSA:
    → bloklovchi TUN yopiladi
    → oddiy TUN ochiladi

═══════════════════════════════════════════════════════════════════════════
ISHLATISH
═══════════════════════════════════════════════════════════════════════════
    python3 tools/fix_killswitch_native.py --check
    python3 tools/fix_killswitch_native.py --dry-run
    python3 tools/fix_killswitch_native.py --self-test
    python3 tools/fix_killswitch_native.py
"""

import io
import os
import re
import shutil
import sys
import time

SRC = "app/src/main/java/com/nurvpn/app"
SVC = SRC + "/service/NurVpnService.kt"
SETTINGS = SRC + "/ui/settings/SettingsFragment.kt"
STORE = SRC + "/storage/KillSwitchStore.kt"
BACKUP_DIR = ".fix-backups"


# ═══════════════════════════════════════════════════════════════════════
# YANGI FAYL: KillSwitchStore.kt
# ═══════════════════════════════════════════════════════════════════════
STORE_KT = '''package com.nurvpn.app.storage

import android.content.Context

/**
 * Kill-switch sozlamasi — ILOVA ICHIDA (tizim sozlamasi kerak emas).
 *
 * ★ C5: Foydalanuvchi qarori — dialogsiz ON/OFF switch.
 *   Yoqilgan bo'lsa, tunnel uzilganda barcha trafik BLOKLANADI
 *   (blackhole TUN orqali) va real IP oshkor bo'lmaydi.
 */
object KillSwitchStore {

    private const val PREFS = "nurvpn_security"
    private const val KEY = "kill_switch"

    /** Yoqilganmi? (sukut: O'CHIQ — foydalanuvchi o'zi yoqadi) */
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    /** Yoqish/o'chirish. */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
'''


# ═══════════════════════════════════════════════════════════════════════
# SettingsFragment: dialogsiz switch
# ═══════════════════════════════════════════════════════════════════════
SET_OLD_LISTENER = '''        killSwitch?.setOnCheckedChangeListener { _, ch ->
            if (binding) return@setOnCheckedChangeListener
            showSystemVpnPrompt(ch)
        }'''

SET_NEW_LISTENER = '''        killSwitch?.setOnCheckedChangeListener { _, ch ->
            if (binding) return@setOnCheckedChangeListener
            // ★ C5: DIALOGSIZ — foydalanuvchi qarori (2026-11-15).
            //   Ilova O'ZI bloklaydi (blackhole TUN), tizim sozlamasi kerak emas.
            com.nurvpn.app.storage.KillSwitchStore.setEnabled(ctx, ch)
            lastWarnedPartial = ch   // ogohlantirishni qayta ko'rsatmaslik
            android.util.Log.i("NurVPN-KS", "Kill-switch: " + (if (ch) "YONIQ" else "O'CHIQ"))
        }'''

SET_OLD_SYNC = '''            // ★ C5: `isAlwaysOn` ISHONCHSIZ (Android 13 da null) — faqat lockdown.
            val selected = com.nurvpn.app.util.KillSwitch.isThisAppSelected(ctx)
            val lockdown = com.nurvpn.app.util.KillSwitch.isLockdown(ctx)
            ks.isChecked = lockdown
            // ★ C5: qisman holat (always-on bor, lockdown yo'q) — ogohlantiramiz,
            //   LEKIN faqat HOLAT O'ZGARGANDA. Ilgari har `onResume`da Toast
            //   chiqardi va foydalanuvchini bezovta qilardi.
            val partial = selected && !lockdown
            if (partial && !lastWarnedPartial) {
                android.widget.Toast.makeText(ctx,
                    getString(R.string.kill_switch_partial_warning),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            lastWarnedPartial = partial'''

SET_NEW_SYNC = '''            // ★ C5: ILOVA ichidagi sozlama (tizim sozlamasi emas).
            ks.isChecked = com.nurvpn.app.storage.KillSwitchStore.isEnabled(ctx)'''

SET_OLD_INIT = '''        // ★ C5 TUZATISH: tizimning HAQIQIY holatidan o'qiymiz (pref emas).
        //   Ilgari pref ko'rsatilardi — foydalanuvchi "Yoqilgan" deb o'ylab,
        //   aslida lockdown=0 bo'lib real IP oshkor bo'lardi.
        syncKillSwitchState()'''
SET_NEW_INIT = '''        // ★ C5: ilova ichidagi sozlamadan o'qiymiz (dialogsiz ON/OFF).
        syncKillSwitchState()'''


# ═══════════════════════════════════════════════════════════════════════
# NurVpnService: blackhole TUN
# ═══════════════════════════════════════════════════════════════════════
SVC_FIELD_OLD = '''    @Volatile private var isRestarting = false           // restart ketmoqda'''
SVC_FIELD_NEW = '''    @Volatile private var isRestarting = false           // restart ketmoqda
    /** ★ C5: kill-switch bloklovchi TUN faolmi? */
    @Volatile private var blockTun: android.os.ParcelFileDescriptor? = null'''

# cleanup ga bloklovchi TUN yopish
SVC_CLEANUP_OLD = '''        runCatching { tun?.close() }
        server = null
        tun = null'''
SVC_CLEANUP_NEW = '''        runCatching { tun?.close() }
        runCatching { blockTun?.close() }
        server = null
        tun = null
        blockTun = null'''

# Bloklovchi TUN va uni boshqarish funksiyalari
SVC_FUNCS = '''
    // ═══════════════════════════════════════════════════════════════════
    // ★ C5 KILL-SWITCH — BLACKHOLE TUN
    // ═══════════════════════════════════════════════════════════════════
    // Foydalanuvchi qarori (2026-11-15): ilova O'ZI bloklasin, tizim
    // "Always-on VPN" sozlamasi kerak emas.
    //
    // MEXANIZM: tunnel uzilganda ROUTESIZ TUN ochamiz. Tizim barcha
    // trafikni unga yo'naltiradi (u default route egasi), lekin biz
    // hech narsa o'qimaymiz -> paketlar QORA TUYNUQQA tushadi.
    // DNS ham qo'yilmaydi -> DNS so'rovlar ham o'ladi.
    //
    // Natija: real IP HECH QACHON oshkor bo'lmaydi.
    // ═══════════════════════════════════════════════════════════════════

    /** Kill-switch yoqilganmi? */
    private fun killSwitchOn(): Boolean =
        try { com.nurvpn.app.storage.KillSwitchStore.isEnabled(this@NurVpnService) } catch (t: Throwable) { false }

    /**
     * Bloklovchi TUN ochadi (kill-switch).
     *
     * ⚠️ ROUTE YO'Q — ataylab. Tizim "VPN bor" deb biladi va default
     *    route'ni shunga o'giradi; biz paketlarni o'qimaymiz.
     *
     * @return `true` — blok faol.
     */
    private fun startBlockTun(): Boolean {
        if (blockTun != null) return true          // allaqachon faol
        if (!killSwitchOn()) return false
        return try {
            // ★ Builder — VpnService'ning INNER class'i, shuning uchun
            //   tashqi sinf orqali (`this@NurVpnService`).
            val b = this@NurVpnService.Builder()

            // ── ★★★ C5 KRITIK TUZATISH (2026-11-15) ★★★ ──────────────
            // ILGARI: route QO'SHILMASDI → Android VPN hech narsani
            //         tutmasdi → trafik ODDIY tarmoqdan chiqib, REAL IP
            //         OSHKOR BO'LARDI. (Qurilmada tasdiqlangan: blok paytida
            //         `inbound connection to 1.1.1.1:853` o'tgan.)
            //
            // ENDI: 0.0.0.0/0 + ::/0 qo'shamiz → BARCHA trafik shu TUN'ga
            //       yo'naltiriladi. Biz uni O'QIMAYMIZ → paketlar qora
            //       tuynuqqa tushadi → hech qanday IP oshkor bo'lmaydi.
            // ───────────────────────────────────────────────────────────
            b.addAddress("10.255.255.1", 32)
            b.addAddress("fd00:0:0:0:0:0:0:1", 128)
            b.setMtu(1280)

            // ★★ BARCHA trafik — bloklovchi TUN orqali (qora tuynuq)
            b.addRoute("0.0.0.0", 0)
            b.addRoute("::", 0)

            // ✅ O'ZIMIZNI blokdan chiqaramiz (`10.255.255.1` yo'q — oddiy
            //    tarmoqdan chiqadi). BOSHQA ilovalar blokda qoladi.
            runCatching { b.addDisallowedApplication(this@NurVpnService.packageName) }

            blockTun = b.establish()
            val ok = blockTun != null
            android.util.Log.w(TAG,
                if (ok) "🛡 KILL-SWITCH FAOLLASHDI — trafik bloklandi"
                else "⚠️ Kill-switch TUN ochilmadi")
            ok
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Kill-switch TUN xatosi", t)
            false
        }
    }

    /** Bloklovchi TUN ni yopadi (tunnel tiklandi). */
    private fun stopBlockTun() {
        if (blockTun == null) return
        runCatching { blockTun?.close() }
        blockTun = null
        android.util.Log.i(TAG, "🛡 Kill-switch o'chdi — trafik tiklandi")
    }

    /**
     * Tunnel holatiga qarab blokni boshqaradi.
     *
     * CHAQIRUV NUQTALARI (NurVpnService.kt):
     *   • connect MUVAFFAQIYATLI  → applyKillSwitch(true)   [blok ochiladi]
     *   • connect FAIL            → applyKillSwitch(false)  [BLOKLAYDI]
     *   • KUTILMAGAN XATO         → applyKillSwitch(false)  [BLOKLAYDI]
     *   • onRevoke (boshqa VPN)   → applyKillSwitch(false)  [BLOKLAYDI]
     *   • PAUSE / STOP (o'zimiz)  → stopBlockTun()          [blok ochiladi]
     */
    private fun applyKillSwitch(tunnelAlive: Boolean) {
        if (tunnelAlive) stopBlockTun() else startBlockTun()
    }
'''


# ═══════════════════════════════════════════════════════════════════════
# QO'SHIMCHA CHAQIRUV NUQTALARI (applyKillSwitch)
# ═══════════════════════════════════════════════════════════════════════
EXTRA_FIXES = [
    # connect MUVAFFAQIYATLI -> blok ochiladi
    ("""            // Shu orada STOP / yangi START kelgan bo'lsa — holatni "connected" qilmaymiz
            if (opSeq.get() != my) {
                Log.i(TAG, "connect[$my]: ulanish paytida bekor qilingan")
                return
            }""",
     """            // Shu orada STOP / yangi START kelgan bo'lsa — holatni "connected" qilmaymiz
            if (opSeq.get() != my) {
                Log.i(TAG, "connect[$my]: ulanish paytida bekor qilingan")
                return
            }
            // ★ C5: tunnel TIKLANDI — kill-switch blokini ochamiz.
            applyKillSwitch(true)"""),

    # KUTILMAGAN XATO -> BLOKLAYDI
    ("""                com.nurvpn.app.core.LogBuffer.add(
                    "FATAL-GUARD: ${t.javaClass.simpleName}: ${t.message}")
                try { cleanup() } catch (_: Throwable) {}""",
     """                com.nurvpn.app.core.LogBuffer.add(
                    "FATAL-GUARD: ${t.javaClass.simpleName}: ${t.message}")
                // ★ C5: KUTILMAGAN uzilish — foydalanuvchi to'xtatmagan,
                //   tunnel o'zi o'lgan. Kill-switch yoqilgan bo'lsa BLOKLAYMIZ.
                applyKillSwitch(false)
                try { cleanup() } catch (_: Throwable) {}"""),

    # connect FAIL -> BLOKLAYDI
    ("""            Log.e(TAG, "connect fail", t)
            com.nurvpn.app.core.LogBuffer.add("ERROR: ${t.message}")
            cleanup()""",
     """            Log.e(TAG, "connect fail", t)
            com.nurvpn.app.core.LogBuffer.add("ERROR: ${t.message}")
            // ★ C5: ulanish muvaffaqiyatsiz — tunnel YO'Q, bloklaymiz.
            applyKillSwitch(false)
            cleanup()"""),


    # tarmoq almashish oynasi (1.5 s) — BLOKLAYDI
    ("""                // b) Eski tunnelni yopish
                synchronized(lifecycleLock) {
                    try { server?.closeService() } catch (_: Throwable) {}
                    try { server?.close() } catch (_: Throwable) {}
                    try { tun?.close() } catch (_: Throwable) {}
                    server = null
                    tun = null
                }

                // c) 1 sekund kutish
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}""",
     """                // b) Eski tunnelni yopish
                synchronized(lifecycleLock) {
                    try { server?.closeService() } catch (_: Throwable) {}
                    try { server?.close() } catch (_: Throwable) {}
                    try { tun?.close() } catch (_: Throwable) {}
                    server = null
                    tun = null
                }

                // \u2605 C5 MUHIM: eski tunnel YOPILDI, yangisi hali YO'Q.
                //   Bu 1.5 s davomida trafik ochiq \u2014 real IP oshkor bo'ladi.
                //   Kill-switch yoqilgan bo'lsa SHU OYNADA bloklaymiz.
                applyKillSwitch(false)

                // c) 1 sekund kutish
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}"""),


    # ★★★ onLost — DARHOL bloklaydi (8 s oyna yopiladi)
    ("""                        Log.i(TAG, "✅ Tarmoq yo'qoldi — restart rejalashtirildi")
                        lastRestartTime = now
                        this@NurVpnService.scheduleNetworkRestart(NETWORK_STABILIZE_MS)""",
     """                        Log.i(TAG, "✅ Tarmoq yo'qoldi — restart rejalashtirildi")
                        // \u2605\u2605\u2605 C5 KRITIK TUZATISH (2026-11-15) \u2605\u2605\u2605
                        // Tarmoq yo'qoldi, lekin restart 8 soniya kutadi
                        // (NETWORK_STABILIZE_MS). SHU OYNADA tunnel uzilgan
                        // tarmoqqa bog'lanib qoladi va trafik ODDIY YO'LGA
                        // o'tib, REAL IP OSHKOR BO'LADI.
                        // DARHOL bloklaymiz \u2014 restart tugagach ochiladi.
                        applyKillSwitch(false)
                        lastRestartTime = now
                        this@NurVpnService.scheduleNetworkRestart(NETWORK_STABILIZE_MS)"""),

    # Bekor qilingan connect — blokni ochadi (abadiy blok xavfi)
    ("""            if (opSeq.get() != my) {
                Log.i(TAG, "connect[$my]: ulanish paytida bekor qilindi")
                return
            }
            // \u2605 C5: tunnel TIKLANDI \u2014 kill-switch blokini ochamiz.
            applyKillSwitch(true)""",
     """            if (opSeq.get() != my) {
                Log.i(TAG, "connect[$my]: ulanish paytida bekor qilindi")
                // \u2605 C5: bu connect bekor qilindi, lekin TUNNEL OCHILGAN.
                //   Blokni ochmasak \u2014 blok abadiy qolib, internet o'ladi.
                applyKillSwitch(true)
                return
            }
            // \u2605 C5: tunnel TIKLANDI \u2014 kill-switch blokini ochamiz.
            applyKillSwitch(true)"""),


    # ★★★ openTun — yangi TUN ochilgach blokni DARHOL tiklaydi
    ("""            tun = builder.establish() ?: throw Exception("TUN ochilmadi")
            Log.i(TAG, "═══ TUN OCHILDI: fd=${tun!!.fd} ═══")
            return tun!!.fd""",
     """            tun = builder.establish() ?: throw Exception("TUN ochilmadi")
            Log.i(TAG, "═══ TUN OCHILDI: fd=${tun!!.fd} ═══")
            // \u2605\u2605\u2605 C5 KRITIK (2026-11-15) \u2605\u2605\u2605
            // Yangi (libbox) TUN ochildi \u2014 Android endi SHUNI faol VPN deb
            // biladi va bloklovchi TUN AVTOMATIK O'LADI (bir vaqtda faqat
            // bitta VPN bo'ladi). `blockTun` ni tozalamasak, keyingi
            // `startBlockTun()` "allaqachon faol" deb o'ylab YANGI blok
            // ochmaydi. Shuning uchun tozalab, DARHOL qayta ochamiz.
            blockTun = null
            val newFd = tun!!.fd
            if (killSwitchOn()) {
                startBlockTun()
            }
            return newFd"""),

    # ★★★ doNetworkRestart — eski TUN yopilganda blockTun majburan tozalanadi
    ("""                // \u2605 C5 MUHIM: eski tunnel YOPILDI, yangisi hali YO'Q.""",
     """                // \u26a0\ufe0f `tun?.close()` bilan Android faol VPN'ni yo'qotadi
                //    va BLOKLOVCHI TUN ham o'ladi \u2014 `blockTun` non-null
                //    qolsa `startBlockTun()` "allaqachon faol" deb yangi blok
                //    ochmaydi (0.66 s bo'shliq, real IP oshkor).
                blockTun = null

                // \u2605 C5 MUHIM: eski tunnel YOPILDI, yangisi hali YO'Q."""),

    # PAUSE -> blok ochiladi
    ("""            Log.i(TAG, "PAUSE so'rovi")
            if (!isPaused) {
                isPaused = true""",
     """            Log.i(TAG, "PAUSE so'rovi")
            // ★ C5: PAUSE — foydalanuvchi o'zi to'xtatdi, blok OCHILADI.
            stopBlockTun()
            if (!isPaused) {
                isPaused = true"""),
]


def backup(path, root):
    d = os.path.join(root, BACKUP_DIR)
    os.makedirs(d, exist_ok=True)
    dst = os.path.join(d, "%s.ksnative.%s.bak" %
                       (os.path.basename(path), time.strftime("%Y%m%d_%H%M%S")))
    shutil.copy2(path, dst)
    return os.path.relpath(dst, root)


def replace_once(root, rel, old, new, dry, label):
    full = os.path.join(root, rel)
    if not os.path.exists(full):
        print("    ✘ %s topilmadi" % rel)
        return False
    t = io.open(full, encoding="utf-8").read()
    if new.strip() and new in t and old not in t:
        print("    ⏭  %s — allaqachon qo'llangan" % label)
        return True
    if old not in t:
        print("    ✘ %s — anchor topilmadi" % label)
        return False
    if dry:
        print("    • %s — o'zgaradi" % label)
        return True
    backup(full, root)
    io.open(full, "w", encoding="utf-8").write(t.replace(old, new, 1))
    print("    ✔ %s" % label)
    return True


def apply(root, dry):
    ok = True

    # ── 1. KillSwitchStore.kt ──
    print("  ── KillSwitchStore.kt ──")
    p = os.path.join(root, STORE)
    if os.path.exists(p):
        print("    ⏭  allaqachon mavjud")
    elif dry:
        print("    • yaratiladi")
    else:
        io.open(p, "w", encoding="utf-8").write(STORE_KT)
        print("    ✔ yaratildi")

    # ── 2. SettingsFragment: dialogsiz ──
    print("  ── SettingsFragment.kt (dialogsiz switch) ──")
    ok &= replace_once(root, SETTINGS, SET_OLD_LISTENER, SET_NEW_LISTENER, dry,
                       "switch listener (dialogsiz)")
    ok &= replace_once(root, SETTINGS, SET_OLD_SYNC, SET_NEW_SYNC, dry,
                       "syncKillSwitchState (ilova sozlamasi)")
    ok &= replace_once(root, SETTINGS, SET_OLD_INIT, SET_NEW_INIT, dry,
                       "onCreateView izohi")

    # ── 3a. onRevoke: boshqa VPN egalladi -> bloklaymiz ──
    print("  ── NurVpnService.kt (onRevoke) ──")
    ok &= replace_once(root, SVC,
        '''    override fun onRevoke() {
        // Boshqa VPN ilova ulanishni egallab oldi
        opSeq.incrementAndGet()''',
        '''    override fun onRevoke() {
        // Boshqa VPN ilova ulanishni egallab oldi
        opSeq.incrementAndGet()
        // ★ C5: kill-switch yoqilgan bo\'lsa — trafikni bloklaymiz
        applyKillSwitch(false)''',
        dry, "onRevoke bloklaydi")

    # ── 3. NurVpnService: blackhole TUN ──
    print("  ── NurVpnService.kt (blackhole TUN) ──")
    ok &= replace_once(root, SVC, SVC_FIELD_OLD, SVC_FIELD_NEW, dry, "blockTun maydoni")
    ok &= replace_once(root, SVC, SVC_CLEANUP_OLD, SVC_CLEANUP_NEW, dry, "cleanup blokni yopadi")

    # Funksiyalarni qo'shamiz (`openTun` funksiyasidan oldin)
    full = os.path.join(root, SVC)
    t = io.open(full, encoding="utf-8").read()
    if "startBlockTun" not in t:
        # ⚠️ `openTun` — libbox CALLBACK (`override fun`), `private fun` EMAS.
        #    8 bo'sh joy bilan boshlanadi.
        anchor = "        override fun openTun(options: TunOptions): Int {"
        if anchor in t:
            if dry:
                print("    • kill-switch funksiyalari qo'shiladi")
            else:
                backup(full, root)
                t = t.replace(anchor, SVC_FUNCS + "\n" + anchor, 1)
                io.open(full, "w", encoding="utf-8").write(t)
                print("    ✔ kill-switch funksiyalari qo'shildi")
        else:
            print("    ✘ openTun anchor topilmadi")
            for cand in ("        override fun openTun",
                         "    private fun doNetworkRestart",
                         "    override fun onRevoke"):
                if cand in t:
                    print("      ↳ mavjud anchor: %r" % cand)
                    break
            ok = False
    else:
        print("    ⏭  funksiyalar allaqachon bor")

    # ── 4. Qo'shimcha chaqiruv nuqtalari ──
    print("  ── NurVpnService.kt (chaqiruv nuqtalari) ──")
    for i, (o, nn) in enumerate(EXTRA_FIXES, 1):
        ok &= replace_once(root, SVC, o, nn, dry, "chaqiruv #%d" % i)

    return 0 if ok else 1


def check(root):
    print("  ══ CHECK ══\n")
    ok = True

    def has(rel, needle, label):
        nonlocal ok
        p = os.path.join(root, rel)
        g = os.path.exists(p) and needle in io.open(p, encoding="utf-8").read()
        ok = ok and g
        print("    %s %s" % ("✔" if g else "❌", label))

    has(STORE, "object KillSwitchStore", "KillSwitchStore mavjud")
    has(STORE, "fun isEnabled", "isEnabled")
    has(STORE, "fun setEnabled", "setEnabled")
    has(SETTINGS, "KillSwitchStore.setEnabled", "switch dialogsiz")
    has(SETTINGS, "showSystemVpnPrompt", "eski dialog OLIB TASHLANMAGAN")
    has(SVC, "startBlockTun", "bloklovchi TUN")
    has(SVC, "stopBlockTun", "blokni ochish")
    has(SVC, "applyKillSwitch", "avtomatik boshqarish")
    has(SVC, "blockTun", "blockTun maydoni")
    has(SVC, 'b.addRoute("0.0.0.0", 0)', "★ blok route IPv4")
    has(SVC, 'b.addRoute("::", 0)', "★ blok route IPv6")

    # Dialog chaqirilmasligi
    p = os.path.join(root, SETTINGS)
    t = io.open(p, encoding="utf-8").read()
    listener = re.search(r'killSwitch\?\.setOnCheckedChangeListener[^}]*?\}', t, re.S)
    g = bool(listener) and "showSystemVpnPrompt" not in listener.group(0)
    ok = ok and g
    print("    %s listener dialog CHAQIRMAYDI" % ("✔" if g else "❌"))

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def self_test():
    print("  ══ SELF-TEST ══\n")
    ok = True

    def t(label, cond):
        nonlocal ok
        ok = ok and cond
        print("    %s %s" % ("✔" if cond else "✘", label))

    t("Store: object", "object KillSwitchStore" in STORE_KT)
    t("Store: sukut O'CHIQ", 'getBoolean(KEY, false)' in STORE_KT)
    t("Store: setEnabled", "fun setEnabled" in STORE_KT)
    t("Store: MODE_PRIVATE", "Context.MODE_PRIVATE" in STORE_KT)
    t("Store: {} muvozanat", STORE_KT.count("{") == STORE_KT.count("}"))

    t("Listener: dialogsiz", "showSystemVpnPrompt" not in SET_NEW_LISTENER)
    t("Listener: store yozadi", "KillSwitchStore.setEnabled" in SET_NEW_LISTENER)
    t("Listener: {} muvozanat", SET_NEW_LISTENER.count("{") == SET_NEW_LISTENER.count("}"))

    t("Sync: store o'qiydi", "KillSwitchStore.isEnabled" in SET_NEW_SYNC)
    t("Sync: tizim lockdown EMAS", "KillSwitch.isLockdown" not in SET_NEW_SYNC)

    t("SVC: blockTun maydoni", "blockTun" in SVC_FIELD_NEW)
    t("SVC: cleanup yopadi", "blockTun?.close()" in SVC_CLEANUP_NEW)
    t("SVC: startBlockTun", "private fun startBlockTun" in SVC_FUNCS)
    t("SVC: stopBlockTun", "private fun stopBlockTun" in SVC_FUNCS)
    t("SVC: applyKillSwitch", "private fun applyKillSwitch" in SVC_FUNCS)
    # ★ Endi route QO'SHILISHI SHART (aks holda blok ishlamaydi!)
    t("SVC: 0.0.0.0/0 route", 'b.addRoute("0.0.0.0", 0)' in SVC_FUNCS)
    t("SVC: ::/0 route", 'b.addRoute("::", 0)' in SVC_FUNCS)
    t("SVC: IPv6 manzil", 'b.addAddress("fd00:0:0:0:0:0:0:1", 128)' in SVC_FUNCS)
    t("SVC: DNS qo'shilmaydi (to'g'ri)", "addDnsServer" not in SVC_FUNCS)
    t("SVC: Builder inner class", "this@NurVpnService.Builder()" in SVC_FUNCS)
    t("SVC: this@NurVpnService", "this@NurVpnService" in SVC_FUNCS)
    t("SVC: %d chaqiruv nuqtasi" % len(EXTRA_FIXES), len(EXTRA_FIXES) == 9)
    t("SVC: connect OK -> ochadi",
      any("applyKillSwitch(true)" in nn for _, nn in EXTRA_FIXES))
    t("SVC: xato -> bloklaydi",
      sum(1 for _, nn in EXTRA_FIXES if "applyKillSwitch(false)" in nn) >= 2)
    t("SVC: PAUSE -> stopBlockTun",
      any("stopBlockTun()" in nn for _, nn in EXTRA_FIXES))
    t("SVC: o'zini blokdan chiqaradi", "addDisallowedApplication" in SVC_FUNCS)
    t("SVC: try/catch himoyasi", "catch (t: Throwable)" in SVC_FUNCS)
    t("SVC: {} muvozanat", SVC_FUNCS.count("{") == SVC_FUNCS.count("}"))
    t("SVC: () muvozanat", SVC_FUNCS.count("(") == SVC_FUNCS.count(")"))

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
    print("  ══ C5: KILL-SWITCH (ilova o'zi, dialogsiz) ══\n")
    rc = apply(root, dry)
    if rc == 0:
        print("\n  Keyingi qadam:\n    ./gradlew assembleDebug --offline")
    return rc


if __name__ == "__main__":
    sys.exit(main())
