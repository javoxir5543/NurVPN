#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX163-REMOVEWARP — WARP (MASQUE) generatorini XAVFSIZ o'chirish
════════════════════════════════════════════════════════════════════════════

 ────────────────────────────────────────────────────────────────────────
  NEGA BU SKRIPT BOSHQA YONDASHUV ISHLATADI
 ────────────────────────────────────────────────────────────────────────

 Oldingi urinishda regex bilan o'chirilganda `SettingsFragment.kt`
 BUZILGAN (qavslar noto'g'ri yopilgan, `Unresolved reference`).

 Sabab: regex **matn** bo'yicha ishlaydi, **kod tuzilishi** bo'yicha emas.
 U funksiya tanasini "ko'rmaydi" — shuning uchun ichki `{}` larni
 hisobga olmay, noto'g'ri joyda kesib qo'yadi.

 ★ BU SKRIPTNING YONDASHUVI:

   1. Matn bo'yicha emas — **belgi pozitsiyasi** bo'yicha ishlaydi
   2. Har bir o'chirishdan OLDIN **qavs balansini** hisoblaydi
   3. Faqat **aniq belgilangan bloklarni** o'chiradi (start/end marker)
   4. O'chirishdan KEYIN **butun fayl** qavs balansini qayta tekshiradi
   5. Agar balans buzilsa — **fayl YOZILMAYDI** (atomik)

 ────────────────────────────────────────────────────────────────────────
  O'CHIRILADI
 ────────────────────────────────────────────────────────────────────────

  `res/layout/fragment_settings.xml`
      • `warp_generate_btn` tugmasi (25-28 qatorlar)

  `ui/settings/SettingsFragment.kt`
      • `WarpGenerator.selftest()` Thread bloki
      • `warp_generate_btn` setOnClickListener bloki
      • `onWarpGenerated()` metodi (butunlay)

  Fayllar (o'chiriladi yoki `.fix-backups/` ga ko'chiriladi)
      • `util/WarpMasque.kt`
      • `util/WarpGenerator.kt`
      • `util/X25519.kt`   (faqat WARP uchun edi)

  Stringlar
      • 10 ta `warp_*` matni × 4 til

 ────────────────────────────────────────────────────────────────────────
  ⚠️ TEGILMAYDIGANLAR
 ────────────────────────────────────────────────────────────────────────

   ❌ `BuiltinAwgConfigs.kt` — MASQUE_1..11 (Kiberportal configlari)
   ❌ `AWGStore.kt` / `AWGParser.kt` — AWG saqlash
   ❌ Ochiq manbalar (`OpenSourceSubscriptions.kt`)
   ❌ Hysteria2, FinalMask, INCY UI, auto-update (FIX159/160)
   ❌ `X25519` boshqa joyda ishlatilsa — skript tekshiradi va SAQLAYDI

 ────────────────────────────────────────────────────────────────────────
  ISHLATISH
 ────────────────────────────────────────────────────────────────────────

     python3 tools/remove_warp_final.py --check
     python3 tools/remove_warp_final.py
     python3 tools/remove_warp_final.py --rollback
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
BACKUP_DIR = ROOT / ".fix-backups"

JAVA = ROOT / "app/src/main/java/com/nurvpn/app"
RES = ROOT / "app/src/main/res"

SETTINGS_KT = JAVA / "ui/settings/SettingsFragment.kt"
SETTINGS_XML = RES / "layout/fragment_settings.xml"

WARP_FILES = [
    JAVA / "util/WarpMasque.kt",
    JAVA / "util/WarpGenerator.kt",
    JAVA / "util/X25519.kt",
]

STRINGS = {loc: RES / loc / "strings.xml"
           for loc in ("values", "values-uz", "values-ru", "values-en")}

# `warp_*` stringlar (FIX161 da qo'shilgan)
WARP_STRINGS = [
    "warp_generate_btn", "warp_progress_title", "warp_progress_msg",
    "warp_success", "warp_already_exists", "warp_err_network",
    "warp_err_crypto", "warp_err_blocked", "warp_err_generic",
    "warp_err_no_activity",
]


# ═══════════════════════════════════════════════════════════════════════
#  XAVFSIZ O'CHIRISH YORDAMCHILARI
# ═══════════════════════════════════════════════════════════════════════

def find_block(text: str, start_marker: str, what: str) -> tuple:
    """
    `start_marker` dan boshlanadigan **KOTLIN BLOKNI** qavs balansi
    bo'yicha topadi.

    ★ NEGA BU XAVFSIZ:
       Regex `{` va `}` ni SANAMAYDI. Bu funksiya esa har bir belgini
       ko'zdan kechirib, ochilish/yopilish juftligini topadi. Ichki
       bloklar (`Thread { ... }`, `try { } catch { }`) to'g'ri hisobga
       olinadi.

    Qaytaradi: `(start_idx, end_idx)` — o'chiriladigan oraliq.
    """
    i = text.find(start_marker)
    if i < 0:
        raise SystemExit(f"✗ {what}: marker topilmadi:\n    {start_marker[:120]!r}")

    # Blokning ochilish qavsini topamiz
    j = text.find("{", i)
    if j < 0:
        raise SystemExit(f"✗ {what}: `{{` topilmadi")

    depth = 0
    k = j
    in_str = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    while k < len(text):
        c = text[k]
        nxt = text[k + 1] if k + 1 < len(text) else ""

        # ── izohlar ──
        if in_line_comment:
            if c == "\n":
                in_line_comment = False
            k += 1
            continue
        if in_block_comment:
            if c == "*" and nxt == "/":
                in_block_comment = False
                k += 2
                continue
            k += 1
            continue
        # ── satrlar ──
        if in_str:
            if c == "\\":
                k += 2
                continue
            if c == '"':
                in_str = False
            k += 1
            continue
        if in_char:
            if c == "\\":
                k += 2
                continue
            if c == "'":
                in_char = False
            k += 1
            continue

        # ── yangi tokenlar ──
        if c == "/" and nxt == "/":
            in_line_comment = True
            k += 2
            continue
        if c == "/" and nxt == "*":
            in_block_comment = True
            k += 2
            continue
        if c == '"':
            in_str = True
            k += 1
            continue
        if c == "'":
            in_char = True
            k += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return (i, k + 1)
        k += 1

    raise SystemExit(f"✗ {what}: yopiluvchi `}}` topilmadi — fayl buzuq?")


def drop_range(text: str, start: int, end: int) -> str:
    """Oraliqni o'chiradi va atrofdagi ortiqcha bo'sh qatorlarni tozalaydi."""
    # Orqaga — bo'sh qatorlarni ham olib tashlaymiz
    s = start
    while s > 0 and text[s - 1] in " \t":
        s -= 1
    while s > 0 and text[s - 1] == "\n":
        s -= 1
    # Oldinga — bitta yangi qatorni saqlaymiz
    e = end
    while e < len(text) and text[e] in " \t":
        e += 1
    if e < len(text) and text[e] == "\n":
        e += 1
    return text[:s] + "\n" + text[e:].lstrip("\n")


def check_balance(t: str) -> int:
    """Kotlin qavs balansi (izoh/satrlarni hisobga olgan holda)."""
    depth = 0
    in_str = in_char = in_line = in_blk = False
    k = 0
    while k < len(t):
        c = t[k]
        nxt = t[k + 1] if k + 1 < len(t) else ""
        if in_line:
            if c == "\n":
                in_line = False
            k += 1
            continue
        if in_blk:
            if c == "*" and nxt == "/":
                in_blk = False
                k += 2
                continue
            k += 1
            continue
        if in_str:
            if c == "\\":
                k += 2
                continue
            if c == '"':
                in_str = False
            k += 1
            continue
        if in_char:
            if c == "\\":
                k += 2
                continue
            if c == "'":
                in_char = False
            k += 1
            continue
        if c == "/" and nxt == "/":
            in_line = True
            k += 2
            continue
        if c == "/" and nxt == "*":
            in_blk = True
            k += 2
            continue
        if c == '"':
            in_str = True
        elif c == "'":
            in_char = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        k += 1
    return depth


class Edit:
    def __init__(self, path: Path):
        self.path = path
        self.orig = path.read_text(encoding="utf-8") if path.exists() else ""
        self.text = self.orig
        self.changes = []

    def write(self) -> bool:
        if self.text == self.orig:
            return False
        BACKUP_DIR.mkdir(exist_ok=True)
        shutil.copy2(self.path, BACKUP_DIR / f"{self.path.name}.{STAMP}.bak")
        self.path.write_text(self.text, encoding="utf-8")
        return True

    def rollback(self) -> bool:
        pats = [p for p in glob.glob(str(BACKUP_DIR / f"{self.path.name}.*.bak"))
                if re.fullmatch(rf"{re.escape(self.path.name)}\.\d{{8}}_\d{{6}}\.bak",
                                Path(p).name)]
        if not pats:
            return False
        bak = Path(max(pats, key=lambda p: Path(p).stat().st_mtime))
        cur = self.path.stat().st_size if self.path.exists() else 0
        if cur and bak.stat().st_size < cur * 0.5:
            print(f"   ✗ {self.path.name}: zaxira juda kichik — o'tkazildi")
            return False
        shutil.copy2(bak, self.path)
        return True


def remove_strings(path: Path, names: list) -> int:
    """`warp_*` stringlarni va ularning FIX161 izohini o'chiradi."""
    if not path.exists():
        return 0
    txt = path.read_text(encoding="utf-8")
    orig = txt
    for n in names:
        txt = re.sub(rf'\n?\s*<string name="{re.escape(n)}">.*?</string>',
                     "", txt, flags=re.S)
    # FIX161 izohini olib tashlaymiz (stringlar qolmasa)
    txt = re.sub(r'\n?\s*<!--\s*★?\s*FIX161-WARPGEN:.*?-->\n?', "\n", txt, flags=re.S)
    if txt == orig:
        return 0
    BACKUP_DIR.mkdir(exist_ok=True)
    shutil.copy2(path, BACKUP_DIR / f"strings.xml.{path.parent.name}.{STAMP}.bak")
    path.write_text(txt, encoding="utf-8")
    return len(orig) - len(txt)


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX163-REMOVEWARP")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 76)
    print("  FIX163-REMOVEWARP — WARP generatorini xavfsiz o'chirish")
    print("═" * 76)

    # ───────────────────────────────────────────────────────────────
    #  ROLLBACK
    # ───────────────────────────────────────────────────────────────
    if args.rollback:
        print("\n  ── ROLLBACK ──")
        n = 0
        for p in (SETTINGS_KT, SETTINGS_XML):
            if Edit(p).rollback():
                print(f"   ↩ {p.relative_to(ROOT)}")
                n += 1
        for loc, p in STRINGS.items():
            prefix = f"strings.xml.{loc}."
            pats = [q for q in glob.glob(str(BACKUP_DIR / "strings.xml.*.bak"))
                    if Path(q).name.startswith(prefix)]
            if pats:
                bak = Path(max(pats, key=lambda q: Path(q).stat().st_mtime))
                shutil.copy2(bak, p)
                print(f"   ↩ {loc}/strings.xml")
                n += 1
        # O'chirilgan fayllarni tiklash
        for f in WARP_FILES:
            pats = glob.glob(str(BACKUP_DIR / f"{f.name}.*.bak"))
            if pats:
                shutil.copy2(Path(max(pats, key=lambda q: Path(q).stat().st_mtime)), f)
                print(f"   ↩ {f.relative_to(ROOT)}")
                n += 1
        print(f"\n   {n} ta fayl tiklandi")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  OLDINDAN TEKSHIRUV
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Oldindan tekshiruv ──")
    for p, label in [(SETTINGS_KT, "SettingsFragment.kt"),
                     (SETTINGS_XML, "fragment_settings.xml")]:
        if not p.exists():
            print(f"   ✗ {label} topilmadi")
            return 1
        print(f"   ✓ {label}")

    # X25519 boshqa joyda ishlatilmasligini tekshiramiz
    print("\n  ── X25519 ishlatilishi ──")
    x25519_users = []
    for f in JAVA.rglob("*.kt"):
        if f.name == "X25519.kt":
            continue
        try:
            if "X25519" in f.read_text(encoding="utf-8"):
                x25519_users.append(f.relative_to(ROOT))
        except Exception:  # noqa: BLE001
            pass
    if x25519_users:
        print(f"   ⚠ X25519 boshqa fayllarda: {x25519_users}")
        print("   → X25519.kt O'CHIRILMAYDI (xavfsiz)")
    else:
        print("   ✓ X25519 faqat WARP uchun edi → o'chiriladi")

    # ───────────────────────────────────────────────────────────────
    #  TAHRIRLAR
    # ───────────────────────────────────────────────────────────────
    e_kt = Edit(SETTINGS_KT)
    e_xml = Edit(SETTINGS_XML)

    print("\n  ── SettingsFragment.kt ──")
    removed = []

    # 1) onWarpGenerated metodi (butunlay)
    try:
        s, en = find_block(e_kt.text, "private fun onWarpGenerated(", "onWarpGenerated")
        e_kt.text = drop_range(e_kt.text, s, en)
        removed.append("onWarpGenerated")
        print("   ✓ O'CHIRILDI  onWarpGenerated()")
    except SystemExit as ex:
        if "topilmadi" in str(ex):
            print("   – bor emas   onWarpGenerated()")
        else:
            raise

    # 2) selftest Thread bloki
    SELFTEST = ("        // ★ Bir marta RFC 7748 vektorlarini tekshiramiz")
    if SELFTEST in e_kt.text:
        s = e_kt.text.find(SELFTEST)
        en = e_kt.text.find(".start()", s)
        if en < 0:
            raise SystemExit("✗ selftest Thread: `.start()` topilmadi")
        en = e_kt.text.find("\n", en) + 1
        e_kt.text = drop_range(e_kt.text, s, en)
        removed.append("selftest Thread")
        print("   ✓ O'CHIRILDI  selftest Thread")
    else:
        print("   – bor emas   selftest Thread")

    # 3) warp_generate_btn setOnClickListener bloki
    try:
        s, en = find_block(e_kt.text, 'view.findViewById<android.widget.Button>(R.id.warp_generate_btn)',
                           "warp_generate_btn listener")
        # Boshidagi izohni ham olib tashlaymiz
        pre = e_kt.text.rfind("// ═══", 0, s)
        if pre >= 0 and s - pre < 900:
            s = pre
        e_kt.text = drop_range(e_kt.text, s, en)
        removed.append("warp_generate_btn listener")
        print("   ✓ O'CHIRILDI  warp_generate_btn listener")
    except SystemExit as ex:
        if "topilmadi" in str(ex):
            print("   – bor emas   warp_generate_btn listener")
        else:
            raise

    # 4) Import/qoldiq havolalar
    for pat, label in [
        (r'\n\s*import com\.nurvpn\.app\.util\.WarpGenerator\b', "WarpGenerator import"),
        (r'\n\s*import com\.nurvpn\.app\.util\.WarpMasque\b', "WarpMasque import"),
        (r'\n\s*import com\.nurvpn\.app\.util\.X25519\b', "X25519 import"),
    ]:
        if re.search(pat, e_kt.text):
            e_kt.text = re.sub(pat, "", e_kt.text)
            removed.append(label)
            print(f"   ✓ O'CHIRILDI  {label}")

    # 5) Qolgan havolalar bormi?
    leftovers = []
    for g in ("WarpGenerator", "WarpMasque", "X25519", "warp_generate_btn",
              "onWarpGenerated", "warp_success", "warp_err_", "warp_progress"):
        if g in e_kt.text:
            leftovers.append(g)
    if leftovers:
        print(f"   ⚠ Qolgan havolalar: {leftovers}")
    else:
        print("   ✓ Barcha WARP havolalari tozalandi")

    print("\n  ── fragment_settings.xml ──")
    m = re.search(
        r'\n\s*<!--\s*★?\s*FIX161:.*?-->\s*\n\s*<Button\s*\n\s*android:id="@\+id/warp_generate_btn".*?/>\s*\n',
        e_xml.text, re.S)
    if m:
        e_xml.text = e_xml.text[:m.start()] + "\n" + e_xml.text[m.end():]
        print("   ✓ O'CHIRILDI  warp_generate_btn tugmasi")
    else:
        # Izohsiz variant
        m2 = re.search(r'\n\s*<Button\s*\n\s*android:id="@\+id/warp_generate_btn".*?/>\s*\n',
                       e_xml.text, re.S)
        if m2:
            e_xml.text = e_xml.text[:m2.start()] + "\n" + e_xml.text[m2.end():]
            print("   ✓ O'CHIRILDI  warp_generate_btn tugmasi")
        else:
            print("   – bor emas   warp_generate_btn tugmasi")

    # ───────────────────────────────────────────────────────────────
    #  TEKSHIRUVLAR
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Tekshiruvlar ──")

    b_kt = check_balance(e_kt.text)
    if b_kt != 0:
        print(f"   ✗ SettingsFragment.kt qavs balansi = {b_kt} — BEKOR")
        return 1
    print("   ✓ SettingsFragment.kt qavs balansi = 0")

    if "warp_generate_btn" in e_xml.text:
        print("   ✗ XML da warp_generate_btn qoldi — BEKOR")
        return 1
    try:
        ET.fromstring(e_xml.text)
        print("   ✓ XML to'g'ri (parse muvaffaqiyatli)")
    except Exception as ex:  # noqa: BLE001
        print(f"   ✗ XML BUZILDI: {ex}")
        return 1

    # Himoyalangan kod joyidami?
    for g, label in [("setupOpenSources", "ochiq manbalar"),
                     ("open_source_container", "open_source_container"),
                     ("showDnsDialog", "DNS dialogi"),
                     ("runDeduplication", "deduplikatsiya")]:
        if g not in e_kt.text:
            print(f"   ✗ HIMOYA BUZILDI: {label} — BEKOR")
            return 1
    print("   ✓ Himoyalangan metodlar joyida")

    n_removed = len(removed)
    print(f"\n  Jami o'chirilgan blok: {n_removed}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  YOZISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Yozish ──")
    for e in (e_kt, e_xml):
        if e.write():
            print(f"   💾 {e.path.relative_to(ROOT)}")

    # Fayllarni o'chirish (zaxira bilan)
    print("\n  ── Fayllarni o'chirish ──")
    BACKUP_DIR.mkdir(exist_ok=True)
    for f in WARP_FILES:
        if not f.exists():
            print(f"   –  {f.name} allaqachon yo'q")
            continue
        if f.name == "X25519.kt" and x25519_users:
            print(f"   ⚠  {f.name} SAQLANDI (boshqa joyda ishlatiladi)")
            continue
        shutil.copy2(f, BACKUP_DIR / f"{f.name}.{STAMP}.bak")
        f.unlink()
        print(f"   🗑  {f.relative_to(ROOT)} (zaxira: .fix-backups/)")

    # Stringlarni o'chirish
    print("\n  ── Stringlar ──")
    for loc, path in STRINGS.items():
        n = remove_strings(path, WARP_STRINGS)
        if n:
            print(f"   💾 {loc}/strings.xml  (-{n} bayt)")

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
        cnt = len(re.findall(r'<string name=', path.read_text(encoding="utf-8")))
        left = sum(1 for s in WARP_STRINGS
                   if f'name="{s}"' in path.read_text(encoding="utf-8"))
        print(f"   ✓ {loc:11} {cnt} string, warp_* qoldi: {left}")

    counts = {loc: len(re.findall(r'<string name=', p.read_text(encoding="utf-8")))
              for loc, p in STRINGS.items()}
    if len(set(counts.values())) != 1:
        print(f"   ✗ Tillar sinxron emas: {counts}")
        return 1
    print(f"   ✓ 4 til sinxron ({counts['values']} string)")

    # MASQUE configlar tegmaganini tasdiqlaymiz
    builtin = JAVA / "config/BuiltinAwgConfigs.kt"
    if builtin.exists():
        bt = builtin.read_text(encoding="utf-8")
        ok = all(f"MASQUE_{i}" in bt for i in range(1, 12))
        print(f"   {'✓' if ok else '✗'} MASQUE_1..11 {'joyida' if ok else 'YO\'Q!'}")

    final = SETTINGS_KT.read_text(encoding="utf-8")
    final_bal = check_balance(final)
    print(f"   {'✓' if final_bal == 0 else '✗'} Yozilgan fayl qavs balansi = {final_bal}")

    print("\n" + "═" * 76)
    print("  ✅ TAYYOR — WARP generatori o'chirildi")
    print("═" * 76)
    print("""
  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Sozlamalar → Ochiq manbalar → MASQUE configlarini sinang

  ROLLBACK: python3 tools/remove_warp_final.py --rollback
""")


if __name__ == "__main__":
    sys.exit(main())
