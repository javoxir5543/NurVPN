#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NurVPN — YAML fayllardan "Ochiq manbalar" obunalarini qo'shish.

═══════════════════════════════════════════════════════════════════════════
NIMA QILADI
═══════════════════════════════════════════════════════════════════════════
Telefonda yuklab olingan Clash/Mihomo YAML fayllaridan MASQUE va
AmneziaWG proxylarni o'qib, ilovaning "Ochiq manbalar" bo'limiga
obuna sifatida qo'shadi.

Har fayldan 1 ta proxy olinadi (ishlasa bo'ldi):

    ClashWARP_18.yaml   -> masque_6   (MASQUE 6)
    PORTALWARP63.yaml   -> masque_7   (MASQUE 7)
    PORTALWARP19.yaml   -> masque_8   (MASQUE 8)
    ClashWARP_22.yaml   -> amnezia_4  (AmneziaWG 4)

Nima o'zgaradi:
  1. `BuiltinAwgConfigs.kt`      — yangi config + ALL ro'yxatiga qo'shiladi
  2. `OpenSourceSubscriptions.kt` — yangi obuna katalogga qo'shiladi
  3. `strings.xml` (uz/ru/en)    — obuna nomi va izohi

═══════════════════════════════════════════════════════════════════════════
MISOL
═══════════════════════════════════════════════════════════════════════════
    python3 tools/add_yaml_sources.py --analyze /tmp/*.yaml
    python3 tools/add_yaml_sources.py --dry-run
    python3 tools/add_yaml_sources.py
    python3 tools/add_yaml_sources.py --check
    python3 tools/add_yaml_sources.py --self-test
"""

import io
import os
import re
import shutil
import sys
import time

BUILTIN = "app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt"
CATALOG = "app/src/main/java/com/nurvpn/app/storage/OpenSourceSubscriptions.kt"
STRINGS = {
    "": "app/src/main/res/values/strings.xml",
    "uz": "app/src/main/res/values-uz/strings.xml",
    "ru": "app/src/main/res/values-ru/strings.xml",
    "en": "app/src/main/res/values-en/strings.xml",
}
BACKUP_DIR = ".fix-backups"

# ═══════════════════════════════════════════════════════════════════════════
# QO'SHILADIGAN OBUNALAR
# ═══════════════════════════════════════════════════════════════════════════
# (id, yaml, profil indeksi, tur, ko'rsatiladigan nom, izoh)
SPEC = [
    ("masque_6", "/tmp/ClashWARP_18.yaml", 1, "masque", "MASQUE 6", "ClashWARP 18"),
    ("masque_7", "/tmp/PORTALWARP63.yaml", 0, "masque", "MASQUE 7", "KiberPortal 63"),
    ("masque_8", "/tmp/PORTALWARP19.yaml", 0, "masque", "MASQUE 8", "KiberPortal 19"),
    ("amnezia_4", "/tmp/ClashWARP_22.yaml", 0, "awg", "AmneziaWG 4", "ClashWARP 22 #1"),
    ("amnezia_5", "/tmp/ClashWARP_22.yaml", 1, "awg", "AmneziaWG 5", "ClashWARP 22 #2"),
    ("amnezia_6", "/tmp/ClashWARP_22.yaml", 2, "awg", "AmneziaWG 6", "ClashWARP 22 #3"),
]

AMNEZIA_DEFAULTS = {
    "jc": "4", "jmin": "40", "jmax": "70", "version": "3",
    "content-padding-addition": "46-77", "rekey-after-time": "80-146",
    "rekey-timeout": "3-14", "reject-after-time": "97-147",
    "keepalive-timeout": "5-22", "max-handshake-attempts": "17-31",
}


# ═══════════════════════════════════════════════════════════════════════════
# YAML o'qish
# ═══════════════════════════════════════════════════════════════════════════

def yaml_scalars(s):
    """`key: value` juftliklarini oladi (bir qatorlik)."""
    out = {}
    for m in re.finditer(r"^[ \t]*([a-z][a-z0-9-]*):[ \t]*(.+?)[ \t]*$", s, re.M):
        out[m.group(1)] = m.group(2).strip()
    return out


def read_yaml(path):
    """YAML faylni o'qib, (anchor, proxylar) qaytaradi."""
    if not os.path.exists(path):
        return None, []
    t = io.open(path, encoding="utf-8", errors="replace").read()

    # Anchor (masalan `msq: &msq` yoki `warp-common: &warp-common`)
    anchor = {}
    m = re.search(r"^([A-Za-z][\w-]*):[ \t]*&([\w-]+)[ \t]*\n(.*?)(?=\n[A-Za-z]|\Z)",
                  t, re.M | re.S)
    if m:
        anchor = yaml_scalars(m.group(3))
        anchor["_type"] = anchor.get("type", "?")

    # Proxylar: `- name:` dan boshlanadi
    proxies = []
    for pm in re.finditer(r"^[ \t]*-[ \t]*name:[ \t]*(.+?)[ \t]*$", t, re.M):
        start = pm.end()
        nxt = re.search(r"^[ \t]*-[ \t]*name:|^[a-z][\w-]*:", t[start:], re.M)
        body = t[start:start + (nxt.start() if nxt else len(t) - start)]

        # amnezia-wg-option ichidagi kalitlar ham olinadi
        vals = yaml_scalars(body)
        awg = {}
        # ⚠️ `i1` qiymati juda uzun (`<b 0x...`) va ichida `,`/`:` bor —
        #    oddiy scalar regex uni kesib qo'yadi. Alohida olamiz.
        am = re.search(r"amnezia-wg-option:\s*\n((?:[ \t]+[a-z0-9-]+:.*\n?)+)",
                       body)
        if am:
            blk = am.group(1)
            for km in re.finditer(r"^[ \t]+([a-z][a-z0-9-]*):[ \t]*(.+?)[ \t]*$",
                                  blk, re.M):
                awg[km.group(1)] = km.group(2).strip()

        name = pm.group(1).strip().strip('"').strip("'")
        # ⚠️ `proxy-groups` yozuvlari (`WARP`, `type: select`) da `server`
        #    bo'lmaydi — ular config emas, guruh. Filtrlaymiz.
        if not vals.get("server"):
            continue
        proxies.append({
            "name": name, "vals": vals, "awg": awg,
            "server": vals.get("server", ""),
            "port": vals.get("port", ""),
            "sni": vals.get("sni", ""),
            "network": vals.get("network", ""),
        })
    return anchor, proxies


def build_masque(cfg, anchor, px, sni_fallback="4pda.to"):
    """MASQUE configni ilova formatида yasaydi."""
    priv = anchor.get("private-key", "")
    pub = anchor.get("public-key", "")
    ip = anchor.get("ip", "172.16.0.2").split("/")[0]
    ipv6 = anchor.get("ipv6", "").split("/")[0]
    mtu = anchor.get("mtu", "1280")
    srv = px["server"]
    port = px["port"] or "443"
    sni = px["sni"] or sni_fallback
    net = px["network"] or "h2"
    return "\n".join([
        "PrivateKey = %s" % priv,
        "PublicKey = %s" % pub,
        # ⚠️ IPv4 va IPv6 BIRGA: core IPv6 manzilni ham kutadi
        #    (`missing IPv6 local address` xatosini oldini oladi).
        "Address = %s" % (("%s, %s" % (ip, ipv6)) if ipv6 else ip),
        "MTU = %s" % mtu,
        "Server = %s" % srv,
        "Port = %s" % port,
        "SNI = %s" % sni,
        "Network = %s" % net,
        "Profile = cloudflare",
    ])


def build_awg(anchor, px):
    """AmneziaWG configni ilova formatида yasaydi."""
    priv = anchor.get("private-key", "")
    pub = anchor.get("public-key", "")
    ip = anchor.get("ip", "172.16.0.2").split("/")[0]
    ipv6 = anchor.get("ipv6", "").split("/")[0]
    mtu = anchor.get("mtu", "1280")
    srv = px["server"]
    port = px["port"] or "4500"
    dns = anchor.get("dns", "[1.1.1.1, 1.0.0.1]")
    dns = dns.strip("[]").replace(" ", "")

    awg = dict(AMNEZIA_DEFAULTS)
    awg.update(px["awg"])

    lines = [
        "[Interface]",
        "PrivateKey = %s" % priv,
        # ⚠️ IPv4 va IPv6 BIRGA (vergul bilan): core IPv6 manzilni kutadi
        #    (`missing IPv6 local address` xatosini oldini oladi).
        "Address = %s" % (("%s/32, %s/128" % (ip, ipv6)) if ipv6 else "%s/32" % ip),
        "DNS = %s" % dns,
        "MTU = %s" % mtu,
        "Jc = %s" % awg.get("jc", "4"),
        "Jmin = %s" % awg.get("jmin", "40"),
        "Jmax = %s" % awg.get("jmax", "70"),
        "S1 = 0",
        "S2 = 0",
        "H1 = 1",
        "H2 = 2",
        "H3 = 3",
        "H4 = 4",
        "",
        "[Peer]",
        "PublicKey = %s" % pub,
        "AllowedIPs = 0.0.0.0/0, ::/0",
        "Endpoint = %s:%s" % (srv, port),
        "PersistentKeepalive = 25",
    ]
    # ★ AmneziaWG niqoblash paketlari (FIX): `i1`/`i2` — SIP/QUIC fake paket.
    #   Busiz DPI handshake'ni taniydi va ulanish qotib qoladi.
    for key in ("i1", "i2", "i3", "i4", "i5"):
        val = awg.get(key, "")
        if val:
            lines.append("%s = %s" % (key.upper(), val))
    return "\n".join(lines)


# ═══════════════════════════════════════════════════════════════════════════
# analyze
# ═══════════════════════════════════════════════════════════════════════════

def analyze(paths):
    print("  ══ YAML TAHLILI ══\n")
    for p in paths:
        anchor, proxies = read_yaml(p)
        print("  %s" % os.path.basename(p))
        if anchor is None:
            print("    ✘ topilmadi\n")
            continue
        print("    tur: %s" % anchor.get("_type"))
        print("    akkaunt: …%s" % anchor.get("private-key", "")[-14:])
        print("    proxylar: %d" % len(proxies))
        for i, px in enumerate(proxies):
            print("      %d. %-18s %-18s %-6s sni=%-16s %s" % (
                i, px["name"][:18], px["server"], px["port"], px["sni"] or "-",
                px["network"] or ""))
        print()
    return 0


# ═══════════════════════════════════════════════════════════════════════════
# Kod yozish
# ═══════════════════════════════════════════════════════════════════════════

def backup(p):
    os.makedirs(BACKUP_DIR, exist_ok=True)
    dst = os.path.join(BACKUP_DIR, "%s.yamlsrc.%s.bak" %
                       (os.path.basename(p), time.strftime("%Y%m%d_%H%M%S")))
    shutil.copy2(p, dst)
    return dst


def builtin_add(root, entries, dry):
    """BuiltinAwgConfigs.kt ga yangi configlar qo'shadi."""
    p = os.path.join(root, BUILTIN)
    t = io.open(p, encoding="utf-8").read()

    todo = [(n, raw) for n, raw in entries
            if not re.search(r'val %s: String = """' % re.escape(n.upper()), t)]
    if not todo:
        print("  ⏭  barcha configlar kodda bor")
        return 0

    if dry:
        for n, _ in todo:
            print("  • builtin: %s qo'shiladi" % n)
        return 0

    # ALL ro'yxatini topamiz
    allm = re.search(r"(val ALL[^\n]*= listOf\(\n)(.*?)(\n[ \t]*\))", t, re.S)
    if not allm:
        print("  ✘ ALL ro'yxati topilmadi")
        return 1

    # 1) Ta'riflarni oxirgi val dan keyin qo'shamiz
    last = None
    for m in re.finditer(r'^[ \t]*val (MASQUE_\d+|AMNEZIA_\d+): String = """',
                         t, re.M):
        last = m
    if not last:
        print("  ✘ hech qanday config topilmadi")
        return 1

    endm = re.search(r'"""\n', t[last.end():])
    ins = last.end() + endm.end()

    defs = []
    for n, raw in todo:
        defs.append('    /** %s — YAML import. */\n' % n)
        valn = n.upper()
        defs.append('    val %s: String = """%s"""\n\n' % (valn, raw))
    t = t[:ins] + "\n" + "".join(defs) + t[ins:]

    # 2) ALL ro'yxatiga qo'shamiz (qayta topamiz — siljigan)
    allm = re.search(r"(val ALL[^\n]*= listOf\(\n)(.*?)(\n[ \t]*\))", t, re.S)
    body = allm.group(2).rstrip()
    if body.endswith(","):
        body = body[:-1]
    add = "".join(',\n        "%s" to %s' % (n.lower(), n.upper()) for n, _ in todo)
    t = t[:allm.start(2)] + body + add + t[allm.end(2):]

    bk = backup(p)
    io.open(p, "w", encoding="utf-8").write(t)
    for n, _ in todo:
        print("  ✔ builtin %s qo'shildi" % n)
    print("  zaxira: %s" % os.path.relpath(bk, root) if root else bk)
    return 0


def catalog_add(root, items, dry):
    """OpenSourceSubscriptions.kt ga obunalarni qo'shadi."""
    p = os.path.join(root, CATALOG)
    t = io.open(p, encoding="utf-8").read()

    todo = [x for x in items
            if not re.search(r'id\s*=\s*"%s"' % re.escape(x["id"]), t)]
    if not todo:
        print("  ⏭  barcha obunalar katalogda bor")
        return 0

    if dry:
        for x in todo:
            print("  • obuna: %s (%s)" % (x["id"], x["name"]))
        return 0

    # Oxirgi OpenSourceSubscription(...) qatoridan keyin qo'shamiz
    last = None
    for m in re.finditer(r"^[ \t]*OpenSourceSubscription\([^\n]*\),\s*$", t, re.M):
        last = m
    if not last:
        print("  ✘ katalogda joy topilmadi")
        return 1

    lines = []
    for x in todo:
        lines.append('        OpenSourceSubscription(')
        lines.append('            id = "%s",' % x["id"])
        lines.append('            nameRes = R.string.os_%s_name,' % x["id"])
        lines.append('            descriptionRes = R.string.os_%s_desc,' % x["id"])
        lines.append('            awgId = "%s",' % x["id"])
        lines.append('            flag = "%s"' % x["flag"])
        lines.append('        ),')
    t = t[:last.end()] + "\n" + "\n".join(lines) + t[last.end():]

    bk = backup(p)
    io.open(p, "w", encoding="utf-8").write(t)
    for x in todo:
        print("  ✔ obuna %s qo'shildi" % x["id"])
    print("  zaxira: %s" % os.path.relpath(bk, root) if root else bk)
    return 0


def strings_add(root, items, dry):
    n = 0
    for lang, rel in STRINGS.items():
        p = os.path.join(root, rel)
        if not os.path.exists(p):
            continue
        t = io.open(p, encoding="utf-8").read()
        add = []
        for x in items:
            key = "os_%s_name" % x["id"]
            if re.search(r'<string name="%s">' % re.escape(key), t):
                continue
            add.append('    <string name="%s">%s</string>' % (key, x["name"]))
            add.append('    <string name="os_%s_desc">%s</string>' % (x["id"], x["desc"]))
        if not add:
            continue
        if dry:
            print("  • %s: %d string" % (rel, len(add)))
            continue
        m = re.search(r"</resources>", t)
        t = t[:m.start()] + "\n".join(add) + "\n" + t[m.start():]
        backup(p)
        io.open(p, "w", encoding="utf-8").write(t)
        n += 1
    if n and not dry:
        print("  ✔ %d strings.xml yangilandi" % n)
    return 0


# ═══════════════════════════════════════════════════════════════════════════
# check / self-test
# ═══════════════════════════════════════════════════════════════════════════

def check(root, items):
    print("  ══ CHECK ══\n")
    ok = True
    bt = io.open(os.path.join(root, BUILTIN), encoding="utf-8").read()
    ct = io.open(os.path.join(root, CATALOG), encoding="utf-8").read()
    for x in items:
        print("  %s" % x["id"])
        for label, good in [
            ("builtin ta'rif", bool(re.search(r'val %s: String = """' % re.escape(x["id"].upper()), bt))),
            ("ALL ro'yxatda", '"%s" to' % x["id"] in bt),
            ("katalogda", bool(re.search(r'id\s*=\s*"%s"' % re.escape(x["id"]), ct))),
        ]:
            ok = ok and good
            print("    %s %s" % ("✔" if good else "❌", label))
        # strings
        found = False
        for rel in STRINGS.values():
            fp = os.path.join(root, rel)
            if os.path.exists(fp) and ('os_%s_name' % x["id"]) in io.open(fp, encoding="utf-8").read():
                found = True
        ok = ok and found
        print("    %s stringlar" % ("✔" if found else "❌"))
    print("\n  " + ("✅ HAMMASI JOYIDA" if ok else "❌ MUAMMO BOR"))
    return 0 if ok else 1


def self_test():
    print("  ══ SELF-TEST ══\n")
    ok = True

    # MASQUE yasash
    anchor = {"private-key": "PRIV", "public-key": "PUB", "ip": "172.16.0.2",
              "ipv6": "2606:4700:110::1", "mtu": "1280", "dns": "[1.1.1.1]"}
    px = {"server": "162.159.198.2", "port": "443", "sni": "4pda.to",
          "network": "h2", "awg": {}}
    m = build_masque({}, anchor, px)
    t = [("PrivateKey", "PrivateKey = PRIV" in m),
         ("PublicKey", "PublicKey = PUB" in m),
         ("Server", "Server = 162.159.198.2" in m),
         ("SNI", "SNI = 4pda.to" in m),
         ("Network", "Network = h2" in m),
         ("Profile", "Profile = cloudflare" in m),
         ("IPv6 Address", "2606:4700:110::1" in m)]
    for n, g in t:
        ok = ok and g
        print("    %s MASQUE %s" % ("✔" if g else "✘", n))

    # AWG yasash
    a = build_awg(anchor, {"server": "162.159.192.1", "port": "4500", "awg": {}})
    t = [("[Interface]", "[Interface]" in a), ("[Peer]", "[Peer]" in a),
         ("Endpoint", "Endpoint = 162.159.192.1:4500" in a),
         ("Jc", "Jc = 4" in a), ("Jmin", "Jmin = 40" in a),
         ("AllowedIPs", "AllowedIPs = 0.0.0.0/0, ::/0" in a)]
    for n, g in t:
        ok = ok and g
        print("    %s AWG %s" % ("✔" if g else "✘", n))

    # YAML o'qish
    if os.path.exists("/tmp/ClashWARP_18.yaml"):
        anc, pxs = read_yaml("/tmp/ClashWARP_18.yaml")
        g = anc is not None and len(pxs) == 2 and anc.get("_type") == "masque"
        ok = ok and g
        print("    %s ClashWARP_18 o'qildi (%d proxy)" % ("✔" if g else "✘", len(pxs)))
    if os.path.exists("/tmp/ClashWARP_22.yaml"):
        anc, pxs = read_yaml("/tmp/ClashWARP_22.yaml")
        g = anc is not None and len(pxs) == 3 and anc.get("_type") == "wireguard"
        ok = ok and g
        print("    %s ClashWARP_22 o'qildi (%d proxy)" % ("✔" if g else "✘", len(pxs)))
        if pxs:
            g = pxs[0]["awg"].get("jc") == "4"
            ok = ok and g
            print("    %s AmneziaWG parametrlari (jc)" % ("✔" if g else "✘"))

    print("\n  " + ("✅ BARCHA TESTLAR O'TDI" if ok else "❌ TEST XATO"))
    return 0 if ok else 1


# ═══════════════════════════════════════════════════════════════════════════

def main():
    args = sys.argv[1:]
    if "--self-test" in args:
        return self_test()
    if "--analyze" in args:
        paths = [a for a in args if a.endswith((".yaml", ".yml"))]
        return analyze(paths)

    root = next((a for a in args if not a.startswith("-") and not a.endswith(".yaml")), ".")
    root = os.path.abspath(root)
    dry = "--dry-run" in args

    # YAML lardan configni yasaymiz
    items = []
    entries = []
    for sid, yp, idx, kind, name, desc in SPEC:
        anchor, proxies = read_yaml(yp)
        if anchor is None:
            print("  ✘ %s topilmadi — %s o'tkazib yuborildi" % (yp, sid))
            continue
        if not proxies:
            print("  ✘ %s: proxy yo'q — o'tkazib yuborildi" % yp)
            continue
        px = proxies[min(idx, len(proxies) - 1)]
        raw = build_masque(sid, anchor, px) if kind == "masque" else build_awg(anchor, px)
        items.append({"id": sid, "name": name, "desc": "%s — %s" % (desc, px["server"]),
                      "flag": "\U0001F310" if kind == "masque" else "\u2601\uFE0F"})
        entries.append((sid, raw))
        print("  %s -> %s (%s:%s)" % (os.path.basename(yp), sid, px["server"], px["port"]))

    if "--check" in args:
        return check(root, items)

    print()
    if dry:
        print("  (dry-run)\n")
    rc = builtin_add(root, entries, dry)
    if rc:
        return rc
    print()
    catalog_add(root, items, dry)
    print()
    strings_add(root, items, dry)
    print("\n  Keyingi qadam:\n    ./gradlew assembleDebug --offline")
    return 0


if __name__ == "__main__":
    sys.exit(main())
