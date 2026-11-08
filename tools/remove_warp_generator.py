from pathlib import Path
import re
import shutil
from datetime import datetime

root = Path('/home/pc/NurVPN')
bak_dir = root / '.fix-backups'
bak_dir.mkdir(exist_ok=True)
ts = int(datetime.now().timestamp())

# 1. XML faylidan tugmani o'chirish
xml_path = root / 'app/src/main/res/layout/fragment_settings.xml'
if xml_path.exists():
    shutil.copy(xml_path, bak_dir / f"fragment_settings.xml.{ts}.bak")
    t = xml_path.read_text(encoding='utf-8')
    # Tugma blokini topib o'chirish (MaterialButton yoki Button)
    pattern = r'<(?:com\.google\.android\.material\.button\.)?MaterialButton[^>]*?android:id="@\+id/btn_warp_generate"[^>]*?>.*?</(?:com\.google\.android\.material\.button\.)?MaterialButton>'
    t = re.sub(pattern, '', t, flags=re.DOTALL)
    # Agar o'z-o'zini yopuvchi teg bo'lsa
    pattern_short = r'<(?:com\.google\.android\.material\.button\.)?MaterialButton[^>]*?android:id="@\+id/btn_warp_generate"[^>]*?/>'
    t = re.sub(pattern_short, '', t, flags=re.DOTALL)
    xml_path.write_text(t, encoding='utf-8')
    print("✅ fragment_settings.xml: WARP tugmasi o'chirildi.")

# 2. SettingsFragment.kt dan tugma mantiqini o'chirish
kt_path = root / 'app/src/main/java/com/nurvpn/app/ui/settings/SettingsFragment.kt'
if kt_path.exists():
    shutil.copy(kt_path, bak_dir / f"SettingsFragment.kt.{ts}.bak")
    t = kt_path.read_text(encoding='utf-8')
    
    # Listener blokini topib o'chirish
    # Naqsh: val warpBtn = ... dan boshlab, uning ichidagi onClickListener tugaguncha
    pattern = r'val warpBtn\s*=\s*.*?\.apply\s*\{.*?\n\s*\}\n'
    t = re.sub(pattern, '', t, flags=re.DOTALL)
    
    # Agar oddiyroq yozilgan bo'lsa
    pattern2 = r'val warpBtn\s*=.*?\n.*?warpBtn\.setOnClickListener\s*\{.*?\n\s*\}\n\s*\}\n'
    t = re.sub(pattern2, '', t, flags=re.DOTALL)
    
    # WarpMasque importlarini o'chirish
    t = re.sub(r'import com\.nurvpn\.app\.util\.WarpMasque\n', '', t)
    t = re.sub(r'import com\.nurvpn\.app\.util\.WarpGenerator\n', '', t)
    
    kt_path.write_text(t, encoding='utf-8')
    print("✅ SettingsFragment.kt: WARP tugmasi mantiqi o'chirildi.")

# 3. Ortiqcha fayllarni o'chirish
files_to_remove = [
    root / 'app/src/main/java/com/nurvpn/app/util/WarpMasque.kt',
    root / 'app/src/main/java/com/nurvpn/app/util/WarpGenerator.kt',
    root / 'app/src/main/java/com/nurvpn/app/util/X25519.kt'
]

for f in files_to_remove:
    if f.exists():
        shutil.move(str(f), str(bak_dir / f"{f.name}.{ts}.bak"))
        print(f"✅ {f.name} fayli .fix-backups ga ko'chirildi.")

print("\n=== TAYYOR! Endi build qiling ===")
