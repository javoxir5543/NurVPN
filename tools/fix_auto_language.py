from pathlib import Path
import shutil
from datetime import datetime
import re

root = Path('/home/pc/NurVPN')
bak_dir = root / '.fix-backups'
bak_dir.mkdir(exist_ok=True)
ts = int(datetime.now().timestamp())

print("=== LANG_AUTO_FIX_V113: Tilni avtomatik aniqlash ===")

# 1. Manifestdan Application klassini qidiramiz
manifest_path = root / 'app/src/main/AndroidManifest.xml'
manifest = manifest_path.read_text(encoding='utf-8')
app_match = re.search(r'<application[^>]*android:name="([^"]+)"', manifest)
existing_app = app_match.group(1).lstrip('.') if app_match else None
print(f"Application class: {existing_app or 'NONE (default)'}")

# 2. Kod (FIX)
LANG_FIX = '''        // LANG_AUTO_FIX_V113: Tilni avtomatik aniqlash (EN zaxira bilan)
        run {
            val saved = com.nurvpn.app.util.ThemeHelper.getLanguage(this)
            val targetLang = if (saved.isNotEmpty()) saved else {
                val sys = java.util.Locale.getDefault().language
                if (sys == "uz" || sys == "ru" || sys == "en") sys else "en"
            }
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(targetLang)
            )
        }
'''

# 3. Application klassini tekshiramiz
app_file = None
if existing_app:
    for p in [
        root / f'app/src/main/java/{existing_app.replace(".", "/")}.kt',
        root / f'app/src/main/java/com/nurvpn/app/{existing_app.replace(".", "/")}.kt',
    ]:
        if p.exists():
            app_file = p
            break

# 4. O'zgartirish kiritamiz
if app_file:
    print(f"Modifying Application: {app_file}")
    shutil.copy(app_file, bak_dir / f"{app_file.name}.{ts}.bak")
    content = app_file.read_text(encoding='utf-8')
    
    if 'LANG_AUTO_FIX_V113' in content:
        print("✓ Allaqachon tuzatilgan")
    else:
        old = 'override fun onCreate() {\n        super.onCreate()'
        if old in content:
            content = content.replace(old, old + '\n' + LANG_FIX, 1)
            app_file.write_text(content, encoding='utf-8')
            print("✅ Application.onCreate ga tuzatish kiritildi")
        else:
            print("⚠ Application.onCreate naqshi topilmadi, MainActivity ga o'tamiz")
            app_file = None

if not app_file:
    # MainActivity ga kiritamiz
    ma_file = root / 'app/src/main/java/com/nurvpn/app/ui/MainActivity.kt'
    print(f"Modifying MainActivity: {ma_file}")
    shutil.copy(ma_file, bak_dir / f"{ma_file.name}.{ts}.bak")
    content = ma_file.read_text(encoding='utf-8')
    
    if 'LANG_AUTO_FIX_V113' in content:
        print("✓ Allaqachon tuzatilgan")
    else:
        old = 'override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)'
        if old in content:
            new = 'override fun onCreate(savedInstanceState: Bundle?) {\n' + LANG_FIX + '\n        super.onCreate(savedInstanceState)'
            content = content.replace(old, new, 1)
            ma_file.write_text(content, encoding='utf-8')
            print("✅ MainActivity.onCreate ga tuzatish kiritildi")
        else:
            print("❌ Xato: MainActivity da onCreate naqshi topilmadi")

print("=== TAYYOR ===")
