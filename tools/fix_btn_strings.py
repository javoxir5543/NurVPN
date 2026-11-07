from pathlib import Path
import re
import shutil
from datetime import datetime

root = Path('/home/pc/NurVPN/app/src/main/res')
bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
ts = int(datetime.now().timestamp())

translations = {
    'values': {'support': 'Support', 'web': 'Website'},
    'values-en': {'support': 'Support', 'web': 'Website'},
    'values-ru': {'support': 'Поддержка', 'web': 'Сайт'},
    'values-uz': {'support': "Qo'llab-quvvatlash", 'web': 'Sayt'}
}

for folder, texts in translations.items():
    xml_path = root / folder / 'strings.xml'
    if not xml_path.exists():
        continue

    shutil.copy(xml_path, bak_dir / f"strings.xml.{folder}.{ts}.bak")

    content = xml_path.read_text(encoding='utf-8')

    content = re.sub(
        r'<string name="sub_btn_support">[^<]*</string>',
        f'<string name="sub_btn_support">{texts["support"]}</string>',
        content
    )
    content = re.sub(
        r'<string name="sub_btn_web">[^<]*</string>',
        f'<string name="sub_btn_web">{texts["web"]}</string>',
        content
    )

    xml_path.write_text(content, encoding='utf-8')
    print(f"✅ {folder}: '{texts['support']}' / '{texts['web']}'")

print("\n=== TAYYOR! Endi build qiling ===")
