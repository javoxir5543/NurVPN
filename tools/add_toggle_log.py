from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

old = '''    private fun toggleConnection() {
        // Ochiq manba ham, obuna ham yo'q — dialog ko'rsatamiz
        val a0 = activity as? MainActivity'''

new = '''    private fun toggleConnection() {
        // Ochiq manba ham, obuna ham yo'q — dialog ko'rsatamiz
        val a0 = activity as? MainActivity
        android.util.Log.i("NurVPN-TOGGLE",
            "toggleConnection: subs=${a0?.subscriptions?.size}, servers=${a0?.servers?.size}, awg=${a0?.awgConfigs?.size}, currentServer=${a0?.currentServer?.displayName()}, protocol=${a0?.protocol}")'''

if 'NurVPN-TOGGLE' in t:
    print("✓ Log allaqachon bor")
elif old in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old, new, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ toggleConnection ga log qo'shildi")
else:
    print("⚠ Naqsh topilmadi")
