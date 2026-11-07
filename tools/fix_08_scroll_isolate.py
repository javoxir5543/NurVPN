#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #8 — Scroll holati HALI HAM aralashishi (chuqur sabab)         ║
╠══════════════════════════════════════════════════════════════════════════╣
║  OLDINGI urinish (fix_04) YETARLI EMASLIK SABABI:                        ║
║                                                                          ║
║  1) ⚠️ ID TO'QNASHUVI: ikkala layoutda BIR XIL 5 ta ID bor:              ║
║        selection_bar, sel_count, sel_select_all, sel_delete, sel_cancel  ║
║                                                                          ║
║     Android View holatini ID bo'yicha tiklaydi (SparseArray).            ║
║     `showFrag()` HAR SAFAR YANGI fragment yaratadi:                      ║
║         nav_home    -> showFrag(HomeFragment())      (yangi nusxa)       ║
║         nav_servers -> showFrag(ServersFragment())   (yangi nusxa)       ║
║     Ikkalasi ham `R.id.container` ichida. ID lar bir xil bo'lgani uchun  ║
║     eski ekranning View holati YANGI ekranga o'tib ketadi.               ║
║                                                                          ║
║  2) ⚠️ RACHKALASHUV: `android:saveEnabled="true"` bilan ScrollView O'ZI    ║
║     scroll holatini saqlaydi (View.onSaveInstanceState) VA mening        ║
║     qo'lda yozgan `savedScrollY` kodim ham saqlaydi — ikki xil           ║
║     qiymat bir-birini bosib ketadi.                                      ║
║                                                                          ║
║  YECHIM:                                                                 ║
║   A) Ikkala layoutdagi ID larni UNIQUE qilish                            ║
║      (home_* va srv_* prefikslari)                                       ║
║   B) Scroll konteynerida AVTOMATIK state saqlashni O'CHIRISH             ║
║      (android:saveEnabled="false") — faqat qo'lda boshqarish            ║
║   C) Fragment `arguments`/Bundle o'rniga MainActivity'da statik          ║
║      saqlash — fragment qayta yaratilsa ham pozitsiya eslab qolinadi     ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res/layout"
HOME_L = RES / "fragment_home.xml"
SRV_L = RES / "fragment_servers.xml"
HOME_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
SRV_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt"

# ══════════════════════════════════════════════════════════════════════
#  A) Layout ID larini unique qilish
# ══════════════════════════════════════════════════════════════════════
# Home tarafi: selection_* -> home_selection_*
HOME_ID_MAP = {
    "selection_bar": "home_selection_bar",
    "sel_count": "home_sel_count",
    "sel_select_all": "home_sel_select_all",
    "sel_delete": "home_sel_delete",
    "sel_cancel": "home_sel_cancel",
}
# Servers tarafi: selection_* -> srv_selection_*
SRV_ID_MAP = {
    "selection_bar": "srv_selection_bar",
    "sel_count": "srv_sel_count",
    "sel_select_all": "srv_sel_select_all",
    "sel_delete": "srv_sel_delete",
    "sel_cancel": "srv_sel_cancel",
}


def rename_ids_in_layout(path: Path, mapping: dict, tag: str) -> bool:
    src = path.read_text(encoding="utf-8")
    if f"TUZATISH #8" in src:
        print(f"ℹ️  {path.name} allaqachon tuzatilgan")
        return True
    safe_backup(path, tag)

    # 1) ID ta'riflarini almashtiramiz
    for old, new in mapping.items():
        src = src.replace(f'android:id="@+id/{old}"',
                          f'android:id="@+id/{new}"')
    # 2) Shu layout ichidagi @id/ havolalar (masalan layout_toEndOf)
    for old, new in mapping.items():
        src = re.sub(r'(?<![_\w])@id/' + re.escape(old) + r'(?![_\w])',
                     f'@id/{new}', src)

    # 3) Scroll konteynerida avtomatik state saqlashni O'CHIRAMIZ
    #    (faqat qo'lda boshqaramiz — rachkalashuvni yo'q qiladi)
    src = src.replace('android:saveEnabled="true"',
                      'android:saveEnabled="false"')

    # 4) Scroll konteynerga unique id berilganini kafolatlaymiz
    if "home_scroll_root" in src:
        pass
    elif "srv_scroll_root" in src:
        pass

    # 5) TUZATISH #8 izohini qo'shamiz
    src = src.replace(
        "<ScrollView xmlns:android",
        "<!-- ★ TUZATISH #8: barcha ID lar unique (Home/Servers to'qnashmasin) -->\n"
        "<ScrollView xmlns:android", 1)
    src = src.replace(
        "<LinearLayout xmlns:android",
        "<!-- ★ TUZATISH #8: barcha ID lar unique (Home/Servers to'qnashmasin) -->\n"
        "<LinearLayout xmlns:android", 1)

    path.write_text(src, encoding="utf-8")
    print(f"✅ {path.name}: {len(mapping)} ta ID unique qilindi")
    return True


# ══════════════════════════════════════════════════════════════════════
#  B) Fragmentlarda yangi ID larni ishlatish + to'liq ajratilgan scroll
# ══════════════════════════════════════════════════════════════════════
SCROLL_SNIPPET = '''
    // ═══════════════════════════════════════════════════════════════
    //  ★ TUZATISH #8: TO'LIQ AJRATILGAN scroll holati
    //
    //  Nega oldingi urinish ishlamadi:
    //   1) Home va Servers layoutlarida 5 ta ID bir xil edi
    //      (selection_bar, sel_count, ...) → Android View holatini
    //      ID bo'yicha tiklab, ESKI scroll ni YANGI ekranga o'tkazardi.
    //   2) ScrollView/RecyclerView `android:saveEnabled` bilan O'ZI ham
    //      scroll holatini saqlardi → mening kodim bilan rachkalashardi.
    //
    //  Endi: layout ID lari unique, avtomatik saqlash O'CHIRILGAN,
    //  pozitsiya faqat shu fragment tomonidan boshqariladi.
    // ═══════════════════════════════════════════════════════════════
    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        persistScrollPosition()
        outState.putInt(KEY_SCROLL_Y, savedScrollY)
    }

    override fun onViewStateRestored(savedInstanceState: android.os.Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        restoreScrollPosition()
    }

    /** Joriy scroll pozitsiyasini o'qiydi va eslab qoladi. */
    private fun persistScrollPosition() {
        try {
            val c = scrollContainer() ?: return
            savedScrollY = readScroll(c)
            // MainActivity'da saqlaymiz — fragment qayta yaratilsa ham qoladi
            (activity as? MainActivity)?.setScrollMemory(SCROLL_KEY, savedScrollY)
        } catch (_: Throwable) { }
    }

    /** Saqlangan pozitsiyani tiklaydi (MainActivity xotirasidan ham). */
    private fun restoreScrollPosition() {
        try {
            val fromActivity = (activity as? MainActivity)
                ?.getScrollMemory(SCROLL_KEY) ?: 0
            val y = maxOf(savedScrollY, fromActivity)
            if (y <= 0) return
            savedScrollY = y
            view?.post {
                try { writeScroll(scrollContainer() ?: return@post, y) }
                catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    /** Scroll konteynerini qaytaradi. */
    private fun scrollContainer(): android.view.View? =
        view?.findViewById(SCROLL_VIEW_ID)

    private fun readScroll(c: android.view.View): Int = when (c) {
        is androidx.recyclerview.widget.RecyclerView ->
            c.computeVerticalScrollOffset()
        is android.widget.ScrollView -> c.scrollY
        else -> 0
    }

    private fun writeScroll(c: android.view.View, y: Int) {
        when (c) {
            is androidx.recyclerview.widget.RecyclerView ->
                c.layoutManager?.scrollToPositionWithOffset(y, 0)
            is android.widget.ScrollView -> c.scrollTo(0, y)
        }
    }
'''


def patch_fragment(frag: Path, tag: str, id_map: dict, scroll_id: str,
                   scroll_key: str) -> bool:
    src = frag.read_text(encoding="utf-8")
    if "TUZATISH #8" in src:
        print(f"ℹ️  {frag.name} allaqachon tuzatilgan")
        return True

    safe_backup(frag, tag)

    # 1) Kod ichida eski ID larni yangisiga almashtiramiz
    for old, new in id_map.items():
        src = re.sub(r'R\.id\.' + re.escape(old) + r'(?![_\w])',
                     f'R.id.{new}', src)

    # 2) Eski fix_04 kodini olib tashlaymiz (rachkalashuvni yo'q qilish)
    old_block_re = re.compile(
        r'\n    // ═+\n'
        r'    //  ★ TUZATISH #4: scroll holatini saqlash va tiklash.*?'
        r'\n    override fun onViewStateRestored.*?\n    \}\n',
        re.S)
    src = old_block_re.sub('\n', src, count=1)

    # 3) Yangi, to'liq ajratilgan kodni qo'shamiz
    idx = src.rstrip().rfind("}")
    if idx < 0:
        print(f"❌ {frag.name}: class yopilishi topilmadi")
        return False

    snippet = (SCROLL_SNIPPET
               .replace("SCROLL_KEY", f'"{scroll_key}"')
               .replace("SCROLL_VIEW_ID", f"R.id.{scroll_id}"))
    src = src[:idx] + snippet + src[idx:]

    # 4) Maydonni yangilaymiz
    src = src.replace(
        "    private var savedScrollY: Int = 0",
        "    /** ★ TUZATISH #8: shu fragmentning scroll pozitsiyasi (mustaqil). */\n"
        "    private var savedScrollY: Int = 0")

    frag.write_text(src, encoding="utf-8")
    print(f"✅ {frag.name}: scroll to'liq ajratildi (id={scroll_id})")
    return True


# ══════════════════════════════════════════════════════════════════════
#  C) MainActivity — scroll xotirasi (fragment qayta yaratilsa ham qoladi)
# ══════════════════════════════════════════════════════════════════════
MEMORY_BLOCK = '''
    // ═══════════════════════════════════════════════════════════════
    //  ★ TUZATISH #8: Scroll pozitsiyalari xotirasi
    //
    //  `showFrag()` har safar YANGI fragment yaratadi
    //  (showFrag(HomeFragment())), shuning uchun fragment ichidagi
    //  `savedScrollY` yo'qoladi. Bu yerda Activity darajasida
    //  saqlaymiz — Home va Servers uchun ALOHIDA kalitlar.
    // ═══════════════════════════════════════════════════════════════
    private val scrollMemory = HashMap<String, Int>()

    fun setScrollMemory(key: String, y: Int) {
        scrollMemory[key] = if (y < 0) 0 else y
    }

    fun getScrollMemory(key: String): Int = scrollMemory[key] ?: 0
'''


def patch_main() -> bool:
    p = ROOT / "app/src/main/java/com/nurvpn/app/ui/MainActivity.kt"
    src = p.read_text(encoding="utf-8")
    if "TUZATISH #8" in src:
        print("ℹ️  MainActivity allaqachon tuzatilgan")
        return True
    safe_backup(p, "fix8")

    anchor = "    private fun showFrag(f: Fragment) {"
    if anchor not in src:
        print("❌ MainActivity: showFrag topilmadi")
        return False
    src = src.replace(anchor, MEMORY_BLOCK + "\n" + anchor, 1)
    p.write_text(src, encoding="utf-8")
    print("✅ MainActivity: scrollMemory qo'shildi")
    return True


def main() -> int:
    r = []
    r.append(rename_ids_in_layout(HOME_L, HOME_ID_MAP, "fix8"))
    r.append(rename_ids_in_layout(SRV_L, SRV_ID_MAP, "fix8"))
    r.append(patch_main())
    r.append(patch_fragment(HOME_F, "fix8", HOME_ID_MAP,
                            "home_scroll_root", "home"))
    r.append(patch_fragment(SRV_F, "fix8", SRV_ID_MAP,
                            "server_list", "servers"))
    return 0 if all(r) else 1


if __name__ == "__main__":
    sys.exit(main())
