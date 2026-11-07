#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #9 — Brauzerdan https:// obuna havolasi ishlamayapti          ║
╠══════════════════════════════════════════════════════════════════════════╣
║  ANIQLANGAN SABABLAR (dalillar bilan):                                   ║
║                                                                          ║
║  1) ❌ MANIFEST'DA http/https SCHEME YO'Q!                               ║
║     `grep -c 'android:scheme="https"' AndroidManifest.xml` → 0           ║
║     Faqat incy/crypt1/nurvpn + 13 ta proxy sxemasi bor.                  ║
║     Natija: `https://sub.dark-bit.cc/...` bosilganda NurVPN              ║
║     UMUMAN ochilmaydi (Android uni brauzerga beradi).                    ║
║                                                                          ║
║  2) ❌ SERVERS ekranida JSON OBUNA qo'llab-quvvatlanmaydi.               ║
║     Tekshirildi: `https://sub.dark-bit.cc/dbabb490-...` aslida           ║
║     XRAY JSON qaytaradi (base64 emas!):                                  ║
║        [{"dns":{...},"outbounds":[{"protocol":"vless"}],"remarks":"..."}]║
║     HomeFragment.loadSub da JSON shoxi BOR, lekin                       ║
║     ServersFragment.loadSub da YO'Q → u yerdan qo'shilsa ishlamaydi.    ║
║                                                                          ║
║  YECHIM:                                                                 ║
║   A) Manifest'ga http/https qo'shamiz — LEKIN faqat MA'LUM HOSTLAR      ║
║      uchun (aks holda har qanday havola ilovani ochib olardi!)           ║
║   B) `autoVerify` bilan App Links (Android 6+)                           ║
║   C) ServersFragment.loadSub ga JSON shoxini qo'shamiz                   ║
║   D) `handleDeepLink` http/https ni allaqachon qabul qiladi (fix_05) —   ║
║      buni tekshirib, manifest bilan mosligini kafolatlaymiz              ║
║                                                                          ║
║  ⚠️ XAVFSIZLIK: bare `<data android:scheme="https"/>` XAVFLI — u        ║
║  HAR QANDAY saytni ushlab qolardi. Shuning uchun host cheklovi shart.    ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
SRV_F = ROOT / "app/src/main/java/com/nurvpn/app/ui/servers/ServersFragment.kt"

# ══════════════════════════════════════════════════════════════════════
#  A) Manifest — http/https, HOST CHEKLOVI bilan
# ══════════════════════════════════════════════════════════════════════
# ⚠️ MUHIM: host cheklovisiz https qo'shsak — har qanday sayt havolasi
#    ilovani ochishni taklif qilardi. Faqat obuna xizmatlari.
SUBSCRIPTION_HOSTS = [
    "sub.dark-bit.cc",
]

NEW_FILTER = '''
            <!-- ═══════════════════════════════════════════════════════
                 ★ TUZATISH #9: Obuna havolalari (http/https)

                 ⚠️ MUHIM: host cheklovi SHART.
                 `<data android:scheme="https"/>` ni yolg'iz qo'shsak,
                 HAR QANDAY veb-sayt havolasini bosganda Android
                 NurVPN ni ochishni taklif qilardi — bu qabul qilib
                 bo'lmaydigan xatti-harakat.

                 Shuning uchun faqat MA'LUM obuna xizmatlari uchun
                 ochamiz. Yangi xizmat qo'shilsa — shu ro'yxatga
                 host qo'shiladi.
                 ═══════════════════════════════════════════════════════ -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
%s            </intent-filter>
'''


def patch_manifest() -> bool:
    src = MANIFEST.read_text(encoding="utf-8")
    if "TUZATISH #9" in src:
        print("ℹ️  Manifest allaqachon tuzatilgan")
        return True
    safe_backup(MANIFEST, "fix9")

    # data qatorlarini yasaymiz: har bir host uchun http va https
    data_lines = ""
    for h in SUBSCRIPTION_HOSTS:
        data_lines += f'                <data android:scheme="https" android:host="{h}"/>\n'
        data_lines += f'                <data android:scheme="http" android:host="{h}"/>\n'

    block = NEW_FILTER % data_lines

    # Proxy scheme intent-filter'dan keyin qo'shamiz
    anchor = '<data android:scheme="ssh"/>\n            </intent-filter>\n'
    if anchor not in src:
        print("❌ Manifest: proxy scheme intent-filter topilmadi")
        return False
    src = src.replace(anchor, anchor + block, 1)

    MANIFEST.write_text(src, encoding="utf-8")
    print(f"✅ Manifest: {len(SUBSCRIPTION_HOSTS)} ta host uchun http/https")
    for h in SUBSCRIPTION_HOSTS:
        print(f"      • https://{h}/...")
    return True


# ══════════════════════════════════════════════════════════════════════
#  C) ServersFragment.loadSub — JSON obuna qo'llab-quvvatlashi
# ══════════════════════════════════════════════════════════════════════
OLD_LINKS = '''            // ═══ Linklarni ajratish ═══
            val directLinks = SubscriptionLinkExtractor.extract(body)
            android.util.Log.i("NurVPN-DBG", "loadSub: directLinks=${directLinks.size}")

            val links = if (directLinks.isNotEmpty()) {
                directLinks
            } else {'''

NEW_LINKS = '''            // ═══════════════════════════════════════════════════════════════
            //  ★ TUZATISH #9: JSON OBUNA (Xray/sing-box konfiguratsiyasi)
            //
            //  Ba'zi obunalar (masalan sub.dark-bit.cc) base64 emas, balki
            //  to'g'ridan-to'g'ri Xray JSON qaytaradi:
            //     [{"dns":{...},"outbounds":[{"protocol":"vless",...}]}]
            //
            //  Avval bu shox FAQAT HomeFragment'da bor edi — Servers
            //  ekranidan qo'shilsa "link yo'q" xatosi chiqardi.
            // ═══════════════════════════════════════════════════════════════
            val trimmedBody = body.trim()
            if (trimmedBody.startsWith("[") || trimmedBody.startsWith("{")) {
                val jsonAdded = importJsonSubscription(trimmedBody, url)
                if (jsonAdded > 0) {
                    activity?.runOnUiThread {
                        Toast.makeText(context,
                            getString(R.string.toast_added_count, jsonAdded),
                            Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
            }

            // ═══ Linklarni ajratish ═══
            val directLinks = SubscriptionLinkExtractor.extract(body)
            android.util.Log.i("NurVPN-DBG", "loadSub: directLinks=${directLinks.size}")

            val links = if (directLinks.isNotEmpty()) {
                directLinks
            } else {'''

JSON_METHOD = '''
    /**
     * ★ TUZATISH #9: Xray/sing-box JSON obunasini import qiladi.
     *
     * Qo'llab-quvvatlanadi:
     *   • JSON massiv:  [ {outbounds:[{protocol:"vless",...}]}, ... ]
     *   • Bitta obyekt: {outbounds:[...]}
     *   • `remarks` maydoni server nomi sifatida olinadi
     *
     * @return qo'shilgan serverlar soni
     */
    private fun importJsonSubscription(json: String, subUrl: String): Int {
        val a = activity as? MainActivity ?: return 0
        var added = 0
        try {
            val configs: List<org.json.JSONObject> = when {
                json.startsWith("[") -> {
                    val arr = org.json.JSONArray(json)
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
                else -> listOf(org.json.JSONObject(json))
            }

            for (cfg in configs) {
                val outbounds = cfg.optJSONArray("outbounds") ?: continue
                val remark = cfg.optString("remarks").ifBlank { null }

                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val proto = ob.optString("protocol")
                    if (proto !in JSON_PROTOCOLS) continue

                    val link = buildLinkFromJsonOutbound(ob, remark) ?: continue
                    if (link.length > 8192) continue

                    val si = parseServer(link, subUrl) ?: continue
                    if (a.servers.any { it.link == si.link }) continue
                    a.servers.add(si)
                    added++
                }
            }

            if (added > 0) {
                com.nurvpn.app.storage.ServerStore.save(a, a.servers)
                activity?.runOnUiThread { ad?.notifyDataChanged() }
            }
            android.util.Log.i("NurVPN-DBG",
                "importJsonSubscription: $added server qo'shildi")
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-DBG",
                "importJsonSubscription xato: ${t.message}")
        }
        return added
    }

    /** JSON outbound → proxy link. */
    private fun buildLinkFromJsonOutbound(
        ob: org.json.JSONObject, remark: String?
    ): String? {
        val proto = ob.optString("protocol")
        val server = ob.optString("address").ifEmpty {
            ob.optJSONObject("settings")
                ?.optJSONArray("vnext")?.optJSONObject(0)?.optString("address")
                ?: ob.optJSONObject("settings")
                    ?.optJSONArray("servers")?.optJSONObject(0)?.optString("address")
                ?: return null
        }
        if (server.isEmpty()) return null
        val port = ob.optInt("port").takeIf { it > 0 }
            ?: ob.optJSONObject("settings")
                ?.optJSONArray("vnext")?.optJSONObject(0)?.optInt("port")
                ?.takeIf { it > 0 }
            ?: ob.optJSONObject("settings")
                ?.optJSONArray("servers")?.optJSONObject(0)?.optInt("port")
                ?.takeIf { it > 0 }
            ?: 443

        val tag = java.net.URLEncoder.encode(remark ?: server, "UTF-8")
        val stream = ob.optJSONObject("streamSettings")
        val security = stream?.optString("security").orEmpty()
        val sni = stream?.optJSONObject("tlsSettings")?.optString("serverName")
            .orEmpty().ifEmpty {
                stream?.optJSONObject("realitySettings")?.optString("serverName")
                    .orEmpty()
            }
        val pbk = stream?.optJSONObject("realitySettings")
            ?.optString("publicKey").orEmpty()

        return try {
            when (proto) {
                "vless" -> {
                    val id = ob.optJSONObject("settings")
                        ?.optJSONArray("vnext")?.optJSONObject(0)
                        ?.optJSONArray("users")?.optJSONObject(0)?.optString("id")
                        ?: return null
                    if (id.isEmpty()) return null
                    val q = StringBuilder("security=")
                    q.append(if (security.isEmpty()) "none" else security)
                    if (sni.isNotEmpty()) q.append("&sni=$sni")
                    if (pbk.isNotEmpty()) q.append("&pbk=$pbk")
                    "vless://$id@$server:$port?$q#$tag"
                }
                "vmess" -> {
                    val vnext = ob.optJSONObject("settings")
                        ?.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                    val id = vnext.optJSONArray("users")
                        ?.optJSONObject(0)?.optString("id") ?: return null
                    if (id.isEmpty()) return null
                    val json = org.json.JSONObject()
                        .put("v", "2").put("ps", remark ?: server)
                        .put("add", server).put("port", port.toString())
                        .put("id", id).put("aid", "0").put("net", "tcp")
                        .put("tls", if (security == "tls") "tls" else "")
                    if (sni.isNotEmpty()) json.put("sni", sni)
                    "vmess://" + android.util.Base64.encodeToString(
                        json.toString().toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP)
                }
                "trojan" -> {
                    val pw = ob.optJSONObject("settings")
                        ?.optJSONArray("servers")?.optJSONObject(0)
                        ?.optString("password") ?: return null
                    val q = if (sni.isNotEmpty()) "?sni=$sni" else ""
                    "trojan://$pw@$server:$port$q#$tag"
                }
                "shadowsocks" -> {
                    val s = ob.optJSONObject("settings")
                        ?.optJSONArray("servers")?.optJSONObject(0) ?: return null
                    val pw = s.optString("password")
                    val method = s.optString("method")
                    if (pw.isEmpty() || method.isEmpty()) return null
                    val ui = android.util.Base64.encodeToString(
                        "$method:$pw".toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
                    "ss://$ui@$server:$port#$tag"
                }
                "hysteria2", "hy2" -> {
                    val pw = ob.optJSONObject("settings")?.optString("password")
                        ?: ob.optString("password").takeIf { it.isNotEmpty() }
                        ?: return null
                    val q = StringBuilder()
                    ob.optJSONObject("obfs")?.let {
                        q.append("obfs=${it.optString("type")}")
                        q.append("&obfs-password=${it.optString("password")}")
                    }
                    "hysteria2://$pw@$server:$port?$q#$tag"
                }
                "tuic" -> {
                    val s = ob.optJSONObject("settings") ?: return null
                    val uuid = s.optString("uuid")
                    val pw = s.optString("password")
                    if (uuid.isEmpty()) return null
                    "tuic://$uuid:$pw@$server:$port#$tag"
                }
                "anytls" -> {
                    val pw = ob.optJSONObject("settings")?.optString("password")
                        ?: return null
                    "anytls://$pw@$server:$port#$tag"
                }
                else -> null
            }
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-DBG", "buildLink xato ($proto): ${t.message}")
            null
        }
    }

    private companion object {
        /** JSON outbound protokollari → link protokollari. */
        val JSON_PROTOCOLS = setOf(
            "vless", "vmess", "trojan", "shadowsocks",
            "hysteria2", "hy2", "tuic", "anytls"
        )
    }
'''


def patch_servers() -> bool:
    src = SRV_F.read_text(encoding="utf-8")
    if "TUZATISH #9" in src:
        print("ℹ️  ServersFragment allaqachon tuzatilgan")
        return True
    safe_backup(SRV_F, "fix9")

    if OLD_LINKS not in src:
        print("❌ ServersFragment.loadSub: anchor topilmadi")
        return False
    src = src.replace(OLD_LINKS, NEW_LINKS, 1)

    # Method + companion ni class oxiriga qo'shamiz
    idx = src.rstrip().rfind("}")
    if idx < 0:
        print("❌ ServersFragment: class yopilishi topilmadi")
        return False
    src = src[:idx] + JSON_METHOD + src[idx:]

    SRV_F.write_text(src, encoding="utf-8")
    print("✅ ServersFragment: JSON obuna import qo'shildi")
    return True


def main() -> int:
    a = patch_manifest()
    b = patch_servers()
    return 0 if (a and b) else 1


if __name__ == "__main__":
    sys.exit(main())
