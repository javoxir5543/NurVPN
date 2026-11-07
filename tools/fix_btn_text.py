from pathlib import Path
import re
import shutil
from datetime import datetime

root = Path('/home/pc/NurVPN')
kt_path = root / 'app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt'
xml_path = root / 'app/src/main/res/layout/item_home_card_header.xml'

bak_dir = root / '.fix-backups'
bak_dir.mkdir(exist_ok=True)
ts = int(datetime.now().timestamp())

# 1. Kotlin kodini tekshirish va tuzatish
if kt_path.exists():
    shutil.copy(kt_path, bak_dir / f"HomeFragment.kt.{ts}.bak")
    kt = kt_path.read_text(encoding='utf-8')
    
    # Tugmalar matnini resursga ulash (har xil yozilish variantlarini qamrab olamiz)
    kt = kt.replace('supportBtn.text = "Support"', 'supportBtn.text = "🛠 " + getString(R.string.sub_btn_support)')
    kt = kt.replace('webBtn.text = "Website"', 'webBtn.text = "🌐 " + getString(R.string.sub_btn_web)')
    
    # Agar o'zgaruvchi nomi boshqacha bo'lsa yoki XML dan olinayotgan bo'lsa
    kt = kt.replace('findViewById<TextView>(R.id.card_info_btn_support).text = "Support"', 
                    'findViewById<TextView>(R.id.card_info_btn_support).text = "🛠 " + getString(R.string.sub_btn_support)')
    kt = kt.replace('findViewById<TextView>(R.id.card_info_btn_web).text = "Website"', 
                    'findViewById<TextView>(R.id.card_info_btn_web).text = "🌐 " + getString(R.string.sub_btn_web)')
    
    # Regex orqali barcha qattiq yozilgan matnlarni almashtirish
    kt = re.sub(r'(card_info_btn_support\).*?\.text\s*=\s*)"[^"]*"', r'\1"🛠 " + getString(R.string.sub_btn_support)', kt)
    kt = re.sub(r'(card_info_btn_web\).*?\.text\s*=\s*)"[^"]*"', r'\1"🌐 " + getString(R.string.sub_btn_web)', kt)
    
    kt_path.write_text(kt, encoding='utf-8')
    print("✅ Kotlin (HomeFragment.kt): Tugma matnlari tarjimaga ulandi.")

# 2. XML faylini tekshirish (agar u yerda ham qattiq yozilgan bo'lsa)
if xml_path.exists():
    shutil.copy(xml_path, bak_dir / f"item_home_card_header.xml.{ts}.bak")
    xml = xml_path.read_text(encoding='utf-8')
    
    # XML da android:text="..." bo'lsa, @string ga almashtiramiz
    xml = xml.replace('android:text="Support"', 'android:text="@string/sub_btn_support"')
    xml = xml.replace('android:text="Website"', 'android:text="@string/sub_btn_web"')
    xml = xml.replace('android:text="🔧 Support"', 'android:text="@string/sub_btn_support"')
    xml = xml.replace('android:text="🌐 Website"', 'android:text="@string/sub_btn_web"')
    
    xml_path.write_text(xml, encoding='utf-8')
    print("✅ XML (item_home_card_header.xml): Qattiq yozilgan matnlar tozalandi.")

print("\n=== TAYYOR! Endi build qiling ===")
