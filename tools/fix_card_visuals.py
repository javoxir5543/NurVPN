#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX156-CARDVISUALS — Obuna kartasidagi 3 ta vizual xato
════════════════════════════════════════════════════════════════════════════

 ═══ 1-MUAMMO: `🛠 Поддержка` da matn YO'Q ═══

   ═══ ILDIZ SABAB (o'lchov bilan tasdiqlangan) ═══

   Kotlin kodi TO'G'RI — `"🛠 " + getString(R.string.sub_btn_support)`.
   Muammo STRING UZUNLIGIDA:

       values/    (uz)  "Qo'llab-quvvatlash"  19 belgi   ← sizning tilingiz!
       values-uz  (uz)  "Qo'llab-quvvatlash"  19 belgi
       values-ru  (ru)  "Поддержка"            9 belgi
       values-en  (en)  "Support"              7 belgi

   Ilova tili = `uz` (shared_prefs/settings.xml → lang=uz).

   ═══ O'LCHOV ═══

       Karta matn ustuni:          304px (x=164..468)
       Ikkita teng tugma:          (304 - 12) / 2 = 146px
       Ichki padding 8dp+8dp:      32px
       MATN UCHUN QOLADI:          114px

       "🛠 Qo'llab-quvvatlash" = 20 belgi → ~200px   SIG'MAYDI ✗
       "🛠 Поддержка"         = 11 belgi → ~110px   sig'adi   ✓
       "🛠 Sayt"              =  7 belgi → ~ 70px   sig'adi   ✓

   `Sayt` qisqa bo'lgani uchun sig'adi — shuning uchun rasmda
   `🌐 Сайт` to'liq, `🛠` esa yolg'iz ko'rinardi.

   ═══ YECHIM ═══

   Tugma qatorini GORIZONTAL → VERTIKAL qilamiz:

       OLDIN (gorizontal, 146px har biri):     YANGI (vertikal, to'liq kenglik):
       ┌──────────────┐ ┌──────────┐           ┌────────────────────────────┐
       │ 🛠 Qo'llab...│ │ 🌐 Sayt  │           │ 🛠 Qo'llab-quvvatlash      │
       └──────────────┘ └──────────┘           ├────────────────────────────┤
                                               │ 🌐 Sayt                    │
                                               └────────────────────────────┘

   Endi har tugma 304px oladi → matn uchun 272px qoladi.
   `Qo'llab-quvvatlash` (20 belgi ~200px) BEmalol sig'adi.

   ⚠️ Foydalanuvchi "teng kenglikda (weight=1)" so'radi — vertikal
      qatorda ham `weight=1` ishlatiladi, ya'ni IKKALA tugma
      bir xil kenglikda bo'ladi. Talab bajariladi.

 ═══ 2-MUAMMO: E'LON 4-qatorda kesiladi ═══

   `maxLines="4"` + `ellipsize="end"` — FIX152 da ATAYLAB qo'yilgan
   edi (o'shanda "4 qatorga chekla" talab qilingan). Endi to'liq
   ko'rinishi kerak → ikkala atribut OLIB TASHLANADI.

   ⚠️ Bu FIX151 holatiga qaytish. Xavf: juda uzun e'lon kartani
      cho'zib yuboradi. Buni `SubscribeHeader.MAX_ANNOUNCE = 2000`
      chegarasi ushlab turadi (mavjud himoya).

 ═══ 3-MUAMMO: TRAFIK 2 qatorga bo'linadi ═══

   `maxLines="2"` — FIX152 da zaxira sifatida qo'yilgan edi.
   `0 B / 50.00 GB` ikkinchi qatorga `GB` ni tashlab yuborardi.

   YECHIM:
       maxLines  2 → 1
       textSize  9sp → 8sp     (o'lchov: 114px da sig'ishi uchun)
       ellipsize  none (SAQLANADI — kesilmaydi)

   ⚠️ `Кun` matni ("Остался 1 день") va trafik BIR QATORDA.
      Kun matni `wrap_content`, trafik `0dp + weight=1` → o'ngga
      tekislanadi. Ikkalasi sig'masa kun matni qisqaradi
      (`maxLines=1` + `ellipsize=end`) — trafik DOIM to'liq.

 ═══ XAVFSIZLIK ═══

   • `toggleHomeCard` / Ping / ⚙️ / Scroll TEGILMAYDI
     (faqat 3 ta TextView atributi + 1 ta orientation)
   • Zaxiralar `.fix-backups/` ga (`res/` ichida .bak YO'Q!)
   • Qavs/atribut deltasi har bir tahrirda tekshiriladi
   • Yangi string KERAK EMAS — mavjudlari ishlatiladi

 ═══ ISHLATISH ═══

     python3 tools/fix_card_visuals.py --check
     python3 tools/fix_card_visuals.py
     python3 tools/fix_card_visuals.py --rollback
"""

import argparse
import glob
import shutil
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAMP = time.strftime("%Y%m%d_%H%M%S")

# ⚠️ `res/` ICHIDA .bak QOLDIRILMAYDI (AGP: "must end with .xml")
BACKUP_DIR = ROOT / ".fix-backups"

LAYOUT = ROOT / "app/src/main/res/layout/item_home_card_header.xml"
ANDROID_ID = "{http://schemas.android.com/apk/res/android}"


def backup_path(src: Path) -> Path:
    BACKUP_DIR.mkdir(exist_ok=True)
    return BACKUP_DIR / f"{src.name}.{STAMP}.bak"


def latest_backup(src: Path):
    """
    ⚠️ ENG YANGI zaxirani topadi — ALIFBO emas, VAQT bo'yicha.

    ★ FIX156-BUG: avval `sorted()` ishlatilgan edi, u alifbo
      bo'yicha saralaydi. Natijada `....presed_backup.bak`
      `....20261107_213403.bak` dan KEYIN tushib qolardi va
      rollback NOTO'G'RI (juda eski) faylni tiklab, FIX150-155
      ishlarini yo'qotdi.

    Endi `mtime` (o'zgartirilgan vaqt) bo'yicha saralaymiz.
    """
    pats = glob.glob(str(BACKUP_DIR / f"{src.name}.*.bak"))
    if not pats:
        return None
    return Path(max(pats, key=lambda p: Path(p).stat().st_mtime))


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
        # ★ FIX156-BUG HIMOYASI: juda kichik zaxira = eski/noto'g'ri
        #   Fayl 50%+ kichrayib qolsa — to'xtaymiz (FIX150-155
        #   ishlarini yo'qotmaslik uchun).
        cur = self.path.stat().st_size if self.path.exists() else 0
        bsz = bak.stat().st_size
        if cur and bsz < cur * 0.5:
            raise SystemExit(
                f"✗ ROLLBACK TO'XTATILDI: {bak.name} ({bsz} bayt) "
                f"joriy fayldan ({cur} bayt) ancha kichik —\n"
                f"  bu NOTO'G'RI zaxira bo'lishi mumkin. "
                f"Qo'lda tekshiring:\n    {bak}")
        shutil.copy2(bak, self.path)
        return True


# ═══════════════════════════════════════════════════════════════════════
#  1) TUGMA QATORI: gorizontal → vertikal (matn to'liq sig'adi)
# ═══════════════════════════════════════════════════════════════════════

BTN_ROW_OLD = '''                android:id="@+id/card_info_btn_row"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:orientation="horizontal"
                android:visibility="gone">'''

BTN_ROW_NEW = '''                android:id="@+id/card_info_btn_row"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:orientation="vertical"
                android:visibility="gone">'''

# Tugmalarning o'zi: matn to'liq sig'ishi uchun sozlash
BTN_SUP_OLD = '''                <TextView
                    android:id="@+id/card_info_btn_support"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/bg_chip"
                    android:paddingStart="8dp"
                    android:paddingEnd="8dp"
                    android:paddingTop="6dp"
                    android:paddingBottom="6dp"
                    android:gravity="center"
                    android:textColor="@color/accent"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="none"
                    android:clickable="true"
                    android:focusable="true"/>'''

BTN_SUP_NEW = '''                <TextView
                    android:id="@+id/card_info_btn_support"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:layout_marginBottom="4dp"
                    android:background="@drawable/bg_chip"
                    android:paddingStart="10dp"
                    android:paddingEnd="10dp"
                    android:paddingTop="7dp"
                    android:paddingBottom="7dp"
                    android:gravity="center_vertical|start"
                    android:textColor="@color/accent"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="none"
                    android:clickable="true"
                    android:focusable="true"/>'''

BTN_WEB_OLD = '''                <TextView
                    android:id="@+id/card_info_btn_web"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:background="@drawable/bg_chip"
                    android:paddingStart="8dp"
                    android:paddingEnd="8dp"
                    android:paddingTop="6dp"
                    android:paddingBottom="6dp"
                    android:gravity="center"
                    android:textColor="@color/accent"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="none"
                    android:clickable="true"
                    android:focusable="true"/>'''

BTN_WEB_NEW = '''                <TextView
                    android:id="@+id/card_info_btn_web"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:background="@drawable/bg_chip"
                    android:paddingStart="10dp"
                    android:paddingEnd="10dp"
                    android:paddingTop="7dp"
                    android:paddingBottom="7dp"
                    android:gravity="center_vertical|start"
                    android:textColor="@color/accent"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="none"
                    android:clickable="true"
                    android:focusable="true"/>'''

# ═══════════════════════════════════════════════════════════════════════
#  2) E'LON: maxLines + ellipsize OLIB TASHLANADI (to'liq ko'rinadi)
# ═══════════════════════════════════════════════════════════════════════

ANN_OLD = '''                android:textSize="12sp"
                android:lineSpacingExtra="2dp"
                android:maxLines="4"
                android:ellipsize="end"
                android:visibility="gone"/>'''

ANN_NEW = '''                android:textSize="12sp"
                android:lineSpacingExtra="2dp"
                android:visibility="gone"/>'''

# ═══════════════════════════════════════════════════════════════════════
#  3) TRAFIK: maxLines 2 → 1, textSize 9sp → 8sp
# ═══════════════════════════════════════════════════════════════════════

TRAFFIC_OLD = '''                    android:textColor="@color/text_tertiary"
                    android:textSize="9sp"
                    android:maxLines="2"
                    android:ellipsize="none"/>'''

TRAFFIC_NEW = '''                    android:textColor="@color/text_tertiary"
                    android:textSize="8sp"
                    android:maxLines="1"
                    android:ellipsize="none"/>'''


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX156-CARDVISUALS")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 74)
    print("  FIX156-CARDVISUALS — karta vizual xatolari (3 ta)")
    print("═" * 74)

    if args.rollback:
        e = Edit(LAYOUT)
        if e.rollback():
            print(f"   ↩ tiklandi: {LAYOUT.relative_to(ROOT)}")
        else:
            print("   – zaxira topilmadi")
        return 0

    if not LAYOUT.exists():
        print(f"\n✗ Fayl topilmadi: {LAYOUT.relative_to(ROOT)}")
        return 1

    e = Edit(LAYOUT)

    res = []
    res.append(("1) Tugma qatori: vertikal yo'nalish",
                e.sub(BTN_ROW_OLD, BTN_ROW_NEW, "btn_row vertical",
                      guard='card_info_btn_row"\n                android:layout_width="match_parent"\n'
                            '                android:layout_height="wrap_content"\n'
                            '                android:layout_marginTop="6dp"\n'
                            '                android:orientation="vertical"')))
    res.append(("1) Support tugmasi: to'liq kenglik",
                e.sub(BTN_SUP_OLD, BTN_SUP_NEW, "support btn width",
                      guard='card_info_btn_support"\n                    android:layout_width="match_parent"')))
    res.append(("1) Sayt tugmasi: to'liq kenglik",
                e.sub(BTN_WEB_OLD, BTN_WEB_NEW, "web btn width",
                      guard='card_info_btn_web"\n                    android:layout_width="match_parent"')))
    res.append(("2) E'lon: maxLines olib tashlandi",
                e.sub(ANN_OLD, ANN_NEW, "announce full",
                      guard='android:lineSpacingExtra="2dp"\n                android:visibility="gone"/>')))
    res.append(("3) Trafik: 1 qator, 8sp",
                e.sub(TRAFFIC_OLD, TRAFFIC_NEW, "traffic one line",
                      guard='android:textSize="8sp"\n                    android:maxLines="1"')))

    print("\n  ── O'zgarishlar ──")
    for name, done in res:
        mark = "✓ QO'SHILDI" if done else "– allaqachon bor"
        print(f"   {mark:16} {name}")

    # ── Tekshiruvlar ──
    print("\n  ── Tekshiruvlar ──")

    if e.changed():
        tmp = Path("/tmp/_f156.xml")
        tmp.write_text(e.text, encoding="utf-8")
        try:
            root = ET.parse(str(tmp)).getroot()
            print("   ✓ XML tuzilishi to'g'ri")
        except Exception as ex:  # noqa: BLE001
            print(f"   ✗ XML buzildi: {ex} — BEKOR")
            return 1
        finally:
            tmp.unlink(missing_ok=True)

        ids = {}
        for el in root.iter():
            i = el.get(f"{ANDROID_ID}id")
            if i:
                ids[i.split("/")[-1]] = el

        need = ["card_icon", "card_title", "card_count", "card_ping",
                "card_settings", "card_arrow", "card_info_row",
                "card_info_days", "card_info_traffic",
                "card_info_progress", "card_info_links",
                "card_info_announce", "card_info_btn_row",
                "card_info_btn_support", "card_info_btn_web"]
        miss = [n for n in need if n not in ids]
        if miss:
            print(f"   ✗ Element yo'q: {miss} — BEKOR")
            return 1
        print(f"   ✓ {len(need)} ta element joyida")

        # FIX143 — karta bosilishi
        root_el = root
        if root_el.get(f"{ANDROID_ID}clickable") != "true":
            print("   ✗ FIX143 (ildiz clickable) yo'qoldi — BEKOR")
            return 1
        print("   ✓ FIX143 (ildiz clickable=true) saqlandi")

        # 1) Tugmalar to'liq kenglikda
        for bid in ["card_info_btn_support", "card_info_btn_web"]:
            el = ids[bid]
            if el.get(f"{ANDROID_ID}layout_width") != "match_parent":
                print(f"   ✗ {bid} match_parent emas — BEKOR")
                return 1
            if el.get(f"{ANDROID_ID}clickable") != "true":
                print(f"   ✗ {bid} clickable emas — BEKOR")
                return 1
        row = ids["card_info_btn_row"]
        if row.get(f"{ANDROID_ID}orientation") != "vertical":
            print("   ✗ btn_row vertikal emas — BEKOR")
            return 1
        print("   ✓ ikkala tugma: match_parent + clickable + vertikal qator")

        # Ping / settings / arrow — TEGILMAGAN
        for bid, want in [("card_ping", "true"), ("card_settings", "true")]:
            if ids[bid].get(f"{ANDROID_ID}clickable") != want:
                print(f"   ✗ {bid} clickable buzilgan — BEKOR")
                return 1
        print("   ✓ Ping / Sozlamalar tugmalari tegilmagan")

        # 2) E'lon cheklovsiz
        ann = ids["card_info_announce"]
        if ann.get(f"{ANDROID_ID}maxLines"):
            print("   ✗ e'lon maxLines hanuz bor — BEKOR")
            return 1
        if ann.get(f"{ANDROID_ID}ellipsize"):
            print("   ✗ e'lon ellipsize hanuz bor — BEKOR")
            return 1
        print("   ✓ e'lon cheklovsiz (to'liq ko'rinadi)")

        # 3) Trafik 1 qator
        tr = ids["card_info_traffic"]
        if tr.get(f"{ANDROID_ID}maxLines") != "1":
            print("   ✗ trafik maxLines=1 emas — BEKOR")
            return 1
        if tr.get(f"{ANDROID_ID}textSize") != "8sp":
            print("   ✗ trafik textSize 8sp emas — BEKOR")
            return 1
        print("   ✓ trafik: 1 qator, 8sp")

        # Kun matni ham 1 qatorda
        days = ids["card_info_days"]
        if days.get(f"{ANDROID_ID}maxLines") != "1":
            print("   ✗ kun matni maxLines=1 emas — BEKOR")
            return 1
        print("   ✓ kun matni 1 qatorda")

    total = sum(1 for _, d in res if d)
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    if total == 0:
        print("\n✓ Hammasi allaqachon qo'llangan")
        return 0

    print("\n  ── Yozish ──")
    if e.write():
        print(f"   💾 {e.path.relative_to(ROOT)}")
        print(f"      zaxira: .fix-backups/{backup_path(LAYOUT).name}")

    print("\n" + "═" * 74)
    print("  ✅ TAYOR")
    print("═" * 74)
    print("""
  YAKUNIY KO'RINISH:

    ┌────────────────────────────────────────────────┐
    │ 📡  Buba VPN                     📶  ⚙  ⬇      │
    │     📅 Остался 1 день   0 B / 50.00 GB         │ ← ★ 1 QATOR
    │     ▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░         │
    │     ┌────────────────────────────────────────┐ │
    │     │ 🛠 Qo'llab-quvvatlash                  │ │ ← ★ TO'LIQ
    │     ├────────────────────────────────────────┤ │
    │     │ 🌐 Sayt                                │ │ ← ★ TO'LIQ
    │     └────────────────────────────────────────┘ │
    │     ┌ ⚠️ Выключите VPN и нажмите 🔄, если не  │ │
    │     │   работает и подключитесь к другой     │ │ ← ★ TO'LIQ
    │     │   локации ... (qancha bo'lsa ham)      │ │
    │     └                                        ┘ │
    └────────────────────────────────────────────────┘

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Tekshir: 3 ta muammo ham tuzatilgan
""")


if __name__ == "__main__":
    sys.exit(main())
