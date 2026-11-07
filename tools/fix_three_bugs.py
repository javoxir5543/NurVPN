from pathlib import Path
import shutil
from datetime import datetime

ROOT = Path('/home/pc/NurVPN')

def backup(p):
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"   📦 {bak.name}")

def patch(path, old, new, name):
    if not path.exists():
        print(f"   ❌ {path.name} yo'q"); return False
    t = path.read_text(encoding='utf-8')
    if new in t and old not in t:
        print(f"   ✓ {name} — allaqachon bor"); return True
    if old not in t:
        print(f"   ⚠ {name} — naqsh topilmadi"); return False
    backup(path)
    path.write_text(t.replace(old, new, 1), encoding='utf-8')
    print(f"   ✅ {name}")
    return True

print("═" * 60)
print("  FIX #1 — Hysteria2 SNI + IP fallback")
print("═" * 60)

sbc = ROOT / "app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt"
if sbc.exists():
    t = sbc.read_text(encoding='utf-8')
    
    # 1a) SNI manbalarini kengaytirish
    old_sni = 'cleanSni(q["sni"]) ?: cleanSni(q["peer"]) ?: host'
    new_sni = 'cleanSni(q["sni"]) ?: cleanSni(q["peer"]) ?: cleanSni(q["serverName"]) ?: cleanSni(q["servername"]) ?: cleanSni(q["host"]) ?: host'
    
    if old_sni in t and new_sni not in t:
        backup(sbc)
        t = t.replace(old_sni, new_sni, 1)
        sbc.write_text(t, encoding='utf-8')
        print("   ✅ SNI manbalari kengaytirildi (peer/serverName/host)")
    elif new_sni in t:
        print("   ✓ SNI allaqachon kengaytirilgan")
    else:
        print("   ⚠ SNI naqshi topilmadi")

print()
print("═" * 60)
print("  FIX #2 — Asosiy tugma tepaga qaytishi")
print("═" * 60)

hf = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
if hf.exists():
    # 2a) onTabShown da restoreScrollPosition o'rniga scrollToTop
    old_tab = '''    fun onTabShown() {
        if (!isAdded || view == null) return
        android.util.Log.i("NurVPN-DBG", "HomeFragment: onTabShown")
        // Scroll pozitsiyasini tiklaymiz
        restoreScrollPosition()
        refresh()
    }'''
    
    new_tab = '''    fun onTabShown() {
        if (!isAdded || view == null) return
        android.util.Log.i("NurVPN-DBG", "HomeFragment: onTabShown")
        // FIX #2: Asosiy tugma bosilganda eng tepaga qaytish
        (view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.home_scroll_root))
            ?.scrollToPosition(0)
        refresh()
    }'''
    
    if new_tab in hf.read_text(encoding='utf-8'):
        print("   ✓ onTabShown allaqachon tuzatilgan")
    elif old_tab in hf.read_text(encoding='utf-8'):
        t = hf.read_text(encoding='utf-8')
        backup(hf)
        t = t.replace(old_tab, new_tab, 1)
        hf.write_text(t, encoding='utf-8')
        print("   ✅ onTabShown: restoreScrollPosition → scrollToPosition(0)")
    else:
        print("   ⚠ onTabShown naqshi topilmadi")

print()
print("═" * 60)
print("  FIX #3 — 200 limiti olib tashlash")
print("═" * 60)

if hf.exists():
    t = hf.read_text(encoding='utf-8')
    
    # 3a) take(200) ni olib tashlash
    old_take = '''            // Limit: faqat birinchi 500 link (xotira uchun)
            val limitedLinks = if (links.size > 200) {
                android.util.Log.w("NurVPN-DBG",
                    "loadSub: ${links.size} ta link, 200 taga cheklandi")
                links.take(200)
            } else links'''
    
    new_take = '''            // FIX #3: 200 limiti olib tashlandi (RecyclerView uchun xavfsiz)
            val limitedLinks = links'''
    
    if old_take in t:
        backup(hf)
        t = t.replace(old_take, new_take, 1)
        hf.write_text(t, encoding='utf-8')
        print("   ✅ take(200) olib tashlandi — barcha serverlar qoladi")
    elif "val limitedLinks = links" in t and "take(200)" not in t:
        print("   ✓ limit allaqachon olib tashlangan")
    else:
        print("   ⚠ take(200) naqshi topilmadi")

print()
print("═" * 60)
print("  NATIJA")
print("═" * 60)
print()
print("  Keyingi qadam:")
print("    ./gradlew assembleDebug")
print("    adb install -r app/build/outputs/apk/debug/app-debug.apk")
