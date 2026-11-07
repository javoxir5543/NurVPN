from pathlib import Path
import re
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/res/layout/item_home_card_header.xml')
if not p.exists():
    print("❌ Fayl topilmadi")
    exit()

t = p.read_text(encoding='utf-8')

# Backup
bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
shutil.copy(p, bak)
print(f"📦 Backup: {bak.name}")

# 1. Tugmalar qatorini gorizontal (yonma-yon) qilish
t = re.sub(r'(android:id="@\+id/card_info_btn_row".*?android:orientation=")[^"]+(")', r'\g<1>horizontal\2', t, flags=re.DOTALL)

# 2. Support tugmasini teng kenglik va kichik shrift qilish
t = re.sub(r'(android:id="@\+id/card_info_btn_support".*?android:layout_width=")[^"]+(")', r'\g<1>0dp\2', t, flags=re.DOTALL)
if 'android:layout_weight' not in t.split('card_info_btn_support')[1].split('>')[0]:
    t = t.replace('android:id="@+id/card_info_btn_support"', 'android:id="@+id/card_info_btn_support"\n            android:layout_weight="1"')
t = re.sub(r'(android:id="@\+id/card_info_btn_support".*?android:textSize=")[^"]+(")', r'\g<1>11sp\2', t, flags=re.DOTALL)
if 'android:textSize' not in t.split('card_info_btn_support')[1].split('>')[0]:
    t = t.replace('android:id="@+id/card_info_btn_support"', 'android:id="@+id/card_info_btn_support"\n            android:textSize="11sp"')

# 3. Sayt tugmasini teng kenglik va kichik shrift qilish
t = re.sub(r'(android:id="@\+id/card_info_btn_web".*?android:layout_width=")[^"]+(")', r'\g<1>0dp\2', t, flags=re.DOTALL)
if 'android:layout_weight' not in t.split('card_info_btn_web')[1].split('>')[0]:
    t = t.replace('android:id="@+id/card_info_btn_web"', 'android:id="@+id/card_info_btn_web"\n            android:layout_weight="1"')
t = re.sub(r'(android:id="@\+id/card_info_btn_web".*?android:textSize=")[^"]+(")', r'\g<1>11sp\2', t, flags=re.DOTALL)
if 'android:textSize' not in t.split('card_info_btn_web')[1].split('>')[0]:
    t = t.replace('android:id="@+id/card_info_btn_web"', 'android:id="@+id/card_info_btn_web"\n            android:textSize="11sp"')

# 4. Sayt tugmasining chap tomoniga 8dp masofa qo'shish (ustma-ustlikdan qutulish)
# Avvalgi vertikal holatdagi marginTop ni o'chirib, marginStart qilamiz
t = re.sub(r'(android:id="@\+id/card_info_btn_web".*?)android:layout_marginTop="[^"]+"', r'\1', t, flags=re.DOTALL)
if 'android:layout_marginStart' not in t.split('card_info_btn_web')[1].split('>')[0]:
    t = t.replace('android:id="@+id/card_info_btn_web"', 'android:id="@+id/card_info_btn_web"\n            android:layout_marginStart="8dp"')

p.write_text(t, encoding='utf-8')
print("✅ Tugmalar yana yonma-yon (gorizontal) qilindi va shrift 11sp ga tushirildi.")
