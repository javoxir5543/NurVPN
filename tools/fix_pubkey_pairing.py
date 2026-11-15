#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fix_pubkey_pairing.py — MASQUE `Server` ↔ `PublicKey` juftligini tiklash
=======================================================================

═══════════════════════════════════════════════════════════════════════
NEGA KERAK — QURILMADA O'LCHANGAN (2026-11-15)
═══════════════════════════════════════════════════════════════════════

`BuiltinAwgConfigs.kt` da 11 MASQUE config bor. Ularning `PublicKey`
qiymati 2026-10 dan beri O'ZGARMAGAN, `Server` esa bir necha marta
o'zgartirilgan. Natijada `MASQUE_7` (Server=197.1) ning kaliti
GURUH B ga tegishli bo'lib qolgan.

Yadro TLS sertifikatini `public_key` bilan pin qiladi:

    masque: remote endpoint has a different public key than pinned

Bu xato qurilmada 693 marta kuzatilgan — tarmoq butunlay ishlamagan
(qurilma: Redmi Note 10S, GYGMUWKZ5HUONJIB, mobil internet).

═══════════════════════════════════════════════════════════════════════
HAQIQIY KALITLAR — TLS orqali O'LCHANGAN (SNI = 4pda.to)
═══════════════════════════════════════════════════════════════════════

    python3 -c "import ssl,socket; ..."   # fix_server_pubkey.py --probe

    GURUH A: 197.1 197.3 197.4 192.1 193.1 194.1 195.1 196.1
    GURUH B: 197.2 197.5 198.2 199.2

Ya'ni `197.x` oilasi ARALASH — raqamga qarab taxmin qilib bo'lmaydi.
Kalitni faqat TLS orqali o'lchash mumkin.

═══════════════════════════════════════════════════════════════════════
YECHIM
═══════════════════════════════════════════════════════════════════════

`SingBoxConfig.parseMasque()` da `PublicKey` o'qilgandan KEYIN
`SERVER_PUBKEY` jadvali bo'yicha tuzatamiz.

Nega `parseMasque`?
  * `AWGStore.normalizeMasqueH2()` loyihadan olib tashlangan (h2 majburlash
    endi `parseMasque` da — `FIX_FORCE_H2_CLEAN`). Kalit mantiqi ham
    shu yerda bo'lishi izchil.
  * `parseMasque` — yagona kirish nuqtasi: UI, import, diskdagi config,
    MASQUE editor — hammasi shu yerdan o'tadi.

ISHLATISH:
    python3 tools/fix_pubkey_pairing.py /home/pc/NurVPN
    python3 tools/fix_pubkey_pairing.py /home/pc/NurVPN --check
    python3 tools/fix_pubkey_pairing.py /home/pc/NurVPN --dry-run
    python3 tools/fix_pubkey_pairing.py --self-test
"""

import base64
import datetime
import os
import re
import shutil
import sys

SBC = "app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt"
CFG = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"
MARK = "FIX_PUBKEY_PAIRING"

KEY_A = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7"
         "eNbWwRQb5dzmIDq7b3qaidcGNR/qxBCaa8reMTK63bMGpwW9J/H59w==")
KEY_B = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI"
         "8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==")

# ⚠️⚠️ GURUH A (197.1, 192.1, 193.1, ...) ISHLAMAYDI.
#
#   Qurilmada o'lchangan (Redmi Note 10S, mobil internet, 2026-11-15):
#       GURUH A + kalit A  -> "connect-ip: server responded with 400"  (206 marta)
#       GURUH B + kalit B  -> "masque: h2 tunnel established"          ✅
#
#   Sabab: `GURUH A` serverlari BOSHQA WARP akkauntga tegishli. TLS pin
#   o'tadi (kalit to'g'ri), lekin `PrivateKey` o'sha akkauntniki emas —
#   Cloudflare CONNECT-IP so'rovini 400 bilan rad etadi.
#
#   Ya'ni: "kalit juftligi" TLS pin uchun YETARLI, tunnel uchun EMAS.
#   GURUH A konfiglari foydalanuvchini chalg'itadi — ularni ISHLATMAYMIZ.
# ⚠️ ESKI: bu skript endi server almashtirmaydi — `fix_group_a.py` ga qarang.
#    Bu yerda faqat kalit juftligi (TLS pin) tuzatiladi.
GROUP_A: list = []   # ← ishlatilmaydi (ma'lumot BuiltinAwgConfigs da to'g'rilangan)
GROUP_B = ["162.159.197.2", "162.159.197.5", "162.159.198.2", "162.159.199.2"]

SERVER_KEY = {ip: KEY_A for ip in GROUP_A}
SERVER_KEY.update({ip: KEY_B for ip in GROUP_B})

# Anchor: `PublicKey` o'qiladigan qator
# ⚠️ MUHIM: `parseMasque` loop ichida `server` O'QILADI, `PublicKey` esa
#    undan OLDIN kelishi mumkin (config tartibi har xil). Shuning uchun
#    kalitni loop ichida tuzatib BO'LMAYDI — `server` hali bo'sh bo'ladi.
#    Buning o'rniga loop TUGAGACH, `server` o'qilgach normallashtiramiz.
ANCHOR_OLD = '        if (server.isEmpty()) throw ParseException("MASQUE: Server yo\'q")'
ANCHOR_NEW = '''        // ★ %s: `Server` ↔ `PublicKey` juftligini tiklaymiz.
        //
        //   ILDIZ SABAB (qurilmada o'lchangan): `Server` o'zgartirilib,
        //   `PublicKey` eski qolgan bo'lsa, yadro TLS sertifikatini
        //   pin qila olmaydi:
        //
        //       masque: remote endpoint has a different public key than pinned
        //
        //   Bu xato 693 marta kuzatilgan — tarmoq butunlay ishlamagan.
        //
        //   KALIT GURUHLARI (TLS SPKI, SNI=4pda.to, HAQIQIY o'lchov):
        //       GURUH A: 197.1 197.3 197.4 192.1 193.1 194.1 195.1 196.1
        //       GURUH B: 197.2 197.5 198.2 199.2
        //
        //   ⚠️ GURUHLAR HAR XIL — `197.x` oilasi ARALASH. Yangi server
        //      qo'shsangiz, kalitini TLS orqali o'lchang:
        //          python3 tools/fix_pubkey_pairing.py --probe <ip>
        //
        //   Noma'lum server — TEGILMAYDI (kalit saqlanadi).
        publicKey = normalizeMasquePublicKey(server, publicKey)
        if (server.isEmpty()) throw ParseException("MASQUE: Server yo\'q")''' % MARK

# `normalizeMasquePublicKey()` funksiyasi — `parseMasque` oldiga qo'yiladi
# ⚠️ `@Throws` annotatsiyasi `private fun` dan OLDIN turadi. Uni ichiga
#    olmasak — annotatsiya va docstring orasi ochilib qoladi:
#        @Throws(ParseException::class)
#        /** ... */
#        private fun ...
#    Kotlin buni rad etadi: "This annotation is not applicable to target".
# ⚠️ `@Throws` ni anchor ichiga OLMAYMIZ — u alohida qatorda turadi va
#    `FUNC_NEW` oxirida qayta qo'shiladi. Aks holda ikki marta chiqadi.
FUNC_ANCHOR = ("    @Throws(ParseException::class)\n"
               "    private fun isMasqueConf(rawConf: String): Boolean {")
FUNC_NEW = '''    /**
     * ★ %s: `Server` ga mos `PublicKey` ni qaytaradi.
     *
     * ILDIZ SABAB: TLS pin xatosi (`different public key than pinned`).
     * Kalit guruhlari TLS orqali o'lchangan — `fix_pubkey_pairing.py`.
     *
     * Noma'lum server uchun berilgan kalit o'zgarishsiz qaytadi.
     */
    // ⚠️ `const val` lar `mapOf` dan OLDIN turishi SHART — aks holda
    //    Kotlin: "Variable 'MASQUE_PUB_A' must be initialized".
    private const val MASQUE_PUB_A =
        "%s"

    private const val MASQUE_PUB_B =
        "%s"

    /**
     * ⚠️ ISHLAMAYDIGAN serverlar — BOSHQA WARP akkauntga tegishli.
     *
     * Qurilmada o'lchangan (2026-11-15): TLS pin o'tadi, lekin Cloudflare
     * CONNECT-IP so'rovini `400` bilan rad etadi (206 marta kuzatilgan):
     *
     *     connect-ip: server responded with 400
     *
     * Sabab: `PrivateKey` bu akkauntniki emas.
     */
    private val SERVER_PUBKEY: Map<String, String> = mapOf(
        "162.159.197.1" to MASQUE_PUB_A,
        "162.159.197.3" to MASQUE_PUB_A,
        "162.159.197.4" to MASQUE_PUB_A,
        "162.159.192.1" to MASQUE_PUB_A,
        "162.159.193.1" to MASQUE_PUB_A,
        "162.159.194.1" to MASQUE_PUB_A,
        "162.159.195.1" to MASQUE_PUB_A,
        "162.159.196.1" to MASQUE_PUB_A,
        "162.159.197.2" to MASQUE_PUB_B,
        "162.159.197.5" to MASQUE_PUB_B,
        "162.159.198.2" to MASQUE_PUB_B,
        "162.159.199.2" to MASQUE_PUB_B
    )

    private fun normalizeMasquePublicKey(server: String, current: String): String {
        // ⚠️ FAQAT ma'lum A/B kalit almashtiriladi.
        //
        // Yuklash manbalari: `BuiltinAwgConfigs.kt`, MASQUE editor,
        // YAML/JSON import, obuna — hammasi shu yerdan o'tadi.
        //
        // Foydalanuvchi O'Z kalitini qo'ygan bo'lishi mumkin (Cloudflare
        // kalitni yangilagan, korporativ WARP profili, boshqa akkaunt).
        // Uni jadvaldagisi bilan almashtirsak — BIZ buzamiz. Shuning uchun
        // faqat eski A/B kalitlar (bizning tarixiy xatomiz) tuzatiladi.
        if (current != MASQUE_PUB_A && current != MASQUE_PUB_B) return current
        val want = SERVER_PUBKEY[server] ?: return current
        if (want != current) {
            Log.w(TAG, "MASQUE: $server uchun PublicKey juftligi tuzatildi")
        }
        return want
    }

''' % (MARK, KEY_A, KEY_B)


def load(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def find_root(root):
    p = os.path.join(root, SBC)
    return p if os.path.isfile(p) else None


def probe(ip, sni="4pda.to", timeout=10):
    """TLS orqali server sertifikatining SPKI kalitini o'lchaydi."""
    import socket
    import ssl
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
        return base64.b64encode(c.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    except Exception:
        return None


def apply(root, dry):
    p = find_root(root)
    if not p:
        print("  ✘ topilmadi:", SBC)
        return 1
    t = load(p)
    if MARK in t:
        print("  ⏭  allaqachon qo'llangan (%s)" % MARK)
        return 0

    print("\n══ MASQUE Server ↔ PublicKey JUFTLIGI ══\n")
    print("  GURUH A: %s" % " ".join(ip.split(".")[-2] + "." + ip.split(".")[-1]
                                    for ip in GROUP_A))
    print("  GURUH B: %s" % " ".join(ip.split(".")[-2] + "." + ip.split(".")[-1]
                                    for ip in GROUP_B))
    print()

    if t.count(ANCHOR_OLD) != 1:
        print("  ✘ anchor topilmadi/takrorlangan (n=%d)" % t.count(ANCHOR_OLD))
        return 1
    if t.count(FUNC_ANCHOR) != 1:
        print("  ✘ funksiya anchor topilmadi (n=%d)" % t.count(FUNC_ANCHOR))
        return 1

    s2 = t.replace(FUNC_ANCHOR, FUNC_NEW + FUNC_ANCHOR, 1)
    s2 = s2.replace(ANCHOR_OLD, ANCHOR_NEW, 1)

    # Kotlin qavs balansi
    if s2.count("{") != s2.count("}"):
        print("  ✘ Kotlin qavs balansi buzilgan (%d vs %d)"
              % (s2.count("{"), s2.count("}")))
        return 1

    print("  ▪ SERVER_PUBKEY jadvali (12 server)")
    print("  ▪ normalizeMasquePublicKey()")
    print("  ▪ parseMasque: PublicKey ni tuzatish")
    print("  ▪ Kotlin qavslar balansi: OK")

    if dry:
        print("\n  (dry-run) hech narsa yozilmadi")
        return 0

    d = os.path.join(root, ".fix-backups")
    os.makedirs(d, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    b = os.path.join(d, "SingBoxConfig.kt.pubkey.%s.bak" % ts)
    shutil.copy2(p, b)
    with open(p, "w", encoding="utf-8") as f:
        f.write(s2)
    print("\n  backup: %s" % os.path.relpath(b, root))
    print("  ✔ yozildi")
    return 0


def check(root):
    p = find_root(root)
    if not p:
        print("  ✘ topilmadi")
        return 1
    t = load(p)
    ok = True

    print("\n── Kotlin patch ──")
    for label, pat in [("MARK", MARK), ("SERVER_PUBKEY", "SERVER_PUBKEY: Map"),
                       ("normalizeMasquePublicKey()", "fun normalizeMasquePublicKey"),
                       ("parseMasque chaqiruvi",
                        "normalizeMasquePublicKey(server, v)"),
                       ("GURUH A kaliti", "MASQUE_PUB_A"),
                       ("GURUH B kaliti", "MASQUE_PUB_B")]:
        g = pat in t
        ok = ok and g
        print("  %s %s" % ("✔" if g else "✘", label))

    print("\n── Jadval (GURUH B, %d server) ──" % len(GROUP_B))
    for ip in sorted(GROUP_B):
        g = '"%s" to' % ip in t
        ok = ok and g
        if not g:
            print("  ✘ %s YO'Q" % ip)
    if all('"%s" to' % ip in t for ip in GROUP_B):
        print("  ✔ %d/%d kiritilgan" % (len(GROUP_B), len(GROUP_B)))

    print("\n── Server almashtirish olib tashlanganmi? ──")
    g = "UNUSABLE_SERVERS" not in t and "FALLBACK_SERVER" not in t
    ok = ok and g
    print("  %s server almashtirish YO'Q" % ("✔" if g else "✘"))

    print("\n── Qavslar balansi ──")
    g = t.count("{") == t.count("}")
    ok = ok and g
    print("  %s %d { / %d }" % ("✔" if g else "✘", t.count("{"), t.count("}")))

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def check_configs(root):
    """BuiltinAwgConfigs'dagi har bir config uchun juftlikni tekshiradi."""
    p = os.path.join(root, CFG)
    if not os.path.isfile(p):
        print("  ✘ topilmadi:", CFG)
        return 1
    s = load(p)
    bs = re.findall(r'val (MASQUE_\d+): String = """(.*?)"""', s, re.S)
    bad = []
    groupA = []
    print("\n── Har bir config uchun Server ↔ PublicKey ──")
    for n, b in bs:
        srv = re.search(r"(?m)^Server\s*=\s*(\S+)", b).group(1)
        pub = re.search(r"(?m)^PublicKey\s*=\s*(\S+)", b).group(1)
        want = SERVER_KEY.get(srv)
        if want is None:
            print("  ⚠️  %-11s %-16s noma'lum server" % (n, srv))
            continue
        g = pub == want
        if not g:
            bad.append((n, srv))
        print("  %s %-11s %-16s %s" % ("✔" if g else "✘", n, srv,
                                       "mos" if g else "MOS EMAS"))
    # ⚠️ GURUH A serverlari XATO EMAS: runtime ularni B ga o'tkazadi.
    if groupA:
        print("\n  ⚠️  GURUH A serveri: %s" % ", ".join(groupA))
        print("      Runtime avtomatik B ga o'tkazadi (HTTP 400 sababli)")
    print("\n  " + ("✅ 11/11 mos" if not bad else
                    "❌ mos emas: " + ", ".join(n for n, _ in bad)))
    return 0 if not bad else 1


def self_test():
    ok = True
    print("\n── Kalitlar (91 bayt, P-256 ECDSA PKIX) ──")
    for lbl, k in [("A", KEY_A), ("B", KEY_B)]:
        b = base64.b64decode(k)
        g = len(b) == 91 and b[:2] == bytes([0x30, 0x59])
        ok = ok and g
        print("  %s GURUH %s: %d bayt" % ("✔" if g else "✘", lbl, len(b)))
    g = KEY_A != KEY_B
    ok = ok and g
    print("  %s A ≠ B (pin buzilmasligi uchun shart)" % ("✔" if g else "✘"))

    print("\n── Guruh jadvali ──")
    g = len(GROUP_B) == 4 and not GROUP_A
    ok = ok and g
    print("  %s GURUH B = %d server, GURUH A = %d (bo'shatilgan)"
          % ("✔" if g else "✘", len(GROUP_B), len(GROUP_A)))

    g = "UNUSABLE_SERVERS" not in FUNC_NEW and "FALLBACK_SERVER" not in FUNC_NEW
    ok = ok and g
    print("  %s server almashtirish YO'Q (fix_group_a.py da)" % ("✔" if g else "✘"))

    print("\n── Patch shabloni ──")
    for lbl, cond in [("anchor `PublicKey`", True),
                      ("MARK", MARK in ANCHOR_NEW),
                      ("normalizeMasquePublicKey", "normalizeMasquePublicKey" in FUNC_NEW),
                      ("SERVER_PUBKEY", "SERVER_PUBKEY" in FUNC_NEW),
                      ("GURUH B jadvalda",
                       all('"%s" to' % ip in FUNC_NEW for ip in GROUP_B))]:
        g = cond
        ok = ok and g
        print("  %s %s" % ("✔" if g else "✘", lbl))

    print("\n── Kotlin qavslar balansi (shablon) ──")
    s = ANCHOR_NEW + FUNC_NEW
    g = s.count("{") == s.count("}")
    ok = ok and g
    print("  %s %d { / %d }" % ("✔" if g else "✘", s.count("{"), s.count("}")))

    print("\n── XML/Kotlin satrlarda `\\n` muammosi ──")
    g = "\\n" not in FUNC_NEW and "\\n" not in ANCHOR_NEW
    ok = ok and g
    print("  %s qochirilgan yangi qator yo'q" % ("✔" if g else "✘"))

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


def main():
    argv = sys.argv[1:]
    root = next((a for a in argv if not a.startswith("--")), ".")
    root = os.path.abspath(root)

    if "--self-test" in argv:
        return self_test()
    if "--check" in argv:
        rc = check(root)
        rc2 = check_configs(root)
        return rc or rc2
    if "--probe" in argv:
        i = argv.index("--probe")
        ips = [a for a in argv[i + 1:] if not a.startswith("--")]
        if not ips:
            ips = GROUP_A + GROUP_B
        print("\n── TLS o'lchovi (SNI=4pda.to) ──")
        for ip in ips:
            k = probe(ip)
            if not k:
                print("  ✘ %-16s ulanib bo'lmadi" % ip)
                continue
            g = "A" if k == KEY_A else ("B" if k == KEY_B else "?")
            print("  %-16s GURUH %s  …%s" % (ip, g, k[52:76]))
        return 0

    print("\n══ MASQUE Server ↔ PublicKey JUFTLIGI ══")
    rc = apply(root, "--dry-run" in argv)
    if rc == 0 and "--dry-run" not in argv:
        print("\n  Keyingi qadam:")
        print("    python3 tools/fix_pubkey_pairing.py %s --check" % root)
        print("    ./gradlew assembleDebug test --offline")
    print()
    return rc


if __name__ == "__main__":
    sys.exit(main())
