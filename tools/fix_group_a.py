#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NurVPN — MASQUE GURUH A ni olib tashlash (runtime almashtirishsiz).

═══════════════════════════════════════════════════════════════════════════
MUAMMO
═══════════════════════════════════════════════════════════════════════════
`GURUH A` serverlari (162.159.197.1 va boshqalar) BOSHQA WARP akkauntga
tegishli. TLS pin o'tadi (kalit to'g'ri), lekin Cloudflare CONNECT-IP
so'rovini `400` bilan rad etadi, chunki `PrivateKey` o'sha akkauntniki emas.

    connect-ip: server responded with 400     (206 marta o'lchangan)

Oldingi yechim runtime'da serverni `198.2` ga JIMGINA almashtirardi. Bu
YOMON edi:
  * UI `197.1` ko'rsatadi, trafik `198.2` ga ketadi — foydalanuvchi
    bilmaydi. Bu config-match xatosining boshqa ko'rinishi.
  * `HTTP 400` faqat BIZNING o'lchov hisoblarimizda kuzatilgan. Boshqa
    hisob (masalan, warp-gen'dan olingan o'z kaliti) bilan A ishlashi
    mumkin — jadval uni ham buzardi.
  * Jadval statik: Cloudflare guruhlarni o'zgartirsa, kod eskirgan
    ma'lumot bilan ishlaydi.

═══════════════════════════════════════════════════════════════════════════
YECHIM
═══════════════════════════════════════════════════════════════════════════
1. MA'LUMOTNI TO'G'RILASH: `BuiltinAwgConfigs.kt` dagi A serverlarini
   GURUH B ga o'tkazamiz. Bu oddiy ma'lumot tuzatishi — UI to'g'ri
   ko'rsatadi, yadro to'g'ri ishlatadi. Hech qanday sehr yo'q.

2. RUNTIME ALMASHTIRISHNI OLIB TASHLASH: `SingBoxConfig.kt` dan
   `UNUSABLE_SERVERS` / `FALLBACK_SERVER` mantig'i o'chiriladi.
   Foydalanuvchi nima tanlasa — o'sha ishlatiladi.

3. KALIT JUFTLIGI QOLADI: `normalizeMasquePublicKey` faqat ma'lum A/B
   kalitlarni almashtiradi (bizning tarixiy xatomiz). Foydalanuvchining
   o'z kaliti TEGILMAYDI.

Ishlatish:
    python3 tools/fix_group_a.py /home/pc/NurVPN --check
    python3 tools/fix_group_a.py /home/pc/NurVPN
    python3 tools/fix_group_a.py /home/pc/NurVPN --self-test
    python3 tools/fix_group_a.py /home/pc/NurVPN --dry-run
"""

import io
import os
import re
import shutil
import sys
import time

MARK = "FIX_GROUP_A"

# ⚠️ GURUH A — ishlamaydi (boshqa WARP akkaunti, HTTP 400).
GROUP_A = [
    "162.159.197.1", "162.159.197.3", "162.159.197.4",
    "162.159.192.1", "162.159.193.1", "162.159.194.1",
    "162.159.195.1", "162.159.196.1",
]
# GURUH B — ishlaydi (bizning WARP akkauntimiz).
GROUP_B = ["162.159.197.2", "162.159.197.5", "162.159.198.2", "162.159.199.2"]
FALLBACK = "162.159.198.2"

BUILTIN = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"
SINGBOX = "app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt"

# ── SingBoxConfig dan olib tashlanadigan bloklar ──────────────────────────
SB_UNUSABLE = '''    /**
     * ⚠️ ISHLAMAYDIGAN serverlar — BOSHQA WARP akkauntga tegishli.
'''

SB_FALLBACK = '''    /** Ishlaydigan server (GURUH B) — A o'rniga ishlatiladi. */
    private const val FALLBACK_SERVER = "162.159.198.2"

'''

SB_LOOP_OLD = '''        // ⚠️ GURUH A (197.1 va boshqalar) ISHLAMAYDI — TLS pin o'tadi,
        //    lekin `PrivateKey` boshqa WARP akkauntniki va Cloudflare
        //    CONNECT-IP so'rovini `400` bilan rad etadi (206 marta o'lchangan).
        //    Server va kalit BIRGALIKDA GURUH B ga o'tkaziladi.
        if (server in UNUSABLE_SERVERS) {
            Log.w(TAG, "MASQUE: $server ishlamaydi (HTTP 400) -> " +
                "GURUH B ga o'tildi: ${FALLBACK_SERVER}")
            server = FALLBACK_SERVER
        }
        publicKey = normalizeMasquePublicKey(server, publicKey)'''

SB_LOOP_NEW = '''        // ★ %s: runtime server almashtirish OLIB TASHLANDI.
        //
        //   Ilgari A guruhi serverlari (197.1 va boshqalar) JIMGINA
        //   `198.2` ga o'tkazilardi. Bu yomon edi: UI bir narsani
        //   ko'rsatib, trafik boshqasiga ketardi.
        //
        //   Endi ma'lumot `BuiltinAwgConfigs.kt` da to'g'rilangan —
        //   foydalanuvchi nima tanlasa, o'sha ishlatiladi.
        //
        //   Faqat KALIT juftligi tuzatiladi (bizning tarixiy xatomiz);
        //   foydalanuvchining o'z kaliti TEGILMAYDI.
        publicKey = normalizeMasquePublicKey(server, publicKey)''' % MARK


def fz(s):
    """Bo'shliqqa chidamli regex."""
    return r"\s+".join(map(re.escape, s.split()))


def head(p, n=3):
    return "\n".join(io.open(p, encoding="utf-8").read().split("\n")[:n])


# ═══════════════════════════════════════════════════════════════════════════
# 1. BuiltinAwgConfigs — ma'lumotni to'g'rilash
# ═══════════════════════════════════════════════════════════════════════════

KEY_B = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI"
         "8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==")


def fix_builtin(root, dry):
    p = os.path.join(root, BUILTIN)
    if not os.path.exists(p):
        print("  ✘ %s topilmadi" % BUILTIN)
        return 1
    t = io.open(p, encoding="utf-8").read()
    orig = t
    changed = []

    # Har bir MASQUE_N blokini alohida ko'rib chiqamiz
    def repl(m):
        head_s, name, body, tail = m.group(1), m.group(2), m.group(3), m.group(4)
        sm = re.search(r"(?m)^(Server\s*=\s*)(\S+)", body)
        if not sm:
            return m.group(0)
        srv = sm.group(2)
        if srv not in GROUP_A:
            return m.group(0)
        new_body = body[:sm.start(2)] + FALLBACK + body[sm.end(2):]
        # Kalitni ham B ga o'tkazamiz
        pm = re.search(r"(?m)^(PublicKey\s*=\s*)(\S+)", new_body)
        if pm:
            new_body = new_body[:pm.start(2)] + KEY_B + new_body[pm.end(2):]
        changed.append((name, srv))
        return head_s + new_body + tail

    t = re.sub(r'(val (MASQUE_\d+): String = """)(.*?)(""")',
               repl, t, flags=re.S)

    if not changed:
        print("  ⏭  BuiltinAwgConfigs: A guruhi serveri yo'q (allaqachon toza)")
        return 0

    if dry:
        for n, s in changed:
            print("  • %s: %s -> %s" % (n, s, FALLBACK))
        return 0

    backup(p, root)
    io.open(p, "w", encoding="utf-8").write(t)
    for n, s in changed:
        print("  ✔ %s: %s -> %s" % (n, s, FALLBACK))
    return 0


# ═══════════════════════════════════════════════════════════════════════════
# 2. SingBoxConfig — runtime almashtirishni olib tashlash
# ═══════════════════════════════════════════════════════════════════════════

def fix_singbox(root, dry):
    p = os.path.join(root, SINGBOX)
    if not os.path.exists(p):
        print("  ✘ %s topilmadi" % SINGBOX)
        return 1
    t = io.open(p, encoding="utf-8").read()

    if MARK in t:
        print("  ⏭  SingBoxConfig: allaqachon qo'llangan (%s)" % MARK)
        return 0

    n = 0

    # a) UNUSABLE_SERVERS bloki
    m = re.search(r"    /\*\*\n     \* ⚠️ ISHLAMAYDIGAN serverlar.*?\n    \)\n\n",
                  t, re.S)
    if m:
        t = t[:m.start()] + t[m.end():]
        n += 1

    # b) FALLBACK_SERVER
    m = re.search(r'    /\*\* Ishlaydigan server.*?\n'
                  r'    private const val FALLBACK_SERVER = "[^"]+"\n\n', t, re.S)
    if m:
        t = t[:m.start()] + t[m.end():]
        n += 1

    # c) loop bloki
    t2, c = re.subn(fz(SB_LOOP_OLD), lambda _: SB_LOOP_NEW, t)
    if c == 1:
        t = t2
        n += 1
    else:
        print("  ✘ loop anchor topilmadi (n=%d)" % c)
        return 1

    if dry:
        print("  • SingBoxConfig: %d blok olib tashlanadi" % n)
        return 0

    backup(p, root)
    io.open(p, "w", encoding="utf-8").write(t)
    print("  ✔ SingBoxConfig: %d blok olib tashlandi" % n)
    return 0


# ═══════════════════════════════════════════════════════════════════════════

def backup(p, root):
    d = os.path.join(root, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    dst = os.path.join(d, "%s.groupA.%s.bak" % (
        os.path.basename(p), time.strftime("%Y%m%d_%H%M%S")))
    shutil.copy2(p, dst)
    print("  zaxira: %s" % os.path.relpath(dst, root))


# ═══════════════════════════════════════════════════════════════════════════
# check
# ═══════════════════════════════════════════════════════════════════════════

def check(root):
    print("  ══ CHECK: %s ══\n" % root)
    ok = True

    # 1. BuiltinAwgConfigs da A serveri bo'lmasin
    p = os.path.join(root, BUILTIN)
    t = io.open(p, encoding="utf-8").read()
    found = []
    for name, body in re.findall(r'val (MASQUE_\d+): String = """(.*?)"""', t, re.S):
        sm = re.search(r"(?m)^Server\s*=\s*(\S+)", body)
        if sm and sm.group(1) in GROUP_A:
            found.append((name, sm.group(1)))
    print("  ── BuiltinAwgConfigs: A guruhi serveri ──")
    if found:
        for n, s in found:
            print("    ❌ %s = %s" % (n, s))
        ok = False
    else:
        print("    ✔ 0 ta (hammasi GURUH B)")

    # 2. SingBoxConfig da runtime almashtirish bo'lmasin
    p = os.path.join(root, SINGBOX)
    t = io.open(p, encoding="utf-8").read()
    print("\n  ── SingBoxConfig: runtime server almashtirish ──")
    for name in ("UNUSABLE_SERVERS", "FALLBACK_SERVER"):
        g = name not in t
        ok = ok and g
        print("    %s %s" % ("✔" if g else "❌", name))

    # 3. Kalit juftligi mantig'i qolsin
    print("\n  ── SingBoxConfig: kalit juftligi (qolishi kerak) ──")
    g = "normalizeMasquePublicKey" in t
    ok = ok and g
    print("    %s normalizeMasquePublicKey" % ("✔" if g else "❌"))

    # 4. Foydalanuvchi kaliti himoyasi
    g = "MASQUE_PUB_A" in t and "MASQUE_PUB_B" in t
    ok = ok and g
    print("    %s foydalanuvchi kaliti himoyasi" % ("✔" if g else "❌"))

    # 5. h2 majburlash
    print("\n  ── h2 majburlash ──")
    g = "FIX_FORCE_H2_CLEAN" in t
    ok = ok and g
    print("    %s FIX_FORCE_H2_CLEAN" % ("✔" if g else "❌"))

    # 6. Server ↔ kalit mosligi
    print("\n  ── Server ↔ PublicKey mosligi ──")
    KB = "IaU7MToJ"
    bad = []
    builtin_t = io.open(os.path.join(root, BUILTIN), encoding="utf-8").read()
    for name, body in re.findall(r'val (MASQUE_\d+): String = """(.*?)"""',
                                 builtin_t, re.S):
        sm = re.search(r"(?m)^Server\s*=\s*(\S+)", body)
        qm = re.search(r"(?m)^PublicKey\s*=\s*(\S+)", body)
        if sm and qm and sm.group(1) not in GROUP_A and KB not in qm.group(1):
            bad.append(name)
    if bad:
        for n in bad:
            print("    ❌ %s kaliti B emas" % n)
        ok = False
    else:
        print("    ✔ hammasi GURUH B kaliti bilan")

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


# ═══════════════════════════════════════════════════════════════════════════
# self-test
# ═══════════════════════════════════════════════════════════════════════════

def self_test():
    print("  ══ SELF-TEST ══\n")
    ok = True
    tests = [
        ("GURUH A ro'yxati to'liq", len(GROUP_A) == 8),
        ("GURUH B ro'yxati to'liq", len(GROUP_B) == 4),
        ("A ∩ B = ∅", not (set(GROUP_A) & set(GROUP_B))),
        ("FALLBACK GURUH B da", FALLBACK in GROUP_B),
        ("SB_LOOP_OLD da UNUSABLE bor", "UNUSABLE_SERVERS" in SB_LOOP_OLD),
        ("SB_LOOP_OLD da FALLBACK bor", "FALLBACK_SERVER" in SB_LOOP_OLD),
        ("SB_LOOP_NEW da almashtirish YO'Q", "UNUSABLE_SERVERS" not in SB_LOOP_NEW
         and "FALLBACK_SERVER" not in SB_LOOP_NEW),
        ("SB_LOOP_NEW da normalize BOR", "normalizeMasquePublicKey" in SB_LOOP_NEW),
        ("SB_LOOP_NEW da MARK BOR", MARK in SB_LOOP_NEW),
        ("fz() bo'shliqqa chidamli", fz("a  b") == re.escape("a") + r"\s+" + re.escape("b")),
    ]
    for name, good in tests:
        ok = ok and good
        print("    %s %s" % ("✔" if good else "✘", name))

    # loop anchor regex HAQIQIY matnga mos kelishini tekshiramiz
    m = re.search(fz(SB_LOOP_OLD), SB_LOOP_OLD)
    m = m and re.search(fz(SB_LOOP_OLD), io.open(
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                     SINGBOX), encoding="utf-8").read())
    g = m is not None
    ok = ok and g
    print("    %s fz() anchor HAQIQIY kodga mos keladi" % ("✔" if g else "✘"))

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

    if "--check" in args:
        return check(root)

    print("  ══ GURUH A ni olib tashlash: %s ══\n" % root)
    if dry:
        print("  (dry-run — hech narsa yozilmaydi)\n")

    rc = fix_builtin(root, dry)
    if rc:
        return rc
    rc = fix_singbox(root, dry)
    if rc:
        return rc

    print("\n  Keyingi qadam:")
    print("    python3 tools/fix_group_a.py %s --check" % root)
    print("    ./gradlew assembleDebug test --offline")
    return 0


if __name__ == "__main__":
    sys.exit(main())
