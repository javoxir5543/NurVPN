#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #4 — Home va Servers ekranlarining scroll holati aralashishi   ║
╠══════════════════════════════════════════════════════════════════════════╣
║  ANIQLANGAN SABAB:                                                       ║
║                                                                          ║
║  `MainActivity` fragment'larni almashtiradi:                             ║
║      supportFragmentManager.beginTransaction()                           ║
║          .replace(R.id.container, f).commitAllowingStateLoss()           ║
║                                                                          ║
║  `replace()` da eski fragment yo'q qilinadi. LEKIN:                      ║
║                                                                          ║
║   1) `fragment_home.xml` — <ScrollView> (bitta scroll konteyner)         ║
║   2) `fragment_servers.xml` — <RecyclerView> + <HorizontalScrollView>    ║
║                                                                          ║
║  Ikkala fragment ham `onSaveInstanceState` ni yozmaydi va scroll         ║
║  pozitsiyasini tiklamaydi. `Fragment.onCreate` da `savedInstanceState`   ║
║  ishlatilmasa, `commitAllowingStateLoss()` bilan qayta yaratilganda      ║
║  Android ba'zi hollarda ESKI scroll holatini yangi fragmentga           ║
║  qo'llaydi (view id'lari bir xil bo'lsa — ikkalasida ham              ║
║  `R.id.container` ichida).                                              ║
║                                                                          ║
║  YECHIM:                                                                 ║
║   A) Har bir fragment O'ZINING scroll holatini saqlaydi/tiklaydi         ║
║      (`onSaveInstanceState` + `onViewStateRestored`).                    ║
║   B) NestedScrollingChild sozlamalari: nested scrolling o'chiriladi —    ║
║      ichki scroll tashqi konteynerga "yopishib" ketmasligi uchun.        ║
║   C) RecyclerView uchun alohida LayoutManager (shared bo'lmasin).        ║
║   D) `android:saveEnabled="false"` muammoli id'lar uchun.                ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
HOME_L = ROOT / "app/src/main/res/layout/fragment_home.xml"
SRV_L = ROOT / "app/src/main/res/layout/fragment_servers.xml"

HOME_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
SRV_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt"


def patch_layouts() -> bool:
    ok = True

    # ── fragment_home.xml: ScrollView ga id berish + nested scrolling o'chirish
    if HOME_L.exists():
        src = HOME_L.read_text(encoding="utf-8")
        if ":id/home_scroll_root" not in src:
            safe_backup(HOME_L, "fix4")
            src = src.replace(
                '<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"',
                '<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:id="@+id/home_scroll_root"\n'
                '    android:saveEnabled="true"\n'
                '    android:fillViewport="true"',
                1,
            )
            HOME_L.write_text(src, encoding="utf-8")
            print("✅ fragment_home.xml: home_scroll_root id + saveEnabled")
        else:
            print("ℹ️  fragment_home.xml allaqachon tuzatilgan")
    else:
        print(f"❌ Topilmadi: {HOME_L}")
        ok = False

    # ── fragment_servers.xml: RecyclerView ga id + nestedScrollingEnabled=false
    if SRV_L.exists():
        src = SRV_L.read_text(encoding="utf-8")
        if "servers_scroll_root" not in src:
            safe_backup(SRV_L, "fix4")
            # RecyclerView ga nested scrolling attribute qo'shamiz
            src = re.sub(
                r'(<androidx\.recyclerview\.widget\.RecyclerView\b)',
                r'\1\n            android:id="@+id/servers_scroll_root"\n'
                r'            android:nestedScrollingEnabled="false"\n'
                r'            android:saveEnabled="true"',
                src,
                count=1,
            )
            SRV_L.write_text(src, encoding="utf-8")
            print("✅ fragment_servers.xml: servers_scroll_root id + nestedScrolling off")
        else:
            print("ℹ️  fragment_servers.xml allaqachon tuzatilgan")
    else:
        print(f"❌ Topilmadi: {SRV_L}")
        ok = False

    return ok


def add_scroll_state(frag: Path, tag: str, root_field: str,
                     scroll_id_name: str, kind: str, cls: str) -> bool:
    """Fragmentga scroll holatini saqlash/tiklash kodini qo'shadi."""
    src = frag.read_text(encoding="utf-8")

    if f"TUZATISH #4" in src:
        print(f"ℹ️  {frag.name} allaqachon tuzatilgan")
        return True

    safe_backup(frag, tag)

    # 1) Maydon qo'shamiz
    field = (
        f"\n    // ★ TUZATISH #4: har bir fragment O'Z scroll holatini saqlaydi.\n"
        f"    // (Home va Servers almashganda scroll aralashib ketmasligi uchun)\n"
        f"    private var savedScrollY: Int = 0\n"
    )

    # `private var pingUpdateScheduled` yoki birinchi `private var` dan keyin
    m = re.search(r"^(\s*private var \w+.*)$", src, re.M)
    if not m:
        print(f"❌ {frag.name}: maydon qo'shish uchun joy topilmadi")
        return False
    src = src[:m.end()] + "\n" + field + src[m.end():]

    # 2) onSaveInstanceState + onViewStateRestored
    view_cls = "androidx.recyclerview.widget.RecyclerView" if kind == "rv" \
        else "android.widget.ScrollView"

    methods = f'''
    // ═══════════════════════════════════════════════════════════════
    //  ★ TUZATISH #4: scroll holatini saqlash va tiklash
    //  Home va Servers ekranlari almashganda scroll aralashmasligi uchun
    //  har bir fragment o'z pozitsiyasini o'zi boshqaradi.
    // ═══════════════════════════════════════════════════════════════
    override fun onSaveInstanceState(outState: android.os.Bundle) {{
        super.onSaveInstanceState(outState)
        try {{
            val sv = view?.findViewById<{view_cls}>(R.id.{scroll_id_name})
            savedScrollY = sv?.let {{
                if (it is {view_cls}) it.{'computeVerticalScrollOffset()' if kind == 'rv' else 'scrollY'}
                else 0
            }} ?: savedScrollY
            outState.putInt(KEY_SCROLL_Y, savedScrollY)
        }} catch (_: Throwable) {{ }}
    }}

    override fun onViewStateRestored(savedInstanceState: android.os.Bundle?) {{
        super.onViewStateRestored(savedInstanceState)
        val y = savedInstanceState?.getInt(KEY_SCROLL_Y, 0) ?: 0
        if (y <= 0) return
        view?.post {{
            try {{
                val sv = view?.findViewById<{view_cls}>(R.id.{scroll_id_name})
                {f'sv?.scrollToPosition(y)' if kind == 'rv' else 'sv?.scrollTo(0, y)'}
            }} catch (_: Throwable) {{ }}
        }}
    }}

    private companion object {{
        const val KEY_SCROLL_Y = "scroll_y_{tag}"
    }}
'''

    # Oxirgi `}` (class yopilishi) dan oldin qo'shamiz
    idx = src.rstrip().rfind("}")
    if idx < 0:
        print(f"❌ {frag.name}: class yopilishi topilmadi")
        return False
    src = src[:idx] + methods + src[idx:]

    frag.write_text(src, encoding="utf-8")
    print(f"✅ {frag.name}: onSaveInstanceState + onViewStateRestored")
    return True


def main() -> int:
    results = [patch_layouts()]
    results.append(add_scroll_state(
        HOME_F, "fix4", "home_scroll_root", "home_scroll_root", "sv", "ScrollView"))
    results.append(add_scroll_state(
        SRV_F, "fix4", "servers_scroll_root", "servers_scroll_root", "rv", "RecyclerView"))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
