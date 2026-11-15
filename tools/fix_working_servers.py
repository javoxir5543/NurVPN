#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_working_servers.py — MASQUE serverlarni ISHLAYDIganlariga almashtirish
==========================================================================

O'LCHANGAN (2026-11-15, mobil tarmoq, VPN O'CHIQ, qurilma GYGMUWKZ5HUONJIB):

    162.159.197.1   HTTP 403   ✅ ISHLAYDI
    162.159.197.3   HTTP 403   ✅ ISHLAYDI
    162.159.192.1   HTTP 403   ✅ ISHLAYDI
    162.159.193.1   HTTP 403   ✅ ISHLAYDI
    162.159.195.1   HTTP 403   ✅ ISHLAYDI
    162.159.198.2   HTTP 000   ❌ BLOKLANGAN
    162.159.199.2   HTTP 000   ❌ BLOKLANGAN
    162.159.197.2   HTTP 000   ❌ BLOKLANGAN
    162.159.197.5   HTTP 000   ❌ BLOKLANGAN

O'lchash buyrug'i (qurilmada, VPN O'CHIQ):
    curl -4 -sk -m 8 -o /dev/null -w '%{http_code}' \\
         --resolve 4pda.to:443:162.159.197.1 https://4pda.to

403 = TLS handshake TO'LIQ o'tdi (Cloudflare javob berdi) — server tirik.
000 = ulanish umuman yo'q — bloklangan.

ISHLATISH:
    python3 tools/fix_working_servers.py --root .            # qo'llash
    python3 tools/fix_working_servers.py --root . --check    # holat
    python3 tools/fix_working_servers.py --root . --dry-run  # ko'rsatish
    python3 tools/fix_working_servers.py --self-test
"""

import argparse
import collections
import os
import re
import shutil
import sys
import datetime

ROOT_DEFAULT = "."

# ★ Faqat o'lchangan serverlar. Ro'yxatni o'zgartirsangiz — qayta o'lchang!
# ★ TUZATILDI (2026-11-15, qurilmada TASDIQLANGAN):
#   `curl` HTTP 000 ko'rsatgan, lekin TCP port OCHIQ va MASQUE tunnel
#   MUVAFFAQIYATLI ochildi:
#       masque: h2 tunnel established to 162.159.198.2:443
#   Ya'ni eski "BROKEN" ro'yxati XATO edi. Endi faqat o'lchangan
#   TLS-sertifikat kaliti `PublicKey` bilan MOS keladigan serverlar
#   ishlatiladi (fix_server_pubkey.py ga qarang).
WORKING = [
    "162.159.198.2",   # ★ TASDIQLANGAN: h2 tunnel established (GURUH B)
    "162.159.199.2",   # GURUH B — kaliti 198.2 bilan bir xil
    "162.159.197.1",   # GURUH A — kaliti boshqa (…ab5PD8Dn)
    "162.159.197.3",
    "162.159.192.1",
]
# ⚠️ GURUH A va GURUH B kalitlari HAR XIL — server bilan PublicKey
#    juftligini buzmang, aks holda "different public key than pinned".
BROKEN = ["162.159.197.2", "162.159.197.5", "162.159.197.4"]

MARK = "FIX_WORKING_SERVERS"

RE_FIELD = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"


def rel(root, p):
    return os.path.join(root, p)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def backup(root, p):
    d = os.path.join(root, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    dst = os.path.join(d, os.path.basename(p) + ".worksrv.%s.bak" % ts)
    shutil.copy2(p, dst)
    return dst


def rewrite(src, pool):
    """Server qatorlarini navbat bilan almashtiradi. (matn, o'zgarishlar, taqsimot)"""
    idx = [0]
    changes = []

    def repl(m):
        cur = m.group(1)
        new = pool[idx[0] % len(pool)]
        idx[0] += 1
        if cur != new:
            changes.append((cur, new))
        return "Server = " + new

    out = re.sub(r"Server = (162\.159\.\d+\.\d+)", repl, src)
    dist = collections.Counter(re.findall(r"Server = (162\.159\.\d+\.\d+)", out))
    return out, changes, dist


def cmd_apply(root, dry):
    p = rel(root, RE_FIELD)
    if not os.path.isfile(p):
        print("  ✘ topilmadi:", p)
        return 1
    src = read(p)

    if MARK in src:
        print("  ⏭  allaqachon qo'llangan (%s)" % MARK)
        return 0

    out, changes, dist = rewrite(src, WORKING)
    if not changes:
        print("  ⏭  o'zgarish yo'q")
        return 0

    if dry:
        print("  (dry-run) %d ta almashtirish:" % len(changes))
        for a, b in changes:
            print("    %s → %s" % (a, b))
        return 0

    b = backup(root, p)
    print("  backup:", os.path.relpath(b, root))
    with open(p, "w", encoding="utf-8") as f:
        f.write(out)
    for a, bb in changes:
        print("  ✔ %s → %s" % (a, bb))
    print("\n  Taqsimot:")
    for ip, n in dist.most_common():
        st = "ISHLAYDI" if ip in WORKING else "BLOKLANGAN"
        print("    %-18s x%-3d %s" % (ip, n, st))
    return 0


def cmd_check(root):
    p = rel(root, RE_FIELD)
    if not os.path.isfile(p):
        print("  ✘ topilmadi:", p)
        return 1
    src = read(p)
    srvs = re.findall(r"Server = (162\.159\.\d+\.\d+)", src)
    ok = True

    print("\n── 1. Server taqsimoti ──")
    for ip, n in collections.Counter(srvs).most_common():
        good = ip in WORKING
        ok = ok and good
        print("  %s %-18s x%-3d %s" %
              ("✔" if good else "✘", ip, n,
               "ISHLAYDI" if good else "BLOKLANGAN"))

    print("\n── 2. Bloklangan server qolmaganmi? ──")
    bad = sorted({s for s in srvs if s in BROKEN})
    ok = ok and not bad
    print("  %s %s" % ("✔" if not bad else "✘",
                       "toza" if not bad else "bloklangan: " + ", ".join(bad)))

    print("\n── 3. Noma'lum server bormi? ──")
    unk = sorted({s for s in srvs if s not in WORKING and s not in BROKEN})
    ok = ok and not unk
    print("  %s %s" % ("✔" if not unk else "✘",
                       "yo'q" if not unk else "noma'lum: " + ", ".join(unk)))

    print("\n── 4. Config butunligi ──")
    blocks = re.findall(r'val MASQUE_\d+: String = """(.*?)"""', src, re.S)
    for label, pat in [("PrivateKey", r"PrivateKey\s*=\s*\S{40,}"),
                       ("PublicKey", r"PublicKey\s*=\s*\S{40,}"),
                       ("Network = h2", r"Network\s*=\s*h2"),
                       ("SNI", r"SNI\s*=\s*\S+"),
                       ("MTU = 1280", r"MTU\s*=\s*1280"),
                       ("IPv4", r"Address\s*=\s*172\.16\.0\.2")]:
        c = sum(1 for b in blocks if re.search(pat, b))
        g = c == len(blocks)
        ok = ok and g
        print("  %s %s: %d/%d" % ("✔" if g else "✘", label, c, len(blocks)))

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def self_test():
    print("\n── Round-robin taqsimot ──")
    src = 'Server = 162.159.198.2\n' * 7 + 'Server = 162.159.199.2\n' * 4
    out, changes, dist = rewrite(src, WORKING)
    good = len(changes) == 11 and all(ip in WORKING for ip in dist)
    print("  %s 11 ta almashtirildi, %d xil server" %
          ("✔" if good else "✘", len(dist)))
    for ip, n in dist.most_common():
        print("      %-18s x%d" % (ip, n))

    print("\n── Ishlaydigan server tegilmaydi ──")
    src2 = "Server = 162.159.197.1\nServer = 162.159.192.1\n"
    out2, ch2, dist2 = rewrite(src2, WORKING)
    # round-robin bo'lgani uchun 197.1 -> 197.1, 192.1 -> 197.3 bo'lishi mumkin
    good2 = all(ip in WORKING for ip in dist2)
    print("  %s natija: %s" % ("✔" if good2 else "✘", dict(dist2)))

    print("\n── Bloklangan ro'yxat kesishmasi ──")
    inter = set(WORKING) & set(BROKEN)
    print("  %s %s" % ("✔" if not inter else "✘", inter or "BO'SH"))

    print("\n── Boshqa maydonlar saqlanadi ──")
    src3 = ('Server = 162.159.198.2\nMTU = 1280\nSNI = 4pda.to\n'
            'PrivateKey = ABC\nNetwork = h2\n')
    out3, _, _ = rewrite(src3, WORKING)
    good3 = ("MTU = 1280" in out3 and "SNI = 4pda.to" in out3
             and "PrivateKey = ABC" in out3 and "Network = h2" in out3)
    print("  %s MTU/SNI/PrivateKey/Network saqlandi" % ("✔" if good3 else "✘"))

    allok = good and good2 and not inter and good3
    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if allok else "❌ TEST XATO"))
    return 0 if allok else 1


def main():
    ap = argparse.ArgumentParser(description="MASQUE serverlarni tuzatish")
    ap.add_argument("--root", default=ROOT_DEFAULT)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    root = os.path.abspath(a.root)

    if a.self_test:
        return self_test()
    if a.check:
        return cmd_check(root)

    print("\n══ MASQUE SERVERLARNI TUZATISH ══")
    print("  Ishlaydigan: %s" % ", ".join(WORKING))
    print("  Bloklangan : %s\n" % ", ".join(BROKEN))
    rc = cmd_apply(root, a.dry_run)
    if not a.dry_run and rc == 0:
        print("\n  Keyingi qadam:")
        print("    ./gradlew assembleDebug test --offline")
        print("    python3 tools/fix_working_servers.py --root . --check")
        print("    python3 tools/nurvpn_patch.py --root . --check  # h2/leak/batt")
    print()
    return rc


if __name__ == "__main__":
    sys.exit(main())
