from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/settings/SettingsFragment.kt')
t = p.read_text(encoding='utf-8')

# Aniq naqsh (ON qismidagi obuna yuklash bloki)
old_block = '''                    if (act != null) {
                        act.subscriptions = SubscriptionStore.load(act)
                    }
                } else {'''

new_block = '''                    if (act != null) {
                        act.subscriptions = SubscriptionStore.load(act)
                        // FIX v1.13: obuna yaratilgandan keyin darhol yangilash
                        act.enqueueRefresh(subId)
                    }
                } else {'''

if 'act.enqueueRefresh(subId)' in t:
    print("✓ enqueueRefresh allaqachon ulangan")
elif old_block in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old_block, new_block, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ SettingsFragment: enqueueRefresh() ulandi (ON qismida)")
else:
    print("⚠ Naqsh topilmadi. Kod o'zgargan bo'lishi mumkin.")
    # Kontekstni ko'rsatish
    lines = t.split('\n')
    for i, line in enumerate(lines, 1):
        if 'SubscriptionStore.load(act)' in line:
            print(f"  {i}: {line.strip()}")
