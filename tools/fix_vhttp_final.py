#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_vhttp_final.py — MASQUE `vhttp` ni .conf `Network` dan o'qish
================================================================

Bu `/home/pc/masque_vhttp.py` + `/home/pc/fix_masque_logic.py`
(foydalanuvchining o'zi yozgan ishlaydigan yechim) ni
`nurvpn_patch.py` hosil qilgan holatga moslashtirilgan varianti.

═══════════════════════════════════════════════════════════════════════
NIMA XATO EDI
═══════════════════════════════════════════════════════════════════════

`nurvpn_patch.py` ikki xato qildi:

  1. `o.put("network", "h2")` qo'ydi.
     → Yadro (libbox) bu kalitni BILMAYDI. Stringlar ichida faqat
       "masque: invalid vhttp: " bor. Ya'ni to'g'ri kalit — **`vhttp`**.

  2. `tunMtu = 1280` qilib qo'ydi (ishlagan versiyada 1400 edi).

Foydalanuvchi BUNI OLDIN tuzatgan edi (`fix_masque_logic.py`), lekin
keyingi patch uni qayta buzdi.

═══════════════════════════════════════════════════════════════════════
FOYDALANUVCHINING ISHLAYDIgan YECHIMI
═══════════════════════════════════════════════════════════════════════

/masque_vhttp.py:
    var vhttp = ""
    k.equals("Network", true) -> vhttp = v.trim().lowercase()
    if (vhttp == "h2" || vhttp == "h3") o.put("vhttp", vhttp)

/fix_masque_logic.py:
    o.put("network", ...) ni OLIB TASHLASH
    tunMtu = 1400 ga qaytarish

MUHIM: `auto` YOZILMAYDI (faqat h2/h3) — chunki `auto` = h3 birinchi,
TSPU uni bloklaydi.

ISHLATISH:
    python3 tools/fix_vhttp_final.py --root .
    python3 tools/fix_vhttp_final.py --root . --check
    python3 tools/fix_vhttp_final.py --root . --dry-run
    python3 tools/fix_vhttp_final.py --self-test
"""

import argparse
import datetime
import os
import re
import shutil
import sys

CFG = "app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt"
MARK = "FIX_VHTTP_FINAL"


def fz(s):
    return r"\s+".join(map(re.escape, s.split()))


def load(root, rel=CFG):
    p = os.path.join(root, rel)
    if not os.path.isfile(p):
        return None, p
    with open(p, encoding="utf-8") as f:
        return f.read(), p


def backup(root, rel):
    d = os.path.join(root, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    dst = os.path.join(d, os.path.basename(rel) + ".vhttpfinal.%s.bak" % ts)
    shutil.copy2(os.path.join(root, rel), dst)
    return dst


def transform(t):
    """(yangi_matn, bajarilgan_qadamlar)"""
    steps = []

    # ── 1. vhttp o'zgaruvchisini parseMasque ICHIDA e'lon qilish ──
    if re.search(r"(?m)^\s*var vhttp\s*=", t):
        steps.append(("SKIP", "var vhttp allaqachon bor"))
    else:
        # parseMasque funksiyasining boshlanishini topamiz
        fm = re.search(r"private fun parseMasque\b[^{]*\{", t)
        if not fm:
            steps.append(("FAIL", "parseMasque topilmadi"))
        else:
            # funksiya ichidagi `var mtu = ...` ni topamiz
            mm = re.compile(r"var mtu = \d+").search(t, fm.end())
            if not mm:
                steps.append(("FAIL", "parseMasque ichida `var mtu` topilmadi"))
            else:
                ind = re.match(r"[ \t]*", t[t.rfind("\n", 0, mm.start()) + 1:]).group(0)
                t = t[:mm.end()] + "\n" + ind + 'var vhttp = ""' + t[mm.end():]
                steps.append(("OK", "var vhttp parseMasque ichiga qo'shildi"))

    # ── 2. Network ni .conf dan o'qish ──
    if 'vhttp = v.trim().lowercase()' in t:
        steps.append(("SKIP", "Network o'qish bor"))
    else:
        pat = re.compile(fz('k.equals("Profile", true) -> o.put("profile", v.lowercase())'))
        if not pat.search(t):
            steps.append(("FAIL", "Profile qatori topilmadi"))
        else:
            t = pat.sub(
                'k.equals("Profile", true) -> o.put("profile", v.lowercase())\n'
                '                k.equals("Network", true) -> vhttp = v.trim().lowercase()',
                t, count=1)
            steps.append(("OK", "Network -> vhttp o'qish"))

    # ── 3. QATTIQ o.put("vhttp","h2") ni olib tashlash ──
    hard = re.compile(
        r'[ \t]*//\s*★\s*FIX_VHTTP_KEY[^\n]*\n'
        r'[ \t]*//[^\n]*\n'
        r'[ \t]*o\.put\("vhttp",\s*"h2"\)\n')
    if hard.search(t):
        t = hard.sub("", t, count=1)
        steps.append(("OK", "qattiq o.put(vhttp,h2) olib tashlandi"))
    else:
        hard2 = re.compile(r'[ \t]*o\.put\("vhttp",\s*"h2"\)\n')
        if hard2.search(t):
            t = hard2.sub("", t, count=1)
            steps.append(("OK", "qattiq o.put(vhttp,h2) olib tashlandi"))
        else:
            steps.append(("SKIP", "qattiq vhttp yo'q"))

    # ── 4. shartli yozish (faqat h2/h3 — auto EMAS) ──
    if 'if (vhttp == "h2"' in t:
        steps.append(("SKIP", "shartli yozish bor"))
    else:
        pat = re.compile(fz('if (sni.isNotEmpty()) o.put("sni", sni)'))
        if not pat.search(t):
            steps.append(("FAIL", "sni qatori topilmadi"))
        else:
            t = pat.sub(
                'if (sni.isNotEmpty()) o.put("sni", sni)\n'
                '        // ★ %s: faqat h2/h3 yoziladi — `auto` = h3 birinchi,\n'
                '        //   TSPU QUIC ni bloklaydi.\n'
                '        if (vhttp == "h2" || vhttp == "h3") o.put("vhttp", vhttp)' % MARK,
                t, count=1)
            steps.append(("OK", "shartli o.put(vhttp)"))

    # ── 5. mtu coerceAtMost(1280) ni olib tashlash (config hurmat qilinsin) ──
    pat_mtu = re.compile(r'o\.put\("mtu",\s*mtu\.coerceAtMost\(1280\)\)')
    if pat_mtu.search(t):
        t = pat_mtu.sub('o.put("mtu", mtu)', t, count=1)
        steps.append(("OK", "mtu cheklovi olib tashlandi (config hurmat)"))
    else:
        steps.append(("SKIP", "mtu cheklovi yo'q"))

    # ── 6. buildMasqueConfig: tunMtu 1280 -> 1400 ──
    pat_tun = re.compile(r'tunMtu\s*=\s*1280,')
    if pat_tun.search(t):
        t = pat_tun.sub('tunMtu = 1400,', t, count=1)
        steps.append(("OK", "buildMasqueConfig tunMtu = 1400"))
    else:
        steps.append(("SKIP", "tunMtu=1280 yo'q"))

    return t, steps


def apply(root, dry):
    t, p = load(root)
    if t is None:
        print("  ✘ topilmadi:", p)
        return 1
    if MARK in t:
        print("  ⏭  allaqachon qo'llangan (%s)" % MARK)
        return 0

    new, steps = transform(t)

    print("\n══ MASQUE vhttp — YAKUNIY ══\n")
    for st, msg in steps:
        sym = {"OK": "✔", "SKIP": "•", "FAIL": "✘"}[st]
        print("  %s %s" % (sym, msg))

    if any(s == "FAIL" for s, _ in steps):
        print("\n  ❌ XATO — hech narsa yozilmadi")
        return 1

    if new == t:
        print("\n  • o'zgarish yo'q")
        return 0

    if dry:
        print("\n  (dry-run) o'zgaradi: %s" % os.path.relpath(p, root))
        return 0

    b = backup(root, CFG)
    print("\n  backup: %s" % os.path.relpath(b, root))
    with open(p, "w", encoding="utf-8") as f:
        f.write(new)
    print("  ✔ yozildi")
    return 0


def check(root):
    t, _ = load(root)
    if t is None:
        print("  ✘ topilmadi")
        return 1
    ok = True

    def t1(label, cond):
        nonlocal ok
        ok = ok and cond
        print("  %s %s" % ("✔" if cond else "✘", label))

    print("\n── 1. vhttp o'zgaruvchisi ──")
    t1("var vhttp e'loni", bool(re.search(r"(?m)^\s*var vhttp\s*=", t)))

    print("\n── 2. .conf `Network` o'qiladi ──")
    t1('k.equals("Network", true) -> vhttp = ...',
       'k.equals("Network", true) -> vhttp = v.trim().lowercase()' in t)

    print("\n── 3. Shartli yozish (h2/h3, auto EMAS) ──")
    t1('if (vhttp == "h2" || vhttp == "h3") o.put("vhttp", vhttp)',
       'if (vhttp == "h2" || vhttp == "h3") o.put("vhttp", vhttp)' in t)

    print("\n── 4. Qattiq/noto'g'ri kalit yo'q ──")
    t1('o.put("vhttp", "h2") qattiq yozuvi yo\'q',
       not re.search(r'o\.put\("vhttp",\s*"h2"\)', t))
    t1('o.put("network", ...) yo\'q',
       not re.search(r'o\.put\("network"\s*,', t))

    print("\n── 5. MTU hurmat qilinadi ──")
    t1("coerceAtMost(1280) yo'q", "mtu.coerceAtMost(1280)" not in t)

    print("\n── 6. tunMtu = 1400 ──")
    t1("buildMasqueConfig tunMtu = 1400", "tunMtu = 1400," in t)

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def self_test():
    ok = True
    print("\n── To'liq o'zgartirish sinovi ──")
    src = '''private fun parseMasque(raw: String): JSONObject {
        var ipv6 = ""
        var mtu = 1280
        val o = JSONObject()
        when {
            k.equals("Profile", true) -> o.put("profile", v.lowercase())
        }
        if (sni.isNotEmpty()) o.put("sni", sni)
        // ★ FIX_VHTTP_KEY: MASQUE kaliti — `vhttp` (yadro `network` ni BILMAYDI).
        //   h2 = TCP/443 TLS; h3 = QUIC/UDP — TSPU bloklaydi.
        o.put("vhttp", "h2")
        o.put("mtu", mtu.coerceAtMost(1280))
        return assemble(proxy, false, dns, null, !hasV6,
            tunMtu = 1280, tunV6 = hasV6, stack = "gvisor")
    }'''
    new, steps = transform(src)
    fails = [s for s, _ in steps if s == "FAIL"]
    ok = ok and not fails
    print("  %s FAIL yo'q (%d qadam)" % ("✔" if not fails else "✘", len(steps)))

    checks = [
        ("var vhttp e'lon", bool(re.search(r"(?m)^\s*var vhttp\s*=", new))),
        ("Network -> vhttp o'qish", 'vhttp = v.trim().lowercase()' in new),
        ("shartli vhttp yozish", 'if (vhttp == "h2" || vhttp == "h3")' in new),
        ("qattiq vhttp yo'q", 'o.put("vhttp", "h2")' not in new),
        ("mtu cheklovsiz", 'coerceAtMost(1280)' not in new),
        ("tunMtu = 1400", 'tunMtu = 1400,' in new),
    ]
    for label, cond in checks:
        ok = ok and cond
        print("  %s %s" % ("✔" if cond else "✘", label))

    print("\n  Natija (muhim qatorlar):")
    for ln in new.split("\n"):
        if any(k in ln for k in ("vhttp", "tunMtu = 1400", 'o.put("mtu"')):
            print("    " + ln.strip()[:72])

    print("\n── `auto` yozilmasligi ──")
    g = '"auto"' not in new
    ok = ok and g
    print("  %s `auto` yo'q (aks holda h3 birinchi bo'lardi)" % ("✔" if g else "✘"))

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    root = os.path.abspath(a.root)

    if a.self_test:
        return self_test()
    if a.check:
        return check(root)
    rc = apply(root, a.dry_run)
    if rc == 0 and not a.dry_run:
        print("\n  Keyingi qadam:")
        print("    python3 tools/fix_vhttp_final.py --root . --check")
        print("    ./gradlew assembleDebug test --offline")
    print()
    return rc


if __name__ == "__main__":
    sys.exit(main())
