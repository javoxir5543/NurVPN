package com.nurvpn.app

import org.junit.Assert.*
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  v1.12.1 — SCROLL ARALASHUVI: ILDIZ SABAB REGRESSIYASI
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  QURILMADA TASDIQLANGAN MUAMMO:
 *    "ikkinchi ekranda scroll qilsam birinchi ekranga ta'sir qilyapti,
 *     ikkinchi ekranda o'zgarish yo'q"
 *
 *  ILDIZ SABAB:
 *    `showFrag(HomeFragment())` har tab almashganda YANGI nusxa yaratib,
 *    `replace()` bilan almashtirardi. Eski fragment view'i darhol yo'q
 *    qilinmasdi → ikkala view daraxti bir vaqtda `R.id.container` ichida
 *    → touch event noto'g'ri (orqadagi) ekranga borardi.
 *
 *  YECHIM: fragment'larni keshlab, `hide()/show()` bilan boshqarish.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class V121ScrollIsolationTest {

    private fun src(rel: String): String {
        for (f in listOf(java.io.File(rel), java.io.File("../$rel"))) {
            if (f.exists()) return f.readText()
        }
        fail("Fayl topilmadi: $rel")
        return ""
    }

    /** Comment'larni olib tashlaydi — faqat haqiqiy kod qoladi. */
    private fun code(t: String): String =
        t.split("\n")
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            .joinToString("\n")

    private val main by lazy {
        src("app/src/main/java/com/nurvpn/app/ui/MainActivity.kt")
    }

    // ═════════════ ILDIZ SABAB: yangi nusxa yaratilmasin ═════════════

    @Test
    fun `tab almashishda YANGI fragment nusxasi yaratilmaydi`() {
        val c = code(main)
        assertFalse(
            "⚠️ ILDIZ SABAB: showFrag(HomeFragment()) — har safar yangi " +
                "nusxa yaratiladi va eski view yo'q qilinmaydi",
            c.contains("showFrag(HomeFragment())"))
        assertFalse("⚠️ showFrag(ServersFragment()) qolgan",
            c.contains("showFrag(ServersFragment())"))
        assertFalse("⚠️ showFrag(SettingsFragment()) qolgan",
            c.contains("showFrag(SettingsFragment())"))
    }

    @Test
    fun `tab almashish switchTab orqali boladi`() {
        val c = code(main)
        assertTrue("switchTab metodi yo'q", c.contains("fun switchTab("))
        assertTrue("nav_home switchTab ga ulanmagan",
            c.contains("R.id.nav_home -> switchTab("))
        assertTrue("nav_servers switchTab ga ulanmagan",
            c.contains("R.id.nav_servers -> switchTab("))
    }

    @Test
    fun `fragmentlar hide-show bilan boshqariladi`() {
        val c = code(main)
        assertTrue("hide() chaqirilmagan", c.contains(".hide("))
        assertTrue("show() chaqirilmagan", c.contains(".show("))
        assertTrue("findFragmentByTag yo'q — fragment qayta topilmaydi",
            c.contains("findFragmentByTag"))
    }

    @Test
    fun `replace emas add ishlatiladi`() {
        val c = code(main)
        assertTrue("add() ishlatilishi kerak (hide/show uchun)",
            c.contains("tx.add(R.id.container"))
        // switchTab ichida replace bo'lmasligi kerak
        val switchBody = c.substringAfter("fun switchTab(")
            .substringBefore("\n    fun ")
        assertFalse(
            "⚠️ switchTab ichida replace() — eski view yo'q qilinadi",
            switchBody.contains(".replace(R.id.container"))
    }

    // ═════════════ HAR FRAGMENT O'Z SCROLL HOLATINI SAQLAYDI ═════════════

    @Test
    fun `Home va Servers onHiddenChanged ni boshqaradi`() {
        for ((rel, name) in listOf(
            "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt" to "Home",
            "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt" to "Servers"
        )) {
            val c = code(src(rel))
            assertTrue("$name: onHiddenChanged yo'q",
                c.contains("override fun onHiddenChanged("))
            assertTrue("$name: onTabShown yo'q", c.contains("fun onTabShown("))
            assertTrue("$name: yashirilganda scroll saqlanmaydi",
                c.contains("persistScrollPosition()"))
        }
    }

    @Test
    fun `scroll tiklashda maxOf xatosi yoq`() {
        // maxOf eski katta qiymatni ustuvor qilib, scroll "sakrashi"ga
        // olib kelardi. Endi fragment'ning o'z qiymati ustuvor.
        for (rel in listOf(
            "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt",
            "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt"
        )) {
            val c = code(src(rel))
            assertFalse("maxOf(savedScrollY...) xatosi qaytgan! ($rel)",
                c.contains("val y = maxOf(savedScrollY"))
        }
    }

    @Test
    fun `Home va Servers scroll kalitlari FARQLI`() {
        val home = code(src(
            "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"))
        val srv = code(src(
            "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt"))
        assertTrue("Home kaliti 'home' emas",
            home.contains("getScrollMemory(\"home\")"))
        assertTrue("Servers kaliti 'servers' emas",
            srv.contains("getScrollMemory(\"servers\")"))
    }

    @Test
    fun `Servers da ikkinchi scroll konteyner yoq`() {
        val xml = src("app/src/main/res/layout/fragment_servers.xml")
        val noComments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
            .replace(xml, "")
        assertFalse("Servers'da ScrollView bo'lmasligi kerak",
            noComments.contains("<ScrollView"))
        assertTrue("server_list bo'lishi kerak",
            noComments.contains("@+id/server_list"))
    }

    @Test
    fun `Home da faqat bitta scroll konteyner`() {
        val xml = src("app/src/main/res/layout/fragment_home.xml")
        val noComments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
            .replace(xml, "")
        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX140-HOMERV: ARXITEKTURA O'ZGARDI
        //
        //  ═══ AVVAL ═══
        //
        //  Home `ScrollView` + ichida `LinearLayout` edi. Test
        //  `RecyclerView` ni TAQIQLARDI, chunki ScrollView ichidagi
        //  RecyclerView "nested scrolling" konfliktini keltirardi:
        //  ikkalasi ham scroll qiladi, View qayta ishlatish ishlamaydi,
        //  natijada karta ochilganda 200 ta `inflate()` = 2s friz.
        //
        //  ═══ ENDI ═══
        //
        //  `ScrollView` BUTUNLAY olib tashlandi. Butun Home — bitta
        //  `RecyclerView`. Nested scroll muammosi YO'Q, chunki faqat
        //  BITTA scroll konteyner bor.
        //
        //  Shuning uchun talab o'zgardi:
        //    • ScrollView SONI = 0  (endi kerak emas)
        //    • RecyclerView SONI = 1 (yagona scroll konteyner)
        //
        //  Bu Servers bo'limidagi bilan AYNAN bir xil arxitektura —
        //  o'sha yerda friz yo'q, endi Home'da ham bo'lmaydi.
        // ═══════════════════════════════════════════════════════════════
        val scrollViews = Regex("<ScrollView").findAll(noComments).count()
        val recyclers = Regex("<androidx\\.recyclerview\\.widget\\.RecyclerView")
            .findAll(noComments).count()
        assertEquals(
            "★ FIX140: Home'da ScrollView BO'LMASLIGI kerak — " +
                "butun ekran bitta RecyclerView (nested scroll yo'q)",
            0, scrollViews)
        assertEquals(
            "★ FIX140: Home'da aynan 1 ta RecyclerView bo'lishi kerak",
            1, recyclers)
        assertTrue("★ FIX140: home_scroll_root id saqlanishi kerak",
            noComments.contains("@+id/home_scroll_root"))
    }
}
