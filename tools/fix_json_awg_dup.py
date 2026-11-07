from pathlib import Path
import shutil
import re
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

# ═══════════════════════════════════════════════════════════════
# 1. Eski blokni topish (obuna avval yaratiladi)
# ═══════════════════════════════════════════════════════════════
old_block = '''                // subId yaratamiz yoki mavjudni olamiz
                val subIdForJson = "sub_" + System.currentTimeMillis().toString(36)
                var subForJson = a.subscriptions.find { it.url == url }
                if (subForJson == null) {
                    subForJson = Subscription(subIdForJson, url, headerTitle ?: subName ?: extractSubTitle(url))
                    a.subscriptions.add(subForJson)
                }
                if (wasEncrypted) subForJson.encrypted = true
                if (headerTitle != null && subForJson.name != headerTitle) {
                    // Provayder nomi header'dan kelgan bo'lsa — yangilaymiz
                    subForJson.name = headerTitle
                    android.util.Log.i("NurVPN-DBG", "sub name updated: $headerTitle")
                    SubscriptionStore.save(a, a.subscriptions)
                }
                // ═══ FIX: WireGuard outbound'larni AWG ga qo'shish ═══
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

                val jsonServers = parseJsonConfigsWithSub(trimmedBody, subForJson.id)'''

# ═══════════════════════════════════════════════════════════════
# 2. Yangi blok (avval parse, keyin obuna yaratish)
# ═══════════════════════════════════════════════════════════════
new_block = '''                // ═══ 1. AWG va serverlarni parse qilamiz ═══
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
                            val tag = ob.optString("tag", "")
                            awgCfg.name = if (tag.isNotBlank()) "WARP $tag" else "WARP"
                            a.awgConfigs.add(awgCfg)
                            wgAdded++
                        }
                    }
                    if (wgAdded > 0) {
                        com.nurvpn.app.storage.AWGStore.save(a, a.awgConfigs)
                        android.util.Log.i("NurVPN-DBG", "WireGuard JSON → $wgAdded AWG qo'shildi")
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("NurVPN-DBG", "WireGuard JSON parse xato: ${t.message}")
                }

                val subIdForJson = "sub_" + System.currentTimeMillis().toString(36)
                val jsonServers = parseJsonConfigsWithSub(trimmedBody, subIdForJson)

                // ═══ 2. Agar oddiy serverlar bo'lsa — obuna yaratamiz ═══
                if (jsonServers.isNotEmpty()) {
                    var subForJson = a.subscriptions.find { it.url == url }
                    if (subForJson == null) {
                        subForJson = Subscription(subIdForJson, url, headerTitle ?: subName ?: extractSubTitle(url))
                        a.subscriptions.add(subForJson)
                    }
                    if (wasEncrypted) subForJson.encrypted = true
                    if (headerTitle != null && subForJson.name != headerTitle) {
                        subForJson.name = headerTitle
                        SubscriptionStore.save(a, a.subscriptions)
                    }
                    // Serverlarga to'g'ri subId beramiz
                    for (si in jsonServers) { si.subId = subForJson.id }
                    a.servers.addAll(jsonServers)
                    android.util.Log.i("NurVPN-DBG", "Obuna yaratildi/yangilandi: ${jsonServers.size} server")
                } else {
                    android.util.Log.i("NurVPN-DBG", "JSON ichida oddiy server yo'q — obuna yaratilmaydi")
                }'''

if old_block in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old_block, new_block, 1)
    
    # ═══ 3. Mavjud bo'sh obunalarni tozalash (a.subscriptions) ═══
    # Bu kod rebuildServerCards dan oldin ishlaydi
    cleanup_anchor = "    private fun rebuildServerCards(force: Boolean = false) {"
    if cleanup_anchor in t:
        cleanup_code = """    private fun rebuildServerCards(force: Boolean = false) {
        // FIX: Mavjud bo'sh obunalarni o'chirish (0 server)
        val a0 = activity as? MainActivity
        if (a0 != null) {
            val before = a0.subscriptions.size
            a0.subscriptions.removeAll { sub ->
                a0.servers.none { it.subId == sub.id }
            }
            if (a0.subscriptions.size < before) {
                com.nurvpn.app.storage.SubscriptionStore.save(a0, a0.subscriptions)
                android.util.Log.i("NurVPN-DBG", "Bo'sh obunalar tozalandi: ${before - a0.subscriptions.size} ta")
            }
        }
"""
        t = t.replace(cleanup_anchor, cleanup_code, 1)
        print("✅ Bo'sh obunalarni avtomatik tozalash qo'shildi")

    p.write_text(t, encoding='utf-8')
    print("✅ JSON import tartibi tuzatildi:")
    print("   Avval: Obuna yaratish → AWG parse → Server parse (bo'sh obuna qoladi)")
    print("   Keyin: AWG parse → Server parse → Agar server bor bo'lsa obuna yaratish")
else:
    print("⚠ Naqsh topilmadi. Kod o'zgargan bo'lishi mumkin.")
