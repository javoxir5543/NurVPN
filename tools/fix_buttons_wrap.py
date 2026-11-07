from pathlib import Path
import re
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/res/layout/item_home_card_header.xml')
t = p.read_text(encoding='utf-8')

# Xavfsiz zaxira nusxa (.fix-backups ga)
bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
bak = bak_dir / f"item_home_card_header.xml.{int(datetime.now().timestamp())}.bak"
shutil.copy(p, bak)
print(f"📦 Backup: {bak.name}")

# 1. Support tugmasi (kenglikni matnga moslash, shriftni 10sp qilish, masofa qo'shish)
match_sup = re.search(r'(<TextView[^>]*?android:id="@\+id/card_info_btn_support"[^>]*?>)', t, re.DOTALL)
if match_sup:
    tag = match_sup.group(1)
    tag = tag.replace('android:layout_width="0dp"', 'android:layout_width="wrap_content"')
    tag = re.sub(r'android:layout_weight="[^"]+"', '', tag)
    tag = re.sub(r'android:textSize="[^"]+"', 'android:textSize="10sp"', tag)
    tag = tag.replace('android:paddingStart="10dp"', 'android:paddingStart="8dp"').replace('android:paddingEnd="10dp"', 'android:paddingEnd="8dp"')
    if 'android:layout_marginEnd' not in tag:
        tag = tag.replace('>', ' android:layout_marginEnd="8dp">')
    t = t.replace(match_sup.group(1), tag)

# 2. Web tugmasi (kenglikni matnga moslash, shriftni 10sp qilish)
match_web = re.search(r'(<TextView[^>]*?android:id="@\+id/card_info_btn_web"[^>]*?>)', t, re.DOTALL)
if match_web:
    tag = match_web.group(1)
    tag = tag.replace('android:layout_width="0dp"', 'android:layout_width="wrap_content"')
    tag = re.sub(r'android:layout_weight="[^"]+"', '', tag)
    tag = re.sub(r'android:textSize="[^"]+"', 'android:textSize="10sp"', tag)
    tag = tag.replace('android:paddingStart="10dp"', 'android:paddingStart="8dp"').replace('android:paddingEnd="10dp"', 'android:paddingEnd="8dp"')
    t = t.replace(match_web.group(1), tag)

# 3. Tugmalar qatorini chapga tekislash (chiroyli turishi uchun)
match_row = re.search(r'(<LinearLayout[^>]*?android:id="@\+id/card_info_btn_row"[^>]*?>)', t, re.DOTALL)
if match_row:
    tag = match_row.group(1)
    tag = re.sub(r'android:orientation="[^"]+"', 'android:orientation="horizontal"', tag)
    tag = re.sub(r'android:gravity="[^"]+"', 'android:gravity="start|center_vertical"', tag)
    if 'android:gravity' not in tag:
        tag = tag.replace('>', ' android:gravity="start|center_vertical">')
    t = t.replace(match_row.group(1), tag)

p.write_text(t, encoding='utf-8')
print("✅ Tugmalar matnga moslashtirildi (wrap_content) va shrift 10sp qilindi.")
