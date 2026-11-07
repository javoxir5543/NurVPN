from pathlib import Path
import shutil
import re
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

# 1. Zaxira nusxa yaratish
bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
shutil.copy(p, bak)
print(f"📦 Backup: {bak.name}")

# 2. "text_expand_to_view" matnini bo'sh satr ("") ga almashtirish
# Regex orqali barcha holatlarni qamrab olamiz (list.size, manual.size va h.k.)
old_text_pattern = r'getString\(R\.string\.text_expand_to_view,\s*[^)]+\)'
t, count = re.subn(old_text_pattern, '""', t)
print(f"✅ 'ko'rish uchun bosing' matni {count} ta joydan o'chirildi (\"\" ga almashtirildi)")

# 3. HintVH klassini yangilash (bo'sh matn ko'rinsa ham joy egallamasligi uchun)
old_hint_vh = """    private inner class HintVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(text: String) {
            itemView.findViewById<TextView>(R.id.hint_text).text = text
        }
    }"""

new_hint_vh = """    private inner class HintVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(text: String) {
            val tv = itemView.findViewById<TextView>(R.id.hint_text)
            tv.text = text
            // FIX: Agar matn bo'sh bo'lsa, TextView butunlay yashiriladi
            tv.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }"""

if old_hint_vh in t:
    t = t.replace(old_hint_vh, new_hint_vh, 1)
    print("✅ HintVH.bind() yangilandi: bo'sh matn ko'rinmaydi (View.GONE)")
else:
    print("⚠ HintVH.bind() naqshi topilmadi, lekin matn o'chirilgan bo'lishi kerak.")

# 4. Saqlash
p.write_text(t, encoding='utf-8')
print("✅ Fayl muvaffaqiyatli saqlandi!")
