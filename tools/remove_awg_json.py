from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

# O'chiriladigan blok (WireGuard JSON parse qismi)
old_block = '''                // ═══ FIX: WireGuard outbound'larni AWG ga qo'shish ═══
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
                }'''

# Blokni o'chirib, o'rniga oddiy izoh qoldiramiz
new_block = '''                // WireGuard (xraywarp.json) import qilish O'CHIRILGAN'''

if old_block in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    
    t = t.replace(old_block, new_block, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ WireGuard (AWG) JSON import qismi o'chirildi")
    print("   Endi JSON faqat oddiy serverlarni (VLESS/Reality) qabul qiladi")
else:
    print("⚠ Naqsh topilmadi. Kod allaqachon o'zgartirilgan bo'lishi mumkin.")
