from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt')
t = p.read_text(encoding='utf-8')
changed = False

# ═══════════════════════════════════════════════════════════════
# BLOK 1: 665-667 — sing-box endpoints chaqiruvi (o'chirish)
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
# BLOK 2: 676-698 — Xray outbounds wireguard (o'chirish)
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
                // JSON fayldan avtomatik AWG qo'shilmaydi (foydalanuvchi talabi).
                // Faqat QR va .conf fayl orqali qo'shiladi.
'''

if old_outbounds in t:
    t = t.replace(old_outbounds, new_outbounds, 1)
    print("✅ BLOK 2 (676-698): Xray outbounds wireguard o'chirildi")
    changed = True
else:
    print("⚠ BLOK 2 topilmadi")

# ═══════════════════════════════════════════════════════════════
# 725-qator: awgAdded == 0 → true (endi doim 0)
# ═══════════════════════════════════════════════════════════════
old_check = 'if (servers.isEmpty() && awgAdded == 0) {'
new_check = 'if (servers.isEmpty()) {  // awgAdded endi doim 0 (JSON import o\'chirilgan)'

if old_check in t:
    t = t.replace(old_check, new_check, 1)
    print("✅ 725-qator: 'awgAdded == 0' sharti olib tashlandi")
    changed = True
else:
    print("⚠ 725-qator topilmadi")

# ═══════════════════════════════════════════════════════════════
# 674-qator: var awgAdded = 0 → saqlab qolish (endi kerak emas, lekin xato bermasin)
# ═══════════════════════════════════════════════════════════════
if changed:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"\n📦 Backup: {bak.name}")
    p.write_text(t, encoding='utf-8')
    print("✅ Fayl saqlandi")
    
    # Yakuniy tekshiruv
    remaining = t.count('awgAdded')
    print(f"\n📊 Tekshiruv: 'awgAdded' {remaining} marta qolgan")
    if remaining <= 2:  # 674 va 725 da
        print("   ✅ Xavfsiz — faqat e'lon va check qolgan")
    else:
        print("   ⚠ Boshqa joylarda ham bor — tekshirish kerak")
