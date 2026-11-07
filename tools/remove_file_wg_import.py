from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt')
t = p.read_text(encoding='utf-8')
changed = False

# ═══════════════════════════════════════════════════════════════
# 1. 686-qator blokini o'chirish (importJsonFile -> outbounds)
# ═══════════════════════════════════════════════════════════════
old_686 = '''                    // ═══ FIX: Xray wireguard outbound → AWG ═══
                    if (proto == "wireguard") {
                        val wgConf = buildWireGuardConfFromJson(ob)
                        if (wgConf != null) {
                            activity?.runOnUiThread { addAWG(wgConf) }
                            added++
                            android.util.Log.i("NurVPN-DBG",
                                "WireGuard JSON → AWG qo'shildi")
                        }
                        continue
                    }'''

new_686 = '''                    // ═══ WireGuard (AWG) JSON fayldan import O'CHIRILGAN ═══
                    if (proto == "wireguard") continue'''

if old_686 in t:
    t = t.replace(old_686, new_686, 1)
    print("✅ 686-qator: JSON fayldan AWG (outbounds) import o'chirildi")
    changed = True
else:
    # Ehtimol blok boshqacha formatda, proto o'rniga ob.optString bilan
    old_686_alt = '''                    // ═══ FIX: Xray wireguard outbound → AWG ═══
                    if (ob.optString("protocol") == "wireguard") {
                        val wgConf = buildWireGuardConfFromJson(ob)
                        if (wgConf != null) {
                            activity?.runOnUiThread { addAWG(wgConf) }
                            added++
                            android.util.Log.i("NurVPN-DBG",
                                "WireGuard JSON → AWG qo'shildi")
                        }
                        continue
                    }'''
    if old_686_alt in t:
        t = t.replace(old_686_alt, new_686, 1)
        print("✅ 686-qator: JSON fayldan AWG import o'chirildi (alt variant)")
        changed = True
    else:
        print("⚠ 686-qator bloki topilmadi")

# ═══════════════════════════════════════════════════════════════
# 2. 565-qator blokini o'chirish (importWireGuardEndpoints -> sing-box)
# ═══════════════════════════════════════════════════════════════
# 665-qatorda: if (trimmed.contains("\"endpoints\"")) { importWireGuardEndpoints(...) }
# Buni ham o'chirish uchun chaqiruvni kommentariyaga olamiz.
# Avval funksiya chaqiruvini topamiz.
old_endpoints_call = '''            if (trimmed.contains("\\"endpoints\\"")) {
                val wgCount = importWireGuardEndpoints(trimmed, fileName)
                if (wgCount > 0) added += wgCount
            }'''

new_endpoints_call = '''            // ═══ sing-box endpoints (WireGuard) import O'CHIRILGAN ═══
            // if (trimmed.contains("\\"endpoints\\"")) {
            //     val wgCount = importWireGuardEndpoints(trimmed, fileName)
            //     if (wgCount > 0) added += wgCount
            // }'''

if old_endpoints_call in t:
    t = t.replace(old_endpoints_call, new_endpoints_call, 1)
    print("✅ 565-qator: sing-box endpoints (AWG) import o'chirildi")
    changed = True
else:
    print("⚠ endpoints chaqiruvi topilmadi (ehtimol formati boshqacha)")

# ═══════════════════════════════════════════════════════════════
# 3. Saqlash
# ═══════════════════════════════════════════════════════════════
if changed:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    p.write_text(t, encoding='utf-8')
    print("✅ Fayl saqlandi")
    
    # Tekshirish
    if 'if (proto == "wireguard") continue' in t or 'if (ob.optString("protocol") == "wireguard") continue' in t:
        print("✅ Tekshiruv: outbounds wireguard endi faqat 'continue' qiladi")
else:
    print("⚠ Hech qanday o'zgarish yo'q")
