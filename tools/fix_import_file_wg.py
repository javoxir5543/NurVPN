from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt')
t = p.read_text(encoding='utf-8')

# Eski Thread bloki (importJsonFile ichida)
old_block = '''        Thread {
            val servers = ArrayList<ServerItem>()
            try {
                if (trimmed.startsWith("[")) {
                    val arr = org.json.JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val si = ServerLinkParser.parse(obj.toString(), subId)
                        if (si != null) {
                            si.subId = subId
                            servers.add(si)
                        }
                    }
                } else {
                    val single = ServerLinkParser.parse(trimmed, subId)
                    if (single != null) {
                        single.subId = subId
                        servers.add(single)
                    } else {
                        var depth = 0
                        var start = -1
                        for (i in trimmed.indices) {
                            when (trimmed[i]) {
                                '{' -> { if (depth == 0) start = i; depth++ }
                                '}' -> {
                                    depth--
                                    if (depth == 0 && start >= 0) {
                                        val chunk = trimmed.substring(start, i + 1)
                                        val si = ServerLinkParser.parse(chunk, subId)
                                        if (si != null) {
                                            si.subId = subId
                                            servers.add(si)
                                        }
                                        start = -1
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NurVPN-JSON", "Parse xato", t)
            }

            activity?.runOnUiThread {
                if (servers.isEmpty()) {
                    Toast.makeText(context, getString(R.string.toast_server_not_found), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }'''

# Yangi Thread bloki (WireGuard qo'shilgan)
new_block = '''        Thread {
            val servers = ArrayList<ServerItem>()
            var awgAdded = 0
            try {
                // ═══ FIX: WireGuard outbound'larni AWG ga qo'shish ═══
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

                // ═══ Mavjud parsing (Xray proxy linklar) ═══
                if (trimmed.startsWith("[")) {
                    val arr = org.json.JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val si = ServerLinkParser.parse(obj.toString(), subId)
                        if (si != null) {
                            si.subId = subId
                            servers.add(si)
                        }
                    }
                } else {
                    val single = ServerLinkParser.parse(trimmed, subId)
                    if (single != null) {
                        single.subId = subId
                        servers.add(single)
                    } else {
                        var depth = 0
                        var start = -1
                        for (i in trimmed.indices) {
                            when (trimmed[i]) {
                                '{' -> { if (depth == 0) start = i; depth++ }
                                '}' -> {
                                    depth--
                                    if (depth == 0 && start >= 0) {
                                        val chunk = trimmed.substring(start, i + 1)
                                        val si = ServerLinkParser.parse(chunk, subId)
                                        if (si != null) {
                                            si.subId = subId
                                            servers.add(si)
                                        }
                                        start = -1
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NurVPN-JSON", "Parse xato", t)
            }

            activity?.runOnUiThread {
                if (servers.isEmpty() && awgAdded == 0) {
                    Toast.makeText(context, getString(R.string.toast_server_not_found), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }'''

if old_block in t:
    t = t.replace(old_block, new_block, 1)
    print("✅ importJsonFile: WireGuard shoxi qo'shildi")
elif "importJsonFile: WireGuard → AWG" in t:
    print("✓ allaqachon tuzatilgan")
else:
    print("❌ importJsonFile naqshi topilmadi")
    exit(1)

# Backup
bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
shutil.copy(p, bak)
backup_dir = Path('/home/pc/NurVPN/.fix-backups')
backup_dir.mkdir(exist_ok=True)
dest = backup_dir / bak.name
shutil.move(str(bak), str(dest))
print(f"📦 Backup: .fix-backups/{dest.name}")

p.write_text(t, encoding='utf-8')
print("✅ ServersFragment.kt saqlandi")
