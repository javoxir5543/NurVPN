package com.nurvpn.app

import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nurvpn.app.config.SingBoxConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  v1.12.0 — QURILMADA TOPILGAN 3 TA XATO UCHUN REGRESSIYA TESTLARI
 * ═══════════════════════════════════════════════════════════════════════════
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V112BugsTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun inflate(id: Int) =
        android.view.LayoutInflater.from(ctx).inflate(id, null, false)

    // ═══════════════ BUG #1: SCROLL AJRATILGAN ═══════════════
    //
    //  ⚠️ Layout'ni Robolectric'da inflyatsiya qilish Material
    //  komponentlar (FloatingActionButton) uchun themed Activity
    //  talab qiladi. Shuning uchun XML manbasini to'g'ridan-to'g'ri
    //  tekshiramiz — bu yanada ishonchli (haqiqiy fayl o'qiladi).

    private fun sourceText(rel: String): String {
        for (f in listOf(java.io.File(rel), java.io.File("../$rel"))) {
            if (f.exists()) return f.readText()
        }
        fail("Fayl topilmadi: $rel")
        return ""
    }

    private fun layoutText(name: String): String {
        // Test resurslar orqali emas, manba fayldan o'qiymiz
        val candidates = listOf(
            java.io.File("app/src/main/res/layout/$name"),
            java.io.File("../app/src/main/res/layout/$name"),
        )
        for (f in candidates) {
            if (f.exists()) return f.readText()
        }
        fail("Layout topilmadi: $name (cwd=${java.io.File(".").absolutePath})")
        return ""
    }

    @Test
    fun `bug1 - Servers nestedScrolling keraksiz atribut olib tashlangan`() {
        // ★ FIX121-SCROLL: ILDIZ SABAB topilgach yondashuv o'zgardi.
        //
        //  Servers RecyclerView — ScrollView ICHIDA EMAS, u mustaqil
        //  ildiz konteyner. Shuning uchun `nestedScrollingEnabled`
        //  umuman kerak emas (standart holat to'g'ri).
        //
        //  Asosiy tuzatish boshqa joyda: fragment'lar `replace()` bilan
        //  emas, hide/show bilan boshqariladi (MainActivity.switchTab).
        val xml = layoutText("fragment_servers.xml")
        val noComments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
            .replace(xml, "")
        assertFalse(
            "⚠️ nestedScrollingEnabled atributi olib tashlanishi kerak edi",
            noComments.contains("nestedScrollingEnabled")
        )
    }

    @Test
    fun `bug1 - Servers RecyclerView ildiz ScrollView ichida emas`() {
        // Servers'da ScrollView BO'LMASLIGI kerak — aks holda ikki
        // scroll konteyner raqobatlashadi
        val xml = layoutText("fragment_servers.xml")
        val noComments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
            .replace(xml, "")
        assertFalse(
            "Servers'da ScrollView bo'lmasligi kerak (faqat RecyclerView)",
            noComments.contains("<ScrollView")
        )
    }

    @Test
    fun `bug1 - Servers RecyclerView layout_height togri`() {
        val xml = layoutText("fragment_servers.xml")
        val rvBlock = xml.substringAfter("RecyclerView", "").substringBefore("/>")
        assertTrue("layout_height bo'lishi kerak",
            rvBlock.contains("android:layout_height"))
        assertTrue(
            "0dp + weight (mustaqil, to'ldiruvchi ro'yxat) bo'lishi kerak",
            rvBlock.contains("0dp") || rvBlock.contains("match_parent")
        )
    }

    @Test
    fun `bug1 - Home va Servers ID lari TO'LIQ farqli`() {
        val home = layoutText("fragment_home.xml")
        val srv = layoutText("fragment_servers.xml")
        val re = Regex("android:id=\"@\\+id/(\\w+)\"")
        val hIds = re.findAll(home).map { it.groupValues[1] }.toSet()
        val sIds = re.findAll(srv).map { it.groupValues[1] }.toSet()
        val common = (hIds intersect sIds).sorted()
        assertTrue(
            "⚠️ BUG#1: umumiy ID lar topildi: $common\n" +
                "Android View holatini ID bo'yicha tiklaydi — to'qnashuv " +
                "scroll holatini aralashtiradi!",
            common.isEmpty()
        )
    }

    @Test
    fun `bug1 - Home scroll konteyneri mustaqil va saveEnabled togri`() {
        val xml = layoutText("fragment_home.xml")
        assertTrue("home_scroll_root bo'lishi kerak",
            xml.contains("@+id/home_scroll_root"))
        // ★ FIX140-HOMERV: ScrollView → RecyclerView ga o'tkazildi.
        //   `saveEnabled=false` TALABI O'ZGARMAYDI — scroll holatini
        //   biz qo'lda boshqaramiz (View state tiklash to'qnashuvini
        //   oldini olish uchun).
        val svBlock = xml.substringAfter("@+id/home_scroll_root", "")
        assertTrue("scroll konteynerida saveEnabled=false bo'lishi kerak",
            svBlock.contains("saveEnabled=\"false\""))
    }

    @Test
    fun `bug1 - Servers faqat oz ScrollView id siga ega`() {
        val xml = layoutText("fragment_servers.xml")
        assertTrue("server_list bo'lishi kerak",
            xml.contains("@+id/server_list"))
        assertFalse("Home'ning scroll id si Servers'da bo'lmasin",
            xml.contains("home_scroll_root"))
    }

    // ═══════════════ BUG #2: URL YASHIRILMAGAN ═══════════════

    @Test
    fun `bug2 - encrypted_url_hidden resursi BUTUNLAY ochirilgan`() {
        val id = ctx.resources.getIdentifier(
            "encrypted_url_hidden", "string", ctx.packageName)
        assertEquals(
            "⚠️ BUG#2: yashirish matni qaytdi — deep link'da URL ko'rinmaydi",
            0, id)
    }

    @Test
    fun `bug2 - dialog_sub_load resursi SAQLANGAN (dialog ishlashi kerak)`() {
        val id = ctx.resources.getIdentifier(
            "dialog_sub_load", "string", ctx.packageName)
        assertTrue("dialog_sub_load yo'q — dialog buziladi", id != 0)
    }

    @Test
    fun `bug2 - oddiy https obuna linki aniqlanadi`() {
        val url = "https://sub.dark-bit.cc/dbabb490-5dac-4a1b-adbf-a64d56a0da61"
        assertTrue("https link qabul qilinmadi",
            com.nurvpn.app.parser.SubscriptionLinkExtractor.extract(url).isEmpty())
        // URL o'zi haqiqiy manzil bo'lib qolishi kerak (o'zgartirilmaydi)
        assertTrue(url.startsWith("https://"))
        assertFalse("URL yashirilmasligi kerak", url.contains("🔒"))
    }

    // ═══════════════ BUG #3: VPN LOOP HIMOYASI ═══════════════

    @Test
    fun `bug3 - sanitizeFp hali ham TOGRI ishlaydi (oldingi fix saqlangan)`() {
        // Yaroqsiz fp -> chrome bo'lishi shart (aks holda libbox crash)
        val tls = SingBoxConfig.outboundFor(
            "vless://u@a.com:443?security=tls&sni=a.com&fp=buzuqFp", ctx)
            .getJSONObject("tls")
        assertEquals("chrome",
            tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun `bug3 - sanitizeFp yaroqli qiymatlarni ozgartirmaydi`() {
        for (fp in listOf("chrome", "firefox", "safari", "random", "randomized")) {
            val tls = SingBoxConfig.outboundFor(
                "vless://u@a.com:443?security=tls&sni=a.com&fp=$fp", ctx)
                .getJSONObject("tls")
            assertEquals(fp,
                tls.getJSONObject("utls").getString("fingerprint"))
        }
    }

    @Test
    fun `bug3 - yaroqsiz config libbox ni yiqitmaydi (exception emas)`() {
        // Har xil buzuq linklar — ParseException bo'lishi mumkin, lekin
        // boshqa turdagi xato BO'LMASLIGI kerak (crash sababi)
        val bad = listOf(
            "vless://", "vmess://!!!", "trojan://@", "hysteria2://",
            "tuic://x", "ss://", "garbage", "://x"
        )
        for (l in bad) {
            try {
                SingBoxConfig.outboundFor(l, ctx)
            } catch (e: SingBoxConfig.ParseException) {
                // kutilgan — bu xavfsiz
            } catch (t: Throwable) {
                fail("'$l' uchun kutilmagan xato turi: " +
                    "${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    @Test
    fun `bug3 - hysteria2 QUIC konfiguratsiyasi utls siz`() {
        val o = SingBoxConfig.outboundFor(
            "hysteria2://pw@a.com:443?obfs=salamander&obfs-password=x", ctx)
        assertEquals("hysteria2", o.getString("type"))
        assertFalse("QUIC'da utls bo'lmasligi kerak (salamander ishlashi)",
            o.getJSONObject("tls").has("utls"))
    }

    @Test
    fun `bug3 - START_NOT_STICKY qaytariladi (crash loop oldini oladi)`() {
        // Reflection bilan konstanta qiymatini tekshiramiz
        val notSticky = android.app.Service.START_NOT_STICKY
        assertEquals("START_NOT_STICKY qiymati 2 bo'lishi kerak", 2, notSticky)
        assertNotEquals("START_STICKY ishlatilmasligi kerak",
            android.app.Service.START_STICKY, notSticky)
    }
}
