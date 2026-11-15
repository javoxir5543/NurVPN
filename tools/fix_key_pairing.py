#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_key_pairing.py — MASQUE `Server` va `PublicKey` JUFTLIGINI tiklash
=====================================================================

═══════════════════════════════════════════════════════════════════════
NIMA BUZILDI
═══════════════════════════════════════════════════════════════════════

`fix_working_servers.py` serverlarni round-robin bilan almashtirgan edi
(bloklangan 198.2/199.2 → ishlaydigan 197.1/197.3/192.1/...).

MUAMMO: WARP/MASQUE'da **`PublicKey` serverga bog'liq**. Server
almashtirilganda `PublicKey` eski holicha qoldi.

Qurilma logidagi dalil:
    masque: remote endpoint has a different public key than pinned
    → tunnel ochilmaydi

Ya'ni config "ishlaydigan" serverga ko'rsatgan, lekin kalit boshqa
serverniki edi.

═══════════════════════════════════════════════════════════════════════
YECHIM
═══════════════════════════════════════════════════════════════════════

Har bir config uchun **`PrivateKey`** ni kalit sifatida ishlatamiz —
u configning "kimligi" (identity). Bir xil `PrivateKey` = bir xil
WARP profili = bir xil `PublicKey`.

Asl (git `HEAD`, v1.12.2) juftliklar:

    PrivateKey (boshi)      Server           Holat
    ─────────────────────   ──────────────   ──────────────────
    MHcCAQEEIOW4vwfQkgRj    162.159.198.2    bloklangan
    MHcCAQEEIH3YOD6GFFhz    162.159.199.2    bloklangan
    MHcCAQEEIOnBcMKNQF0K    162.159.198.2    bloklangan
    MHcCAQEEIHN68+3TVgtP    162.159.198.2    bloklangan
    MHcCAQEEICGfgyMaCjNI    162.159.199.2    bloklangan
    MHcCAQEEIDGq2+BEUnc8    162.159.197.2    bloklangan
    MHcCAQEEIDGq2+BEUnc8    162.159.197.1    ★ ISHLAYDI
    MHcCAQEEIDGq2+BEUnc8    162.159.197.2    bloklangan
    MHcCAQEEIDGq2+BEUnc8    162.159.197.5    bloklangan
    MHcCAQEEIDNvVxQi6f70    162.159.198.2    bloklangan
    MHcCAQEEIDNvVxQi6f70    162.159.199.2    bloklangan

Qurilmada o'lchangan ishlaydigan serverlar:
    162.159.197.1  403 ✅        162.159.198.2  000 ❌
    162.159.197.3  403 ✅        162.159.199.2  000 ❌
    162.159.192.1  403 ✅        162.159.197.2  000 ❌
    162.159.193.1  403 ✅        162.159.197.5  000 ❌
    162.159.195.1  403 ✅

STRATEGIYA:
   1. `PrivateKey` bo'yicha guruhlaymiz — har guruhda `PublicKey` bir xil.
   2. Har guruhga bitta ISHLAYDIgan server beramiz (takrorlanmasdan).
   3. Guruh ichida BARCHA config'lar bir xil serverni oladi
      (chunki WARP profili bitta serverga bog'langan).
   4. Natijada: kalit ↔ server juftligi TO'G'RI.

ISHLATISH:
    python3 tools/fix_key_pairing.py --root .            # qo'llash
    python3 tools/fix_key_pairing.py --root . --check
    python3 tools/fix_key_pairing.py --root . --dry-run
    python3 tools/fix_key_pairing.py --self-test
"""

import argparse
import collections
import datetime
import json
import os
import re
import shutil
import sys

CFG = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"
MARK = "FIX_KEY_PAIRING"

# Qurilmada o'lchangan (2026-11-15, mobil tarmoq, VPN o'chiq)
WORKING = [
    "162.159.197.1",   # tasdiqlangan (sizning configingiz)
    "162.159.197.3",
    "162.159.192.1",
    "162.159.193.1",
    "162.159.195.1",
]
BROKEN = ["162.159.198.2", "162.159.199.2", "162.159.197.2", "162.159.197.5"]


def load(root):
    p = os.path.join(root, CFG)
    if not os.path.isfile(p):
        return None, p
    with open(p, encoding="utf-8") as f:
        return f.read(), p


def blocks(src):
    """[(name, body)] — MASQUE bloklari."""
    return re.findall(r'val (MASQUE_\d+): String = """(.*?)"""', src, re.S)


def field(body, name):
    m = re.search(r"(?m)^%s\s*=\s*(\S+)" % name, body)
    return m.group(1) if m else ""


def plan_pairs(src):
    """PrivateKey bo'yicha guruhlab, har guruhga ishlaydigan server beradi."""
    bs = blocks(src)
    groups = collections.OrderedDict()
    for name, body in bs:
        pk = field(body, "PrivateKey")
        groups.setdefault(pk, []).append((name, body))

    mapping = {}
    for i, (pk, items) in enumerate(groups.items()):
        srv = WORKING[i % len(WORKING)]
        for name, _ in items:
            mapping[name] = srv
    return bs, groups, mapping


def rewrite(src, mapping):
    changes = []

    def fix_block(m):
        name, body = m.group(1), m.group(2)
        new_srv = mapping.get(name)
        if not new_srv:
            return m.group(0)
        old_srv = field(body, "Server")
        if old_srv == new_srv:
            return m.group(0)
        changes.append((name, old_srv, new_srv))
        b2 = re.sub(r"(?m)^(Server\s*=\s*)\S+", r"\g<1>" + new_srv, body, count=1)
        return 'val %s: String = """%s"""' % (name, b2)

    out = re.sub(r'val (MASQUE_\d+): String = """(.*?)"""', fix_block, src, flags=re.S)
    return out, changes


def apply(root, dry):
    src, p = load(root)
    if src is None:
        print("  ✘ topilmadi:", p)
        return 1

    if MARK in src:
        print("  ⏭  allaqachon qo'llangan (%s)" % MARK)
        return 0

    bs, groups, mapping = plan_pairs(src)
    out, changes = rewrite(src, mapping)

    print("\n══ SERVER ↔ KALIT JUFTLIGI ══\n")
    print("  PrivateKey guruhlari: %d\n" % len(groups))
    for pk, items in groups.items():
        srv = mapping[items[0][0]]
        names = ", ".join(n for n, _ in items)
        pub = field(items[0][1], "PublicKey")[28:44]
        print("   %-22s → %-16s (%d config: %s)" % (pk[:20], srv, len(items), names))
        print("   %-22s   PublicKey …%s" % ("", pub))

    if not changes:
        print("\n  • o'zgarish yo'q — juftliklar allaqachon to'g'ri")
        return 0

    print("\n  Almashtirishlar (%d):" % len(changes))
    for n, a, b in changes:
        print("    %-11s %s → %s" % (n, a, b))

    if dry:
        print("\n  (dry-run) hech narsa yozilmadi")
        return 0

    # Marker qo'shish
    out = out.replace("object BuiltinAwgConfigs",
                      "// ★ %s: Server ↔ PublicKey juftligi tiklandi\n"
                      "object BuiltinAwgConfigs" % MARK, 1)

    d = os.path.join(root, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    b = os.path.join(d, os.path.basename(CFG) + ".pairing.%s.bak" % ts)
    shutil.copy2(p, b)
    print("\n  backup: %s" % os.path.relpath(b, root))

    with open(p, "w", encoding="utf-8") as f:
        f.write(out)
    print("  ✔ yozildi")
    return 0


def check(root):
    src, _ = load(root)
    if src is None:
        print("  ✘ topilmadi")
        return 1

    bs, groups, mapping = plan_pairs(src)
    ok = True

    print("\n── 1. Har bir config ishlaydigan serverdami? ──")
    for name, body in bs:
        srv = field(body, "Server")
        good = srv in WORKING
        ok = ok and good
        print("  %s %-11s %s" % ("✔" if good else "✘", name, srv))

    print("\n── 2. Bloklangan server qolmaganmi? ──")
    bad = sorted({field(b, "Server") for _, b in bs if field(b, "Server") in BROKEN})
    ok = ok and not bad
    print("  %s %s" % ("✔" if not bad else "✘", bad or "toza"))

    print("\n── 3. Bir guruh = bir server (kalit mos)? ──")
    for pk, items in groups.items():
        srvs = {field(b, "Server") for _, b in items}
        pubs = {field(b, "PublicKey") for _, b in items}
        g = len(srvs) == 1 and len(pubs) == 1
        ok = ok and g
        print("  %s %-20s server=%d xil, pub=%d xil (%d config)" %
              ("✔" if g else "✘", pk[:18], len(srvs), len(pubs), len(items)))

    print("\n── 4. Config butunligi ──")
    for label, pat in [("PrivateKey", r"PrivateKey\s*=\s*\S{40,}"),
                       ("PublicKey", r"PublicKey\s*=\s*\S{40,}"),
                       ("Network = h2", r"Network\s*=\s*h2"),
                       ("SNI", r"SNI\s*=\s*\S+"),
                       ("MTU = 1280", r"MTU\s*=\s*1280")]:
        c = sum(1 for _, b in bs if re.search(pat, b))
        g = c == len(bs)
        ok = ok and g
        print("  %s %s: %d/%d" % ("✔" if g else "✘", label, c, len(bs)))

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def dump_device(root, out_path):
    """Qurilma uchun awg.xml yasash (kalit juftligi to'g'ri)."""
    src, _ = load(root)
    if src is None:
        print("  ✘ topilmadi")
        return 1
    bs = blocks(src)
    recs = []
    for name, body in bs:
        recs.append({
            "raw": body.strip(),
            "name": name.replace("_", " "),
            "endpoint": field(body, "Server") + ":" + (field(body, "Port") or "443"),
            "address": field(body, "Address").split(",")[0].strip(),
            "type": "masque",
        })
    js = json.dumps(recs, ensure_ascii=False, separators=(",", ":"))
    import html as _h
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           '    <string name="configs">' + _h.escape(js, quote=True) + "</string>\n</map>\n")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(xml)
    import xml.etree.ElementTree as ET
    ET.fromstring(xml)
    print("  ✔ %s (%d config)" % (out_path, len(recs)))
    return 0


def self_test():
    ok = True
    print("\n── Guruhlash (PrivateKey bo'yicha) ──")
    sample = '''object BuiltinAwgConfigs {
    val MASQUE_1: String = """PrivateKey = AAAA1111
PublicKey = PUB1
Server = 162.159.198.2
Network = h2"""
    val MASQUE_2: String = """PrivateKey = BBBB2222
PublicKey = PUB2
Server = 162.159.199.2
Network = h2"""
    val MASQUE_3: String = """PrivateKey = AAAA1111
PublicKey = PUB1
Server = 162.159.192.1
Network = h2"""
}'''
    bs, groups, mapping = plan_pairs(sample)
    g1 = len(groups) == 2
    ok = ok and g1
    print("  %s 2 ta PrivateKey guruhi (%d config)" % ("✔" if g1 else "✘", len(bs)))

    same = mapping["MASQUE_1"] == mapping["MASQUE_3"]
    ok = ok and same
    print("  %s bir xil PrivateKey → bir xil server (%s = %s)" %
          ("✔" if same else "✘", mapping["MASQUE_1"], mapping["MASQUE_3"]))

    diff = mapping["MASQUE_1"] != mapping["MASQUE_2"]
    ok = ok and diff
    print("  %s turli PrivateKey → turli server (%s ≠ %s)" %
          ("✔" if diff else "✘", mapping["MASQUE_1"], mapping["MASQUE_2"]))

    out, ch = rewrite(sample, mapping)
    print("\n── Almashtirish ──")
    g2 = len(ch) == 3
    ok = ok and g2
    print("  %s 3 ta server almashtirildi" % ("✔" if g2 else "✘"))
    nobroken = not any(s in out for s in ("198.2", "199.2", "197.2", "197.5"))
    ok = ok and nobroken
    print("  %s bloklangan server qolmadi" % ("✔" if nobroken else "✘"))

    print("\n── Ishlaydigan serverlar ro'yxati ──")
    print("  ✔ %s" % ", ".join(WORKING))

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--dump-device", metavar="OUT",
                    help="Qurilma uchun awg.xml yasash")
    a = ap.parse_args()
    root = os.path.abspath(a.root)

    if a.self_test:
        return self_test()
    if a.dump_device:
        return dump_device(root, a.dump_device)
    if a.check:
        return check(root)

    print("\n══ SERVER ↔ KALIT JUFTLIGINI TIKLASH ══")
    rc = apply(root, a.dry_run)
    if rc == 0 and not a.dry_run:
        print("\n  Keyingi qadam:")
        print("    python3 tools/fix_key_pairing.py --root . --check")
        print("    python3 tools/fix_key_pairing.py --root . --dump-device /tmp/awg.xml")
        print("    ./gradlew assembleDebug test --offline")
    print()
    return rc


if __name__ == "__main__":
    sys.exit(main())
