from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

# toggleConnection dagi xato shartni topish
old = '''            if (MainActivity.PROTO_XRAY == a.protocol &&
                a.currentServer == null && a.servers.isEmpty()) {
                Toast.makeText(context, R.string.toast_add_server_first,
                    Toast.LENGTH_SHORT).show()
                return
            }
            if (MainActivity.PROTO_AWG == a.protocol && a.currentAWG == null) {
                Toast.makeText(context, R.string.toast_add_awg_first,
                    Toast.LENGTH_SHORT).show()
                return
            }'''

new = '''            if (MainActivity.PROTO_XRAY == a.protocol && a.servers.isEmpty()) {
                Toast.makeText(context, R.string.toast_add_server_first,
                    Toast.LENGTH_SHORT).show()
                return
            }
            // FIX v1.13: agar server tanlanmagan bo'lsa, birinchi serverni avtomatik tanlash
            if (MainActivity.PROTO_XRAY == a.protocol && a.currentServer == null) {
                a.selectServer(a.servers.first())
                android.util.Log.i("NurVPN-TOGGLE",
                    "Avtomatik tanlandi: ${a.servers.first().displayName()}")
            }
            if (MainActivity.PROTO_AWG == a.protocol && a.currentAWG == null) {
                Toast.makeText(context, R.string.toast_add_awg_first,
                    Toast.LENGTH_SHORT).show()
                return
            }'''

if 'Avtomatik tanlandi' in t:
    print("✓ allaqachon tuzatilgan")
elif old in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old, new, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ toggleConnection: avtomatik server tanlash qo'shildi")
else:
    print("⚠ Naqsh topilmadi. Kod o'zgargan:")
    for i, line in enumerate(t.split('\n'), 1):
        if 'toast_add_server_first' in line:
            print(f"  {i}: {line.strip()}")
