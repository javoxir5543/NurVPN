from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/config/SingBoxConfig.kt')
t = p.read_text(encoding='utf-8')

# 1. Loglarni to'g'rilash (haqiqiy JSON holatini ko'rsatish)
old_log = '''        Log.i(TAG, "Hysteria2: $host:$port insecure=${insecureOf(q)}")
        return o'''
        
new_log = '''        Log.i(TAG, "Hysteria2: $host:$port insecure=TRUE (majburiy), obfs=$obfs, obfs-pass=${if (q["obfs-password"] != null) "BOR" else "YO'Q"}")
        Log.i(TAG, "Hysteria2 JSON: $o")
        return o'''

if 'Hysteria2 JSON: $o' in t:
    print("✓ Log allaqachon tuzatilgan")
elif old_log in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old_log, new_log, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ Hysteria2 loglari to'g'rilandi")
else:
    print("⚠ Log naqshi topilmadi")

# 2. Utls holatini tekshirish
print("\n═══ Utls holati (Hysteria2 uchun) ═══")
import re
utls_match = re.search(r'isQuic\s*=\s*true.*?utls', t, re.DOTALL)
if 'utls' in t and 'isQuic = true' in t:
    print("⚠ Utls isQuic=true da o'chirilgan. Ba'zi serverlar uchun utls kerak bo'lishi mumkin.")
