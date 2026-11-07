#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #10 — SCROLL ARALASHUVI (BUG #1, qayta tuzatish)               ║
╠══════════════════════════════════════════════════════════════════════════╣
║  NEGA OLDINGI fix_08 YETARLI EMAS EDI (o'lchandi):                       ║
║                                                                          ║
║  1) ❌ `android:nestedScrollingEnabled="false"` Servers'dagi             ║
║     RecyclerView'ga qo'yilgan edi. Bu NOTO'G'RI YO'NALISH:               ║
║     u mustaqil scroll konteyner, nested scrolling O'CHIRILSA             ║
║     touch event parent'ga uzatiladi → "ro'yxat qotib qoladi,            ║
║     orqadagi ekran scroll bo'ladi" — foydalanuvchi ko'rgan aynan shu!    ║
║                                                                          ║
║  2) ❌ `android:saveEnabled="false"` 6 ta view'ga qo'llangan edi         ║
║     (search_input, settings view'lari ham). Bu haddan oshgan —           ║
║     matn kiritish maydonlari holatini yo'qotadi.                         ║
║     Faqat SCROLL konteynerida kerak.                                     ║
║                                                                          ║
║  3) ❌ Fragment almashganda eski fragment `onDestroyView` da             ║
║     ro'yxatdan o'tkazilmagan listener/timer qoldirishi mumkin.           ║
║                                                                          ║
║  YECHIM:                                                                 ║
║   A) Servers RecyclerView: nestedScrollingEnabled → **true**             ║
║      (mustaqil scroll uchun SHART)                                       ║
║   B) saveEnabled faqat scroll konteynerlarda qoldiriladi                 ║
║   C) RecyclerView: layout_height to'g'ri (match_parent/0dp+weight)       ║
║   D) onDestroyView da listener/timer'larni tozalash                      ║
║   E) Fragment view'larini onDestroyView da null qilish (leak oldini)     ║
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
HOME_L = ROOT / "app/src/main/res/layout/fragment_home.xml"
SET_L = ROOT / "app/src/main/res/layout/fragment_settings.xml"
SRV_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt"
HOME_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"


# ══════════════════════════════════════════════════════════════════════
#  A) Servers: nestedScrolling = TRUE (mustaqil scroll)
# ══════════════════════════════════════════════════════════════════════
def fix_servers_layout() -> bool:
    src = SRV_L.read_text(encoding="utf-8")
    if "TUZATISH #10" in src:
        print("ℹ️  fragment_servers.xml allaqachon tuzatilgan")
        return True
    safe_backup(SRV_L, "fix10")

    # nested scrolling O'CHIRILGAN bo'lsa YONAMIZ — mustaqil scroll uchun
    src = src.replace(
        'android:nestedScrollingEnabled="false"',
        'android:nestedScrollingEnabled="true"')

    # saveEnabled="false" ni faqat RecyclerView'da qoldiramiz
    # (boshqa view'lar holati saqlanishi kerak)
    src = src.replace('android:saveEnabled="false"', '')

    # RecyclerView'ga izoh + to'g'ri atributlar
    src = src.replace(
        '<androidx.recyclerview.widget.RecyclerView\n'
        '            android:id="@+id/server_list"',
        '<!-- ★ TUZATISH #10: mustaqil scroll konteyner.\n'
        '             nestedScrollingEnabled=true SHART: aks holda touch\n'
        '             event parent\'ga uzatilib, orqadagi ekran scroll bo\'ladi. -->\n'
        '        <androidx.recyclerview.widget.RecyclerView\n'
        '            android:id="@+id/server_list"\n'
        '            android:saveEnabled="false"')

    SRV_L.write_text(src, encoding="utf-8")
    print("✅ fragment_servers.xml: nestedScrolling=true, saveEnabled tozalandi")
    return True


# ══════════════════════════════════════════════════════════════════════
#  B) Home/Settings: ortiqcha saveEnabled=false ni olib tashlash
# ══════════════════════════════════════════════════════════════════════
def fix_other_layouts() -> bool:
    ok = True
    for p, scroll_id in ((HOME_L, "home_scroll_root"),
                         (SET_L, None)):
        if not p.exists():
            continue
        src = p.read_text(encoding="utf-8")
        if 'android:saveEnabled="false"' not in src:
            print(f"ℹ️  {p.name}: tozalangan")
            continue
        safe_backup(p, "fix10")

        if scroll_id:
            # Home: faqat ScrollView'da qoldiramiz, boshqalaridan olib tashlaymiz
            lines = src.split("\n")
            out = []
            keep_next = False
            for ln in lines:
                if f'@+id/{scroll_id}' in ln:
                    keep_next = True
                if 'android:saveEnabled="false"' in ln:
                    if keep_next:
                        out.append(ln)      # ScrollView'niki — qoladi
                    # boshqalarniki — olib tashlanadi
                    keep_next = False
                    continue
                out.append(ln)
                if 'android:id=' in ln and f'@+id/{scroll_id}' not in ln:
                    keep_next = False
            src = "\n".join(out)
        else:
            # Settings: scroll konteyner emas — hammasini olib tashlaymiz
            src = src.replace('\n            android:saveEnabled="false"', '')
            src = src.replace('android:saveEnabled="false"\n            ', '')
            src = src.replace('android:saveEnabled="false"', '')

        p.write_text(src, encoding="utf-8")
        print(f"✅ {p.name}: saveEnabled tozalandi")
    return ok


# ══════════════════════════════════════════════════════════════════════
#  C) ServersFragment: RecyclerView sozlamalari + tozalash
# ══════════════════════════════════════════════════════════════════════
OLD_RV = '''        rv = v.findViewById(R.id.server_list)
        search = v.findViewById(R.id.search_input)'''

NEW_RV = '''        rv = v.findViewById(R.id.server_list)
        search = v.findViewById(R.id.search_input)

        // ═══════════════════════════════════════════════════════════════
        //  ★ TUZATISH #10 (BUG #1): mustaqil scroll kafolatlari
        //
        //  Muammo: Servers'da scroll qilinganda ro'yxat qotib qolib,
        //  ORQADAGI Home ekrani scroll bo'lardi. Sabab — touch event
        //  Servers RecyclerView'iga yetib bormay, parent'ga uzatilardi.
        //
        //  Yechim: nested scrolling YOQILADI (mustaqil konteyner),
        //  overScroll rejimi aniq belgilanadi va focus'lanadi.
        // ═══════════════════════════════════════════════════════════════
        rv?.isNestedScrollingEnabled = true
        rv?.overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
        rv?.isFocusable = true
        rv?.isFocusableInTouchMode = true
        rv?.descendantFocusability =
            android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS'''

OLD_INIT = '''        rv?.layoutManager = LinearLayoutManager(context)
        rv?.setHasFixedSize(true)
        rv?.adapter = ad'''

NEW_INIT = '''        rv?.layoutManager = LinearLayoutManager(context).apply {
            // ★ TUZATISH #10: scroll pozitsiyasini aniq saqlash uchun
            recycleChildren = true
        }
        rv?.setHasFixedSize(true)
        rv?.adapter = ad
        // ★ TUZATISH #10: itemAnimator — ro'yxat almashganda sakrashni oldini oladi
        rv?.itemAnimator = null'''


def fix_servers_fragment() -> bool:
    src = SRV_F.read_text(encoding="utf-8")
    if "TUZATISH #10" in src:
        print("ℹ️  ServersFragment allaqachon tuzatilgan")
        return True
    safe_backup(SRV_F, "fix10")

    changed = False
    if OLD_RV in src:
        src = src.replace(OLD_RV, NEW_RV, 1)
        changed = True
    if OLD_INIT in src:
        src = src.replace(OLD_INIT, NEW_INIT, 1)
        changed = True

    if not changed:
        print("⚠️  ServersFragment: anchor topilmadi (allaqachon boshqacha?)")
    else:
        SRV_F.write_text(src, encoding="utf-8")
        print("✅ ServersFragment: nested scrolling + focus sozlamalari")
    return True


# ══════════════════════════════════════════════════════════════════════
#  D) onDestroyView — listener/view tozalash (leak va aralashuv oldini)
# ══════════════════════════════════════════════════════════════════════
CLEANUP = '''
    // ═══════════════════════════════════════════════════════════════
    //  ★ TUZATISH #10 (BUG #1): view tozalash
    //
    //  Fragment almashganda eski view daraxti yo'q qilinadi. Agar
    //  RecyclerView/LayoutManager havolalari qolsa, eski scroll holati
    //  yangi ekranga ta'sir qilishi mumkin. Shuning uchun tozalaymiz.
    // ═══════════════════════════════════════════════════════════════
    override fun onDestroyView() {
        try {
            // Scroll pozitsiyasini eslab qolamiz (keyin tiklash uchun)
            persistScrollPosition()
            // RecyclerView'ni bo'shatamiz
            rv?.adapter = null
            rv?.layoutManager = null
        } catch (_: Throwable) { }
        rv = null
        search = null
        selectionBar = null
        selCountText = null
        super.onDestroyView()
    }
'''


def add_cleanup() -> bool:
    src = SRV_F.read_text(encoding="utf-8")
    if "★ TUZATISH #10 (BUG #1): view tozalash" in src:
        print("ℹ️  ServersFragment cleanup allaqachon bor")
        return True

    # scroll kodidan oldin qo'shamiz
    anchor = "    // ═══════════════════════════════════════════════════════════════\n" \
             "    //  ★ TUZATISH #8: TO'LIQ AJRATILGAN scroll holati"
    if anchor not in src:
        print("⚠️  ServersFragment: scroll anchor topilmadi")
        return False
    src = src.replace(anchor, CLEANUP + "\n" + anchor, 1)
    SRV_F.write_text(src, encoding="utf-8")
    print("✅ ServersFragment: onDestroyView tozalash qo'shildi")
    return True


def main() -> int:
    r = [
        fix_servers_layout(),
        fix_other_layouts(),
        fix_servers_fragment(),
        add_cleanup(),
    ]
    return 0 if all(r) else 1


if __name__ == "__main__":
    sys.exit(main())
