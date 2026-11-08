from pathlib import Path
import re, shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/settings/SettingsFragment.kt')
bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
shutil.copy(p, bak_dir / f"SettingsFragment.kt.{int(datetime.now().timestamp())}.bak")

t = p.read_text(encoding='utf-8')

# 1. Butun WARP generator blokini o'chirish (selftest va onClickListener)
pattern = r'// ★ Bir marta RFC 7748 vektorlarini tekshiramiz.*?\.start\(\)\s*\}'
t = re.sub(pattern, '// WARP GENERATOR O\'CHIRILDI', t, flags=re.DOTALL)

# 2. onWarpGenerated funksiyasini o'chirish
pattern2 = r'(private\s+fun\s+onWarpGenerated\(.*?\n\s*\}\n)'
t = re.sub(pattern2, '', t, flags=re.DOTALL)

# 3. WarpGenerator/WarpMasque importlarini o'chirish
t = re.sub(r'import com\.nurvpn\.app\.util\.WarpGenerator\n', '', t)
t = re.sub(r'import com\.nurvpn\.app\.util\.WarpMasque\n', '')

# 4. WarpGenerator/WarpMasque ga qolgan barcha murojaatlarni o'chirish
t = re.sub(r'^\s*com\.nurvpn\.app\.util\.WarpGenerator\..*$', '        // WARPGEN REMOVED', t, flags=re.MULTILINE)
t = re.sub(r'^\s*com\.nurvpn\.app\.util\.WarpMasque\..*$', '        // WARPMASQUE REMOVED', t, flags=re.MULTILINE)

p.write_text(t, encoding='utf-8')
print("✅ SettingsFragment.kt: Barcha WARP generator murojaatlari o'chirildi.")

# 5. XML dan tugmani ham o'chirish
xml = Path('/home/pc/NurVPN/app/src/main/res/layout/fragment_settings.xml')
if xml.exists():
    shutil.copy(xml, bak_dir / f"fragment_settings.xml.{int(datetime.now().timestamp())}.bak")
    x = xml.read_text(encoding='utf-8')
    # Tugma blokini o'chirish (warp_generate_btn)
    x = re.sub(r'<Button[^>]*?android:id="@\+id/warp_generate_btn"[^>]*?/>', '', x, flags=re.DOTALL)
    x = re.sub(r'<com\.google\.android\.material\.button\.MaterialButton[^>]*?android:id="@\+id/warp_generate_btn"[^>]*?/>', '', x, flags=re.DOTALL)
    x = re.sub(r'<Button[^>]*?android:id="@\+id/warp_generate_btn"[^>]*?>.*?</Button>', '', x, flags=re.DOTALL)
    x = re.sub(r'<com\.google\.android\.material\.button\.MaterialButton[^>]*?android:id="@\+id/warp_generate_btn"[^>]*?>.*?</com\.google\.android\.material\.button\.MaterialButton>', '', x, flags=re.DOTALL)
    xml.write_text(x, encoding='utf-8')
    print("✅ fragment_settings.xml: WARP tugmasi o'chirildi.")
