from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

# ═══════════════════════════════════════════════════════════════
#  FIX 1: loadSubscription da JSON shoxiga WireGuard tekshiruvi
# ═══════════════════════════════════════════════════════════════
old_block = '''                val jsonServers = parseJsonConfigsWithSub(trimmedBody, subForJson.id)
                android.util.Log.i("NurVPN-DBG",
                    "loadSubscription: JSON serverlar=${jsonServers.size}")'''

new_block = '''                // ═══ FIX: WireGuard outbound'larni AWG ga qo'shish ═══
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

                val jsonServers = parseJsonConfigsWithSub(trimmedBody, subForJson.id)
                android.util.Log.i("NurVPN-DBG",
                    "loadSubscription: JSON serverlar=${jsonServers.size}, wgAdded=$wgAdded")'''

if old_block in t:
    t = t.replace(old_block, new_block, 1)
    print("✅ loadSubscription: WireGuard shoxi qo'shildi")
elif "WireGuard JSON →" in t:
    print("✓ allaqachon tuzatilgan")
else:
    print("❌ loadSubscription naqshi topilmadi")
    exit(1)

# ═══════════════════════════════════════════════════════════════
#  FIX 2: buildWireGuardConfFromJson helper qo'shish
# ═══════════════════════════════════════════════════════════════
if 'private fun buildWireGuardConfFromJson(' not in t:
    anchor = "    private fun parseJsonConfigsWithSub("
    if anchor not in t:
        print("❌ parseJsonConfigsWithSub anchor topilmadi")
        exit(1)

    helper = '''    /**
     * Xray JSON dagi wireguard outbound'ni .conf matniga aylantiradi.
     */
    private fun buildWireGuardConfFromJson(ob: org.json.JSONObject): String? {
        return try {
            val settings = ob.optJSONObject("settings") ?: return null
            val secretKey = settings.optString("secretKey", "")
            if (secretKey.isBlank()) return null

            val peers = settings.optJSONArray("peers") ?: return null
            if (peers.length() == 0) return null
            val peer = peers.optJSONObject(0) ?: return null

            val publicKey = peer.optString("publicKey", "")
            val endpoint = peer.optString("endpoint", "")
            if (publicKey.isBlank() || endpoint.isBlank()) return null

            val mtu = settings.optInt("mtu", 1360)
            val addressArr = settings.optJSONArray("address")
            val addresses = mutableListOf<String>()
            if (addressArr != null) {
                for (j in 0 until addressArr.length()) {
                    addresses.add(addressArr.optString(j))
                }
            }
            if (addresses.isEmpty()) addresses.add("172.16.0.2/32")

            val allowedIpsArr = peer.optJSONArray("allowedIPs")
            val allowedIps = if (allowedIpsArr != null && allowedIpsArr.length() > 0) {
                (0 until allowedIpsArr.length()).map { allowedIpsArr.optString(it) }
                    .joinToString(", ")
            } else "0.0.0.0/0, ::/0"

            buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $secretKey")
                appendLine("Address = " + addresses.joinToString(", "))
                appendLine("MTU = $mtu")
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = $publicKey")
                appendLine("Endpoint = $endpoint")
                appendLine("AllowedIPs = $allowedIps")
            }
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-DBG",
                "buildWireGuardConfFromJson xato: " + t.message)
            null
        }
    }

'''
    t = t.replace(anchor, helper + anchor, 1)
    print("✅ buildWireGuardConfFromJson helper qo'shildi")
else:
    print("✓ helper allaqachon mavjud")

# ═══════════════════════════════════════════════════════════════
#  Backup va saqlash
# ═══════════════════════════════════════════════════════════════
bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
shutil.copy(p, bak)
backup_dir = Path('/home/pc/NurVPN/.fix-backups')
backup_dir.mkdir(exist_ok=True)
dest = backup_dir / bak.name
shutil.move(str(bak), str(dest))
print(f"📦 Backup: .fix-backups/{dest.name}")

p.write_text(t, encoding='utf-8')
print("✅ HomeFragment.kt saqlandi")
