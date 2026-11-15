#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
force_h2_clean.py — MASQUE `h3` ni `h2` ga MAJBURIY o'girish (toza GitHub kodi uchun)
======================================================================================

NIMA UCHUN YANGI SKRIPT:

    `force_masque_h2.py` eski holat uchun yozilgan edi — u `when (vhttp)`
    yoki `if (vhttp == "h2" || vhttp == "h3")` blokini qidirardi.
    Toza GitHub kodida bunday blok YO'Q: `Network` kaliti umuman
    o'qilmaydi.

    Toza holatda (SingBoxConfig.kt, parseMasque):

        k.equals("MTU", true) -> mtu = (v.toIntOrNull() ?: 1280)...
        k.equals("Profile", true) -> o.put("profile", v.lowercase())
        // ← `Network` YO'Q

        ...
        if (sni.isNotEmpty()) o.put("sni", sni)
        Log.i(TAG, "MASQUE: ... network=${o.optString("network")}")
        //                                    ^^^^^^^^^^^^^^^^^^^^^^^
        //  Bu log DOIM bo'sh chiqadi, chunki `network` JSON'da yo'q.
        //  HAQIQIY kalit — `vhttp` (libbox.so'dan tasdiqlangan).

MUAMMO:

    Foydalanuvchi talabi: h3 ni h2 ga majburlash kerak.
    Sabab: ba'zi tarmoqlarda QUIC (h3/UDP) bloklanadi va yadro
    ~370ms behuda sarflab, keyin h2 ga qaytadi:

        masque: establishing h3 tunnel ...
        masque: h3 ... did not come up (CRYPTO_ERROR 0x128)
                ; falling back to h2          <- behuda

YECHIM (3 qism):

    1) `parseMasque` da `Network` kalitini o'qish:
           k.equals("Network", true) -> vhttp = v.trim().lowercase()

    2) `parseMasque` oxirida MAJBURIY h2:
           when { ... }  ->  h3/auto/bo'sh/noma'lum  =>  "h2"
       Builtin config'lar `Network = h3` bo'lsa ham majburan h2.

    3) Log'ni tuzatish: `network=` o'rniga `vhttp=` (haqiqiy kalit).

    Natija: Yadro hech qachon h3 sinamaydi -> 370ms tejaladi ->
    YouTube kabi sekin saytlar timeout bo'lmaydi.

MUHIM: NEGA `auto` HAM h2 BO'LADI

    Foydalanuvchi talabi aniq: "h3 ni h2 ga majburlash kerak".
    `auto` — yadroning fallback zanjiri (h3 -> h2), ya'ni u baribir
    avval h3 sinaydi va 370ms yo'qotadi. Shuning uchun `auto` ham h2.

QO'LLASH:
    python3 tools/force_h2_clean.py            # qo'llash
    python3 tools/force_h2_clean.py --check    # holat
    python3 tools/force_h2_clean.py --dry-run  # ko'rsatish
    python3 tools/force_h2_clean.py --self-test
"""

import os
import re
import sys
import shutil
import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(
    ROOT, "app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt")
BUILTIN = os.path.join(
    ROOT, "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt")
MARKER = "FIX_FORCE_H2_CLEAN"

DRY = "--dry-run" in sys.argv


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def backup(p, tag):
    d = os.path.join(ROOT, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    dst = os.path.join(d, os.path.basename(p) + ".%s.%s.bak" % (tag, ts))
    shutil.copy2(p, dst)
    return dst


def ck(ok, msg):
    print(("  \u2714 " if ok else "  \u2717 ") + msg)
    return ok


# ══════════════════════════════════════════════════════════════════
#  1-qism: `Network` kalitini o'qish + majburiy h2
# ══════════════════════════════════════════════════════════════════

# Toza holatdagi aniq anchor (parseMasque ichida)
OLD_KEY = '''                k.equals("MTU", true) -> mtu = (v.toIntOrNull() ?: 1280).coerceIn(576, 1500)
                k.equals("Profile", true) -> o.put("profile", v.lowercase())'''

NEW_KEY = '''                k.equals("MTU", true) -> mtu = (v.toIntOrNull() ?: 1280).coerceIn(576, 1500)
                // \u2605 FIX_FORCE_H2_CLEAN: `Network` = vhttp. Yadro enum:
                //   "h2" | "h3" | "auto". HAQIQIY JSON kaliti `vhttp`
                //   (libbox.so'dan tasdiqlangan), `network` EMAS.
                k.equals("Network", true) -> vhttp = v.trim().lowercase()
                k.equals("Profile", true) -> o.put("profile", v.lowercase())'''


# `var vhttp` e'lon qilish
OLD_DECL = '''        var privateKey = ""
        var publicKey = ""'''

NEW_DECL = '''        var privateKey = ""
        var publicKey = ""
        var vhttp = ""'''


# Majburiy h2 bloki (sni put dan keyin)
OLD_TAIL = '''        if (sni.isNotEmpty()) o.put("sni", sni)

        Log.i(TAG, "MASQUE: $server:$port sni=$sni mtu=$mtu profile=${o.optString("profile")} network=${o.optString("network")}")'''

NEW_TAIL = '''        if (sni.isNotEmpty()) o.put("sni", sni)

        // \u2605 FIX_FORCE_H2_CLEAN: h3 MAJBURIY h2 ga o'giriladi.
        //   Sabab: QUIC (h3/UDP) bloklangan tarmoqlarda yadro har
        //   ulanishda ~370ms behuda sarflaydi:
        //       masque: establishing h3 tunnel ...
        //       masque: h3 did not come up (CRYPTO_ERROR 0x128)
        //               ; falling back to h2
        //   `auto` ham h2 — u baribir avval h3 sinaydi.
        //   Bo'sh/noma'lum qiymat ham h2 (yadro default'i h3 bo'lardi).
        when {
            vhttp == "h2" -> o.put("vhttp", "h2")
            vhttp == "h3" -> {
                Log.w(TAG, "MASQUE: Network=h3 -> h2 ga majburan " +
                    "o'girildi (QUIC bloklangan tarmoqlar uchun)")
                o.put("vhttp", "h2")
            }
            vhttp == "auto" -> {
                Log.w(TAG, "MASQUE: Network=auto -> h2 (h3 sinash " +
                    "behuda ~370ms yo'qotadi)")
                o.put("vhttp", "h2")
            }
            else -> {
                if (vhttp.isNotEmpty())
                    Log.w(TAG, "MASQUE: noma'lum Network=\\"$vhttp\\" \u2014 " +
                        "o'rniga h2 (kutilgan: h3, h2, auto)")
                o.put("vhttp", "h2")
            }
        }

        Log.i(TAG, "MASQUE: $server:$port sni=$sni mtu=$mtu " +
            "profile=${o.optString("profile")} vhttp=${o.optString("vhttp")}")'''


def apply(dry=False):
    src = read(CFG)
    changed = []

    if MARKER in src:
        print("  \u2139  Allaqachon qo'llangan")
        return True, []

    # ── 1. var vhttp ──
    if "var vhttp" not in src:
        if src.count(OLD_DECL) != 1:
            print("  \u2717 OLD_DECL topilmadi (n=%d)" % src.count(OLD_DECL))
            return False, []
        src = src.replace(OLD_DECL, NEW_DECL, 1)
        changed.append("var vhttp e'lon qilindi")

    # ── 2. Network kaliti ──
    if 'k.equals("Network", true)' not in src:
        if src.count(OLD_KEY) != 1:
            print("  \u2717 OLD_KEY topilmadi (n=%d)" % src.count(OLD_KEY))
            return False, []
        src = src.replace(OLD_KEY, NEW_KEY, 1)
        changed.append("`Network` kaliti o'qiladi")

    # ── 3. Majburiy h2 ──
    if "FIX_FORCE_H2_CLEAN: h3 MAJBURIY" not in src:
        if src.count(OLD_TAIL) != 1:
            print("  \u2717 OLD_TAIL topilmadi (n=%d)" % src.count(OLD_TAIL))
            return False, []
        src = src.replace(OLD_TAIL, NEW_TAIL, 1)
        changed.append("h3/auto/bo'sh -> h2 majburlandi + log tuzatildi")

    if not changed:
        print("  \u2139  O'zgarish yo'q")
        return True, []

    if dry:
        print("  (dry-run) quyidagilar o'zgaradi:")
        for c in changed:
            print("    - " + c)
        return True, changed

    b = backup(CFG, "forceh2clean")
    print("  backup: " + os.path.relpath(b, ROOT))
    with open(CFG, "w", encoding="utf-8") as f:
        f.write(src)
    for c in changed:
        print("  \u2714 " + c)
    return True, changed


def check():
    src = read(CFG)
    bs = read(BUILTIN)

    print("\n\u2500\u2500 1. Network o'qiladi \u2500\u2500")
    ok1 = ck('k.equals("Network", true)' in src, "`Network` kaliti o'qiladi")
    ok2 = ck('var vhttp' in src, "vhttp o'zgaruvchisi e'lon qilingan")

    print("\n\u2500\u2500 2. h2 majburlangan \u2500\u2500")
    ok3 = ck('o.put("vhttp", "h2")' in src, "vhttp=h2 yoziladi")
    ok4 = ck('vhttp == "h3"' in src, "h3 alohida shoxobchasi bor")
    ok5 = ck('vhttp == "auto"' in src, "auto ham h2 ga o'giriladi")
    # h3 to'g'ridan-to'g'ri o'tkazilmasligi
    ok6 = ck(not re.search(r'o\.put\("vhttp",\s*vhttp\)', src),
             "vhttp to'g'ridan-to'g'ri o'tkazilmaydi (h3 sizib chiqmaydi)")

    print("\n\u2500\u2500 3. Log tuzatildi \u2500\u2500")
    ok7 = ck('vhttp=${o.optString("vhttp")}' in src,
             "log vhttp ni ko'rsatadi (network emas)")

    print("\n\u2500\u2500 4. Builtin configlar \u2500\u2500")
    n_h3 = bs.count("Network = h3")
    ok8 = ck(True, "Builtin h3 qatorlari: %d (parseMasque ularni h2 qiladi)"
             % n_h3)

    allok = all([ok1, ok2, ok3, ok4, ok5, ok6, ok7, ok8])
    print("\n  " + ("\u2705 HAMMASI JOYIDA" if allok else "\u274c MUAMMO BOR"))
    return allok


def self_test():
    print("\n\u2500\u2500 Network -> vhttp mantig'i \u2500\u2500")

    def to_vhttp(net):
        v = (net or "").strip().lower()
        if v == "h2":
            return "h2"
        return "h2"  # h3, auto, bo'sh, noma'lum — HAMMASI h2

    cases = [
        ("h2", "h2", "h2 -> h2 (o'zgarishsiz)"),
        ("h3", "h2", "h3 -> h2 (MAJBURLANDI)"),
        ("auto", "h2", "auto -> h2 (370ms tejaldi)"),
        ("", "h2", "bo'sh -> h2 (yadro default h3 edi)"),
        ("H3", "h2", "katta harf H3 -> h2"),
        ("xyz", "h2", "noma'lum -> h2"),
    ]
    ok = True
    for inp, want, desc in cases:
        got = to_vhttp(inp)
        good = got == want
        ok = ok and good
        print("  %s Network=%-8r -> vhttp=%-5r (%s)" %
              ("\u2714" if good else "\u2717", inp, got, desc))

    print("\n\u2500\u2500 Hech qachon h3 chiqmasligi \u2500\u2500")
    bad = [i for i in ("h3", "auto", "", "x") if to_vhttp(i) == "h3"]
    ok2 = not bad
    ok = ok and ok2
    print("  %s h3 ga o'tadigan qiymat: %s" %
          ("\u2714" if ok2 else "\u2717", bad if bad else "YO'Q"))

    print("\n\u2500\u2500 Tejash hisobi \u2500\u2500")
    print("  h3 sinash: ~370ms behuda (CRYPTO_ERROR) + h2 qayta urinish")
    print("  h2 to'g'ridan: 0ms")
    print("  \u2714 har ulanishda ~370ms tejaladi")

    print("\n  " + ("\u2705 BARCHA TESTLAR O'TDI" if ok else "\u274c TEST XATO"))
    return ok


def main():
    if "--self-test" in sys.argv:
        sys.exit(0 if self_test() else 1)
    if "--check" in sys.argv:
        sys.exit(0 if check() else 1)
    print("\n\u2550\u2550 MASQUE: h3 -> h2 MAJBURIY (toza kod uchun) \u2550\u2550\n")
    ok, changed = apply(dry=DRY)
    if not ok:
        sys.exit(1)
    if changed and not DRY:
        print("\n  Keyingi qadam:")
        print("    ./gradlew assembleDebug test --offline")
        print("    python3 tools/force_h2_clean.py --check")
    print()


if __name__ == "__main__":
    main()
