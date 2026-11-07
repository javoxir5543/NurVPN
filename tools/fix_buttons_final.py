from pathlib import Path
import re
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/res/layout/item_home_card_header.xml')
t = p.read_text(encoding='utf-8')

# Backup
bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
bak = bak_dir / f"item_home_card_header.xml.{int(datetime.now().timestamp())}.bak"
shutil.copy(p, bak)
print(f"📦 Backup: {bak.name}")

def update_tag(text, tag_id, new_attrs):
    # Tegni topish va ichidagi atributlarni xavfsiz almashtirish
    pattern = re.compile(r'(<[a-zA-Z]+[^>]*?android:id="@\+id/' + tag_id + r'"[^>]*?)(/?>)', re.DOTALL)
    
    def replacer(match):
        prefix = match.group(1)
        closing = match.group(2) # > yoki />
        
        for k, v in new_attrs.items():
            if v is None:
                # Atributni o'chirish
                prefix = re.sub(rf'\s*{k}="[^"]*"', '', prefix)
            else:
                if f'{k}=' in prefix:
                    # Mavjud atributni almashtirish
                    prefix = re.sub(rf'{k}="[^"]*"', f'{k}="{v}"', prefix)
                else:
                    # Yangi atribut qo'shish (closing dan oldin)
                    prefix += f'\n            {k}="{v}"'
        return prefix + closing
        
    return pattern.sub(replacer, text, count=1)

# 1. Qator konteyneri (chapga tekislash)
t = update_tag(t, 'card_info_btn_row', {
    'android:orientation': 'horizontal',
    'android:gravity': 'start|center_vertical'
})

# 2. Support tugmasi (matnga moslash, ortiqcha masofalarni o'chirish)
t = update_tag(t, 'card_info_btn_support', {
    'android:layout_width': 'wrap_content',
    'android:layout_weight': None, 
    'android:textSize': '11sp',
    'android:layout_marginEnd': None
})

# 3. Web tugmasi (matnga moslash, 8dp chap masofa)
t = update_tag(t, 'card_info_btn_web', {
    'android:layout_width': 'wrap_content',
    'android:layout_weight': None,
    'android:textSize': '11sp',
    'android:layout_marginStart': '8dp'
})

p.write_text(t, encoding='utf-8')
print("✅ Tugmalar xavfsiz tuzatildi (wrap_content, 11sp, yonma-yon).")
