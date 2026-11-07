from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt')
t = p.read_text(encoding='utf-8')
changed = False

# ═══════════════════════════════════════════════════════════════
# BLOK 1: 665-667 — sing-box endpoints chaqiruvi
# ═══════════════════════════════════════════════════════════════
old_endpoints = '''        // ═══ sing-box endpoints (WireGuard) ═══
        if (trimmed.startsWith("{") && trimmed.contains("\\"endpoints\\"")) {
            importWireGuardEndpoints(trimmed, fileName)
            return
        }'''

new_endpoints = '''        // ═══ sing-box endpoints (WireGuard) import O'CHIRILGAN ═══
        // if (trimmed.startsWith("{") && trimmed.contains("\\"endpoints\\"")) {
        //     importWireGuardEndpoints(trimmed, fileName)
        //     return
        // }'''

if old_endpoints in t:
    t = t.replace(old_endpoints, new_endpoints, 1)
    print("✅ BLOK 1 (665-667): sing-box endpoints chaqiruvi o'chirildi")
    changed = True
else:
    print("⚠ BLOK 1 topilmadi")

# ═══════════════════════════════════════════════════════════════
# BLOK 2: 676-698 — Xray outbounds wireguard
# ═══════════════════════════════════════════════════════════════
old_outbounds = '''                // ═══ FIX: WireGuard outbound'larni AWG ga qo'shish ═══
                val configs = if (trimmed.startsWith("[")) {
                    val arr = org.json.JSONArray(trimmed)
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                } else listOf(org.json.JSONObject(trimmed))

                for (cfg in configs) {
                    val outbounds = cfg.optJSONArray("outbounds") ?: continue
                    for (i in 0 until outbounds.length()) {
                        val ob = outbounds.optJSONObject(i) ?: continue
                        if (ob.optString("protocol") == "wireguard") {
                            val wgConf = buildWireGuardConfFromJson(ob)
                            if (wgConf != null) {
                                activity?.runOnUiThread { addAWG(wgConf) }
                                awgAdded++
                                android.util.Log.i("NurVPN-DBG",
                                    "importJsonFile: WireGuard → AWG")
                            }
                        }
                    }
                }
'''

new_outbounds = '''                // ═══ WireGuard (AWG) outbound import O'CHIRILGAN ═══
                // Foydalanuvchi talabiga ko'ra JSON fayldan AWG avtomatik
                // qo'shilmaydi. Faqat QR va .conf fayl orqali qo'shiladi.
                @Suppress("UNUSED_VARIABLE")
                val awgAdded = 0
'''

if old_outbounds in t:
    t = t.replace(old_outbounds, new_outbounds, 1)
    print("✅ BLOK 2 (676-698): Xray outbounds wireguard o'chirildi")
    changed = True
else:
    print("⚠ BLOK 2 topilmadi")

# ═══════════════════════════════════════════════════════════════
# Tekshirish: awgAdded boshqa joyda ishlatilganmi?
# ═══════════════════════════════════════════════════════════════
if changed:
    # awgAdded ni tekshirish
    lines = t.split('\n')
    awg_added_uses = [(i+1, line.strip()) for i, line in enumerate(lines)
                      if 'awgAdded' in line and 'val awgAdded' not in line and '//' not in line[:line.find('awgAdded') if 'awgAdded' in line else 0]]
    
    if awg_added_uses:
        print(f"\n⚠ OGOHLANTIRISH: awgAdded {len(awg_added_uses)} marta ishlatilgan:")
        for line_num, line in awg_added_uses[:5]:
            print(f"   {line_num}: {line}")
        print("\n   Bu qatorlarni ham tuzatish kerak. Skriptni to'xtatdim.")
    else:
        bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
        shutil.copy(p, bak)
        print(f"\n📦 Backup: {bak.name}")
        p.write_text(t, encoding='utf-8')
        print("✅ Fayl saqlandi")
        
        # Yakuniy tekshiruv
        remaining_wg = t.count('if (ob.optString("protocol") == "wireguard")')
        if remaining_wg == 0:
            print("✅ Tekshiruv: JSON faylda wireguard import endi YO'Q")
        else:
            print(f"⚠ Diqqat: {remaining_wg} ta wireguard bloki qolgan")
else:
    print("⚠ Hech qanday o'zgarish yo'q")
