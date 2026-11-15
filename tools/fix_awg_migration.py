#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_awg_migration.py — Diskdagi eski MASQUE configlarni kalit/server bo'yicha migratsiya
========================================================================================

═══════════════════════════════════════════════════════════════════════
MUAMMO (foydalanuvchi tashxisi, TASDIQLANGAN)
═══════════════════════════════════════════════════════════════════════

`AWGStore.normalizeMasqueH2()` faqat `Network` va `MTU` ni o'zgartiradi.
`Server` va `PublicKey` ga TEGMAYDI.

Natijada `BuiltinAwgConfigs.kt` da kalit tuzatilsa ham, qurilmada
`shared_prefs/awg.xml` ichida ESKI (buzilgan) `rawConf` qoladi.

`MainActivity` esa `rawConf ==` bo'yicha solishtiradi → eski nusxa
o'chmaydi, yoniga yangisi qo'shiladi → foydalanuvchi ikkitasini
birga ko'radi (biri ishlamaydi).

═══════════════════════════════════════════════════════════════════════
YECHIM — IKKI QISM
═══════════════════════════════════════════════════════════════════════

1) `AWGStore.normalizeMasqueH2()` ni kengaytiramiz: `Server` va
   `PublicKey` juftligini MA'LUM jadval bo'yicha tuzatadi.
   Jadval — TLS orqali O'LCHANGAN (taxmin emas):

        GURUH A: 197.1 197.3 197.4 192.1 193.1 194.1 195.1 196.1
        GURUH B: 197.2 197.5 198.2 199.2

   Har bir server uchun HAQIQIY sertifikat kaliti yoziladi.

2) `BuiltinAwgConfigs.kt` dagi configlar bilan solishtirib,
   diskdagi `awg.xml` ni yangilash uchun `--dump-device` (qo'lda
   ishlatish uchun, adb orqali).

ISHLATISH:
    python3 tools/fix_awg_migration.py --root . --apply
    python3 tools/fix_awg_migration.py --root . --check
    python3 tools/fix_awg_migration.py --root . --dump-device /tmp/awg.xml
    python3 tools/fix_awg_migration.py --self-test
"""

import argparse
import base64
import datetime
import html
import json
import os
import re
import shutil
import sys

STORE = "app/src/main/java/com/nurvpn/app/storage/AWGStore.kt"
CFG = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"
MARK = "FIX_AWG_MIGRATION"

# ── TLS orqali O'LCHANGAN haqiqiy sertifikat kalitlari (SNI=4pda.to) ──
KEY_A = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7"
         "eNbWwRQb5dzmIDq7b3qaidcGNR/qxBCaa8reMTK63bMGpwW9J/H59w==")
KEY_B = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI"
         "8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==")

GROUP_A = ["162.159.197.1", "162.159.197.3", "162.159.197.4", "162.159.192.1",
           "162.159.193.1", "162.159.194.1", "162.159.195.1", "162.159.196.1"]
GROUP_B = ["162.159.197.2", "162.159.197.5", "162.159.198.2", "162.159.199.2"]

SERVER_KEY = {ip: KEY_A for ip in GROUP_A}
SERVER_KEY.update({ip: KEY_B for ip in GROUP_B})

# ⚠️ GURUH A va B kalitlari HAR XIL. Server bilan PublicKey juftligini
#    buzmang — aks holda "different public key than pinned" (693 marta
#    kuzatilgan, 2026-11-15).


def key_of(server):
    return SERVER_KEY.get(server)


# ─────────────────────────── Kotlin patch ───────────────────────────

KOTLIN_INSERT = '''
    /**
     * ★ {MARK}: MASQUE `Server` ↔ `PublicKey` juftligini tuzatadi.
     *
     * ILDIZ SABAB (qurilmada o'lchangan, 2026-11-15):
     *   `normalizeMasqueH2()` faqat `Network` va `MTU` ni o'zgartirardi.
     *   `Server` o'zgartirilib, `PublicKey` eski qolsa, yadro TLS
     *   sertifikatini pin qila olmaydi:
     *
     *       masque: remote endpoint has a different public key than pinned
     *
     *   (693 marta kuzatilgan — tarmoq butunlay ishlamagan).
     *
     * KALIT GURUHLARI (TLS SPKI, SNI=4pda.to, HAQIQIY o'lchov):
     *   GURUH A: 197.1 197.3 197.4 192.1 193.1 194.1 195.1 196.1
     *   GURUH B: 197.2 197.5 198.2 199.2
     *
     * ⚠️ GURUHLAR HAR XIL. Yangi server qo'shsangiz — kalitini TLS
     *    orqali o'lchang (`tools/fix_server_pubkey.py --probe`).
     */
    private val SERVER_PUBKEY: Map<String, String> = mapOf(
        "162.159.197.1" to MASQUE_PUB_A,
        "162.159.197.3" to MASQUE_PUB_A,
        "162.159.197.4" to MASQUE_PUB_A,
        "162.159.192.1" to MASQUE_PUB_A,
        "162.159.193.1" to MASQUE_PUB_A,
        "162.159.194.1" to MASQUE_PUB_A,
        "162.159.195.1" to MASQUE_PUB_A,
        "162.159.196.1" to MASQUE_PUB_A,
        "162.159.197.2" to MASQUE_PUB_B,
        "162.159.197.5" to MASQUE_PUB_B,
        "162.159.198.2" to MASQUE_PUB_B,
        "162.159.199.2" to MASQUE_PUB_B
    )

    private const val MASQUE_PUB_A =
        "{KEY_A}"

    private const val MASQUE_PUB_B =
        "{KEY_B}"

    /**
     * `PublicKey` ni `Server` ga moslashtiradi.
     * Noma'lum server — tegilmaydi (kalit saqlanadi).
     */
    private fun normalizeMasqueKeys(raw: String): String {
        var srv = ""
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.startsWith("Server") && t.contains("=")) {
                srv = t.substringAfter("=").trim().substringBefore(":"); break
            }
            if (t.startsWith("Endpoint") && t.contains("=")) {
                srv = t.substringAfter("=").trim().substringBefore(":"); break
            }
        }
        val want = SERVER_PUBKEY[srv] ?: return raw
        return raw.replace(
            Regex("""(?im)^([ \\t]*)PublicKey[ \\t]*=[ \\t]*\\S+[ \\t]*$""")
        ) { m -> m.groupValues[1] + "PublicKey = " + want }
    }
'''


def patch_kotlin(root, dry):
    p = os.path.join(root, STORE)
    if not os.path.isfile(p):
        print("  ✘ topilmadi:", p)
        return 1
    with open(p, encoding="utf-8") as f:
        s = f.read()
    if MARK in s:
        print("  ⏭  allaqachon qo'llangan (Kotlin)")
        return 0

    body = KOTLIN_INSERT.replace("{KEY_A}", KEY_A).replace("{KEY_B}", KEY_B)
    body = body.replace("{MARK}", MARK)

    # 1) Konstanta va map ni qo'shamiz — `normalizeMasqueH2` oldiga
    anchor = "    /** ★ PATCH-H2: MASQUE confni majburan h2 + MTU<=1280 ga keltirish. */"
    if anchor not in s:
        print("  ✘ anchor topilmadi (normalizeMasqueH2 docstring)")
        return 1
    s2 = s.replace(anchor, body.strip("\n") + "\n\n" + anchor, 1)

    # 2) `normalizeMasqueH2` oxirida kalitlarni ham normallashtiramiz
    old_ret = """        out = out.replace(Regex(\"\"\"(?im)^([ \\t]*)MTU[ \\t]*=[ \\t]*(\\d+)[ \\t]*$\"\"\")) { m ->
            val v = m.groupValues[2].toIntOrNull() ?: 1280
            m.groupValues[1] + "MTU = " + v.coerceIn(576, 1280)
        }
        return out
    }"""
    new_ret = """        out = out.replace(Regex(\"\"\"(?im)^([ \\t]*)MTU[ \\t]*=[ \\t]*(\\d+)[ \\t]*$\"\"\")) { m ->
            val v = m.groupValues[2].toIntOrNull() ?: 1280
            m.groupValues[1] + "MTU = " + v.coerceIn(576, 1280)
        }
        // ★ %s: Server o'zgargan bo'lsa — PublicKey ni ham moslashtiramiz.
        //   Aks holda: "masque: remote endpoint has a different public key than pinned"
        out = normalizeMasqueKeys(out)
        return out
    }""" % MARK
    if old_ret not in s2:
        print("  ✘ anchor topilmadi (MTU replace bloki)")
        return 1
    s2 = s2.replace(old_ret, new_ret, 1)

    print("  ▪ AWGStore.kt: kalit map + normalizeMasqueKeys() qo'shiladi")
    if dry:
        print("  (dry-run) yozilmadi")
        return 0

    d = os.path.join(root, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    b = os.path.join(d, "AWGStore.kt.migration.%s.bak" % ts)
    shutil.copy2(p, b)
    with open(p, "w", encoding="utf-8") as f:
        f.write(s2)
    print("  backup: %s" % os.path.relpath(b, root))
    print("  ✔ yozildi")
    return 0


def check(root):
    p = os.path.join(root, STORE)
    if not os.path.isfile(p):
        print("  ✘ topilmadi")
        return 1
    s = open(p, encoding="utf-8").read()
    ok = True
    print("\n── Kotlin migratsiya ──")
    for label, pat in [("MARK", MARK),
                       ("SERVER_PUBKEY map", "SERVER_PUBKEY: Map"),
                       ("normalizeMasqueKeys()", "fun normalizeMasqueKeys"),
                       ("chaqiruv", "out = normalizeMasqueKeys(out)"),
                       ("GURUH A kaliti", "MASQUE_PUB_A"), ("GURUH B kaliti", "MASQUE_PUB_B")]:
        g = pat in s
        ok = ok and g
        print("  %s %s" % ("✔" if g else "✘", label))
    print("\n── Jadval to'liqligi ──")
    for ip, k in sorted(SERVER_KEY.items()):
        g = ip in s
        ok = ok and g
        if not g:
            print("  ✘ %s jadvalda YO'Q" % ip)
    if ok:
        print("  ✔ 12/12 server kiritilgan")
    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def dump_device(root, out_path):
    """BuiltinAwgConfigs'dan qurilma uchun awg.xml yasaydi."""
    p = os.path.join(root, CFG)
    if not os.path.isfile(p):
        print("  ✘ topilmadi:", p)
        return 1
    src = open(p, encoding="utf-8").read()
    bs = re.findall(r'val (MASQUE_\d+): String = """(.*?)"""', src, re.S)
    recs = []
    for n, b in bs:
        recs.append({
            "raw": b.strip(),
            "name": n.replace("_", " "),
            "endpoint": re.search(r'(?m)^Server\s*=\s*(\S+)', b).group(1) + ":443",
            "address": re.search(r'(?m)^Address\s*=\s*(\S+)', b).group(1).rstrip(','),
            "type": "masque",
        })
    js = json.dumps(recs, ensure_ascii=False, separators=(",", ":"))
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           '    <string name="configs">' + html.escape(js, quote=True) + "</string>\n</map>\n")
    import xml.etree.ElementTree as ET
    ET.fromstring(xml)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(xml)
    print("  ✔ %s (%d config)" % (out_path, len(recs)))
    return 0


def self_test():
    ok = True
    print("\n── Guruh jadvali ──")
    g = len(SERVER_KEY) == 12 and len(GROUP_A) + len(GROUP_B) == 12
    ok = ok and g
    print("  %s 12 server (A=%d, B=%d)" % ("✔" if g else "✘", len(GROUP_A), len(GROUP_B)))

    print("\n── Kalitlar 91 bayt P-256 ──")
    for lbl, k, grp in [("A", KEY_A, GROUP_A), ("B", KEY_B, GROUP_B)]:
        b = base64.b64decode(k)
        g = len(b) == 91 and b[:2] == bytes([0x30, 0x59])
        ok = ok and g
        print("  %s GURUH %s: %d bayt, %d server" % ("✔" if g else "✘", lbl, len(b), len(grp)))

    print("\n── A ≠ B ──")
    g = KEY_A != KEY_B
    ok = ok and g
    print("  %s kalitlar har xil (pin buzilmasligi uchun shart)" % ("✔" if g else "✘"))

    print("\n── key_of() ──")
    for ip, want in [("162.159.198.2", "B"), ("162.159.197.2", "B"),
                     ("162.159.197.5", "B"), ("162.159.199.2", "B"),
                     ("162.159.197.1", "A"), ("162.159.197.4", "A")]:
        k = key_of(ip)
        got = "B" if k == KEY_B else ("A" if k == KEY_A else "?")
        g = got == want
        ok = ok and g
        print("  %s %-16s → GURUH %s" % ("✔" if g else "✘", ip, got))
    g = key_of("10.0.0.1") is None
    ok = ok and g
    print("  %s noma'lum server → None (tegilmaydi)" % ("✔" if g else "✘"))

    print("\n── Kotlin shabloni ──")
    body = KOTLIN_INSERT.replace("{KEY_A}", KEY_A).replace("{KEY_B}", KEY_B).replace("{MARK}", MARK)
    g = "SERVER_PUBKEY" in body and "normalizeMasqueKeys" in body and MARK in body
    ok = ok and g
    print("  %s shablon to'g'ri" % ("✔" if g else "✘"))
    g = body.count('\\"') == 0
    ok = ok and g
    print("  %s qochirilgan qo'shtirnoq yo'q" % ("✔" if g else "✘"))

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--dump-device", metavar="OUT")
    a = ap.parse_args()
    root = os.path.abspath(a.root)

    if a.self_test:
        return self_test()
    if a.check:
        return check(root)
    if a.dump_device:
        return dump_device(root, a.dump_device)

    print("\n══ AWG MIGRATSIYA (Server ↔ PublicKey) ══")
    rc = patch_kotlin(root, a.dry_run)
    if rc == 0 and not a.dry_run:
        print("\n  Keyingi qadam:")
        print("    python3 tools/fix_awg_migration.py --root . --check")
        print("    ./gradlew assembleDebug test --offline")
    print()
    return rc


if __name__ == "__main__":
    sys.exit(main())
