#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
nurvpn_all_fixes.py — NurVPN uchun TO'LIQ tuzatishlar (idempotent).

  python3 nurvpn_all_fixes.py /home/pc/NurVPN            # qo'llash
  python3 nurvpn_all_fixes.py /home/pc/NurVPN --check    # nima qo'llangan/qo'llanmagan
  python3 nurvpn_all_fixes.py /home/pc/NurVPN --dry-run  # hech narsa yozmaydi

Xususiyatlari:
  * Qayta ishga tushirsa xavfsiz: qo'llangan yamoq o'tkazib yuboriladi.
  * ATOMIK: birorta anchor topilmasa, HECH NARSA yozilmaydi.
  * Zaxira nusxa: ~/nurvpn_backup_allfixes_<vaqt>/ (loyiha tashqarisida).
  * Kotlin figurali qavslar balansi tekshiriladi.
"""
import re, glob, sys, os, shutil, time

args = [a for a in sys.argv[1:] if not a.startswith('--')]
ROOT = args[0] if args else '.'
CHECK = '--check' in sys.argv
DRY = '--dry-run' in sys.argv
ROOT = os.path.abspath(ROOT)
os.chdir(ROOT)

def fz(s): return r'\s+'.join(map(re.escape, s.split()))

def find(name):
    r = glob.glob('app/src/main/**/' + name, recursive=True)
    if not r:
        print("TOPILMADI:", name); sys.exit(1)
    return r[0]

ORIG, FILES = {}, {}
APPLIED, SKIPPED, ERR, STATUS = [], [], [], []

def load(p):
    if p not in FILES:
        t = open(p, encoding='utf-8').read()
        ORIG[p] = t; FILES[p] = t
    return FILES[p]

def apply(fname, label, old, new, marker):
    p = find(fname); t = load(p)
    if marker in t:
        SKIPPED.append(label); STATUS.append((label, True)); return
    STATUS.append((label, False))
    if CHECK: return
    t2, n = re.subn(fz(old), lambda m: new, t)
    if n != 1:
        ERR.append("%s: anchor n=%d (%s)" % (label, n, fname)); return
    FILES[p] = t2; APPLIED.append(label)

def apply_region(fname, label, start, end, new, marker):
    p = find(fname); t = load(p)
    if marker in t:
        SKIPPED.append(label); STATUS.append((label, True)); return
    STATUS.append((label, False))
    if CHECK: return
    if t.count(start) != 1 or t.count(end) != 1 or t.index(start) > t.index(end):
        ERR.append("%s: chegaralar topilmadi (%s)" % (label, fname)); return
    i = t.index(start); j = t.index(end) + len(end)
    FILES[p] = t[:i] + new + t[j:]; APPLIED.append(label)

# ═══════════════ 1. NurVpnService ═══════════════
SVC = 'NurVpnService.kt'
apply(SVC, 'servis: netrestart alohida thread (ANR)',
 'private val networkRestartRunnable = Runnable { doNetworkRestart() }',
 'private val networkRestartRunnable = Runnable {\n        Thread { doNetworkRestart() }.apply { name = "NurVPN-netrestart" }.start()\n    }',
 'NurVPN-netrestart')
apply(SVC, 'servis: lastCapPush o\'zgaruvchisi',
 '@Volatile private var networkBaselineReady = false',
 '@Volatile private var networkBaselineReady = false\n    @Volatile private var lastCapPush = 0L',
 'lastCapPush = 0L')
apply(SVC, 'servis: onLost faqat joriy tarmoq uchun',
 'Log.i(TAG, "onLost: $network (handle=$handle)") pushDefaultInterface(l)',
 'Log.i(TAG, "onLost: $network (handle=$handle)")\n                        pushDefaultInterface(l)\n                        val curL = this@NurVpnService.underlyingNetwork\n                        if (curL != null && network != curL) return',
 'val curL = this@NurVpnService.underlyingNetwork')
apply(SVC, 'servis: onCapabilitiesChanged throttle + joriy tarmoq',
 'pushDefaultInterface(l) // NET_PATCH:',
 'val nowP = System.currentTimeMillis()\n                        if (nowP - this@NurVpnService.lastCapPush > 3000L) {\n                            this@NurVpnService.lastCapPush = nowP\n                            pushDefaultInterface(l)\n                        }\n                        val curC = this@NurVpnService.underlyingNetwork\n                        if (curC != null && network != curC) return\n                        // NET_PATCH:',
 'val nowP = System.currentTimeMillis()')
apply(SVC, 'servis: eski network callback unregister',
 'ifaceCallback = cb cm.registerNetworkCallback(req, cb)',
 'ifaceCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }\n                ifaceCallback = cb\n                cm.registerNetworkCallback(req, cb)',
 'ifaceCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }')
apply(SVC, 'servis: setMetered haqiqiy holat',
 'if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)',
 'if (Build.VERSION.SDK_INT >= 29) {\n                val cmM = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager\n                val capsM = pickPhysicalNetwork()?.let { cmM.getNetworkCapabilities(it) }\n                builder.setMetered(capsM?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)\n            }',
 'val capsM =')
apply(SVC, 'servis: interfeys turlari (libbox enum)',
 """jni.name.startsWith("wlan") -> 1 // WiFi
    jni.name.startsWith("eth") -> 3 // Ethernet
    jni.name.startsWith("rmnet") || jni.name.startsWith("ccmni") ||
    jni.name.startsWith("pdp") -> 2 // Cellular
    jni.name.startsWith("dummy") || jni.name.startsWith("tun") -> 0 // Other
    else -> 0""",
 """jni.name.startsWith("wlan") -> 0 // WiFi
                            jni.name.startsWith("rmnet") ||
                                jni.name.startsWith("ccmni") ||
                                jni.name.startsWith("pdp") -> 1  // Cellular
                            jni.name.startsWith("eth") -> 2    // Ethernet
                            else -> 3  // Other (tun, dummy, ...)""",
 'startsWith("wlan") -> 0')
apply(SVC, 'servis: interfeys turi izohi (eskirgan izohni tuzatish)',
 '//   0=other, 1=wifi, 2=cellular, 3=ethernet',
 '//   0=wifi, 1=cellular, 2=ethernet, 3=other (log bilan tasdiqlangan)',
 '0=wifi, 1=cellular, 2=ethernet, 3=other')
apply(SVC, 'servis: crash guard maydonlari (companion)',
 'const val TAG = "NurVPN-Svc"',
 'const val TAG = "NurVPN-Svc"\n        @Volatile private var guardInstalled = false\n        @Volatile private var guardService: java.lang.ref.WeakReference<NurVpnService>? = null',
 'guardInstalled = false')
apply_region(SVC, 'servis: crash guard bir marta + WeakReference (xotira leak)',
 '    private fun installCrashGuard() {',
 '        Log.i(TAG, "Crash guard o\'rnatildi")\n    }\n',
 """    private fun installCrashGuard() {
        // Servis zaif havola orqali saqlanadi: handler eski servisni xotirada
        // ushlab turmaydi va har onCreate'da zanjirga qayta qo'shilmaydi.
        guardService = java.lang.ref.WeakReference(this)
        if (guardInstalled) return
        guardInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "‼️ USHLANMAGAN ISTISNO (${thread.name})", throwable)
                com.nurvpn.app.core.LogBuffer.add(
                    "CRASH: ${throwable.javaClass.simpleName}: ${throwable.message}")
                val svc = guardService?.get()
                try { svc?.stopSelf() } catch (_: Throwable) {}
                try { svc?.broadcast("error",
                    "Kutilmagan xato: ${throwable.javaClass.simpleName}") } catch (_: Throwable) {}
            } catch (_: Throwable) {
                // handler ichida ham crash bo'lmasin
            } finally {
                prev?.uncaughtException(thread, throwable)
            }
        }
        Log.i(TAG, "Crash guard o'rnatildi")
    }
""",
 'guardService = java.lang.ref.WeakReference(this)')

# ═══════════════ 2. SingBoxConfig ═══════════════
SBC = 'SingBoxConfig.kt'
apply(SBC, 'config: redactJson yordamchisi',
 'class ParseException(msg: String) : Exception(msg)',
 'class ParseException(msg: String) : Exception(msg)\n\n'
 '    /** Logga kalit/parol yozmaslik uchun. */\n'
 '    private fun redactJson(s: String): String =\n'
 '        Regex("\\"(private_key|public_key|password|pre_shared_key)\\":\\"[^\\"]*\\"")\n'
 '            .replace(s) { "\\"" + it.groupValues[1] + "\\":\\"***\\"" }',
 'private fun redactJson')
apply(SBC, 'config: Hysteria2 log yashirin',
 'Log.i(TAG, "Hysteria2 JSON: $o")', 'Log.i(TAG, "Hysteria2 JSON: " + redactJson(o.toString()))',
 '"Hysteria2 JSON: " + redactJson')
apply(SBC, 'config: AWG log yashirin',
 'Log.i(TAG, "AWG JSON: " + o.toString())', 'Log.i(TAG, "AWG JSON: " + redactJson(o.toString()))',
 '"AWG JSON: " + redactJson')
apply(SBC, 'config: MASQUE vhttp o\'zgaruvchisi',
 'var ipv6 = "" var mtu = 1280', 'var ipv6 = ""\n        var mtu = 1280\n        var vhttp = ""',
 'var vhttp = ""')
apply(SBC, 'config: MASQUE Network -> vhttp o\'qish',
 'k.equals("Profile", true) -> o.put("profile", v.lowercase())',
 'k.equals("Profile", true) -> o.put("profile", v.lowercase())\n'
 '                // ★ FIX_FORCE_H2_CLEAN: `Network` = vhttp (haqiqiy JSON kaliti `vhttp`).\n'
 '                k.equals("Network", true) -> vhttp = v.trim().lowercase()',
 'k.equals("Network", true) -> vhttp = v.trim().lowercase()')
apply(SBC, 'config: MASQUE har doim h2 (QUIC bloklangan tarmoqlar)',
 'Log.i(TAG, "MASQUE: $server:$port sni=$sni mtu=$mtu profile=${o.optString("profile")} network=${o.optString("network")}")',
 """// ★ FIX_FORCE_H2_CLEAN: h3 MAJBURIY h2 ga o'giriladi (QUIC bloklangan tarmoqlarda
        //   yadro har ulanishda h3 sinab vaqt yo'qotadi). `auto` ham h2.
        when {
            vhttp == "h2" -> o.put("vhttp", "h2")
            vhttp == "h3" || vhttp == "auto" -> {
                Log.w(TAG, "MASQUE: Network=$vhttp -> h2 ga majburan o'girildi")
                o.put("vhttp", "h2")
            }
            else -> {
                if (vhttp.isNotEmpty())
                    Log.w(TAG, "MASQUE: noma'lum Network=\\"$vhttp\\" — o'rniga h2")
                o.put("vhttp", "h2")
            }
        }

        Log.i(TAG, "MASQUE: $server:$port sni=$sni mtu=$mtu " +
            "profile=${o.optString("profile")} vhttp=${o.optString("vhttp")}")""",
 'FIX_FORCE_H2_CLEAN: h3 MAJBURIY')
apply(SBC, 'config: MASQUE JSON log yashirin',
 'Log.i(TAG, "MASQUE JSON: " + o.toString())', 'Log.i(TAG, "MASQUE JSON: " + redactJson(o.toString()))',
 '"MASQUE JSON: " + redactJson')

# ═══════════════ 3. LogBuffer ═══════════════
apply('LogBuffer.kt', 'logbuffer: kalitlarni *** bilan almashtirish',
 'lines.addLast(fmt.format(java.util.Date()) + " " + line.take(400))',
 'lines.addLast(fmt.format(java.util.Date()) + " " + redact(line).take(400))',
 'redact(line).take(400)')
apply('LogBuffer.kt', 'logbuffer: redact funksiyasi',
 '@Synchronized fun clear() {',
 'private val secretRe = Regex("[A-Za-z0-9+/=_-]{40,}")\n    private fun redact(s: String) = secretRe.replace(s, "***")\n\n    @Synchronized\n    fun clear() {',
 'private fun redact(s: String)')

# ═══════════════ 4. MasqueEditorActivity ═══════════════
ME = 'MasqueEditorActivity.kt'
apply(ME, 'masque editor: PrivateKey preview yashirin',
 'val r = buildRaw()',
 'val r = buildRaw().replace(Regex("(?m)^PrivateKey = .*$"), "PrivateKey = ********")',
 'PrivateKey = ********')
apply(ME, 'masque editor: port tekshiruvi',
 'cfg.rawConf = buildRaw()',
 'val portNum = portEt.text.toString().trim().toIntOrNull()\n        if (portNum == null || portNum !in 1..65535) {\n            Toast.makeText(this, R.string.editor_invalid_port, Toast.LENGTH_SHORT).show()\n            return\n        }\n        cfg.rawConf = buildRaw()',
 'val portNum =')
apply(ME, 'masque editor: standart Network = h2',
 'networkEt.setText(m["network"] ?: "h3")', 'networkEt.setText(m["network"] ?: "h2")',
 'm["network"] ?: "h2"')

# ═══════════════ 5. PingTester ═══════════════
PT = 'PingTester.kt'
apply(PT, 'ping: thread pool bo\'sh turganda yopiladi',
 'private val pool: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT)',
 'private val pool: ExecutorService = java.util.concurrent.ThreadPoolExecutor(\n        MAX_CONCURRENT, MAX_CONCURRENT, 20L, TimeUnit.SECONDS,\n        java.util.concurrent.LinkedBlockingQueue<Runnable>()\n    ).apply { allowCoreThreadTimeOut(true) }',
 'allowCoreThreadTimeOut')
apply(PT, 'ping: Batcher.stop()',
 'fun markDone() {',
 'fun stop() {\n            flushed.set(true)\n            main.removeCallbacks(flushTask)\n            pending.clear()\n        }\n\n        fun markDone() {',
 'main.removeCallbacks(flushTask)\n            pending.clear()')
apply(PT, 'ping: bekor qilingan testdan keyin 150ms sikl to\'xtaydi',
 'if (remaining.decrementAndGet() == 0 && runId.get() == myRun) { batcher.markDone() }',
 'if (remaining.decrementAndGet() == 0) {\n                        if (runId.get() == myRun) batcher.markDone() else batcher.stop()\n                    }',
 'else batcher.stop()')

# ═══════════════ 6. ServersFragment: fetchSub cap + ko'p MASQUE ═══════════════
SF = 'ServersFragment.kt'
p = find(SF); t = load(p)
if 'readCapped' in t:
    SKIPPED.append('obuna: yuklash hajm cheklovi'); STATUS.append(('obuna: yuklash hajm cheklovi', True))
else:
    STATUS.append(('obuna: yuklash hajm cheklovi', False))
    if not CHECK:
        t1, n1 = re.subn(fz('val compressedBytes = c.inputStream.use { it.readBytes() }'),
            lambda m: 'val compressedBytes = c.inputStream.use { it.readCapped(4 * 1024 * 1024) }', t)
        t1, n2 = re.subn(fz(').use { it.readBytes() }'), lambda m: ').use { it.readCapped(8 * 1024 * 1024) }', t1)
        t1, n3 = re.subn(fz('private fun fetchSub('), lambda m: (
            'private fun java.io.InputStream.readCapped(max: Int): ByteArray {\n'
            '        val bos = java.io.ByteArrayOutputStream()\n'
            '        val buf = ByteArray(8192)\n'
            '        var total = 0\n'
            '        while (true) {\n'
            '            val r = read(buf)\n'
            '            if (r < 0) break\n'
            '            total += r\n'
            '            if (total > max) break\n'
            '            bos.write(buf, 0, r)\n'
            '        }\n'
            '        return bos.toByteArray()\n'
            '    }\n\n'
            '    private fun fetchSub('), t1)
        if (n1, n3) != (1, 1) or n2 != 2:
            ERR.append("obuna cheklovi: n=%s/%s/%s" % (n1, n2, n3))
        else:
            FILES[p] = t1; APPLIED.append('obuna: yuklash hajm cheklovi')

OLD_BRANCH = r'''val converted = convertYamlMasqueToConf(conf)
            if (converted == null) {
                Toast.makeText(context, getString(R.string.toast_yaml_masque_error),
                    Toast.LENGTH_LONG).show()
                return
            }
            finalConf = converted
            android.util.Log.i("NurVPN-AWG", "YAML MASQUE → conf:\n$converted")'''
NEW_BRANCH = r'''val convertedList = convertYamlMasqueToConfs(conf)
            if (convertedList.isEmpty()) {
                Toast.makeText(context, getString(R.string.toast_yaml_masque_error),
                    Toast.LENGTH_LONG).show()
                return
            }
            if (convertedList.size > 1) {
                var added = 0
                var lastCfg: AWGConfig? = null
                for (c in convertedList) {
                    val pair = extractMasqueServer(c)
                    if (pair.first.isEmpty()) continue
                    val mc = AWGConfig(c)
                    mc.type = "masque"
                    mc.endpoint = pair.first + ":" + pair.second
                    mc.address = ""
                    mc.name = "MASQUE " + pair.first
                    a.awgConfigs.add(mc)
                    lastCfg = mc
                    added++
                }
                val last = lastCfg ?: return
                AWGStore.save(a, a.awgConfigs)
                a.selectAWG(last)
                a.protocol = MainActivity.PROTO_AWG
                AWGEditorBus.init(a.awgConfigs, last, MainActivity.PROTO_AWG)
                ad?.notifyDataChanged()
                Toast.makeText(context,
                    getString(R.string.toast_n_wg_added_fmt, added),
                    Toast.LENGTH_SHORT).show()
                return
            }
            finalConf = convertedList[0]
            android.util.Log.i("NurVPN-AWG", "YAML MASQUE → conf: 1 ta")'''
NEW_FUNCS = r'''    private fun yamlKv(into: MutableMap<String, String>, text: String) {
        val i = text.indexOf(':')
        if (i <= 0) return
        val k = text.substring(0, i).trim().trim('"', '\'').lowercase().replace('_', '-')
        var v = text.substring(i + 1).trim()
        val hash = v.indexOf(" #")
        if (hash >= 0) v = v.substring(0, hash).trim()
        v = v.trim('"', '\'')
        if (k.isNotEmpty() && v.isNotEmpty()) into[k] = v
    }

    /** YAML dagi BARCHA MASQUE proxylarni .conf matnlariga aylantiradi (&msq umumiy qiymatlar bilan). */
    private fun convertYamlMasqueToConfs(yaml: String): List<String> {
        val lines = yaml.lines()
        val defaults = HashMap<String, String>()
        var inAnchor = false
        var anchorIndent = -1
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val ind = line.indexOfFirst { !it.isWhitespace() }
            if (t.startsWith("msq:") || t.contains("&msq")) {
                inAnchor = true
                anchorIndent = ind
                continue
            }
            if (inAnchor) {
                if (ind <= anchorIndent) inAnchor = false else yamlKv(defaults, t)
            }
        }

        val out = ArrayList<String>()
        var cur: HashMap<String, String>? = null
        var curIndent = -1
        var inProxies = false

        fun flush() {
            val p = cur ?: return
            cur = null
            val m = HashMap<String, String>(defaults)
            m.putAll(p)
            val type = m["type"]?.lowercase()
            if (type != null && type != "masque") return
            val server = m["server"] ?: return
            val pk = m["private-key"] ?: return
            val pub = m["public-key"] ?: return
            val addr = listOf(m["ip"], m["ipv6"])
                .mapNotNull { s -> s?.substringBefore("/")?.takeIf { it.isNotEmpty() } }
                .joinToString(", ")
            if (addr.isEmpty()) return
            val net = if ((m["network"] ?: "h2").lowercase() == "h3") "h3" else "h2"
            val sb = StringBuilder()
            sb.append("PrivateKey = ").append(pk).append('\n')
            sb.append("PublicKey = ").append(pub).append('\n')
            sb.append("Address = ").append(addr).append('\n')
            sb.append("MTU = ").append(m["mtu"] ?: "1280").append('\n')
            sb.append("Server = ").append(server).append('\n')
            sb.append("Port = ").append(m["port"] ?: "443").append('\n')
            val sni = m["sni"]
            if (!sni.isNullOrEmpty()) sb.append("SNI = ").append(sni).append('\n')
            sb.append("Network = ").append(net).append('\n')
            sb.append("Profile = cloudflare").append('\n')
            out.add(sb.toString())
        }

        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val ind = line.indexOfFirst { !it.isWhitespace() }
            if (!inProxies) {
                if (t == "proxies:") inProxies = true
                continue
            }
            if (ind == 0 && !t.startsWith("-")) break
            if ((t.startsWith("- ") || t == "-") && (cur == null || ind <= curIndent)) {
                flush()
                val m = HashMap<String, String>()
                cur = m
                curIndent = ind
                val first = t.removePrefix("-").trim()
                if (first.startsWith("{")) {
                    for (part in first.trim('{', '}').split(",")) yamlKv(m, part.trim())
                } else {
                    yamlKv(m, first)
                }
                continue
            }
            cur?.let { yamlKv(it, t) }
        }
        flush()
        android.util.Log.i("NurVPN-AWG", "convertYamlMasqueToConfs: ${out.size} ta proxy")
        return out
    }

    private fun convertYamlMasqueToConf(yaml: String): String? =
        convertYamlMasqueToConfs(yaml).firstOrNull()

'''
apply(SF, 'import: YAML dagi BARCHA MASQUE proxylar (tarmoq)', OLD_BRANCH, NEW_BRANCH,
      'val convertedList = convertYamlMasqueToConfs(conf)')
apply_region(SF, 'import: YAML MASQUE konvertori (ko\'p proxy + Network)',
 '    private fun convertYamlMasqueToConf(yaml: String): String? {',
 '    private fun showSubDialog() {', NEW_FUNCS + '    private fun showSubDialog() {',
 'private fun convertYamlMasqueToConfs')

# ═══════════════ 7. Tanlangan config yo'qolmasligi (MainActivity + AWGStore) ═══════════════
apply('MainActivity.kt', 'config tanlovi: oxirgi bo\'shliqni e\'tiborsiz qoldirish',
 'currentAWG = awgConfigs.find { it.rawConf == savedAwg }',
 '// ★ FIX_CONFIG_MATCH: oxiridagi bo\'shliq farqi aniq solishtirishni buzardi.\n'
 '            currentAWG = awgConfigs.find {\n'
 '                it.rawConf?.trimEnd() == savedAwg.trimEnd()\n'
 '            }',
 'it.rawConf?.trimEnd() == savedAwg.trimEnd()')
apply('MainActivity.kt', 'config tanlovi: jimgina fallback o\'rniga Log.w',
 'if (currentAWG == null && awgConfigs.isNotEmpty()) currentAWG = awgConfigs[0]',
 'if (currentAWG == null && awgConfigs.isNotEmpty()) {\n'
 '            // ★ FIX_CONFIG_MATCH: JIMGINA bo\'lmasin.\n'
 '            android.util.Log.w("NurVPN-CFG",\n'
 '                "current_awg topilmadi (saved=${savedAwg?.length} belgi) " +\n'
 '                "-> awgConfigs[0] ishlatiladi: ${awgConfigs[0].name}")\n'
 '            currentAWG = awgConfigs[0]\n'
 '        }\n',
 'current_awg topilmadi')
apply('AWGStore.kt', 'AWGStore: saqlashda trimEnd',
 'o.put("raw", c.rawConf)', 'o.put("raw", c.rawConf?.trimEnd())',
 'o.put("raw", c.rawConf?.trimEnd())')
apply('AWGStore.kt', 'AWGStore: o\'qishda trimEnd',
 'val c = AWGConfig(o.optString("raw"))', 'val c = AWGConfig(o.optString("raw").trimEnd())',
 'o.optString("raw").trimEnd()')

# ═══════════════ 8. OpenSourcesRefreshWorker: hajm cheklovi ═══════════════
apply('OpenSourcesRefreshWorker.kt', 'worker: yuklash hajm cheklovi (8 MB)',
 'val text = conn.inputStream.bufferedReader().use { it.readText() }',
 """val text = conn.inputStream.use { ins ->
                val bos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > 8 * 1024 * 1024) throw java.io.IOException("javob juda katta")
                    bos.write(buf, 0, r)
                }
                bos.toString("UTF-8")
            }""",
 'javob juda katta')

# ═══════════════ NATIJA ═══════════════
if CHECK:
    ok = 0
    for label, done in STATUS:
        print(("✅ " if done else "❌ ") + label); ok += done
    print("\n%d/%d yamoq qo'llangan" % (ok, len(STATUS)))
    sys.exit(0 if ok == len(STATUS) else 1)

if ERR:
    print("XATO — HECH NARSA YOZILMADI:")
    for e in ERR: print("  ✗", e)
    sys.exit(1)

# qavs balansi (satr/izoh ichidagi qavslar hisobga olinmaydi)
_STRIP = re.compile(r'''"{3}.*?"{3}|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])'|//[^\n]*|/\*.*?\*/''', re.S)
def balance(t):
    t = _STRIP.sub('', t)
    return t.count('{') - t.count('}')
bad = []
for p, t in FILES.items():
    if p.endswith('.kt') and balance(ORIG[p]) != balance(t):
        bad.append(p)
if bad:
    print("XATO: figurali qavs balansi buzildi:", bad); sys.exit(1)

for l in SKIPPED: print("⏭️  allaqachon:", l)
for l in APPLIED: print("✅ qo'llandi:", l)
changed = [p for p in FILES if FILES[p] != ORIG[p]]
if DRY:
    print("\n--dry-run: %d fayl o'zgarardi, hech narsa yozilmadi" % len(changed)); sys.exit(0)
if changed:
    bk = os.path.expanduser('~/nurvpn_backup_allfixes_' + time.strftime('%Y%m%d_%H%M%S'))
    for p in changed:
        os.makedirs(os.path.dirname(os.path.join(bk, p)), exist_ok=True)
        shutil.copy2(p, os.path.join(bk, p))
        open(p, 'w', encoding='utf-8').write(FILES[p])
    print("\nZaxira:", bk)
print("\nHAMMASI OK: %d yangi yamoq, %d allaqachon qo'llangan" % (len(APPLIED), len(SKIPPED)))
