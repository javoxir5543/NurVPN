#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  FIX v1.12.0 — BUG #1: Scroll aralashuvi (nestedScrollingEnabled TESKARI)║
╠══════════════════════════════════════════════════════════════════════════╣
║  TASDIQLANGAN FAKTLAR (o'lchandi):                                       ║
║                                                                          ║
║   1. fragment_servers.xml:222                                            ║
║        android:nestedScrollingEnabled="true"                             ║
║      ⚠️ Comment "touch event parent'ga uzatilmasin" deydi, lekin        ║
║         `true` AYNAN parent'ga uzatadi — comment va kod ZID.            ║
║                                                                          ║
║   2. ServersFragment.kt:225                                              ║
║        rv?.isNestedScrollingEnabled = true                               ║
║      (oldingi fix_10 da men `false` → `true` qildim — XATO edi)         ║
║                                                                          ║
║   3. MainActivity.showFrag():                                            ║
║        supportFragmentManager.beginTransaction()                         ║
║            .replace(R.id.container, f).commitAllowingStateLoss()         ║
║      `executePendingTransactions()` YO'Q → eski fragment darhol         ║
║      yo'q qilinmaydi, ikkala view daraxti bir vaqtda mavjud bo'ladi.    ║
║                                                                          ║
║  NATIJA: Servers'da scroll qilinsa, eski Home ScrollView'i scroll       ║
║          bo'ladi (touch event parent zanjiri orqali).                    ║
║                                                                          ║
║  YECHIM: nestedScrolling = FALSE (mustaqil konteyner parent'ga          ║
║          uzatmaydi) + fragment tranzaksiyasini darhol yakunlash.        ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SRV_L = ROOT / "app/src/main/res/layout/fragment_servers.xml"
SRV_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt"
MAIN = ROOT / "app/src/main/java/com/nurvpn/app/ui/MainActivity.kt"

MARK = "FIX112-BUG1"


# ══════════════════════════════════════════════════════════════════════
#  1) fragment_servers.xml — true → false + comment tuzatish
# ══════════════════════════════════════════════════════════════════════
def fix_layout() -> bool:
    src = SRV_L.read_text(encoding="utf-8")
    if MARK in src:
        print("ℹ️  fragment_servers.xml allaqachon tuzatilgan")
        return True

    if 'android:nestedScrollingEnabled="true"' not in src:
        print("⚠️  fragment_servers.xml: nestedScrollingEnabled=true topilmadi")
        return False

    safe_backup(SRV_L, "fix_v112_bug1")

    # Eski (xato) comment blokini to'g'rilaymiz
    old_comment = (
        '<!-- ★ TUZATISH #10: mustaqil scroll konteyner.\n'
        '             nestedScrollingEnabled=true SHART: aks holda touch\n'
        "             event parent'ga uzatilib, orqadagi ekran scroll bo'ladi. -->"
    )
    new_comment = (
        '<!-- \u2605 FIX112-BUG1: nestedScrollingEnabled=false SHART.\n'
        "             `true` bo'lsa touch event OTA (parent) zanjiriga\n"
        "             uzatiladi va eski Home ScrollView scroll bo'ladi.\n"
        "             `false` — Servers o'z scroll'ini o'zi boshqaradi. -->"
    )
    if old_comment in src:
        src = src.replace(old_comment, new_comment, 1)
    else:
        # Comment boshqacha bo'lsa — shunchaki yangi izoh qo'shamiz
        src = src.replace(
            '<androidx.recyclerview.widget.RecyclerView\n'
            '            android:id="@+id/server_list"',
            new_comment + '\n'
            '        <androidx.recyclerview.widget.RecyclerView\n'
            '            android:id="@+id/server_list"', 1)

    # ASOSIY TUZATISH: true → false
    src = src.replace(
        'android:nestedScrollingEnabled="true"',
        'android:nestedScrollingEnabled="false"')

    SRV_L.write_text(src, encoding="utf-8")

    # Tasdiqlash
    final = SRV_L.read_text(encoding="utf-8")
    assert 'android:nestedScrollingEnabled="false"' in final, "almashtirish bajarilmadi"
    print("✅ fragment_servers.xml: nestedScrollingEnabled → false")
    return True


# ══════════════════════════════════════════════════════════════════════
#  2) ServersFragment.kt — isNestedScrollingEnabled = false
# ══════════════════════════════════════════════════════════════════════
def fix_fragment() -> bool:
    src = SRV_F.read_text(encoding="utf-8")

    # Idempotentlik: marker yoki allaqachon false
    if MARK in src:
        print("ℹ️  ServersFragment allaqachon tuzatilgan")
        return True

    if "rv?.isNestedScrollingEnabled = true" not in src:
        print("⚠️  ServersFragment: isNestedScrollingEnabled=true topilmadi")
        # Balki allaqachon false
        if "rv?.isNestedScrollingEnabled = false" in src:
            print("ℹ️  allaqachon false — o'tkazib yuborildi")
            return True
        return False

    safe_backup(SRV_F, "fix_v112_bug1")

    old_block = """        // ═══════════════════════════════════════════════════════════════
        //  ★ TUZATISH #10 (BUG #1): mustaqil scroll kafolatlari
        //
        //  Muammo: Servers'da scroll qilinganda ro'yxat qotib qolib,
        //  ORQADAGI Home ekrani scroll bo'lardi. Sabab — touch event
        //  Servers RecyclerView'iga yetib bormay, parent'ga uzatilardi.
        //
        //  Yechim: nested scrolling YOQILADI (mustaqil konteyner),
        //  overScroll rejimi aniq belgilanadi va focus'lanadi.
        // ═══════════════════════════════════════════════════════════════
        rv?.isNestedScrollingEnabled = true"""

    new_block = """        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX112-BUG1: nested scrolling O'CHIRILADI (false).
        //
        //  ⚠️ MUHIM: `true` va `false` ma'nosi bu yerda TESKARI tuyuladi.
        //
        //    nestedScrollingEnabled = true
        //       → RecyclerView "nested scrolling" oilasiga qo'shiladi va
        //         scroll'ni OTA (parent) konteyner bilan BO'LISHADI.
        //         Eski ekran (Home ScrollView) ham scroll bo'ladi.  ❌
        //
        //    nestedScrollingEnabled = false
        //       → RecyclerView o'z scroll'ini O'ZI boshqaradi,
        //         parent'ga uzatmaydi. Mustaqil ishlaydi.        ✓
        //
        //  Bu — BUG#1 ning aynan sababi edi: "Servers'da scroll qilsam
        //  orqadagi Home ekrani scroll bo'ladi".
        // ═══════════════════════════════════════════════════════════════
        rv?.isNestedScrollingEnabled = false"""

    if old_block in src:
        src = src.replace(old_block, new_block, 1)
    else:
        src = src.replace("rv?.isNestedScrollingEnabled = true",
                          "rv?.isNestedScrollingEnabled = false", 1)

    SRV_F.write_text(src, encoding="utf-8")
    assert "rv?.isNestedScrollingEnabled = false" in \
        SRV_F.read_text(encoding="utf-8")
    print("✅ ServersFragment: isNestedScrollingEnabled → false")
    return True


# ══════════════════════════════════════════════════════════════════════
#  3) MainActivity.showFrag() — tranzaksiyani darhol yakunlash
# ══════════════════════════════════════════════════════════════════════
def fix_showfrag() -> bool:
    src = MAIN.read_text(encoding="utf-8")
    if MARK in src:
        print("ℹ️  showFrag allaqachon tuzatilgan")
        return True

    old = """    private fun showFrag(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f).commitAllowingStateLoss()
    }"""

    new = """    private fun showFrag(f: Fragment) {
        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX112-BUG1: tranzaksiyani DARHOL yakunlaymiz.
        //
        //  Muammo: `commitAllowingStateLoss()` faqat REJALASHTIRADI.
        //  Tranzaksiya keyingi event loop'da bajariladi. Shu oraliqda
        //  eski fragment'ning view daraxti HALI HAM mavjud bo'ladi.
        //
        //  Natija: ikkala ekran bir vaqtda tirik bo'lib, eski Home
        //  ScrollView'i touch event'larni ushlab qolishi mumkin edi.
        //
        //  `executePendingTransactions()` tranzaksiyani shu zahoti
        //  bajaradi → eski view darhol yo'q qilinadi → mustaqil scroll.
        // ═══════════════════════════════════════════════════════════════
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f).commitAllowingStateLoss()
        // Rejalashtirilgan tranzaksiyalarni darhol bajaramiz
        supportFragmentManager.executePendingTransactions()
    }"""

    if old not in src:
        print("⚠️  showFrag: kutilgan shakl topilmadi")
        # Muqobil: executePendingTransactions allaqachon bormi?
        if "executePendingTransactions" in src:
            print("ℹ️  executePendingTransactions allaqachon bor")
            return True
        return False

    safe_backup(MAIN, "fix_v112_bug1")
    src = src.replace(old, new, 1)
    MAIN.write_text(src, encoding="utf-8")
    print("✅ MainActivity.showFrag: executePendingTransactions() qo'shildi")
    return True


def verify() -> bool:
    print()
    print("── Tekshiruv ──")
    ok = True

    xml = SRV_L.read_text(encoding="utf-8")
    if 'android:nestedScrollingEnabled="false"' in xml:
        print("  ✓ layout: nestedScrollingEnabled=false")
    else:
        print("  ✗ layout: nestedScrollingEnabled=false EMAS")
        ok = False

    kt = SRV_F.read_text(encoding="utf-8")
    if "rv?.isNestedScrollingEnabled = false" in kt:
        print("  ✓ fragment: isNestedScrollingEnabled=false")
    else:
        print("  ✗ fragment: isNestedScrollingEnabled=false EMAS")
        ok = False
    if "isNestedScrollingEnabled = true" in kt:
        print("  ✗ fragment: hali ham =true qoldi!")
        ok = False

    m = MAIN.read_text(encoding="utf-8")
    if "executePendingTransactions" in m:
        print("  ✓ MainActivity: executePendingTransactions()")
    else:
        print("  ✗ MainActivity: executePendingTransactions() YO'Q")
        ok = False

    return ok


def main() -> int:
    r = [fix_layout(), fix_fragment(), fix_showfrag()]
    v = verify()
    return 0 if (all(r) and v) else 1


if __name__ == "__main__":
    sys.exit(main())
