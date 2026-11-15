#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_server_pubkey.py — Server ↔ PublicKey juftligini TIKLASH (TLS pin)
=====================================================================

═══════════════════════════════════════════════════════════════════════
ILDIZ SABAB — ISBOTLANGAN
═══════════════════════════════════════════════════════════════════════

Yadro MASQUE ulanishida TLS sertifikatining SPKI kalitini
`public_key` bilan solishtiradi:

    "masque: remote endpoint has a different public key than pinned"

Buni qurilmada 693 marta ko'rdik (Redmi Note 10S, 2026-11-15).

HAQIQIY KALITLAR (TLS orqali o'lchangan, SNI=4pda.to):

    162.159.197.1  →  MFkw…ab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7eNbWwRQb…
    162.159.192.1  →  MFkw…ab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7eNbWwRQb…
    162.159.193.1  →  MFkw…ab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7eNbWwRQb…
    162.159.195.1  →  MFkw…ab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7eNbWwRQb…
    162.159.198.2  →  MFkw…IaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1…
    162.159.199.2  →  MFkw…IaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1…

Ya'ni IKKI GURUH bor:

    GURUH A (ishlaydi):  197.1, 192.1, 193.1, 195.1
    GURUH B (bloklangan): 198.2, 199.2

Ilovadagi `PublicKey` — GURUH B niki (`…IaU7MToJ…`).
`fix_working_servers.py` serverlarni GURUH A ga o'tkazdi, lekin
`PublicKey` GURUH B niki qoldi → pin mos kelmadi.

═══════════════════════════════════════════════════════════════════════
YECHIM
═══════════════════════════════════════════════════════════════════════

Har bir config uchun `Server` ga mos `PublicKey` ni yozamiz:
TLS orqali o'sha serverning haqiqiy sertifikat kalitini o'lchab
(`--probe`) yoki ma'lum jadvaldan (`--known`).

ISHLATISH:
    python3 tools/fix_server_pubkey.py --root . --probe    # TLS orqali o'lchab
    python3 tools/fix_server_pubkey.py --root . --known    # ma'lum jadvaldan
    python3 tools/fix_server_pubkey.py --root . --check
    python3 tools/fix_server_pubkey.py --self-test
"""

import argparse
import base64
import datetime
import json
import os
import re
import shutil
import socket
import ssl
import sys

CFG = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"
MARK = "FIX_SERVER_PUBKEY"

# O'lchangan haqiqiy kalitlar (SNI=4pda.to, 2026-11-15)
KNOWN_KEYS = {
    "162.159.197.1": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7eNbWwRQb5dzmIDq7b3qaidcGNR/qxBCaa8reMTK63bMGpwW9J/H59w==",
    "162.159.192.1": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7eNbWwRQb5dzmIDq7b3qaidcGNR/qxBCaa8reMTK63bMGpwW9J/H59w==",
    "162.159.193.1": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7eNbWwRQb5dzmIDq7b3qaidcGNR/qxBCaa8reMTK63bMGpwW9J/H59w==",
    "162.159.195.1": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7eNbWwRQb5dzmIDq7b3qaidcGNR/qxBCaa8reMTK63bMGpwW9J/H59w==",
}
DEFAULT_SNI = "4pda.to"
DEFAULT_KEY = KNOWN_KEYS["162.159.197.1"]


def b64len(k):
    try:
        return len(base64.b64decode(k))
    except Exception:
        return -1


def load(root):
    p = os.path.join(root, CFG)
    if not os.path.isfile(p):
        return None, p
    with open(p, encoding="utf-8") as f:
        return f.read(), p


def probe(ip, sni=DEFAULT_SNI, timeout=10):
    """TLS orqali server sertifikatining SPKI kalitini oladi. (str | None)"""
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization
    except ImportError:
        return None
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        s = socket.create_connection((ip, 443), timeout=timeout)
        ss = ctx.wrap_socket(s, server_hostname=sni)
        der = ss.getpeercert(binary_form=True)
        ss.close()
        c = x509.load_der_x509_certificate(der)
        spki = c.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo)
        return base64.b64encode(spki).decode()
    except Exception:
        return None


def get_keys(root, use_probe):
    """{server: public_key}"""
    t, _ = load(root)
    servers = sorted({re.search(r"(?m)^Server\s*=\s*(\S+)", b).group(1)
                      for _, b in re.findall(r'val (MASQUE_\d+): String = """(.*?)"""', t, re.S)})
    keys = {}
    print("\n  ── Serverlar uchun kalitlar ──")
    for ip in servers:
        k = None
        src = ""
        if use_probe:
            k = probe(ip)
            src = "TLS"
        if not k and ip in KNOWN_KEYS:
            k = KNOWN_KEYS[ip]
            src = "jadval"
        if not k:
            k = DEFAULT_KEY
            src = "standart"
        keys[ip] = k
        print("  %-18s …%-24s [%s]" % (ip, k[52:74], src))
    return keys


def transform(t, keys):
    ch = []

    def fix(m):
        name, body = m.group(1), m.group(2)
        sm = re.search(r"(?m)^(Server\s*=\s*)(\S+)", body)
        qm = re.search(r"(?m)^(PublicKey\s*=\s*)(\S+)", body)
        if not sm or not qm:
            return m.group(0)
        srv, cur = sm.group(2), qm.group(2)
        want = keys.get(srv)
        if not want or want == cur:
            return m.group(0)
        ch.append((name, srv, cur[52:72], want[52:72]))
        nb = body[:qm.start(2)] + want + body[qm.end(2):]
        return 'val %s: String = """%s"""' % (name, nb)

    out = re.sub(r'val (MASQUE_\d+): String = """(.*?)"""', fix, t, flags=re.S)
    return out, ch


def apply(root, use_probe, dry):
    t, p = load(root)
    if t is None:
        print("  ✘ topilmadi:", p)
        return 1
    if MARK in t:
        print("  ⏭  allaqachon qo'llangan (%s)" % MARK)
        return 0

    keys = get_keys(root, use_probe)
    out, ch = transform(t, keys)

    print("\n══ SERVER ↔ PUBLICKEY JUFTLIGI ══\n")
    if not ch:
        print("  • o'zgarish yo'q — juftliklar to'g'ri")
        return 0

    print("  %d ta tuzatish:" % len(ch))
    for n, srv, old, new in ch:
        print("    %-11s %-16s …%s → …%s" % (n, srv, old, new))

    if dry:
        print("\n  (dry-run) hech narsa yozilmadi")
        return 0

    out = out.replace("object BuiltinAwgConfigs",
                      "// ★ %s: PublicKey server sertifikatiga moslashtirildi\n"
                      "object BuiltinAwgConfigs" % MARK, 1)

    d = os.path.join(root, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    b = os.path.join(d, os.path.basename(CFG) + ".serverpub.%s.bak" % ts)
    shutil.copy2(p, b)
    print("\n  backup: %s" % os.path.relpath(b, root))
    with open(p, "w", encoding="utf-8") as f:
        f.write(out)
    print("  ✔ yozildi")

    kp = os.path.join(d, "server_keys.%s.json" % ts)
    with open(kp, "w", encoding="utf-8") as f:
        json.dump(keys, f, indent=2)
    print("  ✔ kalitlar: %s" % os.path.relpath(kp, root))
    return 0


def check(root, use_probe):
    t, _ = load(root)
    if t is None:
        print("  ✘ topilmadi")
        return 1
    bs = re.findall(r'val (MASQUE_\d+): String = """(.*?)"""', t, re.S)
    keys = get_keys(root, use_probe)
    ok = True

    print("\n── 1. PublicKey serverga mosmi? ──")
    for n, b in bs:
        srv = re.search(r"(?m)^Server\s*=\s*(\S+)", b).group(1)
        pub = re.search(r"(?m)^PublicKey\s*=\s*(\S+)", b).group(1)
        want = keys.get(srv, DEFAULT_KEY)
        g = pub == want
        ok = ok and g
        print("  %s %-11s %-16s %s" % ("✔" if g else "✘", n, srv,
                                       "mos" if g else "MOS EMAS"))

    print("\n── 2. Bir server = bir kalit ──")
    grp = {}
    for _, b in bs:
        srv = re.search(r"(?m)^Server\s*=\s*(\S+)", b).group(1)
        pub = re.search(r"(?m)^PublicKey\s*=\s*(\S+)", b).group(1)
        grp.setdefault(srv, set()).add(pub)
    for srv, pubs in sorted(grp.items()):
        g = len(pubs) == 1
        ok = ok and g
        print("  %s %-18s %d xil kalit" % ("✔" if g else "✘", srv, len(pubs)))

    print("\n── 3. Format (P-256 ECDSA, 91 bayt) ──")
    bad = [n for n, b in bs
           if b64len(re.search(r"(?m)^PublicKey\s*=\s*(\S+)", b).group(1)) != 91]
    g = not bad
    ok = ok and g
    print("  %s %s" % ("✔" if g else "✘", "11/11 to'g'ri" if g else "xato: " + ", ".join(bad)))

    print("\n── 4. Bloklangan server (GURUH B) qolmaganmi? ──")
    blocked = {"162.159.198.2", "162.159.199.2"}
    used = {re.search(r"(?m)^Server\s*=\s*(\S+)", b).group(1) for _, b in bs}
    g = not (used & blocked)
    ok = ok and g
    print("  %s %s" % ("✔" if g else "✘",
                       "toza" if g else "bor: " + ", ".join(used & blocked)))

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def self_test():
    ok = True
    print("\n── Guruhlarni aniqlash ──")
    a = KNOWN_KEYS["162.159.197.1"]
    b = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w=="
    g = a != b and b64len(a) == 91 and b64len(b) == 91
    ok = ok and g
    print("  %s GURUH A ≠ GURUH B, ikkalasi ham 91 bayt" % ("✔" if g else "✘"))

    print("\n── Almashtirish ──")
    sample = '''object BuiltinAwgConfigs {
    val MASQUE_1: String = """
PrivateKey = K
PublicKey = %s
Server = 162.159.197.1
Network = h2"""
    val MASQUE_2: String = """
PrivateKey = K
PublicKey = %s
Server = 162.159.197.1
Network = h2"""
}''' % (b, a)
    keys = {"162.159.197.1": a}
    out, ch = transform(sample, keys)
    g = len(ch) == 1
    ok = ok and g
    print("  %s faqat noto'g'ri config tuzatildi" % ("✔" if g else "✘"))
    g2 = out.count(a) == 2
    ok = ok and g2
    print("  %s ikkalasi ham GURUH A kalitida" % ("✔" if g2 else "✘"))

    print("\n── Boshqa maydonlar saqlanadi ──")
    for f in ("PrivateKey = K", "Network = h2", "Server = 162.159.197.1"):
        g = f in out
        ok = ok and g
        print("  %s %s" % ("✔" if g else "✘", f))

    print("\n── Ma'lum kalitlar jadvali ──")
    for ip, k in KNOWN_KEYS.items():
        g = b64len(k) == 91
        ok = ok and g
        print("  %s %-18s …%s (%d bayt)" % ("✔" if g else "✘", ip, k[52:70], b64len(k)))

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--probe", action="store_true", help="TLS orqali o'lchash")
    ap.add_argument("--known", action="store_true", help="Ma'lum jadvaldan")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    root = os.path.abspath(a.root)

    if a.self_test:
        return self_test()
    if a.check:
        return check(root, a.probe)

    print("\n══ SERVER ↔ PUBLICKEY JUFTLIGI ══")
    rc = apply(root, a.probe, a.dry_run)
    if rc == 0 and not a.dry_run:
        print("\n  Keyingi qadam:")
        print("    python3 tools/fix_server_pubkey.py --root . --check")
        print("    ./gradlew assembleDebug test --offline")
    print()
    return rc


if __name__ == "__main__":
    sys.exit(main())
