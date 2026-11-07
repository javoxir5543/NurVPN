#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX151-ANNOUNCE — obuna e'lonini (xabarini) TO'LIQ ko'rsatish
════════════════════════════════════════════════════════════════════════════

 ═══ MUAMMO ═══

 INCY'da provayder xabari TO'LIQ ko'rinadi (ko'p qatorli, katta shrift).
 NurVPN'da esa xabar KESILIB qoladi. Sabab — 4 ta alohida to'siq:

   1) `SubscribeHeader.MAX_ANNOUNCE = 220`  → matn 220 belgida "…" bilan kesiladi
   2) `cleanAnnounce()` `\\n` ni BO'SHLIQQA aylantiradi → qatorlar yo'qoladi
   3) `item_home_card_header.xml` `maxLines="3"` + `ellipsize="end"`
   4) `textSize="10sp"` — o'qish qiyin

 ═══ YECHIM ═══

   1) `MAX_ANNOUNCE` 220 → 2000 (amalda "cheksiz", lekin DoS dan himoya)
   2) `cleanAnnounce()` — qatorlarni SAQLAYDI (`\\n` qoladi), faqat
      ortiqcha bo'shliq va 3+ ketma-ket qatorni siqadi.
      Yangi `announceLines()` — qator sonini hisoblaydi (UI uchun).
   3) XML: `maxLines` olib tashlanadi (to'liq ko'rsatiladi),
      `textSize` 10sp → 12sp, `lineSpacingExtra` qo'shiladi.
   4) `bindSubInfo()` — `⚠️` prefiksi olib tashlanadi (fon allaqachon
      ogohlantirish), matn to'liq yoziladi.

 ═══ XAVFSIZLIK ═══

   • `announce` faqat BITTA joyda ko'rsatiladi (CardVH.bindSubInfo)
   • `MEM` himoyasi: 2000 belgi — juda uzun xabar karta balandligini
     cheksiz oshirmasligi uchun (RecyclerView performansi)
   • Boshqa hech qanday funksiya tegmaydi

 ═══ ISHLATISH ═══

     python3 tools/fix_announce_full.py --check
     python3 tools/fix_announce_full.py
     python3 tools/fix_announce_full.py --rollback
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

# ⚠️ `res/` ICHIDA .bak QOLDIRILMAYDI (AGP resurs deb o'qib BUILD YIQILADI)
BACKUP_DIR = ROOT / ".fix-backups"

HELPER = ROOT / "app/src/main/java/com/nurvpn/app/core/SubscribeHeader.kt"
LAYOUT = ROOT / "app/src/main/res/layout/item_home_card_header.xml"
HOME = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"


def backup_path(src: Path) -> Path:
    BACKUP_DIR.mkdir(exist_ok=True)
    return BACKUP_DIR / f"{src.name}.{STAMP}.bak"


def latest_backup(src: Path):
    pats = sorted(glob.glob(str(BACKUP_DIR / f"{src.name}.*.bak")))
    return Path(pats[-1]) if pats else None


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
                f"✗ [{self.path.name}] topilmadi ({what}):\n    {old[:150]!r}")
        d0 = old.count("{") - old.count("}")
        d1 = new.count("{") - new.count("}")
        if d0 != d1:
            raise SystemExit(
                f"✗ [{self.path.name}] QAVSLAR NOMUVOFIQ ({what}): "
                f"eski={d0}, yangi={d1}")
        self.text = self.text.replace(old, new, 1)
        self.changes.append(what)
        return True

    def changed(self) -> bool:
        return self.text != self.orig

    def write(self, dry: bool) -> bool:
        if not self.changed():
            return False
        if dry:
            return True
        if self.path.exists():
            shutil.copy2(self.path, backup_path(self.path))
        self.path.write_text(self.text, encoding="utf-8")
        return True

    def rollback(self) -> bool:
        bak = latest_backup(self.path)
        if not bak:
            return False
        shutil.copy2(bak, self.path)
        return True


# ═══════════════════════════════════════════════════════════════════════
#  1) SubscribeHeader.kt — limit + qatorlarni saqlash
# ═══════════════════════════════════════════════════════════════════════

LIMIT_OLD = '''    /** `announce` uchun maksimal uzunlik (kartada 2 qatorga sig'adi). */
    const val MAX_ANNOUNCE = 220
'''

LIMIT_NEW = '''    /**
     * ★ FIX151-ANNOUNCE: `announce` uchun maksimal uzunlik.
     *
     * ═══ NEGA OSHIRILDI (220 → 2000) ═══
     *
     * Avval 220 belgi edi — provayder xabari O'RTASIDAN "…" bilan
     * kesilardi va INCY'dagidek to'liq o'qib bo'lmasdi.
     *
     * ⚠️ 2000 — "cheksiz" emas, ATAYLAB chegara:
     *    `announce` karta ichida chiziladi. 50 000 belgilik xabar
     *    kelsa karta balandligi bir necha ekranga cho'zilib,
     *    RecyclerView scroll'i sekinlashadi. 2000 belgi — eng uzun
     *    haqiqiy xabardan ancha katta, lekin xavfsiz.
     *
     * ponytail: qattiq chegara. Agar provayder undan uzun xabar
     * yuborsa — `announceLines()` bilan "ko'proq" tugmasi qo'shiladi.
     */
    const val MAX_ANNOUNCE = 2000
'''

CLEAN_OLD = '''    /**
     * `announce` — ko'p qatorli e'lon. Qatorlar birlashtiriladi va
     * `MAX_ANNOUNCE` gacha qisqartiriladi (kartada 2 qator).
     */
    fun cleanAnnounce(raw: String?): String? {
        val s = decode(raw) ?: return null
        // Escaped \\n va haqiqiy qatorlarni bo'shliqqa aylantiramiz
        var t = s.replace("\\n", " ").replace("\\r", " ")
            .lineSequence().joinToString(" ") { it.trim() }
        t = t.replace(Regex("""\\s{2,}"""), " ").trim()
        if (t.isEmpty()) return null
        if (t.length > MAX_ANNOUNCE) t = t.take(MAX_ANNOUNCE - 1).trimEnd() + "…"
        return t
    }
}'''

CLEAN_NEW = '''    /**
     * ★ FIX151-ANNOUNCE: `announce` — KO'P QATORLI e'lon.
     *
     * ═══ NEGA QAYTA YOZILDI ═══
     *
     * Avvalgi kod `\\n` ni BO'SHLIQQA aylantirardi:
     *
     *     "Yangilanish bor\\nServerlar qo'shildi"
     *         ↓
     *     "Yangilanish bor Serverlar qo'shildi"   ← qatorlar YO'QOLDI
     *
     * Natijada xabar bitta uzun qatorga aylanib, karta ichida
     * kesilardi — INCY'dagidek "to'liq xabar" ko'rinmasdi.
     *
     * Endi:
     *   • `\\n` va `\\r` SAQLANADI (Kotlin TextView ularni qator
     *     sifatida chizadi)
     *   • Escaped "\\n" (ikki belgi: backslash + n) ham haqiqiy
     *     qatorga aylantiriladi — provayderlar shunday yuboradi
     *   • 3+ ketma-ket bo'sh qator → 1 ta (xabar siyrak cho'zilmasin)
     *   • Qator oxiridagi ortiqcha bo'shliq olib tashlanadi
     *   • `MAX_ANNOUNCE` dan uzun bo'lsa "…" (xavfsizlik chegarasi)
     */
    fun cleanAnnounce(raw: String?): String? {
        val s = decode(raw) ?: return null

        // Escaped "\\n" (backslash + n) → haqiqiy qator belgisi
        var t = s.replace("\\\\r\\\\n", "\\n").replace("\\\\n", "\\n")
            .replace("\\r\\n", "\\n").replace("\\r", "\\n")

        // Har bir qatorni tozalaymiz, 3+ bo'sh qatorni 1 taga siqamiz
        val lines = t.split("\\n").map { it.trim() }
        val out = StringBuilder()
        var blank = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blank++
                if (blank > 1) continue      // ketma-ket bo'sh qatorlar
            } else {
                blank = 0
            }
            if (out.isNotEmpty()) out.append('\\n')
            out.append(line)
        }
        t = out.toString().trim()

        // Ketma-ket bo'shliqlarni bittaga (qator ichida)
        t = t.split("\\n").joinToString("\\n") { l ->
            l.replace(Regex("""[ \\t]{2,}"""), " ")
        }

        if (t.isEmpty()) return null
        if (t.length > MAX_ANNOUNCE) {
            t = t.take(MAX_ANNOUNCE - 1).trimEnd() + "…"
        }
        return t
    }

    /**
     * ★ FIX151-ANNOUNCE: e'londagi qatorlar soni (UI uchun yordamchi).
     *
     * Karta balandligini oldindan hisoblash yoki "juda uzun xabar"
     * holatida qisqartirish kerak bo'lsa ishlatiladi.
     */
    fun announceLines(announce: String): Int =
        if (announce.isBlank()) 0 else announce.count { it == '\\n' } + 1
}'''


# ═══════════════════════════════════════════════════════════════════════
#  2) XML — maxLines olib tashlanadi, shrift kattalashtiriladi
# ═══════════════════════════════════════════════════════════════════════

XML_OLD = '''                android:id="@+id/card_info_announce"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_announce_alert"
                android:paddingStart="8dp"
                android:paddingEnd="8dp"
                android:paddingTop="5dp"
                android:paddingBottom="5dp"
                android:textColor="@color/text_primary"
                android:textSize="10sp"
                android:maxLines="3"
                android:ellipsize="end"
                android:visibility="gone"/>'''

XML_NEW = '''                android:id="@+id/card_info_announce"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_announce_alert"
                android:paddingStart="10dp"
                android:paddingEnd="10dp"
                android:paddingTop="7dp"
                android:paddingBottom="7dp"
                android:textColor="@color/text_primary"
                android:textSize="12sp"
                android:lineSpacingExtra="2dp"
                android:visibility="gone"/>'''


# ═══════════════════════════════════════════════════════════════════════
#  3) Kotlin — e'lonni to'liq yozish
# ═══════════════════════════════════════════════════════════════════════

ANN_OLD = '''            val ann = sub?.announce.orEmpty()
            if (ann.isBlank()) {
                annTv.visibility = View.GONE
            } else {
                annTv.visibility = View.VISIBLE
                annTv.text = "\\u26A0\\uFE0F $ann"
            }'''

ANN_NEW = '''            val ann = sub?.announce.orEmpty()
            if (ann.isBlank()) {
                annTv.visibility = View.GONE
            } else {
                // ★ FIX151-ANNOUNCE: xabar TO'LIQ ko'rsatiladi.
                //   Avval "\\u26A0\\uFE0F " prefiksi qo'shilar va matn
                //   3 qatorda kesilardi. Endi prefiks YO'Q (fon allaqachon
                //   ogohlantirish rangi) va qatorlar cheklanmagan.
                annTv.visibility = View.VISIBLE
                annTv.text = ann
            }'''


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX151-ANNOUNCE")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 72)
    print("  FIX151-ANNOUNCE — obuna xabarini to'liq ko'rsatish")
    print("═" * 72)

    if args.rollback:
        n = 0
        for p in [HELPER, LAYOUT, HOME]:
            e = Edit(p)
            if e.rollback():
                print(f"   ↩ tiklandi: {p.relative_to(ROOT)}")
                n += 1
        print(f"\n{'✅' if n else '–'} {n} ta fayl tiklandi")
        return 0

    for p in [HELPER, LAYOUT, HOME]:
        if not p.exists():
            print(f"✗ Fayl topilmadi: {p}")
            return 1

    e_h = Edit(HELPER)
    e_l = Edit(LAYOUT)
    e_k = Edit(HOME)

    res = []
    res.append(("SubscribeHeader: limit 220 → 2000",
                e_h.sub(LIMIT_OLD, LIMIT_NEW, "MAX_ANNOUNCE",
                        guard="const val MAX_ANNOUNCE = 2000")))
    res.append(("SubscribeHeader: qatorlarni saqlash + announceLines()",
                e_h.sub(CLEAN_OLD, CLEAN_NEW, "cleanAnnounce",
                        guard="fun announceLines(")))
    res.append(("Layout: maxLines olib tashlandi, 10sp → 12sp",
                e_l.sub(XML_OLD, XML_NEW, "announce XML",
                        guard='android:textSize="12sp"')))
    res.append(("Kotlin: e'lon to'liq yoziladi",
                e_k.sub(ANN_OLD, ANN_NEW, "announce matni",
                        guard="annTv.text = ann")))

    print("\n  ── O'zgarishlar ──")
    for name, done in res:
        mark = "✓ QO'SHILDI" if done else "– allaqachon bor"
        print(f"   {mark:16} {name}")

    # ── Tekshiruvlar ──
    print("\n  ── Tekshiruvlar ──")

    if e_l.changed():
        tmp = Path("/tmp/_f151.xml")
        tmp.write_text(e_l.text, encoding="utf-8")
        try:
            ET.parse(str(tmp))
            print("   ✓ XML tuzilishi to'g'ri")
        except Exception as ex:  # noqa: BLE001
            print(f"   ✗ XML buzildi: {ex}")
            return 1
        finally:
            tmp.unlink(missing_ok=True)
        if 'id="@+id/card_info_announce"' not in e_l.text:
            print("   ✗ announce element yo'qoldi")
            return 1
        if 'android:maxLines' in e_l.text.split('card_info_announce')[1][:500]:
            print("   ✗ maxLines hali ham bor")
            return 1
        print("   ✓ maxLines olib tashlandi, element joyida")

    if e_h.changed():
        d0 = e_h.orig.count("{") - e_h.orig.count("}")
        d1 = e_h.text.count("{") - e_h.text.count("}")
        if d0 != d1:
            print(f"   ✗ qavslar: {d0} → {d1}")
            return 1
        print(f"   ✓ SubscribeHeader qavslar balansi (farq={d1})")
        for m in ["fun cleanAnnounce(", "fun announceLines(", "MAX_ANNOUNCE = 2000"]:
            if m not in e_h.text:
                print(f"   ✗ yo'qolgan: {m}")
                return 1
        print("   ✓ cleanAnnounce / announceLines / limit joyida")

    if e_k.changed():
        d0 = e_k.orig.count("{") - e_k.orig.count("}")
        d1 = e_k.text.count("{") - e_k.text.count("}")
        if d0 != d1:
            print(f"   ✗ Kotlin qavslar: {d0} → {d1}")
            return 1
        print(f"   ✓ HomeFragment qavslar balansi (farq={d1})")

    total = sum(1 for _, d in res if d)
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    if total == 0:
        print("\n✓ Hammasi allaqachon qo'llangan")
        return 0

    print("\n  ── Yozish ──")
    for e in [e_h, e_l, e_k]:
        if e.write(dry=False):
            print(f"   💾 {e.path.relative_to(ROOT)}")
            print(f"      zaxira: .fix-backups/"
                  f"{backup_path(e.path).name}")

    print("\n" + "═" * 72)
    print("  ✅ TAYOR")
    print("═" * 72)
    print("""
  ⚠️ MUHIM: mavjud obunalar QAYTA O'QILMAGUNCHA eski (kesilgan)
     e'lon saqlanadi! Yangilashni unutmang:

       Serverlar bo'limi → obuna ⚙️ → "Yangilash"

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Obunani yangilab, e'lon to'liq ko'rinishini tekshiring
""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
