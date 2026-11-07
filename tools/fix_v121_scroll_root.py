#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH v1.12.1 — BUG #1: SCROLL ARALASHUVI (ILDIZ SABAB)              ║
╠══════════════════════════════════════════════════════════════════════════╣
║  QURILMADA TEKSHIRILDI (Armor 8, Android 14):                            ║
║                                                                          ║
║  Oldingi urinishlar (fix_04, fix_08, fix_10, fix_v112_bug1) faqat       ║
║  ALOMATLARNI tuzatdi: nestedScrolling, ID to'qnashuvi, saveEnabled.     ║
║  ILDIZ SABAB topilmadi.                                                  ║
║                                                                          ║
║  ═══ HAQIQIY ILDIZ SABAB ═══                                            ║
║                                                                          ║
║  `MainActivity.showFrag()` HAR SAFAR YANGI fragment yaratadi:           ║
║                                                                          ║
║      R.id.nav_home    -> showFrag(HomeFragment())      // YANGI nusxa   ║
║      R.id.nav_servers -> showFrag(ServersFragment())   // YANGI nusxa   ║
║                                                                          ║
║  va `replace(R.id.container, f)` bilan almantiradi.                     ║
║                                                                          ║
║  MUAMMO: `replace()` tranzaksiyasi + yangi nusxa = eski fragment'ning  ║
║  holati YO'QOLADI. Har tab almashganda:                                  ║
║     • yangi HomeFragment → scroll 0 dan boshlanadi                       ║
║     • yangi ServersFragment → scroll 0 dan boshlanadi                    ║
║                                                                          ║
║  Lekin asosiy scroll buzilishi boshqa: FragmentManager eski fragment'ni ║
║  DARHOL yo'q qilmaydi — u `onDestroyView()` ni keyinroq chaqiradi.     ║
║  Shu oraliqda IKKALA view daraxti bir vaqtda `R.id.container` ichida    ║
║  mavjud bo'lib qoladi. Touch event zanjiri eski (orqadagi) Home         ║
║  ScrollView'iga borib yetadi → "ikkinchi ekranda scroll qilsam          ║
║  birinchi ekranga ta'sir qilyapti, ikkinchi ekranda o'zgarish yo'q".    ║
║  ═══════════════════════════════════════════════════════════════════════║
║                                                                          ║
║  YECHIM — fragment'larni SAQLAB QOLISH (hide/show):                     ║
║                                                                          ║
║  1. Fragment nusxalarini bir marta yaratib, keshlash                     ║
║  2. `replace()` o'rniga `add()` + `hide()`/`show()`                      ║
║     → faqat BITTA fragment ko'rinadi, lekin view daraxti saqlanadi       ║
║  3. `onHiddenChanged()` da scroll holatini saqlash/tiklash              ║
║                                                                          ║
║  ⚠️ NEGA hide/show: `replace()` da eski view butunlay yo'q qilinadi,    ║
║  shuning uchun scroll pozitsiyasi ham yo'qoladi. `hide()/show()` da     ║
║  view daraxti SAQLANADI — scroll o'z joyida qoladi va faqat bitta       ║
║  fragment ko'rinadi (touch ham faqat unga boradi).                       ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/nurvpn/app/ui/MainActivity.kt"
HOME_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
SRV_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt"
SRV_L = ROOT / "app/src/main/res/layout/fragment_servers.xml"

MARK = "FIX121-SCROLL"

# ══════════════════════════════════════════════════════════════════════
#  A) MainActivity — fragment'larni keshga olish, hide/show
# ══════════════════════════════════════════════════════════════════════
OLD_NAV = '''        bottom?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showFrag(HomeFragment())
                R.id.nav_servers -> showFrag(ServersFragment())
                R.id.nav_settings -> showFrag(SettingsFragment())
            }
            true
        }'''

NEW_NAV = '''        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX121-SCROLL (BUG #1): fragment'larni SAQLAB QOLAMIZ.
        //
        //  Avval har safar YANGI nusxa yaratilardi (HomeFragment()).
        //  Endi `switchTab()` orqali ishlaymiz — u fragment'ni bir marta
        //  yaratib, keyin faqat hide/show qiladi.
        // ═══════════════════════════════════════════════════════════════
        bottom?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchTab(TAB_HOME)
                R.id.nav_servers -> switchTab(TAB_SERVERS)
                R.id.nav_settings -> switchTab(TAB_SETTINGS)
            }
            true
        }'''

SWITCH_BLOCK = '''    // ═══════════════════════════════════════════════════════════════════
    //  ★ FIX121-SCROLL (BUG #1): TAB ALMASHISH — hide/show
    //
    //  ILDIZ SABAB: `replace(R.id.container, f)` + har safar yangi nusxa
    //  → eski fragment view'i darhol yo'q qilinmasdi → ikkala view
    //  daraxti bir vaqtda mavjud bo'lib, touch event noto'g'ri ekranga
    //  borardi ("ikkinchi ekranda scroll qilsam birinchi ekran scroll
    //  bo'ladi").
    //
    //  YECHIM: fragment nusxalarini keshlab, faqat hide/show qilamiz.
    //    • Faqat BITTA fragment ko'rinadi → touch faqat unga boradi
    //    • View daraxti SAQLANADI → scroll pozitsiyasi o'z joyida qoladi
    //    • Har tab o'z scroll holatini mustaqil eslab qoladi
    // ═══════════════════════════════════════════════════════════════════
    private var homeFragment: Fragment? = null
    private var serversFragment: Fragment? = null
    private var settingsFragment: Fragment? = null
    private var activeTab: String = TAB_HOME

    private fun fragmentFor(tab: String): Fragment = when (tab) {
        TAB_HOME -> homeFragment ?: HomeFragment().also { homeFragment = it }
        TAB_SERVERS -> serversFragment ?: ServersFragment().also { serversFragment = it }
        else -> settingsFragment ?: SettingsFragment().also { settingsFragment = it }
    }

    fun switchTab(tab: String) {
        if (isFinishing || isDestroyed) return
        // ⚠️ Recreate bo'lgandan keyin eski havolalar yaroqsiz bo'lishi mumkin
        if (tab != activeTab) {
            android.util.Log.i("NurVPN-DBG", "switchTab: $activeTab → $tab")
        }
        activeTab = tab

        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        // Barcha boshqa fragment'larni yashiramiz
        for (t in listOf(TAB_HOME, TAB_SERVERS, TAB_SETTINGS)) {
            if (t == tab) continue
            val f = fm.findFragmentByTag(tagFor(t))
            if (f != null && !f.isHidden) tx.hide(f)
        }

        val target = fm.findFragmentByTag(tagFor(tab))
        if (target == null) {
            // Birinchi marta — qo'shamiz
            tx.add(R.id.container, fragmentFor(tab), tagFor(tab))
        } else {
            // Allaqachon bor — faqat ko'rsatamiz
            tx.show(target)
            fragmentFor(tab) // keshni sinxronlaymiz
        }

        tx.commitAllowingStateLoss()
        fm.executePendingTransactions()

        // ★ Muhim: view daraxti tayyor bo'lgach, scroll holatini tiklaymiz
        fm.findFragmentByTag(tagFor(tab))?.let { f ->
            f.view?.post { notifyTabShown(f) }
        }
    }

    private fun tagFor(tab: String): String = "tab_$tab"

    private fun notifyTabShown(f: Fragment) {
        try {
            when (f) {
                is HomeFragment -> f.onTabShown()
                is ServersFragment -> f.onTabShown()
            }
        } catch (_: Throwable) { }
    }

    private fun showFrag(f: Fragment) {
        // Eski API — moslik uchun saqlanadi (boshqa joydan chaqirilishi mumkin)
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f).commitAllowingStateLoss()
        supportFragmentManager.executePendingTransactions()
    }'''


def patch_main() -> bool:
    src = MAIN.read_text(encoding="utf-8")
    if MARK in src:
        print("ℹ️  MainActivity allaqachon tuzatilgan")
        return True

    if OLD_NAV not in src:
        print("❌ MainActivity: bottom nav listener topilmadi")
        return False

    safe_backup(MAIN, "fix121")

    src = src.replace(OLD_NAV, NEW_NAV, 1)

    # switchTab blokini showFrag dan oldin qo'shamiz
    anchor = "    private fun showFrag(f: Fragment) {"
    if anchor not in src:
        print("❌ showFrag topilmadi")
        return False
    src = src.replace(anchor, SWITCH_BLOCK + "\n\n" + anchor, 1)

    # TAB_* konstantalarini companion object ga qo'shamiz
    src = src.replace(
        '        const val EXTRA_STATE = "state"',
        '        const val EXTRA_STATE = "state"\n\n'
        '        // ★ FIX121-SCROLL: tab identifikatorlari\n'
        '        const val TAB_HOME = "home"\n'
        '        const val TAB_SERVERS = "servers"\n'
        '        const val TAB_SETTINGS = "settings"', 1)

    MAIN.write_text(src, encoding="utf-8")
    print("✅ MainActivity: hide/show tab almashish (fragment saqlanadi)")
    return True


# ══════════════════════════════════════════════════════════════════════
#  B) Fragment'larga onTabShown() + scroll tiklash
# ══════════════════════════════════════════════════════════════════════
TAB_SHOWN_HOME = '''
    /**
     * ★ FIX121-SCROLL (BUG #1): tab ko'rsatilganda chaqiriladi.
     *
     * `hide()/show()` da `onResume()` QAYTA chaqirilmaydi, shuning uchun
     * scroll holatini tiklash va UI yangilash shu yerda qilamiz.
     */
    fun onTabShown() {
        if (!isAdded || view == null) return
        android.util.Log.i("NurVPN-DBG", "HomeFragment: onTabShown")
        // Scroll pozitsiyasini tiklaymiz
        restoreScrollPosition()
        // Ma'lumotlarni yangilaymiz (server o'zgargan bo'lishi mumkin)
        if (::ui.isInitialized) refresh()
    }

    /** ★ FIX121-SCROLL: fragment yashirilganda scroll holatini saqlaymiz. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            android.util.Log.i("NurVPN-DBG", "HomeFragment: yashirildi — scroll saqlanadi")
            persistScrollPosition()
        } else {
            android.util.Log.i("NurVPN-DBG", "HomeFragment: ko'rsatildi — scroll tiklanadi")
            restoreScrollPosition()
        }
    }
'''

TAB_SHOWN_SRV = '''
    /**
     * ★ FIX121-SCROLL (BUG #1): tab ko'rsatilganda chaqiriladi.
     */
    fun onTabShown() {
        if (!isAdded || view == null) return
        android.util.Log.i("NurVPN-DBG", "ServersFragment: onTabShown")
        restoreScrollPosition()
    }

    /** ★ FIX121-SCROLL: fragment yashirilganda scroll holatini saqlaymiz. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            android.util.Log.i("NurVPN-DBG", "ServersFragment: yashirildi — scroll saqlanadi")
            persistScrollPosition()
        } else {
            android.util.Log.i("NurVPN-DBG", "ServersFragment: ko'rsatildi — scroll tiklanadi")
            restoreScrollPosition()
        }
    }
'''


def patch_fragment(path: Path, block: str, name: str) -> bool:
    src = path.read_text(encoding="utf-8")
    if MARK in src:
        print(f"ℹ️  {name} allaqachon tuzatilgan")
        return True

    anchor = "    // ═══════════════════════════════════════════════════════════════\n" \
             "    //  ★ TUZATISH #8: TO'LIQ AJRATILGAN scroll holati"
    if anchor not in src:
        # fix_08 boshqacha bo'lishi mumkin — scroll kodini qidiramiz
        m = re.search(r'\n    override fun onSaveInstanceState\(outState: android\.os\.Bundle\)',
                      src)
        if not m:
            print(f"❌ {name}: scroll kodi topilmadi")
            return False
        safe_backup(path, "fix121")
        src = src[:m.start()] + "\n" + block + src[m.start():]
        path.write_text(src, encoding="utf-8")
        print(f"✅ {name}: onHiddenChanged + onTabShown")
        return True

    safe_backup(path, "fix121")
    src = src.replace(anchor, block + "\n" + anchor, 1)
    path.write_text(src, encoding="utf-8")
    print(f"✅ {name}: onHiddenChanged + onTabShown")
    return True


# ══════════════════════════════════════════════════════════════════════
#  C) restoreScrollPosition ni tuzatish (maxOf xato)
# ══════════════════════════════════════════════════════════════════════
BAD_RESTORE_HOME = '''    /** Saqlangan pozitsiyani tiklaydi (MainActivity xotirasidan ham). */
    private fun restoreScrollPosition() {
        try {
            val fromActivity = (activity as? MainActivity)
                ?.getScrollMemory("home") ?: 0
            val y = maxOf(savedScrollY, fromActivity)
            if (y <= 0) return'''

GOOD_RESTORE_HOME = '''    /**
     * Saqlangan pozitsiyani tiklaydi.
     *
     * ★ FIX121-SCROLL: avval `maxOf(savedScrollY, fromActivity)` edi —
     * bu XATO edi: eski katta qiymat yangi kichik qiymatni bosib ketardi
     * va scroll "sakrab" ketardi. Endi fragment'ning o'z qiymati ustuvor,
     * u 0 bo'lsa Activity xotirasidan olinadi.
     */
    private fun restoreScrollPosition() {
        try {
            val fromActivity = (activity as? MainActivity)
                ?.getScrollMemory("home") ?: 0
            val y = if (savedScrollY > 0) savedScrollY else fromActivity
            if (y <= 0) return'''


def fix_restore(path: Path, key: str, name: str) -> bool:
    src = path.read_text(encoding="utf-8")
    old = BAD_RESTORE_HOME.replace('"home"', f'"{key}"')
    if old not in src:
        print(f"ℹ️  {name}: restore allaqachon to'g'ri yoki boshqacha")
        return True
    new = GOOD_RESTORE_HOME.replace('"home"', f'"{key}"')
    safe_backup(path, "fix121")
    src = src.replace(old, new, 1)
    path.write_text(src, encoding="utf-8")
    print(f"✅ {name}: restoreScrollPosition maxOf xatosi tuzatildi")
    return True


# ══════════════════════════════════════════════════════════════════════
#  D) Servers layout: nestedScrollingEnabled ni BUTUNLAY olib tashlash
# ══════════════════════════════════════════════════════════════════════
def fix_layout() -> bool:
    """
    `nestedScrollingEnabled="false"` — RecyclerView o'z scroll'ini
    boshqaradi. Lekin u `ScrollView` ichida EMAS, shuning uchun bu
    atribut umuman kerak emas. Olib tashlasak — standart (true) bo'ladi,
    bu to'g'ri, chunki Servers RecyclerView mustaqil ildiz-konteyner.
    """
    src = SRV_L.read_text(encoding="utf-8")
    if f"{MARK}-LAYOUT" in src:
        print("ℹ️  fragment_servers.xml allaqachon tuzatilgan")
        return True

    safe_backup(SRV_L, "fix121")

    # Izohni yangilaymiz va atributni olib tashlaymiz
    src = re.sub(
        r'<!--[^>]*?nestedScrollingEnabled[^>]*?-->\s*',
        '<!-- \u2605 FIX121-SCROLL: Servers RecyclerView — mustaqil ildiz\n'
        '             konteyner. `nestedScrollingEnabled` kerak emas:\n'
        '             u ScrollView ichida EMAS, ota bilan scroll\n'
        '             bo\'lishmaydi. Standart (true) to\'g\'ri. -->\n'
        '        ',
        src, count=1, flags=re.S)
    src = src.replace(
        '            android:nestedScrollingEnabled="false"\n', '')

    SRV_L.write_text(src, encoding="utf-8")
    print("✅ fragment_servers.xml: nestedScrollingEnabled olib tashlandi")
    return True


def verify() -> bool:
    print()
    print("── Tekshiruv ──")
    ok = True

    m = MAIN.read_text(encoding="utf-8")
    checks = [
        ("switchTab", "tab almashish metodi"),
        ("TAB_HOME", "tab konstantalari"),
        ("findFragmentByTag", "fragment qidirish"),
        ("tx.hide(f)", "yashirish"),
        ("tx.show(target)", "ko'rsatish"),
    ]
    for needle, label in checks:
        if needle in m:
            print(f"  ✓ MainActivity: {label}")
        else:
            print(f"  ✗ MainActivity: {label} YO'Q")
            ok = False

    # Faqat KOD qatorlarini tekshiramiz (comment'larni olib tashlaymiz)
    code_lines = [ln for ln in m.split("\n")
                  if not ln.strip().startswith("//")
                  and not ln.strip().startswith("*")]
    code = "\n".join(code_lines)
    if "showFrag(HomeFragment())" in code or "showFrag(ServersFragment())" in code:
        print("  ✗ MainActivity: hali ham yangi nusxa yaratiladi!")
        ok = False
    else:
        print("  ✓ MainActivity: yangi nusxa yaratish yo'q (faqat switchTab)")

    for p, name in ((HOME_F, "HomeFragment"), (SRV_F, "ServersFragment")):
        t = p.read_text(encoding="utf-8")
        if "onHiddenChanged" in t and "onTabShown" in t:
            print(f"  ✓ {name}: onHiddenChanged + onTabShown")
        else:
            print(f"  ✗ {name}: lifecycle hook yo'q")
            ok = False
        tcode = "\n".join(ln for ln in t.split("\n")
                           if not ln.strip().startswith("//")
                           and not ln.strip().startswith("*"))
        if "val y = maxOf(savedScrollY" in tcode:
            print(f"  ✗ {name}: maxOf xatosi qoldi!")
            ok = False
        else:
            print(f"  ✓ {name}: restore scroll to'g'ri (maxOf yo'q)")

    l = SRV_L.read_text(encoding="utf-8")
    lcode = re.sub(r'<!--.*?-->', '', l, flags=re.S)
    if "nestedScrollingEnabled" not in lcode:
        print("  ✓ layout: keraksiz atribut olib tashlandi")
    else:
        print("  ⚠️  layout: nestedScrollingEnabled hali bor")

    return ok


def main() -> int:
    r = [patch_main(),
         patch_fragment(HOME_F, TAB_SHOWN_HOME, "HomeFragment"),
         patch_fragment(SRV_F, TAB_SHOWN_SRV, "ServersFragment"),
         fix_restore(HOME_F, "home", "HomeFragment"),
         fix_restore(SRV_F, "servers", "ServersFragment"),
         fix_layout()]
    v = verify()
    return 0 if (all(r) and v) else 1


if __name__ == "__main__":
    sys.exit(main())
