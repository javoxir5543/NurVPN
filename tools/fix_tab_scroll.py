from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

old_block = '''    fun onTabShown() {
        if (!isAdded || view == null) return
        android.util.Log.i("NurVPN-DBG", "HomeFragment: onTabShown")
        // Scroll pozitsiyasini tiklaymiz
        restoreScrollPosition()
        // Ma'lumotlarni yangilaymiz (server o'zgargan bo'lishi mumkin)
        refresh()
    }'''

new_block = '''    fun onTabShown() {
        if (!isAdded || view == null) return
        android.util.Log.i("NurVPN-DBG", "HomeFragment: onTabShown")
        // FIX #2: Asosiy tugma bosilganda eng tepaga (VPN tugmasiga) qaytish
        view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.home_scroll_root)
            ?.scrollToPosition(0)
        // Ma'lumotlarni yangilaymiz
        refresh()
    }'''

if 'FIX #2: Asosiy tugma bosilganda eng tepaga' in t:
    print("✓ allaqachon tuzatilgan")
elif old_block in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old_block, new_block, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ onTabShown(): restoreScrollPosition → scrollToPosition(0)")
else:
    print("⚠ Naqsh topilmadi. Kodni qo'lda tekshiring.")
