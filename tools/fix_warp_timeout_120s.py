from pathlib import Path
import re, shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/util/WarpMasque.kt')
t = p.read_text(encoding='utf-8')
bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
shutil.copy(p, bak_dir / f"WarpMasque.kt.{int(datetime.now().timestamp())}.bak")

# Timeout larni 25 soniyaga oshiramiz
t = re.sub(r'CONNECT_TIMEOUT\s*=\s*\d+', 'CONNECT_TIMEOUT = 25000', t)
t = re.sub(r'HANDSHAKE_TIMEOUT\s*=\s*\d+', 'HANDSHAKE_TIMEOUT = 25000', t)
t = re.sub(r'READ_TIMEOUT\s*=\s*\d+', 'READ_TIMEOUT = 30000', t)

p.write_text(t, encoding='utf-8')
print("✅ Timeout'lar 25 soniyaga oshirildi.")
