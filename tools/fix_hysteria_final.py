from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt')
t = p.read_text(encoding='utf-8')

# 1. Hysteria2 da TLS blokini topib, insecure=true qo'shish
old_tls = '        o.put("tls", baseTls(q, host, "h3", isQuic = true))'
new_tls = '''        // FIX: Hysteria2 uchun sertifikat tekshiruvini o'chirish (IP/self-signed uchun)
        val tlsObj = baseTls(q, host, "h3", isQuic = true)
        tlsObj.put("insecure", true)
        o.put("tls", tlsObj)'''

if 'val tlsObj = baseTls' in t:
    print("✓ TLS insecure allaqachon qo'shilgan")
elif old_tls in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old_tls, new_tls, 1)
    print("✅ Hysteria2: TLS insecure=true qo'shildi")
else:
    print("⚠ TLS naqshi topilmadi")

# 2. Log qo'shish (qurilmada obfs va insecure ni ko'rish uchun)
old_log = '        o.put("password", u.userInfo ?: "")'
new_log = '''        o.put("password", u.userInfo ?: "")
        android.util.Log.i("NurVPN-HY2",
            "hysteria2: host=$host, obfs=${q["obfs"]}, insecure=true (majburiy)")'''

if 'NurVPN-HY2' in t:
    print("✓ Log allaqachon qo'shilgan")
elif old_log in t:
    t = t.replace(old_log, new_log, 1)
    print("✅ Hysteria2: Log qo'shildi (NurVPN-HY2)")
else:
    print("⚠ Log naqshi topilmadi")

p.write_text(t, encoding='utf-8')
print("✅ Fayl saqlandi")
