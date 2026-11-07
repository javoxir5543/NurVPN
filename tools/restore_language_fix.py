#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX158-RESTORELANG — Buzilgan til (locale) tarjimalarini tiklash
════════════════════════════════════════════════════════════════════════════

 ═══ XATO TAHLILI ═══

 ── Belgi (symptom) ──
 Ilova telefon tilini aniqlamay qo'ydi — doim INGLIZ tilida ochiladi.
 Qurilma `ru-RU`, lekin interfeys inglizcha.

 ── Haqiqiy sabab: KOD EMAS, TARJIMA FAYLLARI ──
 `MainActivity.kt` dagi `LANG_AUTO_FIX_V113` mantig'i TO'G'RI ishlaydi:

     val saved = ThemeHelper.getLanguage(this)
     val targetLang = if (saved.isNotEmpty()) saved else {
         val sys = java.util.Locale.getDefault().language
         if (sys == "uz" || sys == "ru" || sys == "en") sys else "en"
     }
     AppCompatDelegate.setApplicationLocales(
         LocaleListCompat.forLanguageTags(targetLang))

 UZ→UZ, RU→RU, EN→EN, boshqa→EN mantig'i TO'G'RI.
 Qo'lda tanlangan til ham TO'G'RI ustunlik qiladi.

 ── ASL MUAMMO: 4 ta `strings.xml` fayl BIR XIL bo'lib qolgan ──

     $ md5sum app/src/main/res/values*/strings.xml
     8a9b9350628d61d3a20e6fa7cb7f584a  values/strings.xml
     8a9b9350628d61d3a20e6fa7cb7f584a  values-uz/strings.xml
     8a9b9350628d61d3a20e6fa7cb7f584a  values-ru/strings.xml
     8a9b9350628d61d3a20e6fa7cb7f584a  values-en/strings.xml
                        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                        TO'RT FAYL HAM BIR XIL (MD5)

     $ ls --time-style=full-iso app/src/main/res/values*/strings.xml
     2026-11-07 23:59:41.021130564  values-en/strings.xml
     2026-11-07 23:59:41.021130564  values-ru/strings.xml
     2026-11-07 23:59:41.021130564  values/strings.xml
     2026-11-07 23:59:41.021130564  values-uz/strings.xml
     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ — AYNAN BIR XIL VAQT (nanosekundgacha)

 Ya'ni BIR operatsiyada to'rtta faylning HAMMASI inglizcha matn bilan
 qayta yozilgan. Natijada:

     values-uz/strings.xml:  <string name="nav_home">Home</string>      ← "Asosiy" edi
     values-ru/strings.xml:  <string name="nav_home">Home</string>      ← "Главная" edi
     values/strings.xml:     <string name="nav_home">Home</string>

 Android resurs tanlash mantig'i:
     • Qurilma RU → `values-ru` dan "Home" oladi (inglizcha!)
     • Qurilma UZ → `values-uz` dan "Home" oladi (inglizcha!)
     • Boshqa    → `values` dan "Home" oladi

 Shu sababli ilova "doim inglizcha" bo'lib ko'rinadi — aslida u
 TO'G'RI faylni tanlayapti, lekin o'sha fayllar ichi inglizcha.

 ── FIX157 BUNGA ALOQASI YO'Q ──
 FIX157 faqat 5 ta Kotlin faylga + `strings.xml` larga YANGI string
 QO'SHGAN (`<string name=...>` qatorlar). U mavjud tarjimalarni
 O'CHIRMAGAN. Buzilish vaqti `23:59:41` — FIX157 ning birinchi
 ishga tushishidan keyin, lekin boshqa jarayon tufayli.

 ═══ TIKLASH MANBASI ═══

 `.fix-backups/main__res__values-*/strings.xml.*.bak` (2026-11-05)
fayllarida HAQIQIY tarjimalar saqlanib qolgan:

     values-ru: 5452 kirill belgisi, 345 tarjima  (Главная, Добавить сервер…)
     values-uz:  322 tarjima                      (Asosiy, Server qo'shish…)
     values:     297 tarjima

 Tekshirildi: bu zaxiralarda joriy faylda YO'Q string YO'Q
 (`faqat-zaxirada = 0`), ya'ni tiklash YO'QOTISHGA olib kelmaydi —
 FIX157 ning 12 ta yangi stringi joriy holatdan saqlanadi.

 ═══ STRATEGIYA (yo'qotishsiz) ═══

   1. Zaxiradan tarjimalarni o'qiymiz
   2. Joriy fayldagi har bir string uchun:
        • zaxirada tarjimasi BO'LSA va inglizchadan farq qilsa → zaxira qiymati
        • zaxirada YO'Q bo'lsa (FIX157 ning yangi stringlari) → joriy qiymat
   3. `values-en` TEGILMAYDI (u inglizcha manba)

 ═══ ★★ HIMOYA: TEGILMAYDIGAN FUNKSIYALAR ★★ ═══

   ❌ Kotlin fayllari — UMUMAN tegilmaydi (0 ta)
   ❌ `item_home_card_header.xml` — INCY UI
   ❌ `SingBoxConfig.kt`          — Hysteria2
   ❌ `FinalMaskJson.kt`          — FinalMask
   ❌ `values-en`                 — inglizcha manba
   ❌ `DiffUtil`                  — refaktor qilinmaydi

 ═══ ISHLATISH ═══

     python3 tools/restore_language_fix.py --check
     python3 tools/restore_language_fix.py
     python3 tools/restore_language_fix.py --rollback
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

RES = ROOT / "app/src/main/res"

# ⚠️ `values-en` ATAYLAB YO'Q — u inglizcha MANBA, tegilmaydi.
TARGETS = {
    "values":    RES / "values/strings.xml",
    "values-uz": RES / "values-uz/strings.xml",
    "values-ru": RES / "values-ru/strings.xml",
}

# ── Kirill belgisi bo'lsa — tarjima bor deb hisoblaymiz ──
CYRILLIC = re.compile(r'[А-Яа-яЁё]')

# ── Test uchun majburiy tarjimalar (tekshiruvda ishlatiladi) ──
#    (kalit, til, kutilgan_qiymat)
SMOKE = [
    ("nav_home",    "values-ru", "Главная"),
    ("nav_servers", "values-ru", "Серверы"),
    ("nav_home",    "values-uz", "Asosiy"),
    ("settings_language", "values-ru", "Язык"),
    ("settings_language", "values-uz", "Til"),
]


# ═══════════════════════════════════════════════════════════════════════
#  STRING O'QISH / YOZISH
# ═══════════════════════════════════════════════════════════════════════

def parse_strings(path: Path):
    """
    `{nom: (qiymat, izoh_oldi)}` qaytaradi — TARTIB saqlanadi.
    `re.finditer` ishlatamiz, chunki ElementTree izohlarni yo'qotadi.
    """
    txt = path.read_text(encoding="utf-8")
    out = {}
    for m in re.finditer(
            r'([ \t]*)(<string\s+name="([^"]+)"[^>]*>)(.*?)(</string>)',
            txt, re.S):
        # ichidagi boshqa izohlarni olib tashlaymiz
        out[m.group(3)] = (m.group(4), m.group(1))
    return out


def backup_path(src: Path) -> Path:
    BACKUP_DIR.mkdir(exist_ok=True)
    return BACKUP_DIR / f"{src.name}.{STAMP}.bak"


def latest_backup(src: Path):
    """
    ⚠️ ENG YANGI zaxira — ALIFBO emas, VAQT bo'yicha.

    ⚠️ `values` va `values-en` ikkalasi ham `strings.xml` deb nomlanadi.
    Shuning uchun faqat `strings.xml.<STAMP>.bak` (til belgisisiz)
    nomlarni olamiz — FIX157 ning `strings.xml.values.*` larini
    aralashtirmaymiz.
    """
    pats = glob.glob(str(BACKUP_DIR / f"{src.name}.*.bak"))
    # faqat shu skript yozgan nomlar: strings.xml.<8 raqam>_<6 raqam>.bak
    pats = [p for p in pats
            if re.fullmatch(r"strings\.xml\.\d{8}_\d{6}\.bak", Path(p).name)]
    if not pats:
        return None
    return Path(max(pats, key=lambda p: Path(p).stat().st_mtime))


def find_source(loc: str):
    """
    HAQIQIY tarjimali eng yaxshi zaxirani topadi.

    Nom namunasi: `main__res__values-ru__strings.xml.<STAMP>.bak`
    """
    pats = glob.glob(str(BACKUP_DIR / f"main__res__{loc}__strings.xml.*.bak"))
    if not pats:
        return None
    # eng ko'p tarjimasi (kirill / inglizchadan farqli) borini olamiz
    def score(p):
        t = Path(p).read_text(encoding="utf-8")
        return (len(CYRILLIC.findall(t)), len(parse_strings(Path(p))))
    return Path(max(pats, key=score))


# ═══════════════════════════════════════════════════════════════════════
#  ASOSIY
# ═══════════════════════════════════════════════════════════════════════

def main() -> int:
    ap = argparse.ArgumentParser(description="FIX158-RESTORELANG")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 76)
    print("  FIX158-RESTORELANG — til tarjimalarini tiklash")
    print("═" * 76)

    # ───────────────────────────────────────────────────────────────
    #  ROLLBACK
    # ───────────────────────────────────────────────────────────────
    if args.rollback:
        print("\n  ── ROLLBACK ──")
        n = 0
        for loc, path in TARGETS.items():
            bak = latest_backup(path)
            if not bak:
                print(f"   – {loc}: zaxira topilmadi")
                continue
            # ⚠️ Xavfsizlik: zaxira joriy fayldan ancha kichik bo'lmasin
            cur = path.stat().st_size if path.exists() else 0
            if cur and bak.stat().st_size < cur * 0.5:
                print(f"   ✗ {loc}: zaxira juda kichik — o'tkazib yuborildi")
                continue
            shutil.copy2(bak, path)
            print(f"   ↩ {loc}/strings.xml   ({bak.name})")
            n += 1
        print(f"\n   {n} ta fayl tiklandi")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  HOLATNI TEKSHIRISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Hozirgi holat ──")
    md5 = {}
    for loc, path in TARGETS.items():
        if not path.exists():
            print(f"   ✗ {loc}: fayl yo'q")
            return 1
        d = parse_strings(path)
        md5[loc] = __import__("hashlib").md5(path.read_bytes()).hexdigest()
        print(f"   {loc:11} {len(d):4d} string, kirill={len(CYRILLIC.findall(path.read_text(encoding='utf-8'))):5d}")

    all_same = len(set(md5.values())) == 1
    if all_same:
        print(f"\n   ⚠️  BARCHA fayllar bir xil (MD5={list(md5.values())[0][:12]}…)")
        print("       — tarjimalar yo'qolgan, tiklash KERAK")
    else:
        print("\n   ✓ Fayllar har xil — tarjimalar joyida ko'rinadi")

    # ───────────────────────────────────────────────────────────────
    #  MANBANI TOPISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Tiklash manbasi ──")
    sources = {}
    for loc in TARGETS:
        src = find_source(loc)
        if not src:
            print(f"   ✗ {loc}: HAQIQIY tarjimali zaxira TOPILMADI")
            return 1
        st = parse_strings(src)
        cyr = len(CYRILLIC.findall(src.read_text(encoding="utf-8")))
        sources[loc] = (src, st)
        print(f"   {loc:11} ← {src.name}")
        print(f"   {'':11}   {len(st)} string, kirill={cyr}")

    # ───────────────────────────────────────────────────────────────
    #  REJA: har string uchun manba tanlash
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Reja (yo'qotishsiz birlashtirish) ──")
    plan = {}
    for loc, path in TARGETS.items():
        cur = parse_strings(path)
        src_path, src = sources[loc]
        merged = {}
        restored = kept = 0
        for name, (val, indent) in cur.items():
            if name in src and src[name][0] != val:
                merged[name] = (src[name][0], indent)
                restored += 1
            else:
                merged[name] = (val, indent)
                kept += 1
        plan[loc] = merged
        print(f"   {loc:11} tiklanadi={restored:4d}  o'zgarishsiz={kept:4d}  jami={len(merged)}")

    # Xavfsizlik: manbada yo'q stringlar saqlanishi shart
    for loc, path in TARGETS.items():
        cur = parse_strings(path)
        missing = set(cur) - set(plan[loc])
        if missing:
            print(f"   ✗ {loc}: {len(missing)} string yo'qoladi — BEKOR")
            return 1
    print("   ✓ Hech bir string yo'qolmaydi")

    if args.check:
        print("\n  ── Namunali tiklanishlar ──")
        for loc, path in TARGETS.items():
            cur = parse_strings(path)
            src_path, src = sources[loc]
            shown = 0
            for name, (val, _) in cur.items():
                if name in src and src[name][0] != val and shown < 5:
                    print(f"   {loc:11} {name}")
                    print(f"   {'':11}   EN : {val[:52]!r}")
                    print(f"   {'':11}   →  : {src[name][0][:52]!r}")
                    shown += 1
        total = sum(1 for loc in plan for n, (v, _) in plan[loc].items()
                    if sources[loc][1].get(n, (v,))[0] != v)
        print(f"\n  Jami tiklanadigan string: {total}")
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  YOZISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Yozish ──")
    for loc, path in TARGETS.items():
        txt = path.read_text(encoding="utf-8")
        cur = parse_strings(path)
        merged = plan[loc]
        n = 0
        for name, (val, indent) in merged.items():
            old_val = cur[name][0]
            if val == old_val:
                continue
            # ⚠️ Faqat SHU stringning ichini almashtiramiz
            pat = re.compile(
                r'(<string\s+name="' + re.escape(name) + r'"[^>]*>)(.*?)(</string>)',
                re.S)
            new_txt, cnt = pat.subn(lambda m: m.group(1) + val + m.group(3), txt, count=1)
            if cnt == 1:
                txt = new_txt
                n += 1
        if n:
            shutil.copy2(path, backup_path(path))     # ★ YOZISHDAN OLDIN
            path.write_text(txt, encoding="utf-8")
            print(f"   💾 {loc}/strings.xml   ({n} string tiklandi)")
        else:
            print(f"   –  {loc}/strings.xml   (o'zgarish yo'q)")

    # ───────────────────────────────────────────────────────────────
    #  YAKUNIY VALIDATSIYA
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Yakuniy validatsiya ──")
    ok = True
    for loc, path in TARGETS.items():
        try:
            ET.parse(str(path))
        except Exception as ex:  # noqa: BLE001
            print(f"   ✗ {loc}: XML BUZILDI — {ex}")
            ok = False
            continue
        d = parse_strings(path)
        cyr = len(CYRILLIC.findall(path.read_text(encoding="utf-8")))
        print(f"   ✓ {loc:11} {len(d):4d} string, kirill={cyr:5d}")

    if not ok:
        return 1

    # ── Majburiy tarjimalar joyidami? ──
    print("\n  ── Majburiy tarjima tekshiruvi ──")
    bad = []
    for name, loc, expect in SMOKE:
        d = parse_strings(TARGETS[loc])
        got = d.get(name, ("", ""))[0]
        mark = "✓" if got == expect else "✗"
        if got != expect:
            bad.append(f"{loc}.{name}: kutilgan {expect!r}, topildi {got!r}")
        print(f"   {mark} {loc:11} {name:20} = {got[:34]!r}")
    if bad:
        for b in bad:
            print(f"   ✗ {b}")
        print("\n   ⚠️ Ba'zi tarjimalar mos emas")
        return 1

    # ── Fayllar endi har xilmi? ──
    import hashlib
    h = {loc: hashlib.md5(p.read_bytes()).hexdigest() for loc, p in TARGETS.items()}
    if len(set(h.values())) == 1:
        print("\n   ✗ Fayllar HANuz bir xil — tiklash ishlamadi")
        return 1
    print("\n   ✓ Fayllar endi har xil (tarjimalar tiklandi)")

    # ── `values-en` TEGILMAGANINI tasdiqlaymiz ──
    en = RES / "values-en/strings.xml"
    if en.exists():
        print(f"   ✓ values-en tegilmadi ({len(parse_strings(en))} string)")

    print("\n" + "═" * 76)
    print("  ✅ TIL TARJIMALARI TIKLANDI")
    print("═" * 76)
    print("""
  TEKSHIRISH MANTIG'I:
      Telefon tili UZ -> values-uz  (Asosiy, Server qo'shish…)
      Telefon tili RU -> values-ru  (Главная, Добавить сервер…)
      Telefon tili EN -> values-en  (Home, Add server…)
      Boshqa til      -> values-en  (Android standart fallback)

      Qo'lda tanlangan til (Sozlamalar) `lang` pref orqali
      ustunlik qiladi — bu mantiq TEGILMAGAN.

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Sozlamalar -> Til -> "System language" ni tanlang va
        telefon tilini o'zgartirib tekshiring
""")


if __name__ == "__main__":
    sys.exit(main())
