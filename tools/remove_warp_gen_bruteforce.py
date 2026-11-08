from pathlib import Path
import re, shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/settings/SettingsFragment.kt')
bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
shutil.copy(p, bak_dir / f"SettingsFragment.kt.{int(datetime.now().timestamp())}.bak")

t = p.read_text(encoding='utf-8')

# 1. Selftest Thread blokini o'chirish
selftest_pattern = r'Thread\s*\{\s*if\s*\(!com\.nurvpn\.app\.util\.WarpGenerator\.selftest\(\)\).*?\}\.start\(\)'
t = re.sub(selftest_pattern, '// WARP SELFTEST REMOVED', t, flags=re.DOTALL)

# 2. Tugma va uning ichidagi barcha kodlarni o'chirish
# view.findViewById<android.widget.Button>(R.id.warp_generate_btn) dan boshlab, uning yopilishigacha
button_pattern = r'view\.findViewById<android\.widget\.Button>\(R\.id\.warp_generate_btn\).*?onWarpGenerated\(act, res\.conf, res\.endpoint, res\.error\)\s*\}\s*\}\.start\(\)\s*\}'
t = re.sub(button_pattern, '// WARP BUTTON REMOVED', t, flags=re.DOTALL)

# 3. Qolgan barcha WarpGenerator murojaatlarini o'chirish (ehtiyot uchun)
t = re.sub(r'com\.nurvpn\.app\.util\.WarpGenerator\.selftest\(\)', 'false', t)
t = re.sub(r'com\.nurvpn\.app\.util\.WarpGenerator\.generate\(\)', 'null', t)

# 4. onWarpGenerated funksiyasini o'chirish (agar mavjud bo'lsa)
t = re.sub(r'private\s+fun\s+onWarpGenerated\s*\([^)]*\)\s*\{.*?\n\s*\}', '// onWarpGenerated REMOVED', t, flags=re.DOTALL)

p.write_text(t, encoding='utf-8')
print("✅ Barcha WarpGenerator murojaatlari o'chirildi.")
