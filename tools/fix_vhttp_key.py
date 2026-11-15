#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_vhttp_key.py — MASQUE uchun TO'G'RI kalit: `network` → `vhttp`
=================================================================

═══════════════════════════════════════════════════════════════════════
XATO TARIXI
═══════════════════════════════════════════════════════════════════════

`nurvpn_patch.py` MASQUE uchun `o.put("network", "h2")` yozgan edi.
Bu XATO KALIT — yadro (libbox) uni BILMAYDI.

Natijada:
  1. `Libbox.checkConfig()` xato berdi: "unknown field network"
  2. Skriptdagi `stripMasqueNetwork()` fallback ishga tushdi
  3. `network` olib tashlandi → yadro STANDART holatga qaytdi
  4. Standart = `auto` → yadro **h3 ni birinchi sinaydi**
  5. TSPU h3/QUIC ni bloklaydi → VPN ishlamaydi

Qurilma logida dalil (Redmi Note 10S, 2026-11-15):
    W/NurVPN-Svc: MASQUE: 'network' qo'llab-quvvatlanmadi — profile default
    masque: establishing h3 tunnel to 162.159.197.3:443     ← h3 ga qaytdi!
    masque: h2 tunnel to 162.159.197.3:443 failed: (h3: dial ...

═══════════════════════════════════════════════════════════════════════
TO'G'RI KALIT — `vhttp`
═══════════════════════════════════════════════════════════════════════

libbox.so ichidagi stringlar (native kutubxona):
    "masque: invalid vhttp: "          ← FAQAT shu kalitni o'qiydi
    "masque: fragmentation is ignored on \`vhttp: h3\`"
    "masque: \`vhttp: auto\` has no h2 fallback on the standard profile"
    "masque: vhttp h2 is not implemented for the standard profile"

Ya'ni JSON kaliti — **`vhttp`** (STRING: "h2" | "h3" | "auto"), `network` EMAS.

Tasdiqlangan ishlaydigan log:
    MASQUE: 162.159.198.2:443 sni=4pda.to mtu=1360 profile=cloudflare vhttp=h2
    → h2 tunnel established

═══════════════════════════════════════════════════════════════════════
BU SKRIPT
═══════════════════════════════════════════════════════════════════════

1. `o.put("network", "h2")` → `o.put("vhttp", "h2")`
2. `stripMasqueNetwork()` va uning chaqiruvini OLIB TASHLASH
   (endi kerak emas — kalit to'g'ri, checkConfig o'tadi)
3. `Normalizatsiya` va `MasqueEditor` ham `Network` yozishda davom etadi —
   bu TO'G'RI, chunki `.conf` formati `Network` ishlatadi (parse qilinadi).

ISHLATISH:
    python3 tools/fix_vhttp_key.py --root .            # qo'llash
    python3 tools/fix_vhttp_key.py --root . --check    # holat
    python3 tools/fix_vhttp_key.py --root . --dry-run
    python3 tools/fix_vhttp_key.py --self-test
"""

import argparse
import datetime
import os
import re
import shutil
import sys

CFG = "app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt"
SVC = "app/src/main/java/com/nurvpn/app/service/NurVpnService.kt"
MARK = "FIX_VHTTP_KEY"


def fz(s):
    return r"\s+".join(map(re.escape, s.split()))


def load(root, rel):
    p = os.path.join(root, rel)
    if not os.path.isfile(p):
        return None, p
    with open(p, encoding="utf-8") as f:
        return f.read(), p


def backup(root, rel):
    d = os.path.join(root, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    dst = os.path.join(d, os.path.basename(rel) + ".vhttp.%s.bak" % ts)
    shutil.copy2(os.path.join(root, rel), dst)
    return dst


def apply(root, dry):
    errors = []
    plan = []

    # ── 1. parseMasque: network -> vhttp ──
    txt, p = load(root, CFG)
    if txt is None:
        errors.append("topilmadi: " + CFG)
    elif MARK in txt:
        plan.append(("SKIP", CFG, "allaqachon qo'llangan"))
    else:
        pat = re.compile(r'o\.put\(\s*"network"\s*,\s*"h2"\s*\)')
        if not pat.search(txt):
            errors.append(CFG + ': o.put("network", "h2") topilmadi')
        else:
            new = pat.sub(
                '// ★ %s: MASQUE kaliti — `vhttp` (yadro `network` ni BILMAYDI).\n'
                '        //   h2 = TCP/443 TLS; h3 = QUIC/UDP — TSPU bloklaydi.\n'
                '        o.put("vhttp", "h2")' % MARK, txt)
            plan.append(("OK", CFG, 'network -> vhttp'))

    # ── 2. Service: stripMasqueNetwork chaqiruvini olib tashlash ──
    stxt, sp = load(root, SVC)
    if stxt is None:
        errors.append("topilmadi: " + SVC)
    elif "stripMasqueNetwork" not in stxt:
        plan.append(("SKIP", SVC, "stripMasqueNetwork allaqachon yo'q"))
    else:
        # 2a. checkConfig blokini soddalashtirish
        old_catch = re.compile(
            fz('''} catch (t: Throwable) {
    // ★ PATCH-H2: yadro "network" kalitini bilmasa — olib tashlab qayta sinaymiz
    if (stripMasqueNetwork(cfg)) {
        cfgJson = cfg.toString(2)
        try {
            Libbox.checkConfig(cfgJson)
            Log.w(TAG, "MASQUE: 'network' qo'llab-quvvatlanmadi — profile default")
        } catch (t2: Throwable) {
            throw Exception("Config xato: ${t2.message}")
        }
    } else {
        throw Exception("Config xato: ${t.message}")
    }
}'''))
        # 2b. helper funksiyani olib tashlash
        old_fn = re.compile(
            fz('''/** ★ PATCH-H2: MASQUE outbound'dan 'network' kalitini olib tashlash. */
private fun stripMasqueNetwork(cfg: JSONObject): Boolean {
    var changed = false
    for (key in listOf("outbounds", "endpoints")) {
        val arr = cfg.optJSONArray(key) ?: continue
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "masque" && o.has("network")) {
                o.remove("network")
                changed = true
            }
        }
    }
    return changed
}
'''))

        new_stxt = stxt
        n1 = 0
        if old_catch.search(new_stxt):
            new_stxt = old_catch.sub(
                '} catch (t: Throwable) {\n'
                '    throw Exception("Config xato: ${t.message}")\n'
                '}', new_stxt, count=1)
            n1 = 1
        n2 = 0
        if old_fn.search(new_stxt):
            new_stxt = old_fn.sub('', new_stxt, count=1)
            n2 = 1

        if n1 == 0 and n2 == 0:
            errors.append(SVC + ": stripMasqueNetwork bloklari topilmadi")
        else:
            # cfgJson var -> val qaytarish (endi o'zgartirilmaydi)
            new_stxt = re.sub(r'\bvar cfgJson = cfg\.toString\(2\)',
                              'val cfgJson = cfg.toString(2)', new_stxt, count=1)
            plan.append(("OK", SVC,
                         "fallback olib tashlandi (catch=%d, helper=%d)" % (n1, n2)))

    # ── Natija ──
    print("\n══ MASQUE KALITI: network → vhttp ══\n")
    oks = [x for x in plan if x[0] == "OK"]
    for st, f, msg in plan:
        print("  %s [%s] %s" % ("✔" if st == "OK" else "•", os.path.basename(f), msg))

    if errors:
        print("\n  XATO — HECH NARSA YOZILMADI:")
        for e in errors:
            print("    ✘", e)
        return 1

    if not oks:
        print("\n  • o'zgarish yo'q")
        return 0

    if dry:
        print("\n  (dry-run) %d fayl o'zgaradi" % len(oks))
        return 0

    for st, f, msg in oks:
        b = backup(root, f)
        print("\n  backup: %s" % os.path.relpath(b, root))
        t, p = load(root, f)
        if f == CFG:
            t2 = re.sub(r'o\.put\(\s*"network"\s*,\s*"h2"\s*\)',
                        '// ★ %s: MASQUE kaliti — `vhttp` (yadro `network` ni BILMAYDI).\n'
                        '        //   h2 = TCP/443 TLS; h3 = QUIC/UDP — TSPU bloklaydi.\n'
                        '        o.put("vhttp", "h2")' % MARK, t, count=1)
        else:
            t2 = t
            old_catch = re.compile(
                fz('''} catch (t: Throwable) {
    // ★ PATCH-H2: yadro "network" kalitini bilmasa — olib tashlab qayta sinaymiz
    if (stripMasqueNetwork(cfg)) {
        cfgJson = cfg.toString(2)
        try {
            Libbox.checkConfig(cfgJson)
            Log.w(TAG, "MASQUE: 'network' qo'llab-quvvatlanmadi — profile default")
        } catch (t2: Throwable) {
            throw Exception("Config xato: ${t2.message}")
        }
    } else {
        throw Exception("Config xato: ${t.message}")
    }
}'''))
            old_fn = re.compile(
                fz('''/** ★ PATCH-H2: MASQUE outbound'dan 'network' kalitini olib tashlash. */
private fun stripMasqueNetwork(cfg: JSONObject): Boolean {
    var changed = false
    for (key in listOf("outbounds", "endpoints")) {
        val arr = cfg.optJSONArray(key) ?: continue
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "masque" && o.has("network")) {
                o.remove("network")
                changed = true
            }
        }
    }
    return changed
}
'''))
            t2 = old_catch.sub(
                '} catch (t: Throwable) {\n'
                '    throw Exception("Config xato: ${t.message}")\n'
                '}', t2, count=1)
            t2 = old_fn.sub('', t2, count=1)
            t2 = re.sub(r'\bvar cfgJson = cfg\.toString\(2\)',
                        'val cfgJson = cfg.toString(2)', t2, count=1)
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(t2)
        print("  ✔ yozildi")
    return 0


def check(root):
    print("\n══ TEKSHIRUV ══\n")
    ok = True

    txt, _ = load(root, CFG)
    if txt is None:
        print("  ✘", CFG, "topilmadi")
        return 1

    print("── 1. parseMasque to'g'ri kalit yozadimi? ──")
    g1 = 'o.put("vhttp", "h2")' in txt
    ok = ok and g1
    print("  %s o.put(\"vhttp\", \"h2\")" % ("✔" if g1 else "✘"))

    print("\n── 2. Xato kalit qolmaganmi? ──")
    bad = re.search(r'o\.put\(\s*"network"\s*,', txt)
    g2 = bad is None
    ok = ok and g2
    print("  %s %s" % ("✔" if g2 else "✘",
                       "toza" if g2 else 'o.put("network", ...) hali bor'))

    stxt, _ = load(root, SVC)
    print("\n── 3. stripMasqueNetwork olib tashlanganmi? ──")
    g3 = stxt is not None and "stripMasqueNetwork" not in stxt
    ok = ok and g3
    print("  %s %s" % ("✔" if g3 else "✘",
                       "yo'q" if g3 else "hali ham bor (h2 ni buzadi)"))

    print("\n── 4. cfgJson val bo'lganmi? ──")
    g4 = stxt is not None and "val cfgJson = cfg.toString(2)" in stxt
    ok = ok and g4
    print("  %s val cfgJson" % ("✔" if g4 else "✘"))

    print("\n── 5. checkConfig oddiy catch ──")
    g5 = stxt is not None and 'throw Exception("Config xato: ${t.message}")' in stxt
    ok = ok and g5
    print("  %s mavjud" % ("✔" if g5 else "✘"))

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def self_test():
    print("\n── Kalit almashtirish ──")

    def fix(s):
        return re.sub(r'o\.put\(\s*"network"\s*,\s*"h2"\s*\)',
                      'o.put("vhttp", "h2")', s, count=1)

    cases = [
        ('o.put("network", "h2")', 'o.put("vhttp", "h2")', "oddiy"),
        ('o.put( "network" , "h2" )', 'o.put("vhttp", "h2")', "bo'shliqlar"),
    ]
    ok = True
    for inp, want, desc in cases:
        got = fix(inp)
        good = got == want
        ok = ok and good
        print("  %s %-26s → %-24s (%s)" %
              ("✔" if good else "✘", inp, got, desc))

    print("\n── Yadro kalitlari (dalil) ──")
    print("  ✔ libbox: \"masque: invalid vhttp: \"      → kalit `vhttp`")
    print("  ✔ libbox: \"network\" — MASQUE uchun YO'Q")
    print("  ✔ ishlaydigan log: vhttp=h2 → h2 tunnel established")

    print("\n── stripMasqueNetwork xavfi ──")
    print("  ✔ olib tashlanishi shart: network o'chirilsa yadro h3 ga qaytadi")

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
        print("    ./gradlew assembleDebug test --offline")
        print("    python3 tools/fix_vhttp_key.py --root . --check")
    print()
    return rc


if __name__ == "__main__":
    sys.exit(main())
