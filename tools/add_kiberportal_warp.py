#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX162-KIBERWARP — Kiberportal'dan 6 ta tayyor MASQUE configini qo'shish
════════════════════════════════════════════════════════════════════════════

 ────────────────────────────────────────────────────────────────────────
  NIMA QILADI
 ────────────────────────────────────────────────────────────────────────

  `BuiltinAwgConfigs.kt` ga 6 ta yangi MASQUE config qo'shadi
  (MASQUE_6 .. MASQUE_11) va `ALL` ro'yxatiga `masque_6..11` yozuvlarini.

  `OpenSourceSubscriptions.kt` ga 6 ta yangi obuna yozuvi qo'shadi.

  `strings.xml` × 4 tilga 12 ta matn (nom + tavsif).

 ────────────────────────────────────────────────────────────────────────
  MANBA — Kiberportal ilovasidan olingan tayyor kalitlar
 ────────────────────────────────────────────────────────────────────────

  1-GURUH (`masque_warp_v9.yaml`):
      PrivateKey: MHcCAQEEIDGq2+BEUnc88zXO3v2H622vxJ7/...
      Server: 162.159.197.2 (h3) / 162.159.197.1 (h3)
              162.159.197.2 (h2) / 162.159.197.5 (h2)

  2-GURUH (`PORTALWARP67.yaml`):
      PrivateKey: MHcCAQEEIDNvVxQi6f70IY5ZXe57iwAxjLJRMA2Z...
      Server: 162.159.198.2 / 162.159.199.2

 ────────────────────────────────────────────────────────────────────────
  ★ MUHIM: KALIT TURI HAQIDA
 ────────────────────────────────────────────────────────────────────────

  Ikkala PrivateKey ham **ECDSA P-256** (PKCS#8), X25519 EMAS:

      MHcCAQEE...  → ASN.1: 302e020100300506032b656e04220420
                                ^^^^^^^^^^^^^^^^^^
                                bu OID PKCS#8 header'i

  Bu MAVJUD `MASQUE_1..5` bilan AYNAN bir xil tur — va siz ular
  qurilmada 100% ishlashini tasdiqladingiz.

  ⚠️ Bu `BuiltinAwgConfigs` uchun MUAMMO EMAS, chunki u yerdagi
     qiymatlar XOM `.conf` MATN — hech qanday prefiks tekshirilmaydi.
     (Prefiks tekshiruvi faqat `WarpGenerator.kt` da, u API uchun.)

  `PublicKey` ikkala guruhda ham bir xil:
      MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6...
  Bu — Cloudflare'ning STANDART server kaliti (`MASQUE_1..5` da ham shu).

 ────────────────────────────────────────────────────────────────────────
  ⚠️ TEGILMAYDIGANLAR
 ────────────────────────────────────────────────────────────────────────

   ❌ `MASQUE_1..5` / `AMNEZIA_1..3` — mavjud configlar
   ❌ `WarpGenerator.kt` / `X25519.kt` — API generatori
   ❌ `AWGStore.kt` — saqlash mantig'i
   ❌ Hysteria2, FinalMask, INCY UI, auto-update (FIX159/160/161)
   ❌ `DiffUtil`

 ────────────────────────────────────────────────────────────────────────
  ISHLATISH
 ────────────────────────────────────────────────────────────────────────

     python3 tools/add_kiberportal_warp.py --check
     python3 tools/add_kiberportal_warp.py
     python3 tools/add_kiberportal_warp.py --rollback
"""

import argparse
import glob
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAMP = time.strftime("%Y%m%d_%H%M%S")
BACKUP_DIR = ROOT / ".fix-backups"          # ⚠️ res/ ichida .bak YO'Q

JAVA = ROOT / "app/src/main/java/com/nurvpn/app"
RES = ROOT / "app/src/main/res"

BUILTIN = JAVA / "config/BuiltinAwgConfigs.kt"
OPENSRC = JAVA / "storage/OpenSourceSubscriptions.kt"

STRINGS = {loc: RES / loc / "strings.xml"
           for loc in ("values", "values-uz", "values-ru", "values-en")}


# ═══════════════════════════════════════════════════════════════════════
#  1. KALITLAR VA SERVERLAR (Kiberportal'dan)
# ═══════════════════════════════════════════════════════════════════════

# 1-guruh — masque_warp_v9.yaml
PRIV_A = ("MHcCAQEEIDGq2+BEUnc88zXO3v2H622vxJ7/J6ydI7iSZRmv3ymZoAoG"
          "CCqGSM49AwEHoUQDQgAEcQfc8lrqDeNvzXA+9rCrPd1BUpqhu9ehtAMQQ"
          "IL1sdQI8wJGOaf7+jyhPQ8oguczV1UthXd0QeecnF4SAzjSog==")

# 2-guruh — PORTALWARP67.yaml
PRIV_B = ("MHcCAQEEIDNvVxQi6f70IY5ZXe57iwAxjLJRMA2ZqPWVHIfQcLCEoAoG"
          "CCqGSM49AwEHoUQDQgAEJXErBnXsRWEgVDgjz3CgZlc62HW2LovHdWLme"
          "Mqr8Yt9uYJx4ur3NhVM0100W9zyp7LLVTf/dTxb2MlFFK2T+g==")

# Cloudflare standart server kaliti (MASQUE_1..5 bilan bir xil)
PUB_STD = ("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r"
           "+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu"
           "+ox+k3dr6w==")

MTU = 1360

# nom, kalit, IPv6, server, network, SNI, tavsif
NEW_CONFIGS = [
    ("masque_6",  PRIV_A, "2606:4700:110:8a8b:5a75:3d7f:af48:303b",
     "162.159.197.2", "h3", "4pda.to",
     "MASQUE h3 · 162.159.197.2 · 4pda.to"),
    ("masque_7",  PRIV_A, "2606:4700:110:8a8b:5a75:3d7f:af48:303b",
     "162.159.197.1", "h3", "4pda.to",
     "MASQUE h3 · 162.159.197.1 · 4pda.to"),
    ("masque_8",  PRIV_A, "2606:4700:110:8a8b:5a75:3d7f:af48:303b",
     "162.159.197.2", "h2", "4pda.to",
     "MASQUE h2 · 162.159.197.2 · 4pda.to"),
    ("masque_9",  PRIV_A, "2606:4700:110:8a8b:5a75:3d7f:af48:303b",
     "162.159.197.5", "h2", "4pda.to",
     "MASQUE h2 · 162.159.197.5 · 4pda.to"),
    ("masque_10", PRIV_B, "2606:4700:110:8cfe:cd54:bba3:2914:42c1",
     "162.159.198.2", "h2", "4pda.to",
     "MASQUE h2 · 162.159.198.2 · 4pda.to"),
    ("masque_11", PRIV_B, "2606:4700:110:8cfe:cd54:bba3:2914:42c1",
     "162.159.199.2", "h2", "mail.ru",
     "MASQUE h2 · 162.159.199.2 · mail.ru"),
]


def conf_text(priv: str, ipv6: str, server: str, network: str, sni: str) -> str:
    """
    MASQUE `.conf` matni — `MASQUE_1..5` bilan AYNAN bir xil 9 maydon.

    ⚠️ Maydon TARTIBI muhim emas (parser kalit bo'yicha o'qiydi),
       lekin biz mavjudlar bilan bir xil tartibni saqlaymiz.
    """
    return (
        f"PrivateKey = {priv}\n"
        f"PublicKey = {PUB_STD}\n"
        f"Address = 172.16.0.2, {ipv6}\n"
        f"MTU = {MTU}\n"
        f"Server = {server}\n"
        f"Port = 443\n"
        f"SNI = {sni}\n"
        f"Network = {network}\n"
        f"Profile = cloudflare"
    )


# ═══════════════════════════════════════════════════════════════════════
#  2. STRINGLAR — 4 til
# ═══════════════════════════════════════════════════════════════════════

# Har bir config uchun nom + tavsif
STR_DEFS = {
    "masque_6":  ("MASQUE h3 (4pda)", "Cloudflare WARP h3 · 162.159.197.2"),
    "masque_7":  ("MASQUE h3 (Alt)", "Cloudflare WARP h3 · 162.159.197.1"),
    "masque_8":  ("MASQUE h2 (4pda)", "Cloudflare WARP h2 · 162.159.197.2"),
    "masque_9":  ("MASQUE h2 (Alt)", "Cloudflare WARP h2 · 162.159.197.5"),
    "masque_10": ("MASQUE 198.2", "Cloudflare WARP h2 · 162.159.198.2"),
    "masque_11": ("MASQUE mail.ru", "Cloudflare WARP h2 · 162.159.199.2"),
}

# Tillar bo'yicha tarjimalar (tavsiflar texnik — tarjima shart emas)
TRANSLATIONS = {
    "values": {
        "masque_6": ("MASQUE h3 (4pda)", "Cloudflare WARP h3 \u00b7 162.159.197.2"),
        "masque_7": ("MASQUE h3 (Alt)", "Cloudflare WARP h3 \u00b7 162.159.197.1"),
        "masque_8": ("MASQUE h2 (4pda)", "Cloudflare WARP h2 \u00b7 162.159.197.2"),
        "masque_9": ("MASQUE h2 (Alt)", "Cloudflare WARP h2 \u00b7 162.159.197.5"),
        "masque_10": ("MASQUE 198.2", "Cloudflare WARP h2 \u00b7 162.159.198.2"),
        "masque_11": ("MASQUE mail.ru", "Cloudflare WARP h2 \u00b7 162.159.199.2"),
    },
    "values-uz": {
        "masque_6": ("MASQUE h3 (4pda)", "Cloudflare WARP h3 \u00b7 162.159.197.2"),
        "masque_7": ("MASQUE h3 (Alt)", "Cloudflare WARP h3 \u00b7 162.159.197.1"),
        "masque_8": ("MASQUE h2 (4pda)", "Cloudflare WARP h2 \u00b7 162.159.197.2"),
        "masque_9": ("MASQUE h2 (Alt)", "Cloudflare WARP h2 \u00b7 162.159.197.5"),
        "masque_10": ("MASQUE 198.2", "Cloudflare WARP h2 \u00b7 162.159.198.2"),
        "masque_11": ("MASQUE mail.ru", "Cloudflare WARP h2 \u00b7 162.159.199.2"),
    },
    "values-ru": {
        "masque_6": ("MASQUE h3 (4pda)", "Cloudflare WARP h3 \u00b7 162.159.197.2"),
        "masque_7": ("MASQUE h3 (Alt)", "Cloudflare WARP h3 \u00b7 162.159.197.1"),
        "masque_8": ("MASQUE h2 (4pda)", "Cloudflare WARP h2 \u00b7 162.159.197.2"),
        "masque_9": ("MASQUE h2 (Alt)", "Cloudflare WARP h2 \u00b7 162.159.197.5"),
        "masque_10": ("MASQUE 198.2", "Cloudflare WARP h2 \u00b7 162.159.198.2"),
        "masque_11": ("MASQUE mail.ru", "Cloudflare WARP h2 \u00b7 162.159.199.2"),
    },
    "values-en": {
        "masque_6": ("MASQUE h3 (4pda)", "Cloudflare WARP h3 \u00b7 162.159.197.2"),
        "masque_7": ("MASQUE h3 (Alt)", "Cloudflare WARP h3 \u00b7 162.159.197.1"),
        "masque_8": ("MASQUE h2 (4pda)", "Cloudflare WARP h2 \u00b7 162.159.197.2"),
        "masque_9": ("MASQUE h2 (Alt)", "Cloudflare WARP h2 \u00b7 162.159.197.5"),
        "masque_10": ("MASQUE 198.2", "Cloudflare WARP h2 \u00b7 162.159.198.2"),
        "masque_11": ("MASQUE mail.ru", "Cloudflare WARP h2 \u00b7 162.159.199.2"),
    },
}


def str_entries(loc: str) -> dict:
    """`{string_name: value}` — 12 ta yozuv (6 nom + 6 tavsif)."""
    out = {}
    for cid, (name, desc) in TRANSLATIONS[loc].items():
        short = cid.replace("masque_", "")
        out[f"os_masque_{short}_name"] = name
        out[f"os_masque_{short}_desc"] = desc
    return out


# ═══════════════════════════════════════════════════════════════════════
#  3. YORDAMCHILAR
# ═══════════════════════════════════════════════════════════════════════

class Edit:
    def __init__(self, path: Path):
        self.path = path
        self.orig = path.read_text(encoding="utf-8") if path.exists() else ""
        self.text = self.orig
        self.changes = []

    def sub(self, old: str, new: str, what: str, guard: str = None) -> bool:
        if guard and guard in self.text:
            return False
        if old not in self.text:
            raise SystemExit(
                f"✗ [{self.path.name}] topilmadi ({what}):\n    {old[:170]!r}")
        self.text = self.text.replace(old, new, 1)
        self.changes.append(what)
        return True

    def changed(self) -> bool:
        return self.text != self.orig

    def brace_ok(self) -> bool:
        return (self.text.count("{") - self.text.count("}")
                == self.orig.count("{") - self.orig.count("}"))

    def write(self) -> bool:
        if not self.changed():
            return False
        BACKUP_DIR.mkdir(exist_ok=True)
        shutil.copy2(self.path, BACKUP_DIR / f"{self.path.name}.{STAMP}.bak")
        self.path.write_text(self.text, encoding="utf-8")
        return True

    def rollback(self) -> bool:
        pats = glob.glob(str(BACKUP_DIR / f"{self.path.name}.*.bak"))
        if not pats:
            return False
        bak = Path(max(pats, key=lambda p: Path(p).stat().st_mtime))
        cur = self.path.stat().st_size if self.path.exists() else 0
        if cur and bak.stat().st_size < cur * 0.5:
            print(f"   ✗ {self.path.name}: zaxira juda kichik — o'tkazildi")
            return False
        shutil.copy2(bak, self.path)
        return True


def escape_xml(v: str) -> str:
    """
    ★ Android `strings.xml` da YALANG'OCH apostrof XATO beradi:

        error: unescaped apostrophe in string

    Shuning uchun `'` → `\\'`. Bu loyihadagi mavjud amaliyot.
    ⚠️ `%1$s` kabi format belgilariga TEGMAYMIZ.
    """
    return v.replace("'", "\\'")


def string_names(path: Path):
    if not path.exists():
        return []
    return re.findall(r'<string name="([^"]+)"',
                      path.read_text(encoding="utf-8"))


def insert_strings(path: Path, entries: dict) -> int:
    if not path.exists():
        return 0
    txt = path.read_text(encoding="utf-8")
    have = set(string_names(path))
    block = [f'    <string name="{n}">{escape_xml(v)}</string>'
             for n, v in entries.items() if n not in have]
    if not block:
        return 0
    if "</resources>" not in txt:
        raise SystemExit(f"✗ {path.name}: `</resources>` topilmadi")
    add = ("\n    <!-- ★ FIX162-KIBERWARP: Kiberportal WARP configlari -->\n"
           + "\n".join(block) + "\n")
    path.write_text(txt.replace("</resources>", add + "</resources>", 1),
                    encoding="utf-8")
    return len(block)


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX162-KIBERWARP")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 76)
    print("  FIX162-KIBERWARP — 6 ta MASQUE config (Kiberportal'dan)")
    print("═" * 76)

    if args.rollback:
        print("\n  ── ROLLBACK ──")
        n = 0
        for p in (BUILTIN, OPENSRC):
            if Edit(p).rollback():
                print(f"   ↩ {p.relative_to(ROOT)}")
                n += 1
        for loc, p in STRINGS.items():
            prefix = f"strings.xml.{loc}."
            pats = [q for q in glob.glob(str(BACKUP_DIR / "strings.xml.*.bak"))
                    if Path(q).name.startswith(prefix)]
            if not pats:
                print(f"   – {loc}: zaxira topilmadi")
                continue
            bak = Path(max(pats, key=lambda q: Path(q).stat().st_mtime))
            shutil.copy2(bak, p)
            print(f"   ↩ {loc}/strings.xml ({bak.name})")
            n += 1
        print(f"\n   {n} ta fayl tiklandi")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  OLDINDAN TEKSHIRUV
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Oldindan tekshiruv ──")
    for p, label in [(BUILTIN, "BuiltinAwgConfigs.kt"),
                     (OPENSRC, "OpenSourceSubscriptions.kt")]:
        if not p.exists():
            print(f"   ✗ {label} topilmadi")
            return 1
        print(f"   ✓ {label}")

    # Kalitlar to'g'ri formatdami?
    print("\n  ── Kalit tekshiruvi ──")
    import base64
    for label, key in (("1-guruh PrivateKey", PRIV_A), ("2-guruh PrivateKey", PRIV_B),
                       ("PublicKey", PUB_STD)):
        try:
            raw = base64.b64decode(key, validate=True)
            ok = raw[0:2] == b"\x30\x2e"          # PKCS#8 SEQUENCE
            print(f"   {'✓' if ok else '⚠'} {label}: {len(raw)} bayt, "
                  f"PKCS#8={ok}, OID={'X25519' if b'\x2b\x65\x6e' in raw[:20] else 'ECDSA/P-256'}")
        except Exception as ex:  # noqa: BLE001
            print(f"   ✗ {label} Base64 xato: {ex}")
            return 1

    # Mavjud configlar tegmaganini tasdiqlaymiz
    cur = BUILTIN.read_text(encoding="utf-8")
    for i in range(1, 6):
        if f"MASQUE_{i}" not in cur:
            print(f"   ✗ MASQUE_{i} yo'q — BEKOR")
            return 1
    print("   ✓ MASQUE_1..5 joyida (tegilmaydi)")

    # ───────────────────────────────────────────────────────────────
    #  TAHRIRLAR
    # ───────────────────────────────────────────────────────────────
    e_b = Edit(BUILTIN)
    e_o = Edit(OPENSRC)

    print("\n  ── BuiltinAwgConfigs.kt ──")

    # 6 ta yangi val MASQUE_N blokini MASQUE_5 dan keyin qo'shamiz
    blocks = []
    for cid, priv, ipv6, server, network, sni, _desc in NEW_CONFIGS:
        n = cid.replace("masque_", "")
        body = conf_text(priv, ipv6, server, network, sni)
        blocks.append(f'    val MASQUE_{n}: String = """{body}"""')
    new_vals = "\n\n".join(blocks)

    d1 = e_b.sub(
        '    val ALL: List<Pair<String, String>> = listOf(',
        new_vals + "\n\n    val ALL: List<Pair<String, String>> = listOf(",
        "6 ta MASQUE_N val",
        guard="val MASQUE_11")
    print(f"   {'✓ QO\'SHILDI' if d1 else '– bor':14} MASQUE_6..11")

    # ALL ro'yxatiga yozuvlar
    d2 = e_b.sub(
        '        "masque_5" to MASQUE_5\n    )',
        '        "masque_5" to MASQUE_5,\n'
        + "\n".join(f'        "masque_{i}" to MASQUE_{i}'
                    for i in range(6, 12))
        + "\n    )",
        "ALL ro'yxati",
        guard='"masque_11" to MASQUE_11')
    print(f"   {'✓ QO\'SHILDI' if d2 else '– bor':14} ALL yozuvlari")

    print("\n  ── OpenSourceSubscriptions.kt ──")
    d3 = e_o.sub(
        '        OpenSourceSubscription(id = "masque_5",'
        ' nameRes = R.string.os_masque_5_name, descriptionRes ='
        ' R.string.os_masque_5_desc, awgId = "masque_5", flag = "\\u2601\\uFE0F"),',
        '        OpenSourceSubscription(id = "masque_5",'
        ' nameRes = R.string.os_masque_5_name, descriptionRes ='
        ' R.string.os_masque_5_desc, awgId = "masque_5", flag = "\\u2601\\uFE0F"),\n'
        + "\n".join(
            f'        OpenSourceSubscription(id = "{cid}",'
            f' nameRes = R.string.os_masque_{cid.replace("masque_", "")}_name,'
            f' descriptionRes = R.string.os_masque_{cid.replace("masque_", "")}_desc,'
            f' awgId = "{cid}", flag = "\\u2601\\uFE0F"),'
            for cid, *_ in NEW_CONFIGS),
        "6 ta obuna",
        guard='id = "masque_11"')
    print(f"   {'✓ QO\'SHILDI' if d3 else '– bor':14} obunalar")

    # ───────────────────────────────────────────────────────────────
    #  TEKSHIRUVLAR
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Tekshiruvlar ──")

    if not e_b.brace_ok():
        print("   ✗ BuiltinAwgConfigs.kt qavs balansi buzildi — BEKOR")
        return 1
    if not e_o.brace_ok():
        print("   ✗ OpenSourceSubscriptions.kt qavs balansi buzildi — BEKOR")
        return 1
    print("   ✓ Kotlin qavs balanslari saqlandi")

    # Har bir config 9 maydonga ega
    for cid, priv, ipv6, server, network, sni, _d in NEW_CONFIGS:
        body = conf_text(priv, ipv6, server, network, sni)
        keys = [l.split("=")[0].strip() for l in body.splitlines()]
        want = ["PrivateKey", "PublicKey", "Address", "MTU",
                "Server", "Port", "SNI", "Network", "Profile"]
        if keys != want:
            print(f"   ✗ {cid}: maydonlar noto'g'ri {keys}")
            return 1
    print(f"   ✓ {len(NEW_CONFIGS)} ta config × 9 maydon to'g'ri")

    # Kalitlar haqiqatan matnda bormi
    for cid, priv, ipv6, server, network, sni, _d in NEW_CONFIGS:
        for frag in (priv[:40], PUB_STD[:40], server, sni, f"Network = {network}"):
            if frag not in e_b.text:
                print(f"   ✗ {cid}: '{frag[:30]}' matnda yo'q")
                return 1
    print("   ✓ Barcha kalit/server qiymatlari matnda")

    # ALL ro'yxati 11 ta
    n_all = len(re.findall(r'"masque_\d+" to MASQUE_\d+', e_b.text))
    if n_all != 11:
        print(f"   ✗ ALL da {n_all} ta masque yozuvi (11 kutilgan)")
        return 1
    print(f"   ✓ ALL ro'yxatida {n_all} ta masque yozuvi")

    # Obunalar 11 ta
    n_sub = len(re.findall(r'id = "masque_\d+"', e_o.text))
    if n_sub != 11:
        print(f"   ✗ {n_sub} ta obuna (11 kutilgan)")
        return 1
    print(f"   ✓ {n_sub} ta obuna yozuvi")

    # Mavjud 1-5 tegmagani
    for i in range(1, 6):
        for g in (f"MASQUE_{i}: String", f'"masque_{i}" to MASQUE_{i}',
                  f'id = "masque_{i}"'):
            if g not in (e_b.text if 'MASQUE' in g or ' to ' in g else e_o.text):
                print(f"   ✗ HIMOYA BUZILDI: {g} — BEKOR")
                return 1
    print("   ✓ MASQUE_1..5 tegilmagan")

    for g, label in [("WarpGenerator", "WarpGenerator"),
                     ("object X25519", "X25519")]:
        for p in (BUILTIN, OPENSRC):
            if g in p.read_text(encoding="utf-8"):
                print(f"   ⚠ {label} {p.name} da uchradi — tekshiring")
    print("   ✓ generator fayllariga tegilmagan")

    # ★ AAPT2 SINOVI — stringlar kompilyatsiya bo'ladimi
    aapt2 = None
    for cand in sorted(glob.glob(str(Path.home() / "Android/Sdk/build-tools/*/aapt2"))):
        aapt2 = cand
    if aapt2:
        with tempfile.TemporaryDirectory() as td:
            vd = Path(td) / "values"
            vd.mkdir()
            body = "\n".join(
                f'    <string name="{n}">{escape_xml(v)}</string>'
                for n, v in str_entries("values").items())
            (vd / "strings.xml").write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                + body + "\n</resources>\n", encoding="utf-8")
            r = subprocess.run([aapt2, "compile", "-o", str(Path(td) / "o.zip"),
                                str(vd / "strings.xml")],
                               capture_output=True, text=True)
            if r.returncode != 0:
                print("   ✗ AAPT2 string xatosi:")
                for line in (r.stderr or "").splitlines()[:8]:
                    print(f"       {line}")
                return 1
        print("   ✓ AAPT2: stringlar kompilyatsiya bo'ladi")
    else:
        print("   ⚠ aapt2 topilmadi — string sinovi o'tkazib yuborildi")

    total = sum(1 for d in (d1, d2, d3) if d) + len(NEW_CONFIGS) * 2
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n  ── Yangi configlar ──")
        for cid, priv, ipv6, server, network, sni, desc in NEW_CONFIGS:
            print(f"     + {cid}: {server} ({network}) SNI={sni}")
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  YOZISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Yozish ──")
    for e in (e_b, e_o):
        if e.write():
            print(f"   💾 {e.path.relative_to(ROOT)}")

    for loc, path in STRINGS.items():
        if path.exists():
            BACKUP_DIR.mkdir(exist_ok=True)
            shutil.copy2(path, BACKUP_DIR / f"strings.xml.{loc}.{STAMP}.bak")
        n = insert_strings(path, str_entries(loc))
        if n:
            print(f"   💾 {loc}/strings.xml  (+{n} string)")

    # ───────────────────────────────────────────────────────────────
    #  YAKUNIY VALIDATSIYA
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Yakuniy validatsiya ──")
    for loc, path in STRINGS.items():
        try:
            ET.parse(str(path))
        except Exception as ex:  # noqa: BLE001
            print(f"   ✗ {loc}/strings.xml BUZILDI: {ex}")
            return 1
        print(f"   ✓ {loc:11} {len(string_names(path))} string")

    counts = {loc: len(string_names(p)) for loc, p in STRINGS.items()}
    if len(set(counts.values())) != 1:
        print(f"   ✗ Tillar sinxron emas: {counts}")
        return 1
    print(f"   ✓ 4 til sinxron ({counts['values']} string)")

    final = BUILTIN.read_text(encoding="utf-8")
    if final.count("MASQUE_") < 11:
        print("   ✗ BuiltinAwgConfigs.kt da MASQUE_ yetarli emas")
        return 1
    print("   ✓ BuiltinAwgConfigs.kt: 11 ta MASQUE")

    print("\n" + "═" * 76)
    print("  ✅ TAYYOR — Kiberportal WARP configlari qo'shildi")
    print("═" * 76)
    print("""
  QO'SHILDI:
     💾 BuiltinAwgConfigs.kt   — MASQUE_6..11 (6 ta, 9 maydon)
     💾 OpenSourceSubscriptions.kt — masque_6..11 obunalari
     💾 strings.xml × 4        — 12 ta matn (nom + tavsif)

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Sozlamalar → Ochiq manbalar → MASQUE h3/h2 ni yoqing
     5) Ulanishni sinang

  ROLLBACK: python3 tools/add_kiberportal_warp.py --rollback
""")


if __name__ == "__main__":
    sys.exit(main())
