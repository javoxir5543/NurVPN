from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/MainActivity.kt')
t = p.read_text(encoding='utf-8')

# Aniq naqsh (diagnostikadan)
old_block = """    private fun refreshHome() {
        val f = supportFragmentManager.findFragmentById(R.id.container)
        if (f is HomeFragment) f.refresh()
    }"""

# Yangi: tag bilan aniq qidirish + isAdded tekshiruvi
new_block = """    private fun refreshHome() {
        // FIX: add/hide/show arxitekturasida container'da 3 ta fragment bor.
        // findFragmentById birinchisini qaytaradi (ko'pincha noto'g'ri).
        // Tag bilan ANIQ qidiramiz — switchTab() shu tag bilan qo'shadi.
        val f = supportFragmentManager.findFragmentByTag(tagFor(TAB_HOME))
        if (f is HomeFragment && f.isAdded) f.refresh()
    }"""

if old_block in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old_block, new_block, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ refreshHome() tuzatildi:")
    print("   findFragmentById → findFragmentByTag(tagFor(TAB_HOME))")
    print("   isAdded tekshiruvi qo'shildi")
elif "findFragmentByTag(tagFor(TAB_HOME))" in t:
    print("✓ allaqachon tuzatilgan")
else:
    print("⚠ Naqsh topilmadi. Qo'lda tekshiring:")
    for i, line in enumerate(t.split('\n'), 1):
        if 'refreshHome' in line:
            print(f"  {i}: {line}")
