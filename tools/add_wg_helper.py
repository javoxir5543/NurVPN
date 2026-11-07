from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt')
t = p.read_text(encoding='utf-8')

# ANIQ tekshiruv: "private fun buildWireGuardConfFromJson(" bor-yo'qligi
if 'private fun buildWireGuardConfFromJson(' in t:
    print("✓ funksiya allaqachon mavjud")
    exit(0)

# Anchor
anchor = "    private fun buildLinkFromJsonOutbound("
if anchor not in t:
    print("❌ anchor topilmadi")
    exit(1)

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

# Backup
bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
shutil.copy(p, bak)
backup_dir = Path('/home/pc/NurVPN/.fix-backups')
backup_dir.mkdir(exist_ok=True)
dest = backup_dir / bak.name
shutil.move(str(bak), str(dest))
print(f"📦 Backup: .fix-backups/{dest.name}")

p.write_text(t, encoding='utf-8')
print("✅ buildWireGuardConfFromJson funksiyasi qo'shildi")
