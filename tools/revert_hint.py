from pathlib import Path
import shutil

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
# Barcha .bak fayllarni vaqti bo'yicha saralaymiz (eng yangisi birinchi)
backups = sorted(p.parent.glob(p.name + ".bak.*"), key=lambda x: x.stat().st_mtime, reverse=True)

if backups:
    latest = backups[0]
    shutil.copy(latest, p)
    print(f"✅ Ortga qaytarildi: {latest.name}")
    print(f"   -> {p.name} fayli tiklandi")
else:
    print("❌ Zaxira nusxa topilmadi!")
