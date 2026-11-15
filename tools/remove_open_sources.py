#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NurVPN — "Ochiq manbalar" dan keraksiz MASQUE obunalarini o'chirish.

═══════════════════════════════════════════════════════════════════════════
MUAMMO ZANJIRI (aniqlandi)
═══════════════════════════════════════════════════════════════════════════
Telefonда `MASQUE h2 (Alt)`, `MASQUE h2 (4pda)`, `MASQUE h3 (Alt)` configlar
ko'rinardi. Ular `awg.xml` dan o'chirilsa ham QAYTIB KELARDI, chunki:

    MainActivity.kt:230-244
        for (open in OpenSourceCatalog.ALL) {
            if (!OpenSourceStore.isEnabled(this, open.id)) continue
            val raw = BuiltinAwgConfigs.byId(open.awgId ?: continue) ?: continue
            ...
            awgConfigs.add(cfg)          // ← qayta tiklanadi!
        }

Ya'ni "Ochiq manbalar" da obuna YOQIQ bo'lsa, config telefonда paydo bo'ladi.
Configni o'chirish — BEHUDA. Obunani o'chirish kerak.

═══════════════════════════════════════════════════════════════════════════
YECHIM
═══════════════════════════════════════════════════════════════════════════
1. `OpenSourceCatalog.ALL` dan obunani olib tashlash (kod).
2. Telefonда `enabled_ids` dan chiqarish (adb).
3. `awg.xml` dan configni o'chirish (adb).

Ishlatish:
    python3 tools/remove_open_sources.py --list
    python3 tools/remove_open_sources.py --dry-run
    python3 tools/remove_open_sources.py
    python3 tools/remove_open_sources.py --check
    python3 tools/remove_open_sources.py --self-test
    python3 tools/remove_open_sources.py --ids masque_6,masque_7
"""

import html
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time

# ★ O'CHIRILADIGAN obuna ID lari (foydalanuvchi tanladi).
DEFAULT_IDS = ["masque_6", "masque_7", "masque_8", "masque_9"]

CATALOG = "app/src/main/java/com/nurvpn/app/storage/OpenSourceSubscriptions.kt"
BUILTIN = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"
STRINGS = [
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-uz/strings.xml",
    "app/src/main/res/values-ru/strings.xml",
    "app/src/main/res/values-en/strings.xml",
]
BACKUP_DIR = ".fix-backups"


def adb(dev, *args):
    cmd = ["adb"] + (["-s", dev] if dev else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True).stdout


def find_device():
    for line in adb(None, "devices").split("\n")[1:]:
        p = line.split()
        if len(p) >= 2 and p[1] == "device":
            return p[0]
    return None


def backup(p, root):
    d = os.path.join(root, BACKUP_DIR)
    os.makedirs(d, exist_ok=True)
    dst = os.path.join(d, "%s.opensrc.%s.bak" %
                       (os.path.basename(p), time.strftime("%Y%m%d_%H%M%S")))
    shutil.copy2(p, dst)
    return os.path.relpath(dst, root)


def read_xml(s):
    import xml.etree.ElementTree as ET
    ET.fromstring(s)


# ═══════════════════════════════════════════════════════════════════════════
# 1. Kod: OpenSourceCatalog dan olib tashlash
# ═══════════════════════════════════════════════════════════════════════════

def clean_catalog(root, ids, dry):
    p = os.path.join(root, CATALOG)
    if not os.path.exists(p):
        print("  ✘ %s topilmadi" % CATALOG)
        return 1
    t = io.open(p, encoding="utf-8").read()
    removed = []

    for sid in ids:
        # Butun `OpenSourceSubscription(...)` qatorini topamiz
        pat = re.compile(
            r'^[ \t]*OpenSourceSubscription\([^\n]*\bid\s*=\s*"%s"[^\n]*\n'
            % re.escape(sid), re.M)
        m = pat.search(t)
        if not m:
            print("  ⏭  %s: katalogda yo'q" % sid)
            continue
        if dry:
            print("  • %s: katalogdan o'chiriladi" % sid)
            removed.append(sid)
            continue
        t = t[:m.start()] + t[m.end():]
        removed.append(sid)

    if not removed:
        return 0
    if dry:
        return 0

    bk = backup(p, root)
    io.open(p, "w", encoding="utf-8").write(t)
    for sid in removed:
        print("  ✔ %s katalogdan o'chirildi" % sid)
    print("  zaxira: %s" % bk)
    return 0


def clean_builtin(root, ids, dry):
    """BuiltinAwgConfigs.ALL dan ham olib tashlaymiz (endi keraksiz)."""
    p = os.path.join(root, BUILTIN)
    t = io.open(p, encoding="utf-8").read()
    removed = []
    for sid in ids:
        pat = re.compile(r'^[ \t]*"%s"[^\n]*\n' % re.escape(sid), re.M)
        m = pat.search(t)
        if not m:
            continue
        if dry:
            print("  • builtin: %s" % sid)
            removed.append(sid)
            continue
        t = t[:m.start()] + t[m.end():]
        removed.append(sid)
    if not removed or dry:
        return 0
    bk = backup(p, root)
    io.open(p, "w", encoding="utf-8").write(t)
    for sid in removed:
        print("  ✔ builtin %s olib tashlandi" % sid)
    print("  zaxira: %s" % bk)
    return 0


def clean_strings(root, ids, dry):
    """strings.xml dan `os_masque_N_name/_desc` ni olib tashlaymiz."""
    n = 0
    for rel in STRINGS:
        p = os.path.join(root, rel)
        if not os.path.exists(p):
            continue
        t = io.open(p, encoding="utf-8").read()
        orig = t
        for sid in ids:
            for kind in ("name", "desc"):
                key = "os_%s_%s" % (sid, kind)
                t = re.sub(r'^[ \t]*<string name="%s">[^\n]*</string>\n'
                           % re.escape(key), "", t, flags=re.M)
        if t == orig:
            continue
        if dry:
            print("  • %s: stringlar tozalanadi" % rel)
            continue
        backup(p, root)
        io.open(p, "w", encoding="utf-8").write(t)
        n += 1
    if n and not dry:
        print("  ✔ %d strings.xml tozalandi" % n)
    return 0


# ═══════════════════════════════════════════════════════════════════════════
# 2. Telefon: enabled_ids + awg.xml
# ═══════════════════════════════════════════════════════════════════════════

def clean_device(dev, ids, dry):
    if not dev:
        print("  ⚠️  qurilma yo'q")
        return

    # ── enabled_ids ──
    raw = adb(dev, "shell",
              "run-as com.nurvpn.app cat shared_prefs/nurvpn_open_sources.xml "
              "2>/dev/null")
    if raw.strip():
        en = re.search(r'<set name="enabled_ids">(.*?)</set>', raw, re.S)
        de = re.search(r'<set name="deleted_ids">(.*?)</set>', raw, re.S)
        enabled = set(re.findall(r"<string>([^<]+)</string>", en.group(1))) if en else set()
        deleted = set(re.findall(r"<string>([^<]+)</string>", de.group(1))) if de else set()
        print("  enabled_ids: %d | deleted_ids: %d" % (len(enabled), len(deleted)))

        hit = sorted(enabled & set(ids))
        already = sorted(set(ids) & deleted)
        for sid in hit:
            print("  🗑  obuna: %s" % sid)
        for sid in already:
            print("  ⏭  allaqachon o'chirilgan: %s" % sid)

        if hit and not dry:
            enabled -= set(ids)
            deleted |= set(ids)
            xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                   '    <set name="enabled_ids">\n' +
                   "".join("        <string>%s</string>\n" % html.escape(x)
                           for x in sorted(enabled)) +
                   "    </set>\n"
                   '    <set name="deleted_ids">\n' +
                   "".join("        <string>%s</string>\n" % html.escape(x)
                           for x in sorted(deleted)) +
                   "    </set>\n</map>\n")
            read_xml(xml)
            os.makedirs(BACKUP_DIR, exist_ok=True)
            bk = os.path.join(BACKUP_DIR, "device_open_sources.%s.xml" %
                              time.strftime("%Y%m%d_%H%M%S"))
            io.open(bk, "w", encoding="utf-8").write(raw)
            print("  zaxira: %s" % bk)
            tmp = "/tmp/nurvpn_os.xml"
            io.open(tmp, "w", encoding="utf-8").write(xml)
            adb(dev, "shell", "am force-stop com.nurvpn.app")
            time.sleep(2)
            adb(dev, "push", tmp, "/data/local/tmp/nurvpn_os.xml")
            adb(dev, "shell",
                "run-as com.nurvpn.app cp /data/local/tmp/nurvpn_os.xml "
                "shared_prefs/nurvpn_open_sources.xml")
            print("  ✔ enabled_ids: %d -> %d" % (len(enabled) + len(hit), len(enabled)))
    else:
        print("  ⏭  nurvpn_open_sources.xml yo'q")

    # ── awg.xml ──
    raw = adb(dev, "shell",
              "run-as com.nurvpn.app cat shared_prefs/awg.xml 2>/dev/null")
    m = re.search(r'<string name="configs">(.*?)</string>', raw, re.S)
    if not m:
        return
    arr = json.loads(html.unescape(m.group(1)))

    # Qaysi config nomlari o'chiriladigan obunalarga tegishli?
    names = subscription_names(os.path.join(os.getcwd()), ids)
    keep = [c for c in arr if c.get("name") not in names]
    gone = [c for c in arr if c.get("name") in names]
    if gone:
        for c in gone:
            print("  🗑  config: %s" % c.get("name"))
    if not gone or dry:
        return
    if len(keep) == len(arr):
        return

    js = json.dumps(keep, ensure_ascii=False, separators=(",", ":"))
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           '    <string name="configs">' + html.escape(js, quote=True) +
           "</string>\n</map>\n")
    read_xml(xml)
    tmp = "/tmp/nurvpn_awg_os.xml"
    io.open(tmp, "w", encoding="utf-8").write(xml)
    adb(dev, "shell", "am force-stop com.nurvpn.app")
    time.sleep(2)
    adb(dev, "push", tmp, "/data/local/tmp/nurvpn_awg_os.xml")
    adb(dev, "shell",
        "run-as com.nurvpn.app cp /data/local/tmp/nurvpn_awg_os.xml "
        "shared_prefs/awg.xml")
    print("  ✔ awg.xml: %d -> %d config" % (len(arr), len(keep)))


def subscription_names(root, ids):
    """Obuna ID -> ko'rsatiladigan nom (strings.xml dan)."""
    out = {}
    for rel in STRINGS:
        p = os.path.join(root, rel)
        if not os.path.exists(p):
            continue
        t = io.open(p, encoding="utf-8").read()
        for sid in ids:
            m = re.search(r'<string name="os_%s_name">([^<]+)</string>'
                          % re.escape(sid), t)
            if m:
                out[sid] = m.group(1)
                break
    return set(out.values())


# ═══════════════════════════════════════════════════════════════════════════
# list
# ═══════════════════════════════════════════════════════════════════════════

def list_all(root):
    p = os.path.join(root, CATALOG)
    t = io.open(p, encoding="utf-8").read()
    print("  ══ OchIQ MANBALAR O'LCHAMI ══\n")
    for m in re.finditer(r'OpenSourceSubscription\(id\s*=\s*"([^"]+)"[^\n]*?'
                         r'nameRes\s*=\s*R\.string\.([a-z0-9_]+)', t):
        sid, sres = m.group(1), m.group(2)
        nm = sid
        for rel in STRINGS:
            fp = os.path.join(root, rel)
            if not os.path.exists(fp):
                continue
            mm = re.search(r'<string name="%s">([^<]+)</string>' % re.escape(sres),
                           io.open(fp, encoding="utf-8").read())
            if mm:
                nm = mm.group(1)
                break
        mark = "  ← O'CHIRILADI" if sid in DEFAULT_IDS else ""
        print("  %-14s %-22s%s" % (sid, nm, mark))
    return 0


# ═══════════════════════════════════════════════════════════════════════════
# check
# ═══════════════════════════════════════════════════════════════════════════

def check(root, dev, ids):
    print("  ══ CHECK ══\n")
    ok = True

    print("  ── Kod: OpenSourceCatalog ──")
    t = io.open(os.path.join(root, CATALOG), encoding="utf-8").read()
    for sid in ids:
        g = not re.search(r'OpenSourceSubscription\([^\n]*\bid\s*=\s*"%s"'
                          % re.escape(sid), t)
        ok = ok and g
        print("    %s %s" % ("✔" if g else "❌", sid))

    print("\n  ── Telefon ──")
    if dev:
        raw = adb(dev, "shell",
                  "run-as com.nurvpn.app cat shared_prefs/nurvpn_open_sources.xml "
                  "2>/dev/null")
        en = re.search(r'<set name="enabled_ids">(.*?)</set>', raw, re.S)
        enabled = set(re.findall(r"<string>([^<]+)</string>", en.group(1))) if en else set()
        for sid in ids:
            g = sid not in enabled
            ok = ok and g
            print("    %s enabled_ids da yo'q: %s" % ("✔" if g else "❌", sid))
    else:
        print("    ⚠️  qurilma yo'q")

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


# ═══════════════════════════════════════════════════════════════════════════
# self-test
# ═══════════════════════════════════════════════════════════════════════════

def self_test():
    print("  ══ SELF-TEST ══\n")
    ok = True

    t = [
        ("4 ta ID", len(DEFAULT_IDS) == 4),
        ("masque_6 bor", "masque_6" in DEFAULT_IDS),
        ("masque_9 bor", "masque_9" in DEFAULT_IDS),
    ]
    for n, g in t:
        ok = ok and g
        print("    %s %s" % ("✔" if g else "✘", n))

    # Katalogdan o'chirish mantig'i
    sample = '''        OpenSourceSubscription(id = "masque_1", nameRes = R.string.a, awgId = "masque_1"),
        OpenSourceSubscription(id = "masque_6", nameRes = R.string.b, awgId = "masque_6"),
        OpenSourceSubscription(id = "masque_9", nameRes = R.string.c, awgId = "masque_9"),
'''
    out = sample
    for sid in ("masque_6", "masque_9"):
        pat = re.compile(r'^[ \t]*OpenSourceSubscription\([^\n]*\bid\s*=\s*"%s"[^\n]*\n'
                         % re.escape(sid), re.M)
        out = pat.sub("", out)
    g = "masque_6" not in out and "masque_9" not in out and "masque_1" in out
    ok = ok and g
    print("    %s katalog filtri (masque_1 qoladi)" % ("✔" if g else "✘"))

    # XML generatsiyasi
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           '    <set name="enabled_ids">\n'
           "        <string>masque_1</string>\n"
           "    </set>\n"
           '    <set name="deleted_ids">\n'
           "        <string>masque_6</string>\n"
           "    </set>\n</map>\n")
    try:
        read_xml(xml)
        g = True
    except Exception:
        g = False
    ok = ok and g
    print("    %s XML parse bo'ladi" % ("✔" if g else "✘"))

    # enabled_ids ni qayta o'qish
    en = re.search(r'<set name="enabled_ids">(.*?)</set>', xml, re.S)
    got = set(re.findall(r"<string>([^<]+)</string>", en.group(1)))
    g = got == {"masque_1"}
    ok = ok and g
    print("    %s enabled_ids o'qish" % ("✔" if g else "✘"))

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
    dev = find_device()

    ids = DEFAULT_IDS
    for i, a in enumerate(args):
        if a == "--ids" and i + 1 < len(args):
            ids = [x.strip() for x in args[i + 1].split(",") if x.strip()]

    if "--list" in args:
        return list_all(root)
    if "--check" in args:
        return check(root, dev, ids)

    print("  ══ Ochiq manbalardan o'chirish ══")
    print("  ID lar: %s\n" % ", ".join(ids))
    if dry:
        print("  (dry-run)\n")

    rc = clean_catalog(root, ids, dry)
    if rc:
        return rc
    print()
    clean_builtin(root, ids, dry)
    print()
    clean_strings(root, ids, dry)
    print()
    clean_device(dev, ids, dry)

    print("\n  Keyingi qadam:")
    print("    ./gradlew assembleDebug --offline")
    print("    python3 tools/remove_open_sources.py --check")
    return 0


if __name__ == "__main__":
    sys.exit(main())
