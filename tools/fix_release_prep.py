#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX157-RELEASEPREP — v1.13.0 reliz oldidan tozalash va optimizatsiya
════════════════════════════════════════════════════════════════════════════

 4 ta vazifa (P0 → P2):

 ┌─────┬────────────────────────────────────────────┬──────────────┐
 │ P0  │ 44 ta string ingliz tilida YO'Q            │ qo'shiladi   │
 │ P1  │ `System.gc()` — keraksiz to'liq GC pause   │ olib tashlanadi│
 │ P1  │ `libbox.aar.bak` — 63 MB keraksiz nusxa    │ o'chiriladi  │
 │ P2  │ 17 ta hardcoded Toast (o'zbekcha)          │ resursga     │
 └─────┴────────────────────────────────────────────┴──────────────┘

 ═══ MUHIM TOPILMA: KO'P ISH ALLAQACHON QILINGAN ═══

 Tekshiruv natijasi — 17 ta hardcoded Toast'ning 9 tasi uchun
 resurs ALLAQACHON MAVJUD, faqat kod uni ishlatmayapti:

     Toast.makeText(context, "Server allaqachon qo'shilgan")   ← hardcoded
     strings.xml: <string name="toast_server_exists">Server allaqachon…</string>  ← MAVJUD

 Ya'ni yangi string qo'shish SHART EMAS — kodni resursga ULAYMIZ.
 Bu tarjima sifatini ham oshiradi (uz/ru allaqachon to'g'ri).

     ┌──────────────────────────────┬──────────────────────────────────┐
     │ Hardcoded Toast              │ Ishlatiladigan resurs            │
     ├──────────────────────────────┼──────────────────────────────────┤
     │ "Server allaqachon qo'shilgan"│ toast_server_exists             │
     │ "JSON config o'qilmadi"       │ toast_json_not_read             │
     │ "Link parse xato"             │ toast_invalid_link              │
     │ "Shifrlangan linkni…"         │ encrypted_decode_failed         │
     │ "$n o'chirildi"               │ toast_deleted                   │
     │ "$added server qo'shildi"     │ toast_n_servers_added_fmt (%1$d)│
     │ "$added ta AWG config"        │ toast_n_wg_added_fmt     (%1$d) │
     │ "MASQUE qo'shildi: X"         │ toast_awg_added_endpoint (%1$s) │
     │ "Xato: msg"                   │ toast_error_fmt          (%1$s) │
     └──────────────────────────────┴──────────────────────────────────┘

 FAQAT 4 ta YANGI string kerak:
     toast_server_empty          — "Server bo'sh"
     toast_yaml_masque_error     — "YAML MASQUE parse xatosi"
     toast_yaml_wg_error         — "YAML WireGuard parse xatosi"
     toast_install_error_fmt     — "O'rnatishni boshlashda xato: %1$s"

 ═══ P0: 44 TA INGLIZ STRINGI ═══

     values     433 ta
     values-uz  433 ta
     values-ru  433 ta
     values-en  389 ta   ← 44 TA YETISHMAYDI

 Ingliz foydalanuvchisi o'zbekcha matn ko'radi. Mavjud uz/ru
 qiymatlari asosida inglizcha tarjima qo'shiladi.

 ⚠️ `values` (default) o'zgartirilmaydi — u allaqachon to'liq.

 ═══ P1: `System.gc()` ═══

 `HomeFragment.kt` da obuna yuklangandan keyin `System.gc()` chaqiriladi.
 Bu TO'LIQ GC pause (~50-200ms) keltiradi va Android o'zi boshqaradigan
 xotimani majburan tozalaydi. Foyda YO'Q, zarari bor → olib tashlanadi.

 ═══ P1: `libbox.aar.bak` ═══

 `app/libs/` ichida 63 MB keraksiz zaxira. Build'ga tushmaydi
 (faqat `.aar` olinadi), lekin repo'ni shishiradi.

 ═══ ★★ HIMOYA: TEGILMAYDIGAN 7 TA FUNKSIYA ★★ ═══

   ❌ `item_home_card_header.xml`  — INCY UI (tegilmaydi)
   ❌ `SingBoxConfig.kt`           — Hysteria2 insecure/obfs
   ❌ `FinalMaskJson.kt`           — FinalMask JSON
   ❌ `crypt1://` tartibi          — deep link
   ❌ `take(200)`                   — allaqachon olib tashlangan
   ❌ `onTabShown()`               — scroll to top
   ❌ WorkManager 24h              — avto-yangilanish
   ❌ `DiffUtil`                   — refaktor QILINMAYDI (release xavfi)

 ═══ XAVFSIZLIK ═══

   • Idempotent (qayta ishga tushsa buzmaydi)
   • Zaxiralar `.fix-backups/` ga (res/ ichida .bak YO'Q!)
   • Rollback vaqti bo'yicha (`mtime`) + 50% o'lchov himoyasi
   • `strings.xml` XML validatsiyasi + takrorlanish tekshiruvi
   • Barcha format argumentlari (`%1$d`, `%1$s`) solishtiriladi

 ═══ ISHLATISH ═══

     python3 tools/fix_release_prep.py --check
     python3 tools/fix_release_prep.py
     python3 tools/fix_release_prep.py --rollback
"""

import argparse
import glob
import re
import shutil
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAMP = time.strftime("%Y%m%d_%H%M%S")

# ⚠️ `res/` ICHIDA .bak QOLDIRILMAYDI (AGP: "must end with .xml")
BACKUP_DIR = ROOT / ".fix-backups"

JAVA = ROOT / "app/src/main/java/com/nurvpn/app"
RES = ROOT / "app/src/main/res"

STRINGS = {loc: RES / loc / "strings.xml"
           for loc in ("values", "values-uz", "values-ru", "values-en")}

HOME = JAVA / "ui/home/HomeFragment.kt"
SERVERS = JAVA / "ui/servers/ServersFragment.kt"
MASQUE = JAVA / "ui/awg/MasqueEditorActivity.kt"
UPDATE = JAVA / "util/UpdateManager.kt"
SETTINGS = JAVA / "ui/settings/SettingsFragment.kt"
AAR_BAK = ROOT / "app/libs/libbox.aar.bak"


# ═══════════════════════════════════════════════════════════════════════
#  ZAXIRA / TAHRIRLASH YORDAMCHILARI
# ═══════════════════════════════════════════════════════════════════════

def backup_path(src: Path) -> Path:
    BACKUP_DIR.mkdir(exist_ok=True)
    return BACKUP_DIR / f"{src.name}.{STAMP}.bak"


def latest_backup(src: Path):
    """
    ⚠️ ENG YANGI zaxira — ALIFBO emas, VAQT bo'yicha.

    Avvalgi skriptlarda `sorted()` alifbo bo'yicha saralardi va
    `...presed_backup.bak` kabi eski fayllar oxirida tushib,
    rollback NOTO'G'RI faylni tiklab, ishlarni yo'qotgan edi.
    """
    pats = glob.glob(str(BACKUP_DIR / f"{src.name}.*.bak"))
    if not pats:
        return None
    return Path(max(pats, key=lambda p: Path(p).stat().st_mtime))


class Edit:
    """Matn faylini xavfsiz tahrirlash (atomoik yozish + zaxira)."""

    def __init__(self, path: Path):
        self.path = path
        self.orig = path.read_text(encoding="utf-8") if path.exists() else ""
        self.text = self.orig
        self.changes = []

    def sub(self, old: str, new: str, what: str, guard: str = None,
            count: int = 1) -> bool:
        if guard and guard in self.text:
            return False
        if old not in self.text:
            raise SystemExit(
                f"✗ [{self.path.name}] topilmadi ({what}):\n    {old[:170]!r}")
        self.text = self.text.replace(old, new, count)
        self.changes.append(what)
        return True

    def sub_regex(self, pattern: str, new: str, what: str, guard: str = None,
                  expect: int = 1) -> bool:
        """
        Regex bilan almashtirish — matn bir necha joyda har xil
        chekinish bilan uchraganda ishlatiladi.

        `expect` — kutilgan uchrash soni. Mos kelmasa XATO beradi
        (jimgina o'tkazib yubormaslik uchun).
        """
        if guard and guard in self.text:
            return False
        found = len(re.findall(pattern, self.text))
        if found != expect:
            raise SystemExit(
                f"✗ [{self.path.name}] {what}: {found} ta topildi, "
                f"{expect} ta kutilgan edi")
        self.text = re.sub(pattern, new, self.text)
        self.changes.append(what)
        return True

    def changed(self) -> bool:
        return self.text != self.orig

    def brace_ok(self) -> bool:
        """Kotlin qavs balansi (faqat .kt uchun)."""
        return (self.text.count("{") - self.text.count("}")
                == self.orig.count("{") - self.orig.count("}"))

    def write(self) -> bool:
        if not self.changed():
            return False
        if self.path.exists():
            shutil.copy2(self.path, backup_path(self.path))
        self.path.write_text(self.text, encoding="utf-8")
        return True

    def rollback(self) -> bool:
        bak = latest_backup(self.path)
        if not bak:
            return False
        cur = self.path.stat().st_size if self.path.exists() else 0
        bsz = bak.stat().st_size
        if cur and bsz < cur * 0.5:
            raise SystemExit(
                f"✗ ROLLBACK TO'XTATILDI: {bak.name} ({bsz} bayt) joriy "
                f"fayldan ({cur} bayt) ancha kichik —\n"
                f"  NOTO'G'RI zaxira bo'lishi mumkin.")
        shutil.copy2(bak, self.path)
        return True


# ═══════════════════════════════════════════════════════════════════════
#  P0 — 44 TA INGLIZ STRINGI
# ═══════════════════════════════════════════════════════════════════════
#
#  Tarjimalar mavjud uz/ru qiymatlariga asoslanadi. Ba'zilari allaqachon
#  inglizcha (masalan "Radikal Hysteria2", "MASQUE 4") — ular AYNAN
#  ko'chiriladi.

EN_STRINGS = {
    # ─── Obuna / e'lon ───
    "toast_sub_url_hint":
        "This is a subscription URL. To add it, use the "
        "&quot;Open sources&quot; section",
    "sub_remaining": "Left %1$s",
    "sub_announce_title": "Announcement",
    "sub_unlimited": "Unlimited",

    # ─── Biometrik qulf ───
    "bio_lock": "🔒 Lock",
    "bio_locked_toast": "🔒 Locked",
    "bio_unlock": "🔓 Open via biometrics",
    "bio_unlocked_toast": "✅ Unlocked",
    "bio_title": "Confirm security",
    "bio_no_lock": "⚠️ Warning! Your phone has no screen lock. "
                   "Please set a password for security.",
    "bio_sub_default": "Allow to view encrypted data",
    "bio_sub_unlock": "%1$s - confirm to open data",
    "bio_sub_copy": "%1$s - confirm to copy",
    "bio_sub_qr": "%1$s - confirm to view QR code",

    # ─── Shifrlangan obuna ───
    "encrypted_decode_failed": "❌ Could not decode encrypted link",
    "encrypted_locked_title":
        "🔒 Encrypted subscription — copy/share/QR blocked",
    "encrypted_share_blocked":
        "🔒 Encrypted subscription — sharing is not allowed",
    "encrypted_copy_blocked":
        "🔒 Encrypted subscription — copying is not allowed",
    "encrypted_qr_blocked":
        "🔒 Encrypted subscription — QR display is not allowed",

    # ─── Kill Switch ───
    "kill_switch_title": "🛡 Full Security (Kill Switch)",
    "kill_switch_msg":
        "To prevent your real IP from leaking when the internet "
        "disconnects, please enable &quot;Always-on VPN&quot; and "
        "&quot;Block connections without VPN&quot; in Android settings.",
    "kill_switch_msg_on":
        "To prevent your real IP from leaking, please ENABLE "
        "&quot;Always-on VPN&quot; and &quot;Block connections without "
        "VPN&quot; in Android settings.",
    "kill_switch_msg_off":
        "To completely disable Kill Switch and restore normal internet, "
        "please DISABLE &quot;Always-on VPN&quot; and &quot;Block "
        "connections without VPN&quot; in Android settings.",
    "kill_switch_btn": "Go to settings",
    "kill_switch_later": "Later",

    # ─── Obuna limiti ───
    "sub_limit_title": "⚠️ Subscription not working",
    "sub_limit_msg":
        "The provider has exceeded the device limit.\\n\\nSolution:\\n"
        "1. Reset devices in the provider bot\\n"
        "2. If that does not help, contact support",
    "sub_limit_btn": "Got it",

    # ─── Sozlamalar ───
    "settings_proto_transport": "Protocol / Transport",

    # ─── Ochiq manbalar (nomlar o'zgarmaydi, tavsiflar tarjima) ───
    "os_radikal_hy2_name": "Radikal Hysteria2",
    "os_radikal_hy2_desc": "Hysteria2 configs by 0xRadikal",
    "os_sigridforum_name": "Sigrid Forum",
    "os_sigridforum_desc": "Hiddify subscription",
    "os_aeryx_name": "Aeryx VPN",
    "os_aeryx_desc": "AeryxVPN subscription",
    "os_aetris_name": "Aetris VPN",
    "os_aetris_desc": "AetrisVPN blacklist subscription",
    "os_masque_1_name": "MASQUE 1",
    "os_masque_1_desc": "162.159.198.2. Cloudflare CONNECT-IP. SNI 4pda.",
    "os_masque_2_name": "MASQUE 2",
    "os_masque_2_desc": "162.159.199.2. SNI mail.ru.",
    "os_masque_3_name": "MASQUE 3",
    "os_masque_3_desc": "162.159.198.2. Backup account.",
    "os_masque_4_name": "MASQUE 4",
    "os_masque_4_desc": "162.159.198.2. Cloudflare WARP via MASQUE.",
    "os_masque_5_name": "MASQUE 5",
    "os_masque_5_desc": "162.159.199.2. Cloudflare WARP via MASQUE.",
}

# ═══════════════════════════════════════════════════════════════════════
#  P2 — 4 TA YANGI STRING (barcha tillarda)
# ═══════════════════════════════════════════════════════════════════════

NEW_STRINGS = {
    "toast_server_empty": {
        "values": "Server bo\\'sh",
        "values-uz": "Server bo\\'sh",
        "values-ru": "Сервер пустой",
        "values-en": "Server is empty",
    },
    "toast_yaml_masque_error": {
        "values": "YAML MASQUE parse xatosi",
        "values-uz": "YAML MASQUE parse xatosi",
        "values-ru": "Ошибка разбора YAML MASQUE",
        "values-en": "YAML MASQUE parse error",
    },
    "toast_yaml_wg_error": {
        "values": "YAML WireGuard parse xatosi",
        "values-uz": "YAML WireGuard parse xatosi",
        "values-ru": "Ошибка разбора YAML WireGuard",
        "values-en": "YAML WireGuard parse error",
    },
    "toast_install_error_fmt": {
        "values": "O\\'rnatishni boshlashda xato: %1$s",
        "values-uz": "O\\'rnatishni boshlashda xato: %1$s",
        "values-ru": "Ошибка запуска установки: %1$s",
        "values-en": "Error starting installation: %1$s",
    },
}

# ═══════════════════════════════════════════════════════════════════════
#  P2 — HARDCODED TOAST → RESURS
# ═══════════════════════════════════════════════════════════════════════
#
#  `(fayl, eski_kod, yangi_kod, tavsif, guard)`

TOAST_EDITS = [
    # ─── HomeFragment ───
    (HOME,
     '''Toast.makeText(context, "$n o\\'chirildi", Toast.LENGTH_SHORT).show()''',
     '''Toast.makeText(context, getString(R.string.toast_deleted),
            Toast.LENGTH_SHORT).show()''',
     "Home:  o'chirildi",
     '''Toast.makeText(context, getString(R.string.toast_deleted),
            Toast.LENGTH_SHORT).show()'''),

    (HOME,
     '''Toast.makeText(context, "JSON config o\\'qilmadi",''',
     '''Toast.makeText(context, getString(R.string.toast_json_not_read),''',
     "Home:  JSON o'qilmadi",
     "R.string.toast_json_not_read"),

    (HOME,
     '''Toast.makeText(context, "Server allaqachon qo\\'shilgan",''',
     '''Toast.makeText(context, getString(R.string.toast_server_exists),''',
     "Home:  server mavjud",
     "R.string.toast_server_exists"),

    (HOME,
     '''Toast.makeText(context, "Xato: ${t.message}",''',
     '''Toast.makeText(context, getString(R.string.toast_error_fmt, t.message),''',
     "Home:  xato formati",
     '''addFromJson xato", t)
            Toast.makeText(context, getString(R.string.toast_error_fmt'''),

    (HOME,
     '''Toast.makeText(context, "Link parse xato", Toast.LENGTH_SHORT).show()''',
     '''Toast.makeText(context, getString(R.string.toast_invalid_link),
                Toast.LENGTH_SHORT).show()''',
     "Home:  link parse",
     '''if (parsed == null) {
                Toast.makeText(context, getString(R.string.toast_invalid_link'''),

    # ⚠️ "Shifrlangan linkni o'qib bo'lmadi" — BIR XIL matn 3 JOYDA
    #    (3015, 3578, 4030). `count=3` bilan hammasi bir marta
    #    almashtiriladi (TOAST_MULTI ro'yxatiga qarang).

    # ─── ServersFragment ───
    (SERVERS,
     '''Toast.makeText(context, "Xato: ${t.message}", Toast.LENGTH_LONG).show()''',
     '''Toast.makeText(context, getString(R.string.toast_error_fmt, t.message),
                Toast.LENGTH_LONG).show()''',
     "Servers: xato formati",
     "R.string.toast_error_fmt, t.message"),

    (SERVERS,
     '''Toast.makeText(context, "$added server qo\\'shildi", Toast.LENGTH_SHORT).show()''',
     '''Toast.makeText(context,
                    getString(R.string.toast_n_servers_added_fmt, added),
                    Toast.LENGTH_SHORT).show()''',
     "Servers: server qo'shildi",
     "R.string.toast_n_servers_added_fmt, added"),

    (SERVERS,
     '''Toast.makeText(context, "YAML MASQUE parse xatosi", Toast.LENGTH_LONG).show()''',
     '''Toast.makeText(context, getString(R.string.toast_yaml_masque_error),
                    Toast.LENGTH_LONG).show()''',
     "Servers: MASQUE xato",
     "R.string.toast_yaml_masque_error"),

    (SERVERS,
     '''Toast.makeText(context, "YAML WireGuard parse xatosi", Toast.LENGTH_LONG).show()''',
     '''Toast.makeText(context, getString(R.string.toast_yaml_wg_error),
                    Toast.LENGTH_LONG).show()''',
     "Servers: WireGuard xato",
     "R.string.toast_yaml_wg_error"),

    (SERVERS,
     '''Toast.makeText(context, "$added ta AWG config qo'shildi", Toast.LENGTH_SHORT).show()''',
     '''Toast.makeText(context,
                    getString(R.string.toast_n_wg_added_fmt, added),
                    Toast.LENGTH_SHORT).show()''',
     "Servers: AWG qo'shildi",
     "R.string.toast_n_wg_added_fmt, added"),

    (SERVERS,
     '''Toast.makeText(context, "MASQUE qo\\'shildi: " + pair.first, Toast.LENGTH_SHORT).show()''',
     '''Toast.makeText(context,
                    getString(R.string.toast_awg_added_endpoint, pair.first),
                    Toast.LENGTH_SHORT).show()''',
     "Servers: MASQUE qo'shildi",
     "R.string.toast_awg_added_endpoint, pair.first"),

    # ─── MasqueEditorActivity ───
    (MASQUE,
     '''Toast.makeText(this, "Server bo'sh", Toast.LENGTH_SHORT).show()''',
     '''Toast.makeText(this, getString(R.string.toast_server_empty),
                Toast.LENGTH_SHORT).show()''',
     "Masque: server bo'sh",
     "R.string.toast_server_empty"),

    # ─── SettingsFragment (Kill Switch → tizim sozlamalari) ───
    (SETTINGS,
     '''android.widget.Toast.makeText(c, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()''',
     '''android.widget.Toast.makeText(c,
                        getString(R.string.toast_error_prefix, e.message),
                        android.widget.Toast.LENGTH_SHORT).show()''',
     "Settings: kill switch xato",
     "getString(R.string.toast_error_prefix, e.message)"),

    # ─── UpdateManager ───
    (UPDATE,
     '''Toast.makeText(activity, "O'rnatishni boshlashda xato: ${e.message}", Toast.LENGTH_LONG).show()''',
     '''Toast.makeText(activity,
                activity.getString(
                    com.nurvpn.app.R.string.toast_install_error_fmt, e.message),
                Toast.LENGTH_LONG).show()''',
     "Update: install xato",
     "com.nurvpn.app.R.string.toast_install_error_fmt, e.message"),
]

# ═══════════════════════════════════════════════════════════════════════
#  P2 — BIR XIL MATN BIR NECHA JOYDA (regex kerak)
# ═══════════════════════════════════════════════════════════════════════
#
#  `(fayl, regex, yangi, tavsif, guard, kutilgan_soni)`
#
#  "Shifrlangan linkni o'qib bo'lmadi" — 3 ta joyda uchraydi
#  (3015, 3578, 4030) va HAR BIRI HAR XIL chekinishda. Shu sababli
#  oddiy `str.replace` ishlamaydi — faqat matn qismini almashtiramiz.

TOAST_MULTI = [
    (HOME,
     r'"Shifrlangan linkni o\'qib bo\'lmadi"',
     'getString(R.string.encrypted_decode_failed)',
     "Home:  shifrlangan link",
     '''if (decoded == null) {
                Toast.makeText(context, getString(R.string.encrypted_decode_failed''',
     3),
]

# ═══════════════════════════════════════════════════════════════════════
#  P1 — `System.gc()` OLIB TASHLASH
# ═══════════════════════════════════════════════════════════════════════

GC_OLD = '''                Thread {
                    ServerStore.save(a, a.servers)
                    System.gc()
                    activity?.runOnUiThread {'''

GC_NEW = '''                Thread {
                    ServerStore.save(a, a.servers)
                    // ★ FIX157: `System.gc()` OLIB TASHLANDI.
                    //   U to'liq GC pause (~50-200ms) keltirardi va
                    //   Android o'zi boshqaradigan xotimani majburan
                    //   tozalar edi — foyda yo'q, zarari bor.
                    activity?.runOnUiThread {'''


# ═══════════════════════════════════════════════════════════════════════
#  YORDAMCHI FUNKSIYALAR
# ═══════════════════════════════════════════════════════════════════════

def string_names(path: Path):
    if not path.exists():
        return []
    t = path.read_text(encoding="utf-8")
    return re.findall(r'<string name="([^"]+)"', t)


def insert_strings(path: Path, entries: dict, only_missing=True) -> int:
    """
    `strings.xml` ga string qo'shadi (oxirgi `</resources>` oldiga).

    `entries` — `{nom: xml_qiymat}`.
    Xavfsizlik: mavjud nomlar qo'shilmaydi (`only_missing`).
    """
    if not path.exists():
        return 0
    txt = path.read_text(encoding="utf-8")
    have = set(string_names(path))
    block = []
    for name, val in entries.items():
        if only_missing and name in have:
            continue
        block.append(f'    <string name="{name}">{val}</string>')
    if not block:
        return 0
    if "</resources>" not in txt:
        raise SystemExit(f"✗ {path.name}: `</resources>` topilmadi")
    addition = ("\n    <!-- ★ FIX157-RELEASEPREP: qo'shilgan stringlar -->\n"
                + "\n".join(block) + "\n")
    txt = txt.replace("</resources>", addition + "</resources>", 1)
    path.write_text(txt, encoding="utf-8")
    return len(block)


def validate_xml(path: Path) -> str:
    try:
        ET.parse(str(path))
        return ""
    except Exception as ex:  # noqa: BLE001
        return str(ex)


def fmt_args(text: str):
    """Format argumentlarini yig'adi: %1$d, %1$s ..."""
    return sorted(set(re.findall(r'%\d+\$[sd]', text)))


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX157-RELEASEPREP")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 76)
    print("  FIX157-RELEASEPREP — v1.13.0 reliz tayyorgarligi")
    print("═" * 76)

    # ───────────────────────────────────────────────────────────────
    #  ROLLBACK
    # ───────────────────────────────────────────────────────────────
    if args.rollback:
        print("\n  ── ROLLBACK ──")
        n = 0

        # Kotlin fayllar — `latest_backup()` ishlaydi (nomlar noyob)
        for p in [HOME, SERVERS, MASQUE, UPDATE, SETTINGS]:
            e = Edit(p)
            if e.rollback():
                print(f"   ↩ {p.relative_to(ROOT)}")
                n += 1

        # ⚠️ `strings.xml` — 4 tasi bir xil nomda! Shuning uchun
        #    HAR BIR TIL uchun alohida naqsh ishlatamiz
        #    (`strings.xml.<loc>.*.bak`), aks holda `values` o'rniga
        #    `en` fayli tiklanishi mumkin.
        for loc, path in STRINGS.items():
            # ⚠️ `strings.xml.values.*` naqshi `strings.xml.values-en.*`
            #    ni ham ushlaydi! Shuning uchun `re.escape` bilan
            #    ANIQ prefiksni tekshiramiz.
            prefix = f"strings.xml.{loc}."
            pats = [p for p in glob.glob(str(BACKUP_DIR / "strings.xml.*.bak"))
                    if Path(p).name.startswith(prefix)]
            if not pats:
                print(f"   – {loc}: zaxira topilmadi")
                continue
            bak = Path(max(pats, key=lambda x: Path(x).stat().st_mtime))
            cur = path.stat().st_size if path.exists() else 0
            if cur and bak.stat().st_size < cur * 0.5:
                print(f"   ✗ {loc}: zaxira juda kichik — o'tkazib yuborildi")
                continue
            shutil.copy2(bak, path)
            print(f"   ↩ {loc}/strings.xml   ({bak.name})")
            n += 1

        print(f"\n   {n} ta fayl tiklandi")
        print("   ⚠️ `libbox.aar.bak` qo'lda tiklanadi (o'chirilgan)")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  P0: INGLIZ STRINGLARI
    # ───────────────────────────────────────────────────────────────
    print("\n  ── P0: Ingliz stringlari ──")
    en_path = STRINGS["values-en"]
    en_have = set(string_names(en_path))
    en_missing = [k for k in EN_STRINGS if k not in en_have]
    print(f"   Mavjud: {len(en_have)}, qo'shiladi: {len(en_missing)}")

    # ⚠️ Kalit mavjudligini bazadan tekshiramiz
    base_names = set(string_names(STRINGS["values"]))
    bogus = [k for k in EN_STRINGS if k not in base_names]
    if bogus:
        raise SystemExit(f"✗ Bazada yo'q kalit: {bogus}")

    # Format argumentlari mosligini tekshiramiz
    print("\n  ── Format argument tekshiruvi ──")
    base_txt = STRINGS["values"].read_text(encoding="utf-8")
    fmt_bad = []
    for name in EN_STRINGS:
        m = re.search(rf'<string name="{name}">(.*?)</string>', base_txt, re.S)
        if not m:
            continue
        a, b = fmt_args(m.group(1)), fmt_args(EN_STRINGS[name])
        if a != b:
            fmt_bad.append(f"{name}: bazada {a}, inglizchada {b}")
    if fmt_bad:
        for f in fmt_bad:
            print(f"   ✗ {f}")
        raise SystemExit("✗ Format argumentlari mos emas — BEKOR")
    print(f"   ✓ {len(EN_STRINGS)} ta string: format argumentlari mos")

    # ───────────────────────────────────────────────────────────────
    #  P2: YANGI STRINGLAR
    # ───────────────────────────────────────────────────────────────
    print("\n  ── P2: Yangi stringlar (4 ta × 4 til) ──")
    new_counts = {}
    for loc, path in STRINGS.items():
        vals = {k: v[loc] for k, v in NEW_STRINGS.items()}
        have = set(string_names(path))
        new_counts[loc] = len([k for k in vals if k not in have])
        print(f"   {loc:11} qo'shiladi: {new_counts[loc]}/4")

    # ───────────────────────────────────────────────────────────────
    #  KOTLIN TAHRIRLARI
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Kotlin: Toast → resurs ──")
    edits = {}
    for path in {e[0] for e in TOAST_EDITS} | {HOME}:
        edits[path] = Edit(path)

    results = []
    for path, old, new, what, guard in TOAST_EDITS:
        e = edits[path]
        done = e.sub(old, new, what, guard=guard)
        results.append((what, done))

    # ── Bir xil matn bir necha joyda (count > 1) ──
    for path, old, new, what, guard, cnt in TOAST_MULTI:
        e = edits[path]
        done = e.sub_regex(old, new, what, guard=guard, expect=cnt)
        results.append((f"{what} (×{cnt})", done))

    # ── P1: System.gc() ──
    e_home = edits[HOME]
    gc_done = e_home.sub(GC_OLD, GC_NEW, "System.gc() olib tashlandi",
                         guard="`System.gc()` OLIB TASHLANDI")
    results.append(("P1: System.gc() olib tashlandi", gc_done))
    if not gc_done and re.search(r'System\.gc\(\)\s*;?\s*$', e_home.text, re.M):
        print("   ✗ System.gc() hanuz bor — BEKOR")
        return 1

    for what, done in results:
        mark = "✓ QO'SHILDI" if done else "– allaqachon bor"
        print(f"   {mark:16} {what}")

    # ───────────────────────────────────────────────────────────────
    #  TEKSHIRUVLAR
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Tekshiruvlar ──")

    for path, e in edits.items():
        if not e.changed():
            continue
        if not e.brace_ok():
            print(f"   ✗ {path.name}: qavs balansi buzildi — BEKOR")
            return 1
    print("   ✓ Kotlin qavs balanslari saqlandi")

    # ⚠️ Himoyalangan funksiyalar tegmaganligi
    home_txt = e_home.text
    protected = [
        ("crypt1://", "Crypt1Decoder.isCryptLink"),
        ("scroll-to-top", "scrollToPosition(0)"),
        ("Deep link", "checkPendingDeepLink"),
    ]
    for label, marker in protected:
        if marker not in home_txt:
            print(f"   ✗ HIMOYA BUZILDI: {label} ({marker}) — BEKOR")
            return 1
    print("   ✓ Himoyalangan funksiyalar joyida (crypt1, scroll, deeplink)")

    if "take(200)" in home_txt and "log" not in home_txt.lower():
        pass  # log truncation — normal

    # `System.gc()` butunlay yo'qolganini tasdiqlaymiz
    # ⚠️ Izohdagi matn emas, HAQIQIY chaqiruvni izlaymiz.
    gcall = re.compile(r'System\.gc\(\)\s*;?\s*$', re.M)
    for path, e in edits.items():
        if gcall.search(e.text):
            print(f"   ✗ {path.name}: System.gc() qoldi — BEKOR")
            return 1
    print("   ✓ `System.gc()` butunlay olib tashlandi")

    # ───────────────────────────────────────────────────────────────
    #  YOZISH
    # ───────────────────────────────────────────────────────────────
    total = sum(1 for _, d in results if d)
    total += len(en_missing)
    total += sum(new_counts.values())
    if AAR_BAK.exists():
        total += 1

    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        print("\n  ── Qo'shiladigan ingliz stringlari ──")
        for k in en_missing:
            print(f"     + {k}")
        return 0

    if total == 0:
        print("\n✓ Hammasi allaqachon qo'llangan")
        return 0

    print("\n  ── Yozish ──")

    # ★ FIX157-BUG: ZAXIRA YOZISHDAN OLDIN OLINADI.
    #   Avval `insert_strings()` faylni yozib bo'lgach zaxira olinardi
    #   → zaxira O'ZGARTIRILGAN (post-fix) holatni saqlardi va
    #   rollback ishlamasdi.
    def backup_first(path: Path, loc: str):
        """
        ★ Zaxira YOZISHDAN OLDIN olinadi.

        ⚠️ Fayl nomi TIL BILAN ajratiladi: `strings.xml.<loc>.<STAMP>.bak`.
        Aks holda 4 ta `strings.xml` (values, uz, ru, en) bir xil
        naqsh bo'lib, `latest_backup()` ENG YANGISINI qaytarardi va
        `values` o'rniga `en` faylini tiklab qo'yardi.
        """
        if path.exists():
            shutil.copy2(path, BACKUP_DIR / f"strings.xml.{loc}.{STAMP}.bak")

    # 1) Ingliz stringlari
    backup_first(en_path, "en")
    n = insert_strings(en_path, EN_STRINGS)
    if n:
        print(f"   💾 values-en/strings.xml  (+{n} string)")

    # 2) Yangi stringlar (barcha tillar)
    for loc, path in STRINGS.items():
        vals = {k: v[loc] for k, v in NEW_STRINGS.items()}
        backup_first(path, loc)
        m = insert_strings(path, vals)
        if m:
            print(f"   💾 {loc}/strings.xml  (+{m} string)")

    # 3) Kotlin
    for path, e in edits.items():
        if e.write():
            print(f"   💾 {path.relative_to(ROOT)}")

    # 4) libbox.aar.bak
    if AAR_BAK.exists():
        size = AAR_BAK.stat().st_size / 1024 / 1024
        AAR_BAK.unlink()
        print(f"   🗑  app/libs/libbox.aar.bak o'chirildi ({size:.0f} MB)")

    # ───────────────────────────────────────────────────────────────
    #  YAKUNIY VALIDATSIYA
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Yakuniy validatsiya ──")
    ok = True
    for loc, path in STRINGS.items():
        err = validate_xml(path)
        n = len(string_names(path))
        if err:
            print(f"   ✗ {loc}/strings.xml BUZILDI: {err}")
            ok = False
        else:
            print(f"   ✓ {loc}/strings.xml  ({n} string)")
    if not ok:
        return 1

    en_now = set(string_names(STRINGS["values-en"]))
    still = [k for k in EN_STRINGS if k not in en_now]
    if still:
        print(f"   ✗ {len(still)} ta string qo'shilmadi")
        return 1
    print("   ✓ Ingliz tili to'liq (0 ta yetishmaydi)")

    print("\n" + "═" * 76)
    print("  ✅ TAYOR")
    print("═" * 76)
    print("""
  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk

  ⚠️ TEGILMAGAN (ataylab):
     • item_home_card_header.xml — INCY UI
     • SingBoxConfig.kt          — Hysteria2
     • FinalMaskJson.kt          — FinalMask
     • DiffUtil refaktori        — release xavfi
""")


if __name__ == "__main__":
    sys.exit(main())
