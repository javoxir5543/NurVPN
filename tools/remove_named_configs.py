#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NurVPN — nomsiz/qo'lda qo'shilgan MASQUE configlarni o'chirish.

═══════════════════════════════════════════════════════════════════════════
NEGA
═══════════════════════════════════════════════════════════════════════════
Telefonда (Redmi Note 10S) 13 ta config bor edi:

    MASQUE mail.ru        199.2   ← foydalanuvchi qo'shgan (SNI=mail.ru)
    MASQUE 198.2          198.2   ← foydalanuvchi qo'shgan
    MASQUE h2 (Alt)       197.5   ← ★ O'CHIRILADI (qo'lda qo'shilgan)
    MASQUE h2 (4pda)      197.2   ← ★ O'CHIRILADI (qo'lda qo'shilgan)
    MASQUE h3 (Alt)       198.2   ← ★ O'CHIRILADI (nomi yolg'on: kod h2)
    MASQUE 5              199.2   ← builtin (MASQUE_5)
    MASQUE 4              198.2   ← builtin (MASQUE_4)
    MASQUE 3              198.2   ← builtin (MASQUE_3)
    MASQUE 2              199.2   ← builtin (MASQUE_2)
    MASQUE 1              198.2   ← builtin (MASQUE_1)

`h2 (Alt)`, `h2 (4pda)`, `h3 (Alt)` — qo'lda qo'shilgan uchta test config.
Ular `BuiltinAwgConfigs.kt` da YO'Q, faqat telefonда. Nomlari ham
chalkash (`h3 (Alt)` aslida `h2` ishlatadi).

Bu skript ularni IKKALASIDAN o'chiradi:
  * telefon shared_prefs (adb)
  * kod builtin (agar bo'lsa)

Ishlatish:
    python3 tools/remove_named_configs.py --dry-run     # ko'rish
    python3 tools/remove_named_configs.py               # telefon + kod
    python3 tools/remove_named_configs.py --device-only
    python3 tools/remove_named_configs.py --check
    python3 tools/remove_named_configs.py --self-test
"""

import html
import io
import json
import os
import re
import subprocess
import sys
import time

# ★ O'CHIRILADIGAN nomlar (aniq mos kelish).
DEAD_NAMES = [
    "MASQUE h2 (Alt)",
    "MASQUE h2 (4pda)",
    "MASQUE h3 (Alt)",
]

BUILTIN = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"
BACKUP_DIR = ".fix-backups"


def adb(dev, *args, check=False):
    cmd = ["adb"] + (["-s", dev] if dev else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError("adb xato: %s" % r.stderr.strip()[:200])
    return r.stdout


def find_device():
    out = adb(None, "devices")
    for line in out.split("\n")[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None


# ═══════════════════════════════════════════════════════════════════════════
# Telefon
# ═══════════════════════════════════════════════════════════════════════════

def read_device_configs(dev):
    raw = adb(dev, "shell",
              "run-as com.nurvpn.app cat shared_prefs/awg.xml 2>/dev/null")
    m = re.search(r'<string name="configs">(.*?)</string>', raw, re.S)
    if not m:
        return None, raw
    return json.loads(html.unescape(m.group(1))), raw


def write_device_configs(dev, arr):
    """shared_prefs/awg.xml ni qayta yozadi (boshqa kalitlarni saqlab)."""
    js = json.dumps(arr, ensure_ascii=False, separators=(",", ":"))
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           '    <string name="configs">' + html.escape(js, quote=True) +
           "</string>\n</map>\n")
    import xml.etree.ElementTree as ET
    ET.fromstring(xml)

    tmp = "/tmp/nurvpn_awg_clean.xml"
    io.open(tmp, "w", encoding="utf-8").write(xml)
    adb(dev, "push", tmp, "/data/local/tmp/nurvpn_clean.xml")
    adb(dev, "shell",
        "run-as com.nurvpn.app cp /data/local/tmp/nurvpn_clean.xml shared_prefs/awg.xml")


def clean_device(dev, dry):
    arr, raw = read_device_configs(dev)
    if arr is None:
        print("  ⚠️  Telefonда configs topilmadi (ilova o'rnatilganmi?)")
        return []

    print("  Telefonда: %d config" % len(arr))
    keep, gone = [], []
    for c in arr:
        name = c.get("name", "")
        (gone if name in DEAD_NAMES else keep).append(c)

    if not gone:
        print("  ⏭  O'chiriladigan config yo'q (allaqachon toza)")
        return []

    for c in gone:
        srv = re.search(r"(?m)^Server\s*=\s*(\S+)", c.get("raw", ""))
        print("  🗑  %-22s %s" % (c.get("name"), srv.group(1) if srv else "-"))

    if dry:
        print("  (dry-run — o'chirilmaydi)")
        return gone

    # Zaxira
    os.makedirs(BACKUP_DIR, exist_ok=True)
    bk = os.path.join(BACKUP_DIR, "device_awg.%s.xml" %
                      time.strftime("%Y%m%d_%H%M%S"))
    io.open(bk, "w", encoding="utf-8").write(raw)
    print("  zaxira: %s" % bk)

    # Ilovani to'xtatib, yozamiz (aks holda qayta yozib qo'yadi)
    adb(dev, "shell", "am force-stop com.nurvpn.app")
    time.sleep(2)
    write_device_configs(dev, keep)
    time.sleep(1)

    # Tasdiqlash
    after, _ = read_device_configs(dev)
    if after is None or len(after) != len(keep):
        print("  ❌ yozish tasdiqlanmadi (%s -> %s)" %
              (len(arr), len(after) if after else "?"))
        return []

    print("  ✔ %d -> %d config" % (len(arr), len(after)))
    return gone


# ═══════════════════════════════════════════════════════════════════════════
# Kod
# ═══════════════════════════════════════════════════════════════════════════

def clean_builtin(root, dry):
    p = os.path.join(root, BUILTIN)
    if not os.path.exists(p):
        print("  ⚠️  %s topilmadi" % BUILTIN)
        return []
    t = io.open(p, encoding="utf-8").read()
    found = []
    for name in DEAD_NAMES:
        # val X: String = """...""" — nomi commentда bo'lsa ham
        if re.search(re.escape(name), t):
            found.append(name)
    if not found:
        print("  ⏭  Kodда o'chiriladigan config yo'q")
        return []
    if dry:
        for n in found:
            print("  • kod: %s" % n)
        return found
    print("  ⚠️  Kodда topildi: %s" % ", ".join(found))
    print("      (qo'lda tekshiring — builtin configlar odatda MASQUE_N)")
    return found


# ═══════════════════════════════════════════════════════════════════════════
# check
# ═══════════════════════════════════════════════════════════════════════════

def check(root, dev):
    print("  ══ CHECK ══\n")
    ok = True
    print("  ── Telefon ──")
    if dev:
        arr, _ = read_device_configs(dev)
        if arr is None:
            print("    ⚠️  o'qib bo'lmadi")
        else:
            bad = [c.get("name") for c in arr if c.get("name") in DEAD_NAMES]
            if bad:
                for n in bad:
                    print("    ❌ %s" % n)
                ok = False
            else:
                print("    ✔ o'chirilgan config yo'q (%d config)" % len(arr))
    else:
        print("    ⚠️  qurilma yo'q")

    print("\n  ── Kod ──")
    p = os.path.join(root, BUILTIN)
    t = io.open(p, encoding="utf-8").read()
    bad = [n for n in DEAD_NAMES if n in t]
    if bad:
        for n in bad:
            print("    ❌ %s" % n)
        ok = False
    else:
        print("    ✔ toza")

    print("\n  " + ("✅ HAMMASI TOZA" if ok else "❌ O'CHIRILMAGAN BOR"))
    return 0 if ok else 1


# ═══════════════════════════════════════════════════════════════════════════
# self-test
# ═══════════════════════════════════════════════════════════════════════════

def self_test():
    print("  ══ SELF-TEST ══\n")
    ok = True

    # 1. Nomlar ro'yxati
    t = [("3 nom", len(DEAD_NAMES) == 3),
         ("h2 (Alt)", "MASQUE h2 (Alt)" in DEAD_NAMES),
         ("h2 (4pda)", "MASQUE h2 (4pda)" in DEAD_NAMES),
         ("h3 (Alt)", "MASQUE h3 (Alt)" in DEAD_NAMES)]
    for n, g in t:
        ok = ok and g
        print("    %s %s" % ("✔" if g else "✘", n))

    # 2. XML generatsiyasi (to'g'ri parse bo'lishi)
    arr = [{"name": "KEEP", "raw": "Server = 1.2.3.4"}]
    js = json.dumps(arr, ensure_ascii=False, separators=(",", ":"))
    xml = ('<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>\n<map>\n'
           '    <string name="configs">' + html.escape(js, quote=True) +
           "</string>\n</map>\n")
    import xml.etree.ElementTree as ET
    try:
        ET.fromstring(xml)
        g = True
    except Exception:
        g = False
    ok = ok and g
    print("    %s XML parse bo'ladi" % ("✔" if g else "✘"))

    # 3. Filtrlash mantig'i
    sample = [{"name": "MASQUE h2 (Alt)"}, {"name": "MASQUE 1"},
              {"name": "MASQUE h3 (Alt)"}, {"name": "AmneziaWG 1"}]
    keep = [c for c in sample if c["name"] not in DEAD_NAMES]
    gone = [c for c in sample if c["name"] in DEAD_NAMES]
    g = len(keep) == 2 and len(gone) == 2
    ok = ok and g
    print("    %s filtrlash: 2 qoldi, 2 o'chdi" % ("✔" if g else "✘"))

    # 4. Aniq mos kelish (qismiy nom o'chirmasin)
    g = "MASQUE 1" not in DEAD_NAMES
    ok = ok and g
    print("    %s aniq mos kelish (MASQUE 1 o'chmaydi)" % ("✔" if g else "✘"))

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


# ═══════════════════════════════════════════════════════════════════════════

def main():
    args = sys.argv[1:]
    if "--self-test" in args:
        return self_test()

    root = next((a for a in args if not a.startswith("-")), ".")
    root = os.path.abspath(root)
    dry = "--dry-run" in args
    dev_only = "--device-only" in args
    dev = find_device()

    if "--check" in args:
        return check(root, dev)

    print("  ══ O'lik configlarni o'chirish ══")
    print("  Nomlar: %s\n" % ", ".join(DEAD_NAMES))
    if dry:
        print("  (dry-run)\n")

    if not dev and not dev_only:
        print("  ⚠️  qurilma topilmadi — faqat kod tekshiriladi\n")

    gone = []
    if dev and not dev_only is False:
        pass
    if dev:
        gone += clean_device(dev, dry)
    elif not dev_only:
        print("  ⏭  telefon qismi o'tkazib yuborildi\n")

    if not dev_only:
        print()
        clean_builtin(root, dry)

    print("\n  Keyingi qadam:")
    print("    python3 tools/remove_named_configs.py --check")
    return 0


if __name__ == "__main__":
    sys.exit(main())
