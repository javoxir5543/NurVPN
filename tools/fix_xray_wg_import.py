from pathlib import Path
import shutil
from datetime import datetime
import glob
import os

# ═══════════════════════════════════════════════════════════════
#  QADAM 0: .bak fayllarni src/ dan xavfsiz tozalash (DeepSeek ogohlantirgan)
# ═══════════════════════════════════════════════════════════════
print("═══ QADAM 0: .bak fayllarni tozalash ═══")
src_dir = Path('/home/pc/NurVPN/app/src')
backup_dir = Path('/home/pc/NurVPN/.fix-backups')
backup_dir.mkdir(exist_ok=True)

bak_files = list(src_dir.rglob('*.bak.*'))
if bak_files:
    for f in bak_files:
        dest = backup_dir / f.name
        # Agar bir xil nomli fayl bo'lsa, timestamp qo'shamiz
        if dest.exists():
            dest = backup_dir / f"{f.name}.{int(datetime.now().timestamp())}"
        shutil.move(str(f), str(dest))
        print(f"  📦 {f.relative_to(src_dir)} → .fix-backups/")
    print(f"✅ {len(bak_files)} ta .bak fayl ko'chirildi")
else:
    print("✓ .bak fayllar yo'q")

# ═══════════════════════════════════════════════════════════════
#  QADAM 1: ServersFragment.kt ni tahrirlash
# ═══════════════════════════════════════════════════════════════
p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt')
if not p.exists():
    print(f"❌ {p} topilmadi")
    exit(1)

t = p.read_text(encoding='utf-8')

# ─── 1a) importJsonSubscription: wireguard shoxini qo'shish ───
old_loop = """                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val proto = ob.optString("protocol")
                    if (proto !in JSON_PROTOCOLS) continue"""

new_loop = """                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val proto = ob.optString("protocol")

                    // ═══ FIX: Xray wireguard outbound → AWG ═══
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

                    if (proto !in JSON_PROTOCOLS) continue"""

if old_loop in t:
    t = t.replace(old_loop, new_loop, 1)
    print("✅ importJsonSubscription: wireguard shoxi qo'shildi")
elif "Xray wireguard outbound" in t:
    print("✓ wireguard shoxi allaqachon mavjud")
else:
    print("❌ importJsonSubscription naqshi topilmadi")
    exit(1)

# ─── 1b) buildWireGuardConfFromJson helper qo'shish ───
anchor = "    private fun buildLinkFromJsonOutbound("
helper = '''    /**
     * Xray JSON dagi wireguard outbound'ni .conf matniga aylantiradi.
     * Maydonlar: secretKey, publicKey, endpoint (host:port), allowedIPs.
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
                (0 until allowedIpsArr.length()).map { allowedIpsArr.optString(it) }.joinToString(", ")
            } else "0.0.0.0/0, ::/0"

            buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $secretKey")
                appendLine("Address = ${addresses.joinToString(", ")}")
                appendLine("MTU = $mtu")
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = $publicKey")
                appendLine("Endpoint = $endpoint")
                appendLine("AllowedIPs = $allowedIps")
            }
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-DBG",
                "buildWireGuardConfFromJson xato: ${t.message}")
            null
        }
    }

'''

if anchor in t and "buildWireGuardConfFromJson" not in t:
    t = t.replace(anchor, helper + anchor, 1)
    print("✅ buildWireGuardConfFromJson helper qo'shildi")
elif "buildWireGuardConfFromJson" in t:
    print("✓ helper allaqachon mavjud")
else:
    print("⚠ anchor topilmadi, lekin importJsonSubscription tuzatilgan")

# ─── Saqlash (backup bilan) ───
bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
shutil.copy(p, bak)
# Bu .bak faylni src/ dan tashqariga ko'chiramiz
dest_bak = backup_dir / bak.name
shutil.move(str(bak), str(dest_bak))
print(f"📦 Backup: .fix-backups/{dest_bak.name}")

p.write_text(t, encoding='utf-8')
print("✅ ServersFragment.kt saqlandi")

print()
print("══════════════════════════════════════════════════════════")
print("  TAYYOR — keyingi qadam:")
print("    ./gradlew assembleDebug")
print("    adb install -r app/build/outputs/apk/debug/app-debug.apk")
print("══════════════════════════════════════════════════════════")
