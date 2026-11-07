from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt')
t = p.read_text(encoding='utf-8')

# Eski blok (WireGuard → AWG)
old_block = '''                    // ═══ FIX: Xray wireguard outbound → AWG ═══
                    if (proto == "wireguard") {
                        val wgConf = buildWireGuardConfFromJson(ob)
                        if (wgConf != null) {
                            activity?.runOnUiThread { addAWG(wgConf) }
                            added++
                            android.util.Log.i("NurVPN-DBG",
                                "WireGuard JSON → AWG qo'shildi")
                        }
                        continue
                    }
'''

# Yangi blok (WireGuard butunlay o'tkazib yuboriladi)
new_block = '''                    // ═══ WireGuard (AWG) JSON import O'CHIRILGAN ═══
                    if (proto == "wireguard") continue
'''

if old_block in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old_block, new_block, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ ServersFragment: WireGuard → AWG import o'chirildi")
else:
    print("⚠ Naqsh topilmadi. Kod o'zgargan bo'lishi mumkin.")
    # Aniqroq qidirish
    for i, line in enumerate(t.split('\n'), 1):
        if 'proto == "wireguard"' in line:
            print(f"  {i}: {line.strip()}")
