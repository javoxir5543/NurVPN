#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_x25519_keys.py — MASQUE kalitlarini X25519 ga o'tkazish
===========================================================

═══════════════════════════════════════════════════════════════════════
ILDIZ SABAB (isbotlangan)
═══════════════════════════════════════════════════════════════════════

Qurilma logi (501 marta):
    masque: remote endpoint has a different public key than pinned

Yadro stringlari (libbox.so):
    "public_key is not an ECDSA key"
    "private_key and public_key are required for the cloudflare profile"
    "masque: public key required for pinning"
    "masque: remembered h..."        ← kalit eslab qolinadi

CLOUDFLARE API JAVOBI (zero-trust-client/v0i1909051800/reg):
    PublicKey  : bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
    PrivateKey : 7HGrI/InYcrVpYjTeoxc253X+ocYLxEdufyhHY7Oe1M=

BAYT TAHLILI:
    bmXOC…  → 32 bayt, X25519          ✅ Cloudflare beradi
    7HGrI…  → 32 bayt, X25519          ✅ Cloudflare beradi

    Hozirgi ilovada:
    MFkw…   → 91 bayt, DER P-256       ❌ Cloudflare BERMAYDI
    MHcC…   → 138 bayt, DER P-256      ❌ Cloudflare BERMAYDI

Ya'ni ilovadagi BARCHA MASQUE kalitlari noto'g'ri formatda:
WireGuard/MASQUE X25519 (32 bayt) talab qiladi, ilova esa P-256 DER
(ASN.1 SubjectPublicKeyInfo) ishlatgan.

NATIJA:
  • X25519 PublicKey + P-256 PrivateKey → "asn1: structure error"
  • P-256 PublicKey + P-256 PrivateKey  → "different public key than pinned"

Ikkalasi ham X25519 bo'lishi SHART.

═══════════════════════════════════════════════════════════════════════
YECHIM
═══════════════════════════════════════════════════════════════════════

Cloudflare API'dan yangi WARP profil olib, uning X25519 kalitlarini
(`PrivateKey` + `PublicKey`) barcha MASQUE config'larga yozamiz.

`PrivateKey` — foydalanuvchi identifikatori (har profil uchun boshqa).
`PublicKey` — Cloudflare'ning server kaliti (barcha profillarda bir xil).

MUHIM: `PrivateKey` va `PublicKey` JUFTLIK bo'lishi shart — Cloudflare
`PrivateKey` dan `PublicKey` ni hosil qiladi.

ISHLATISH:
    python3 tools/fix_x25519_keys.py --root . --fetch     # API'dan olib qo'llash
    python3 tools/fix_x25519_keys.py --root . --check
    python3 tools/fix_x25519_keys.py --self-test
"""

import argparse
import base64
import datetime
import json
import os
import re
import shutil
import subprocess
import sys

CFG = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"
MARK = "FIX_X25519_KEYS"
WARP_API = "https://zero-trust-client.cloudflareclient.com/v0i1909051800/reg"


def key_len(k):
    try:
        return len(base64.b64decode(k))
    except Exception:
        return -1


def is_x25519(k):
    return key_len(k) == 32


def is_der(k):
    b = base64.b64decode(k) if k else b""
    return len(b) > 32 and b[:1] == b"\x30"


def load(root):
    p = os.path.join(root, CFG)
    if not os.path.isfile(p):
        return None, p
    with open(p, encoding="utf-8") as f:
        return f.read(), p


def fetch_warp_profile():
    """Cloudflare'dan yangi WARP profil oladi. (dict | None)"""
    priv = base64.b64encode(os.urandom(32)).decode()
    body = json.dumps({"key": priv, "install_id": "", "fcm_token": "",
                       "tos": "2023-01-01T00:00:00.000Z", "model": "PC",
                       "serial_number": "", "locale": "en_US"})
    try:
        r = subprocess.run(
            ["curl", "-s", "-m", "30", "-X", "POST", WARP_API,
             "-H", "Content-Type: application/json",
             "-H", "User-Agent: okhttp/3.12.1",
             "-H", "CF-Client-Version: a-6.30-2158", "-d", body],
            capture_output=True, text=True, timeout=40)
    except Exception as e:
        print("  ✘ API chaqiruvi xato:", e)
        return None

    try:
        d = json.loads(r.stdout)
    except Exception:
        print("  ✘ javob JSON emas:", r.stdout[:150])
        return None

    res = d.get("result") or {}
    cfg = res.get("config") or {}
    peers = cfg.get("peers") or [{}]
    peer = peers[0]
    pk_priv = res.get("private_key") or res.get("key") or ""
    pk_pub = peer.get("public_key") or ""
    if not pk_priv or not pk_pub:
        print("  ✘ javobda kalitlar yo'q:", json.dumps(d)[:200])
        return None
    return {"private_key": pk_priv, "public_key": pk_pub,
            "endpoint": (peer.get("endpoint") or {}).get("host", ""),
            "v4": (((cfg.get("interface") or {}).get("addresses") or {}).get("v4", "")),
            "v6": (((cfg.get("interface") or {}).get("addresses") or {}).get("v6", ""))}


def transform(t, prof):
    """(yangi_matn, o'zgarishlar)"""
    ch = []

    def fix(m):
        name, body = m.group(1), m.group(2)
        nb = body

        # PrivateKey → X25519
        pm = re.search(r"(?m)^(PrivateKey\s*=\s*)(\S+)", nb)
        if pm and not is_x25519(pm.group(2)):
            ch.append((name, "PrivateKey", key_len(pm.group(2))))
            nb = nb[:pm.start(2)] + prof["private_key"] + nb[pm.end(2):]

        # PublicKey → X25519
        qm = re.search(r"(?m)^(PublicKey\s*=\s*)(\S+)", nb)
        if qm and not is_x25519(qm.group(2)):
            ch.append((name, "PublicKey", key_len(qm.group(2))))
            nb = nb[:qm.start(2)] + prof["public_key"] + nb[qm.end(2):]

        return 'val %s: String = """%s"""' % (name, nb) if nb != body else m.group(0)

    out = re.sub(r'val (MASQUE_\d+): String = """(.*?)"""', fix, t, flags=re.S)
    return out, ch


def apply(root, prof, dry):
    t, p = load(root)
    if t is None:
        print("  ✘ topilmadi:", p)
        return 1
    if MARK in t:
        print("  ⏭  allaqachon qo'llangan (%s)" % MARK)
        return 0

    out, ch = transform(t, prof)

    print("\n══ MASQUE KALITLARI → X25519 ══\n")
    print("  PrivateKey : %s (%d bayt)" % (prof["private_key"][:40] + "…",
                                           key_len(prof["private_key"])))
    print("  PublicKey  : %s (%d bayt)" % (prof["public_key"],
                                           key_len(prof["public_key"])))
    print("  Endpoint   : %s" % prof["endpoint"])

    if not ch:
        print("\n  • o'zgarish yo'q — barcha kalitlar X25519")
        return 0

    print("\n  %d ta tuzatish:" % len(ch))
    for n, f, kl in ch[:6]:
        print("    %-11s %-11s (%d bayt → 32)" % (n, f, kl))
    if len(ch) > 6:
        print("    … va yana %d ta" % (len(ch) - 6))

    if dry:
        print("\n  (dry-run) hech narsa yozilmadi")
        return 0

    out = out.replace("object BuiltinAwgConfigs",
                      "// ★ %s: MASQUE kalitlari X25519 (P-256 DER edi)\n"
                      "object BuiltinAwgConfigs" % MARK, 1)

    d = os.path.join(root, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    b = os.path.join(d, os.path.basename(CFG) + ".x25519.%s.bak" % ts)
    shutil.copy2(p, b)
    print("\n  backup: %s" % os.path.relpath(b, root))

    with open(p, "w", encoding="utf-8") as f:
        f.write(out)
    print("  ✔ yozildi")

    wp = os.path.join(d, "warp_profile.%s.json" % ts)
    with open(wp, "w", encoding="utf-8") as f:
        json.dump(prof, f, indent=2)
    print("  ✔ profil saqlandi: %s" % os.path.relpath(wp, root))
    return 0


def check(root):
    t, _ = load(root)
    if t is None:
        print("  ✘ topilmadi")
        return 1
    bs = re.findall(r'val (MASQUE_\d+): String = """(.*?)"""', t, re.S)
    ok = True

    print("\n── 1. PrivateKey (X25519, 32 bayt) ──")
    for n, b in bs:
        m = re.search(r"(?m)^PrivateKey\s*=\s*(\S+)", b)
        k = m.group(1) if m else ""
        g = is_x25519(k)
        ok = ok and g
        print("  %s %-11s %3d bayt %s" % ("✔" if g else "✘", n, key_len(k),
                                          "" if g else ("DER" if is_der(k) else "?")))

    print("\n── 2. PublicKey (X25519, 32 bayt) ──")
    allok = True
    for n, b in bs:
        m = re.search(r"(?m)^PublicKey\s*=\s*(\S+)", b)
        k = m.group(1) if m else ""
        g = is_x25519(k)
        allok = allok and g
        if not g:
            print("  ✘ %-11s %3d bayt" % (n, key_len(k)))
    ok = ok and allok
    if allok:
        print("  ✔ 11/11 X25519")

    print("\n── 3. PrivateKey ↔ PublicKey juftligi ──")
    privs = {re.search(r"(?m)^PrivateKey\s*=\s*(\S+)", b).group(1) for _, b in bs}
    pubs = {re.search(r"(?m)^PublicKey\s*=\s*(\S+)", b).group(1) for _, b in bs}
    g = len(pubs) == 1
    ok = ok and g
    print("  %s %d xil PrivateKey, %d xil PublicKey (1 bo'lishi kerak)"
          % ("✔" if g else "✘", len(privs), len(pubs)))

    print("\n── 4. Boshqa maydonlar ──")
    for label, pat in [("Server", r"Server\s*=\s*\d"),
                       ("Network = h2", r"Network\s*=\s*h2"),
                       ("MTU", r"MTU\s*=\s*\d+"),
                       ("SNI", r"SNI\s*=\s*\S+")]:
        c = sum(1 for _, b in bs if re.search(pat, b))
        g = c == len(bs)
        ok = ok and g
        print("  %s %s: %d/%d" % ("✔" if g else "✘", label, c, len(bs)))

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def self_test():
    ok = True
    print("\n── Kalit turlarini aniqlash ──")
    x = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    p256 = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4"
            "mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==")
    for label, k, want in [("Cloudflare X25519", x, True),
                           ("eski P-256 DER", p256, False)]:
        g = is_x25519(k) == want and key_len(k) == (32 if want else 91)
        ok = ok and g
        print("  %s %-18s %3d bayt X25519=%s" % ("✔" if g else "✘", label,
                                                 key_len(k), is_x25519(k)))

    print("\n── Blok almashtirish ──")
    prof = {"private_key": x, "public_key": x, "endpoint": "e:2408", "v4": "", "v6": ""}
    sample = '''object BuiltinAwgConfigs {
    val MASQUE_1: String = """
PrivateKey = %s
PublicKey = %s
Server = 162.159.197.1
Network = h2
MTU = 1280"""
}''' % (p256, p256)
    out, ch = transform(sample, prof)
    g = len(ch) == 2
    ok = ok and g
    print("  %s 2 ta kalit almashtirildi (PrivateKey + PublicKey)" % ("✔" if g else "✘"))
    for f, pat in [("PrivateKey", r"(?m)^PrivateKey\s*=\s*(\S+)"),
                   ("PublicKey", r"(?m)^PublicKey\s*=\s*(\S+)")]:
        mm = re.search(pat, out)
        k = mm.group(1) if mm else ""
        gg = is_x25519(k)
        ok = ok and gg
        print("  %s %s → X25519" % ("✔" if gg else "✘", f))

    print("\n── Boshqa maydonlar saqlanadi ──")
    for f in ("Server = 162.159.197.1", "Network = h2", "MTU = 1280"):
        g = f in out
        ok = ok and g
        print("  %s %s" % ("✔" if g else "✘", f))

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--fetch", action="store_true",
                    help="Cloudflare API'dan yangi WARP profil olib qo'llash")
    a = ap.parse_args()
    root = os.path.abspath(a.root)

    if a.self_test:
        return self_test()
    if a.check:
        return check(root)

    print("\n══ MASQUE KALITLARI X25519 ══")
    if a.fetch:
        print("  Cloudflare API'dan yangi WARP profil olinmoqda…")
        prof = fetch_warp_profile()
        if not prof:
            print("  ❌ profil olinmadi")
            return 1
        print("  ✔ profil olindi (PublicKey %d bayt, PrivateKey %d bayt)"
              % (key_len(prof["public_key"]), key_len(prof["private_key"])))
    else:
        t, _ = load(root)
        m = re.search(r"(?m)^PrivateKey\s*=\s*(\S+)", t) if t else None
        if not m:
            print("  ✘ PrivateKey topilmadi")
            return 1
        prof = {"private_key": m.group(1), "public_key":
                re.search(r"(?m)^PublicKey\s*=\s*(\S+)", t).group(1),
                "endpoint": "", "v4": "", "v6": ""}

    rc = apply(root, prof, a.dry_run)
    if rc == 0 and not a.dry_run:
        print("\n  Keyingi qadam:")
        print("    python3 tools/fix_x25519_keys.py --root . --check")
        print("    ./gradlew assembleDebug test --offline")
    print()
    return rc


if __name__ == "__main__":
    sys.exit(main())
