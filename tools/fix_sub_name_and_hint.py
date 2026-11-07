from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

changed = False

# ═══ FIX 1: "ko'rish uchun bosing" matnlarini o'chirish ═══
# 844-qator: Fav card hint
old_844 = """                out.add(HomeItem.Hint(getString(R.string.text_expand_to_view,
                    list.size)))"""
new_844 = """                // FIX: hint matni o'chirildi
                out.add(HomeItem.Hint(""))"""
if old_844 in t:
    t = t.replace(old_844, new_844, 1)
    print("✅ [1/2] Fav card hint o'chirildi (844)")
    changed = True

# 864-qator: Sub card hint
old_864 = """                if (!homeExpandedSubs.contains(sub.id))
                    out.add(HomeItem.Hint(
                        getString(R.string.text_expand_to_view, list.size)))"""
new_864 = """                // FIX: hint matni o'chirildi
                if (!homeExpandedSubs.contains(sub.id))
                    out.add(HomeItem.Hint(""))"""
if old_864 in t:
    t = t.replace(old_864, new_864, 1)
    print("✅ [2/2] Sub card hint o'chirildi (864)")
    changed = True

# 880-qator: Manual card hint
old_880 = """            if (!homeExpandedSubs.contains("manual"))
                out.add(HomeItem.Hint(
                    getString(R.string.text_expand_to_view, manual.size)))"""
new_880 = """            // FIX: hint matni o'chirildi
            if (!homeExpandedSubs.contains("manual"))
                out.add(HomeItem.Hint(""))"""
if old_880 in t:
    t = t.replace(old_880, new_880, 1)
    print("✅ [3/3] Manual card hint o'chirildi (880)")
    changed = True

# ═══ FIX 2: extractSubTitle funksiyasini qo'shish ═══
# HomeFragment da yo'qligini tekshirish
if 'private fun extractSubTitle' not in t:
    # getHwid funksiyasidan oldin qo'shamiz
    anchor = "    private fun getHwid(): String = HwidHelper.fromContext(requireContext())"
    if anchor in t:
        new_func = """    /**
     * URL'dan qisqa nom chiqarish (ServersFragment dan ko'chirilgan).
     * sub.example.com/path -> "Example"
     * gist.githubusercontent.com/... -> "Githubusercontent"
     */
    private fun extractSubTitle(url: String): String {
        return try {
            val host = java.net.URL(url).host
            val parts = host.split(".")
            when {
                parts.size >= 2 && parts[0].length < 4 -> parts[1]
                parts.isNotEmpty() -> parts[0]
                else -> "Subscription"
            }.replaceFirstChar { it.uppercase() }
        } catch (t: Throwable) { "Subscription" }
    }

"""
        t = t.replace(anchor, new_func + anchor, 1)
        print("✅ extractSubTitle() funksiyasi qo'shildi")
        changed = True
    else:
        print("⚠ getHwid anchor topilmadi")

# ═══ FIX 3: "Subscription" o'rniga extractSubTitle(url) ═══
# JSON sub (2966)
old_json = 'headerTitle ?: subName ?: "JSON Sub"'
new_json = 'headerTitle ?: subName ?: extractSubTitle(url)'
if old_json in t:
    t = t.replace(old_json, new_json, 1)
    print('✅ JSON sub nomi: extractSubTitle(url) ga o\'zgardi')
    changed = True

# Oddiy sub (3073)
old_plain = 'headerTitle ?: subName ?: "Subscription"'
new_plain = 'headerTitle ?: subName ?: extractSubTitle(url)'
if old_plain in t:
    t = t.replace(old_plain, new_plain, 1)
    print('✅ Oddiy sub nomi: extractSubTitle(url) ga o\'zgardi')
    changed = True

if changed:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    p.write_text(t, encoding='utf-8')
    print("✅ Fayl saqlandi")
else:
    print("⚠ Hech qanday o'zgarish yo'q")
