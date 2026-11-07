from pathlib import Path
import shutil
from datetime import datetime

ROOT = Path('/home/pc/NurVPN')
changed_files = []

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
    changed_files.append(path.name)
    return True

print("═" * 60)
print("  [1/4] HomeFragment — refreshOneSub() public funksiyasi")
print("═" * 60)

hf = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
hf_text = hf.read_text(encoding='utf-8') if hf.exists() else ""

anchor = "    fun refreshSubscriptions() {"
new_func = """    /** FIX: Bitta obunani yangilash (MainActivity navbati uchun public). */
    fun refreshOneSub(subId: String) {
        val a = activity as? MainActivity ?: return
        val sub = a.subscriptions.find { it.id == subId } ?: return
        android.util.Log.i("NurVPN-REFRESH", "refreshOneSub: ${sub.name}")
        loadSubscription(sub.url, sub.name)
    }

"""

if "fun refreshOneSub" in hf_text:
    print("   ✓ refreshOneSub() allaqachon bor")
elif anchor in hf_text:
    backup(hf)
    hf_text = hf_text.replace(anchor, new_func + anchor, 1)
    hf.write_text(hf_text, encoding='utf-8')
    print("   ✅ refreshOneSub() qo'shildi")
    changed_files.append("HomeFragment.kt")
else:
    print("   ⚠ refreshSubscriptions() naqshi topilmadi")

print()
print("═" * 60)
print("  [2/4] MainActivity — enqueueRefresh() + navbat")
print("═" * 60)

ma = ROOT / "app/src/main/java/com/nurvpn/app/ui/MainActivity.kt"
ma_text = ma.read_text(encoding='utf-8') if ma.exists() else ""

anchor2 = "    fun switchToTab(index: Int) {"
new_func2 = """    // ═══ FIX v1.13: Ochiq manbalar yangilash navbati (2s interval) ═══
    private val refreshQueue = ArrayDeque<String>()
    private var refreshRunning = false
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Ochiq manba yoqilganda navbatga qo'shish (2s interval bilan). */
    fun enqueueRefresh(subId: String) {
        if (refreshQueue.contains(subId)) {
            android.util.Log.i("NurVPN-REFRESH", "enqueue: $subId allaqachon navbatda")
            return
        }
        refreshQueue.addLast(subId)
        android.util.Log.i("NurVPN-REFRESH", "enqueue: $subId, navbat=${refreshQueue.size}")
        if (!refreshRunning) runNextRefresh()
    }

    private fun runNextRefresh() {
        val subId = refreshQueue.removeFirstOrNull() ?: run {
            refreshRunning = false
            return
        }
        refreshRunning = true
        val f = supportFragmentManager.findFragmentByTag("tab_home") as? HomeFragment
        if (f != null && f.isAdded) {
            f.refreshOneSub(subId)
        } else {
            android.util.Log.w("NurVPN-REFRESH", "HomeFragment topilmadi, skip: $subId")
        }
        refreshHandler.postDelayed({ runNextRefresh() }, 2000L)
    }

"""

if "fun enqueueRefresh" in ma_text:
    print("   ✓ enqueueRefresh() allaqachon bor")
elif anchor2 in ma_text:
    backup(ma)
    ma_text = ma_text.replace(anchor2, new_func2 + anchor2, 1)
    ma.write_text(ma_text, encoding='utf-8')
    print("   ✅ enqueueRefresh() + navbat qo'shildi")
    changed_files.append("MainActivity.kt")
else:
    print("   ⚠ switchToTab() naqshi topilmadi")

print()
print("═" * 60)
print("  [3/4] SettingsFragment — os_switch listeneriga ulash")
print("═" * 60)

sf = ROOT / "app/src/main/java/com/nurvpn/app/ui/settings/SettingsFragment.kt"
sf_text = sf.read_text(encoding='utf-8') if sf.exists() else ""

old_switch = """sw.setOnCheckedChangeListener { _, checked ->
            OpenSourceStore.setEnabled(requireContext(), open.id, checked)"""
new_switch = """sw.setOnCheckedChangeListener { _, checked ->
            OpenSourceStore.setEnabled(requireContext(), open.id, checked)
            // FIX v1.13: yoqilganda darhol yangilash (2s navbat bilan)
            if (checked) {
                val subId = OpenSourceStore.subId(open.id)
                (activity as? MainActivity)?.enqueueRefresh(subId)
            }"""

if "enqueueRefresh(subId)" in sf_text:
    print("   ✓ os_switch listener allaqachon ulangan")
elif old_switch in sf_text:
    backup(sf)
    sf_text = sf_text.replace(old_switch, new_switch, 1)
    sf.write_text(sf_text, encoding='utf-8')
    print("   ✅ os_switch listeneriga enqueueRefresh() ulandi")
    changed_files.append("SettingsFragment.kt")
else:
    print("   ⚠ os_switch listener naqshi topilmadi")

print()
print("═" * 60)
print("  [4/4] Worker — lastUpdated yangilashni olib tashlash")
print("═" * 60)

worker = ROOT / "app/src/main/java/com/nurvpn/app/worker/OpenSourcesRefreshWorker.kt"
if worker.exists():
    w_text = worker.read_text(encoding='utf-8')
    old_upd = "sub.lastUpdated = System.currentTimeMillis()"
    new_upd = "// FIX v1.13: Worker lastUpdated yangilamaydi\n                // sub.lastUpdated = System.currentTimeMillis()"
    
    if "// FIX v1.13: Worker lastUpdated yangilamaydi" in w_text:
        print("   ✓ Worker allaqachon tuzatilgan")
    elif old_upd in w_text:
        backup(worker)
        w_text = w_text.replace(old_upd, new_upd, 1)
        worker.write_text(w_text, encoding='utf-8')
        print("   ✅ Worker: lastUpdated yangilash o'chirildi")
        changed_files.append("OpenSourcesRefreshWorker.kt")
    else:
        print("   ⚠ lastUpdated yangilash topilmadi")
else:
    print("   ⚠ Worker fayli yo'q")

print()
print("═" * 60)
print("  NATIJA")
print("═" * 60)
if changed_files:
    print(f"   ✅ {len(changed_files)} fayl o'zgartirildi:")
    for f in changed_files:
        print(f"      • {f}")
else:
    print("   ⚠ Hech qanday o'zgarish yo'q")
print()
