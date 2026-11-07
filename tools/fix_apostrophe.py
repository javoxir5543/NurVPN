from pathlib import Path
import re
import shutil
from datetime import datetime

root = Path('/home/pc/NurVPN/app/src/main/res')
bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
ts = int(datetime.now().timestamp())

xml_path = root / 'values-uz' / 'strings.xml'
if xml_path.exists():
    shutil.copy(xml_path, bak_dir / f"strings.xml.values-uz.{ts}.bak")
    content = xml_path.read_text(encoding='utf-8')
    
    # Apostrofni escape qilish: Qo'llab-quvvatlash -> Qo\'llab-quvvatlash
    content = content.replace(
        '>Qo\'llab-quvvatlash<',
        '>Qo\\\'llab-quvvatlash<'
    )
    
    xml_path.write_text(content, encoding='utf-8')
    print("✅ values-uz/strings.xml: Apostrof to'g'rilandi")
    
# Tekshirish
import subprocess
result = subprocess.run(['grep', '-n', 'sub_btn_support', str(xml_path)], capture_output=True, text=True)
print(f"\nNatija: {result.stdout}")
