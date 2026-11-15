#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NurVPN — universal IPv6 himoyasi (FIX_AAAA_BLOCK).

═══════════════════════════════════════════════════════════════════════════
MUAMMO
═══════════════════════════════════════════════════════════════════════════
Har xil YAML fayllar har xil bo'ladi:
    ClashWARP_18.yaml   ipv6: 2606:4700:110:8e35:...   ← IPv6 BOR
    PORTALWARP63.yaml   ipv6: false                    ← IPv6 YO'Q
    PORTALWARP19.yaml   ipv6: false                    ← IPv6 YO'Q

IPv6 yo'q config bilan MASQUE tunnel OCHILADI, lekin ilovalar AAAA
so'rab, mavjud bo'lmagan IPv6 manzilga ulanib OSILIB qoladi:

    outbound/masque[proxy]: missing IPv6 local address       (15 marta)
    open connection to [2a03:2880:...]:443: missing IPv6     ← Telegram/FB
    context deadline exceeded                                (23 marta)

Ya'ni "tunnel ishlayapti, lekin internet yo'q".

═══════════════════════════════════════════════════════════════════════════
YECHIM
═══════════════════════════════════════════════════════════════════════════
`tunV6=false` bo'lganda DNS'da AAAA so'rovlarini bo'sh javob bilan
qaytarish (`rcode: NOERROR`, manzilsiz). Ilova "IPv6 yo'q" deb tushunadi
va IPv4 ga o'tadi.

Natija: HAR QANDAY YAML ishlaydi — IPv6 bor-yo'qligidan qat'i nazar.

Ishlatish:
    python3 tools/fix_ipv6_fallback.py --check
    python3 tools/fix_ipv6_fallback.py --dry-run
    python3 tools/fix_ipv6_fallback.py
    python3 tools/fix_ipv6_fallback.py --self-test
"""

import io
import os
import re
import shutil
import sys
import time

CFG = "app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt"
BACKUP_DIR = ".fix-backups"

ANCHOR = '''        val dns = JSONObject()
            .put("servers", servers)
            .put("final", "dns-remote")
            .put("strategy", if (ipv6Blocked || !tunV6) "ipv4_only" else "prefer_ipv4")
        if (dnsRules.length() > 0) dns.put("rules", dnsRules)
        root.put("dns", dns)'''

NEW = '''        // ═══ UNIVERSAL IPv6 HIMOYASI (FIX_AAAA_BLOCK) ═══
        //  TUN'da IPv6 manzil YO'Q bo'lsa (`tunV6=false`), ilovalar AAAA
        //  so'rab, mavjud bo'lmagan IPv6 manzilga ulanib OSILIB qoladi:
        //      "outbound/masque[proxy]: missing IPv6 local address"
        //      "open connection to [2a03:2880:...]:443 ... missing IPv6"
        //  Har qanday YAML uchun ishlaydi (`ipv6` bor yoki yo'q — farqi yo'q).
        //  IPv6 KERAK bo'lsa config'ga `Address = 172.16.0.2, 2606:...` yozing.
        if (!tunV6) {
            dnsRules.put(JSONObject()
                .put("query_type", JSONArray().put("AAAA"))
                .put("action", "predefined")
                .put("rcode", "NOERROR"))
        }
        val dns = JSONObject()
            .put("servers", servers)
            .put("final", "dns-remote")
            .put("strategy", if (ipv6Blocked || !tunV6) "ipv4_only" else "prefer_ipv4")
        if (dnsRules.length() > 0) dns.put("rules", dnsRules)
        root.put("dns", dns)'''


def backup(p, root):
    d = os.path.join(root, BACKUP_DIR)
    os.makedirs(d, exist_ok=True)
    dst = os.path.join(d, "%s.ipv6.%s.bak" %
                       (os.path.basename(p), time.strftime("%Y%m%d_%H%M%S")))
    shutil.copy2(p, dst)
    return os.path.relpath(dst, root)


def apply(root, dry):
    p = os.path.join(root, CFG)
    if not os.path.exists(p):
        print("  ✘ %s topilmadi" % CFG)
        return 1
    t = io.open(p, encoding="utf-8").read()

    if "FIX_AAAA_BLOCK" in t:
        print("  ⏭  allaqachon qo'llangan")
        return 0
    if ANCHOR not in t:
        print("  ✘ anchor topilmadi (SingBoxConfig.kt o'zgargan?)")
        return 1
    if dry:
        print("  • FIX_AAAA_BLOCK qo'shiladi")
        return 0

    t = t.replace(ANCHOR, NEW, 1)
    bk = backup(p, root)
    io.open(p, "w", encoding="utf-8").write(t)
    print("  ✔ FIX_AAAA_BLOCK qo'shildi")
    print("  zaxira: %s" % bk)
    return 0


def check(root):
    print("  ══ CHECK ══\n")
    ok = True
    p = os.path.join(root, CFG)
    t = io.open(p, encoding="utf-8").read()

    tests = [
        ("FIX_AAAA_BLOCK izohi", "FIX_AAAA_BLOCK" in t),
        ("AAAA query_type", '"AAAA"' in t),
        ("predefined action", '"predefined"' in t),
        ("rcode NOERROR", '"NOERROR"' in t),
        ("tunV6 sharti", "if (!tunV6)" in t),
        ("ipv4_only strategiya", '"ipv4_only"' in t),
    ]
    for n, g in tests:
        ok = ok and g
        print("    %s %s" % ("✔" if g else "❌", n))

    # Muvozanat: `dns` obyekti bir marta
    n_dns = len(re.findall(r'val dns = JSONObject\(\)', t))
    g = n_dns == 1
    ok = ok and g
    print("    %s val dns bir marta (%d)" % ("✔" if g else "❌", n_dns))

    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def self_test():
    print("  ══ SELF-TEST ══\n")
    ok = True

    t = [("ANCHOR bor", len(ANCHOR) > 100),
         ("NEW da FIX_AAAA_BLOCK", "FIX_AAAA_BLOCK" in NEW),
         ("NEW da AAAA", '"AAAA"' in NEW),
         ("NEW da rcode", '"NOERROR"' in NEW),
         ("NEW da tunV6 sharti", "if (!tunV6)" in NEW),
         ("NEW da val dns bir marta",
          NEW.count("val dns = JSONObject()") == 1)]
    for n, g in t:
        ok = ok and g
        print("    %s %s" % ("✔" if g else "✘", n))

    # Idempotentlik: qo'llangandan keyin ANCHOR FAQAT BIR MARTA uchraydi
    # (NEW uning o'rnini bosadi, dublikat yaratmaydi).
    g = NEW.count(ANCHOR) == 1 and "FIX_AAAA_BLOCK" in NEW
    ok = ok and g
    print("    %s idempotent (ANCHOR 1 marta)" % ("✔" if g else "✘"))

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


def main():
    args = sys.argv[1:]
    if "--self-test" in args:
        return self_test()
    root = next((a for a in args if not a.startswith("-")), ".")
    root = os.path.abspath(root)
    if "--check" in args:
        return check(root)
    dry = "--dry-run" in args
    print("  ══ Universal IPv6 himoyasi (FIX_AAAA_BLOCK) ══\n")
    rc = apply(root, dry)
    if rc:
        return rc
    print("\n  Keyingi qadam:\n    ./gradlew assembleDebug --offline")
    return 0


if __name__ == "__main__":
    sys.exit(main())
