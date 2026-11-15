#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
builtin_h2_comment.py — BuiltinAwgConfigs'dagi `Network = h3` ni h2 ga o'girish
                          va izoh qo'shish (TSPU bloklashi sababini ko'rsatish)
================================================================================

SABAB:

    Boshqa qurilmadan olingan ishlaydigan config'da shunday yozilgan:

        Network = h2   # FIX_FORCE_H2: TSPU h3/QUIC ni bloklaydi

    Ya'ni: Rossiya TSPU (DPI) tizimi QUIC (h3/UDP) ni bloklaydi. Shuning
    uchun MASQUE TLS-over-TCP (h2) da ishlashi SHART.

    Hozirgi holat:
        • `parseMasque()` da h2 majburlangan ✅ (ishlaydi)
        • LEKIN BuiltinAwgConfigs'da 4 ta config hali `Network = h3`
          (MASQUE_4, 5, 6, 7)

    Natijada: faylни o'qigan odam "nega h3 bor?" deb o'ylaydi, va agar
    `parseMasque` dagi himoya olib tashlansa — h3 bilan ishlamaydi.

YECHIM:

    Builtin'da ham `Network = h3` -> `Network = h2` ga o'giriladi va
    izoh qo'shiladi:

        Network = h2   # FIX_FORCE_H2: TSPU h3/QUIC ni bloklaydi

    Ikki qavat himoya: Builtin'da h2 + parseMasque'da h2.

QO'LLASH:
    python3 tools/builtin_h2_comment.py            # qo'llash
    python3 tools/builtin_h2_comment.py --check    # holat
    python3 tools/builtin_h2_comment.py --dry-run  # ko'rsatish
    python3 tools/builtin_h2_comment.py --self-test
    python3 tools/builtin_h2_comment.py --revert   # izohni olib tashlash
"""

import os
import re
import sys
import shutil
import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(
    ROOT, "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt")

NOTE = "# FIX_FORCE_H2: TSPU h3/QUIC ni bloklaydi"
MARKER = "FIX_FORCE_H2: TSPU"

DRY = "--dry-run" in sys.argv
REVERT = "--revert" in sys.argv


def read():
    with open(CFG, encoding="utf-8") as f:
        return f.read()


def backup():
    d = os.path.join(ROOT, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    dst = os.path.join(d, os.path.basename(CFG) + ".h2note.%s.bak" % ts)
    shutil.copy2(CFG, dst)
    return dst


def blocks(src):
    return {
        m.group(1): m.group(2)
        for m in re.finditer(
            r'val (MASQUE_\d+): String = """(.*?)"""', src, re.S)
    }


def apply(dry=False, revert=False):
    src = read()
    out = src
    changes = []

    for name, body in blocks(src).items():
        # \n NI SAQLASH SHART: `(\s*...)` uni yutib yuborsa, keyingi qator
        # ("Profile = cloudflare") izohga yopishib qoladi.
        m = re.search(r'(Network\s*=\s*)(\w+)([ \t]*(?:#[^\n]*)?)', body)
        if not m:
            continue
        cur_val, tail = m.group(2), m.group(3)

        if revert:
            if MARKER not in tail:
                continue
            new_block = body.replace(m.group(0), m.group(1) + cur_val, 1)
            out = out.replace(
                'val %s: String = """%s"""' % (name, body),
                'val %s: String = """%s"""' % (name, new_block), 1)
            changes.append("%s: izoh olib tashlandi" % name)
            continue

        if MARKER in tail:
            continue  # allaqachon bor

        new_line = m.group(1) + "h2   " + NOTE
        new_block = body.replace(m.group(0), new_line, 1)
        out = out.replace(
            'val %s: String = """%s"""' % (name, body),
            'val %s: String = """%s"""' % (name, new_block), 1)
        if cur_val != "h2":
            changes.append("%s: h3 \u2192 h2 + izoh" % name)
        else:
            changes.append("%s: izoh qo'shildi" % name)

    if not changes:
        print("  \u2139  O'zgarish yo'q (allaqachon qo'llangan)")
        return True

    if dry:
        print("  (dry-run) quyidagilar o'zgaradi:")
        for c in changes:
            print("    - " + c)
        return True

    b = backup()
    print("  backup: " + os.path.relpath(b, ROOT))
    with open(CFG, "w", encoding="utf-8") as f:
        f.write(out)
    for c in changes:
        print("  \u2714 " + c)
    return True


def check():
    src = read()
    bs = blocks(src)
    ok = True

    print("\n\u2500\u2500 1. Builtin'da h3 qolmaganmi? \u2500\u2500")
    h3 = [n for n, b in bs.items()
          if re.search(r'Network\s*=\s*h3', b)]
    good = not h3
    ok = ok and good
    print(("  \u2714 " if good else "  \u2717 ") +
          ("h3 yo'q \u2014 hammasi h2" if good else "h3 qolgan: " + ", ".join(sorted(h3))))

    print("\n\u2500\u2500 2. Izoh qo'shilganmi? \u2500\u2500")
    noted = [n for n, b in bs.items() if MARKER in b]
    good2 = len(noted) == len(bs)
    ok = ok and good2
    print("  " + ("\u2714 " if good2 else "\u2717 ") +
          "izohli config: %d/%d" % (len(noted), len(bs)))

    print("\n\u2500\u2500 3. parseMasque himoyasi (ikkinchi qavat) \u2500\u2500")
    sbc = os.path.join(ROOT, "app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt")
    if os.path.exists(sbc):
        s = open(sbc, encoding="utf-8").read()
        g3 = 'o.put("vhttp", "h2")' in s
        ok = ok and g3
        print("  " + ("\u2714 " if g3 else "\u2717 ") +
              "parseMasque h2 yozadi")

    print("\n\u2500\u2500 4. Boshqa maydonlar buzilmaganmi? \u2500\u2500")
    for fld, pat in [("PrivateKey", r'PrivateKey\s*=\s*\S{40,}'),
                     ("PublicKey", r'PublicKey\s*=\s*\S{40,}'),
                     ("Server", r'Server\s*=\s*[\d.]+'),
                     ("SNI", r'SNI\s*=\s*\S+'),
                     ("MTU", r'MTU\s*=\s*\d+')]:
        c = sum(1 for b in bs.values() if re.search(pat, b))
        g = c == len(bs)
        ok = ok and g
        print("  " + ("\u2714 " if g else "\u2717 ") +
              "%s: %d/%d" % (fld, c, len(bs)))

    print("\n  " + ("\u2705 HAMMASI JOYIDA" if ok else "\u274c MUAMMO BOR"))
    return ok


def self_test():
    print("\n\u2500\u2500 Network qatorini almashtirish \u2500\u2500")

    def fix(line):
        m = re.search(r'(Network\s*=\s*)(\w+)([ \t]*(?:#[^\n]*)?)', line)
        if not m:
            return line
        return line.replace(m.group(0), m.group(1) + "h2   " + NOTE, 1)

    cases = [
        ("Network = h3", "Network = h2", "h3 -> h2 + izoh"),
        ("Network = h2", "Network = h2", "h2 -> izoh qo'shiladi"),
        ("Network = auto", "Network = h2", "auto -> h2"),
    ]
    ok = True
    for inp, want_val, desc in cases:
        got = fix(inp)
        good = got.startswith(want_val) and NOTE in got
        ok = ok and good
        print("  %s %-18s -> %-46s (%s)" %
              ("\u2714" if good else "\u2717", inp, got[:46], desc))

    print("\n\u2500\u2500 h3 hech qachon qolmasligi \u2500\u2500")
    for v in ("h3", "H3", "auto"):
        got = fix("Network = " + v)
        if "Network = h3" in got:
            ok = False
            print("  \u2717 %s -> h3 qoldi!" % v)
    print("  \u2714 h3 qolmaydi")

    print("\n  " + ("\u2705 BARCHA TESTLAR O'TDI" if ok else "\u274c TEST XATO"))
    return ok


def main():
    if "--self-test" in sys.argv:
        sys.exit(0 if self_test() else 1)
    if "--check" in sys.argv:
        sys.exit(0 if check() else 1)
    print("\n\u2550\u2550 BUILTIN CONFIGLAR: h3 \u2192 h2 + TSPU izohi \u2550\u2550\n")
    if not apply(dry=DRY, revert=REVERT):
        sys.exit(1)
    if not DRY and not REVERT:
        print("\n  Keyingi qadam:")
        print("    ./gradlew assembleDebug test --offline")
        print("    python3 tools/builtin_h2_comment.py --check")
    print()


if __name__ == "__main__":
    main()
