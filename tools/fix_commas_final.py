from pathlib import Path
import re
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/config/BuiltinAwgConfigs.kt')
t = p.read_text(encoding='utf-8')

bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
shutil.copy(p, bak_dir / f"BuiltinAwgConfigs.kt.{int(datetime.now().timestamp())}.bak")

# Vergul yo'q qatorlarni topib, ularning oxiriga vergul qo'shamiz
# Naqsh: "masque_X" to MASQUE_X (vergul yo'q bo'lsa)
t = re.sub(r'("masque_\d+"\s+to\s+MASQUE_\d+)(?!\s*,)', r'\1,', t)

p.write_text(t, encoding='utf-8')
print("✅ Vergullar qo'shildi.")
