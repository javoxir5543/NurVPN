from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/MainActivity.kt')
t = p.read_text(encoding='utf-8')

old = '''    fun selectServer(s: ServerItem) {'''

new = '''    fun selectServer(s: ServerItem) {
        android.util.Log.i("NurVPN-SELECT",
            "selectServer: ${s.displayName()}, isRunning=$isRunning, current=${currentServer?.displayName()}")'''

if 'NurVPN-SELECT' in t:
    print("✓ Log allaqachon bor")
elif old in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old, new, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ selectServer ga log qo'shildi")
else:
    print("⚠ Naqsh topilmadi")
