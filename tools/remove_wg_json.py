from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

changed = False

# ═══ 1. WireGuard parse blokini o'chirish ═══
old_wg_block = '''                // ═══ FIX: WireGuard outbound'larni AWG ga qo'shish ═══
                var wgAdded = 0
                try {
                    val configs: List<org.json.JSONObject> = when {
                        trimmedBody.startsWith("[") -> {
                            val arr = org.json.JSONArray(trimmedBody)
                            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                        }
                        else -> listOf(org.json.JSONObject(trimmedBody))
                    }
                    for (cfg in configs) {
                        val outbounds = cfg.optJSONArray("outbounds") ?: continue
                        for (i in 0 until outbounds.length()) {
                            val ob = outbounds.optJSONObject(i) ?: continue
                            if (ob.optString("protocol") != "wireguard") continue

                            val wgConf = buildWireGuardConfFromJson(ob) ?: continue
                            val awgCfg = com.nurvpn.app.core.AWGConfig(wgConf)
                            // Nom: tag yoki endpoint host
                            val tag = ob.optString("tag", "")
                            awgCfg.name = if (tag.isNotBlank()) "WARP $tag" else "WARP"
                            a.awgConfigs.add(awgCfg)
                            wgAdded++
                        }
                    }
                    if (wgAdded > 0) {
                        com.nurvpn.app.storage.AWGStore.save(a, a.awgConfigs)
                        android.util.Log.i("NurVPN-DBG",
                            "WireGuard JSON → $wgAdded AWG qo'shildi")
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("NurVPN-DBG",
                        "WireGuard JSON parse xato: ${t.message}")
                }
'''

new_wg_block = '''                // WireGuard (AWG) JSON import O'CHIRILGAN — foydalanuvchi so'rovi
'''

if old_wg_block in t:
    t = t.replace(old_wg_block, new_wg_block, 1)
    print("✅ WireGuard parse bloki o'chirildi")
    changed = True
else:
    print("⚠ WireGuard parse bloki topilmadi")

# ═══ 2. Log qatoridan wgAdded ni olib tashlash ═══
old_log = '''                android.util.Log.i("NurVPN-DBG",
                    "loadSubscription: JSON serverlar=${jsonServers.size}, wgAdded=$wgAdded")'''

new_log = '''                android.util.Log.i("NurVPN-DBG",
                    "loadSubscription: JSON serverlar=${jsonServers.size}")'''

if old_log in t:
    t = t.replace(old_log, new_log, 1)
    print("✅ Log qatoridan wgAdded olib tashlandi")
    changed = True
else:
    print("⚠ Log qatori topilmadi")

if changed:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    p.write_text(t, encoding='utf-8')
    print("✅ Fayl saqlandi")
    
    # Tekshirish
    remaining = t.count("wgAdded")
    if remaining == 0:
        print("✅ Tekshiruv: 'wgAdded' endi HECH QAYERDA YO'Q")
    else:
        print(f"⚠ Diqqat: 'wgAdded' hali ham {remaining} marta qolgan")
else:
    print("⚠ Hech qanday o'zgarish yo'q")
