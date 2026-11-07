from pathlib import Path
import re
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/res/layout/item_home_card_header.xml')
if not p.exists():
    print("❌ XML fayl topilmadi")
    exit()

t = p.read_text(encoding='utf-8')

# Backup
bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
shutil.copy(p, bak)
print(f"📦 Backup: {bak.name}")

# card_info_traffic blokini topish va maxLines ni o'zgartirish
# (DeepSeek uni TextView qilib qo'shgan, id si card_info_traffic)
block_pattern = r'(<TextView\s+[^>]*?android:id="@\+id/card_info_traffic"[^>]*?)(/>)'
match = re.search(block_pattern, t, re.DOTALL)

if match:
    old_block = match.group(1)
    new_block = old_block
    
    # 1. maxLines="1" ni maxLines="2" ga o'zgartirish
    if 'android:maxLines="1"' in new_block:
        new_block = new_block.replace('android:maxLines="1"', 'android:maxLines="2"')
    elif 'android:maxLines' not in new_block:
        # Agar umuman maxLines bo'lmasa, qo'shamiz
        new_block += '\n            android:maxLines="2"'
        
    # 2. ellipsize="end" ni olib tashlash (yoki none qilish)
    if 'android:ellipsize="end"' in new_block:
        new_block = new_block.replace('android:ellipsize="end"', 'android:ellipsize="none"')
        
    # 3. layout_width ni match_parent qilish (agar wrap_content bo'lsa, joy yetmasligi mumkin)
    if 'android:layout_width="wrap_content"' in new_block:
        new_block = new_block.replace('android:layout_width="wrap_content"', 'android:layout_width="match_parent"')

    # O'zgartirishni faylga yozish
    t = t.replace(old_block + "/>", new_block + "/>")
    p.write_text(t, encoding='utf-8')
    print("✅ Matn kesilishi to'xtatildi (maxLines=2, ellipsize=none)")
else:
    print("⚠ card_info_traffic topilmadi. Ehtimol ID boshqacha.")

