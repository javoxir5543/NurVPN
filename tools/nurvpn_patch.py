#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NurVPN — MASQUE h2 majburiy + IPv4/IPv6/DNS leak + xotira leak + batareya patch.

Ishlatish:
    python3 nurvpn_patch.py --root /path/to/NurVPN            # patch
    python3 nurvpn_patch.py --root . --dry-run                # tekshirish
    python3 nurvpn_patch.py --root . --only h2                # faqat h2
    python3 nurvpn_patch.py --root . --revert                 # backup'dan qaytarish

Backup: <root>/.nurvpn_patch_backup/<relpath>
"""

import argparse
import os
import re
import shutil
import sys
from dataclasses import dataclass, field
from typing import Callable, Optional

BACKUP_DIR = ".nurvpn_patch_backup"

# ══════════════════════════════════════════════════════════════════════════
#  YORDAMCHILAR
# ══════════════════════════════════════════════════════════════════════════

def flex(snippet: str) -> str:
    """Kotlin bo'lagini indentatsiyaga chidamli regex'ga aylantiradi."""
    out = []
    for tok in re.split(r"(\s+)", snippet.strip()):
        if not tok:
            continue
        if tok.isspace():
            out.append(r"\s*" if "\n" in tok else r"[ \t]*")
        else:
            out.append(re.escape(tok))
    return "".join(out)


def render(m: re.Match, new: str) -> str:
    """Yangi matnni asl indentatsiyaga moslaydi. '<' = 4 bo'shliq orqaga."""
    base = m.groupdict().get("ind", "")
    lines = []
    for line in new.split("\n"):
        ded = 0
        while line.startswith("<"):
            ded += 1
            line = line[1:]
        ind = base[: max(0, len(base) - 4 * ded)]
        lines.append(ind + line if line.strip() else "")
    return "\n".join(lines)


@dataclass
class Rule:
    name: str
    group: str
    path: str
    marker: str                       # allaqachon patch qilinganini bildiradi
    kind: str = "flex"                # flex | raw | func
    old: str = ""
    new: str = ""
    pattern: str = ""
    repl: str = ""
    count: int = 1                    # 0 = hammasi
    flags: int = re.M
    optional: bool = False
    fn: Optional[Callable[[str], tuple]] = None


def F(name, group, path, marker, old, new, optional=False):
    return Rule(name, group, path, marker, "flex", old=old, new=new, optional=optional)


def R(name, group, path, marker, pattern, repl, count=1, optional=False, flags=re.M):
    return Rule(name, group, path, marker, "raw", pattern=pattern, repl=repl,
                count=count, optional=optional, flags=flags)


def L(name, group, path, marker, literal, repl, count=1, optional=False):
    return R(name, group, path, marker, re.escape(literal), repl, count, optional, 0)


# ══════════════════════════════════════════════════════════════════════════
#  Fayl yo'llari
# ══════════════════════════════════════════════════════════════════════════
J = "app/src/main/java/com/nurvpn/app/"
CFG      = J + "config/SingBoxConfig.kt"
BUILTIN  = J + "config/BuiltinAwgConfigs.kt"
AWGSTORE = J + "storage/AWGStore.kt"
SVC      = J + "service/NurVpnService.kt"
HOME     = J + "ui/home/HomeFragment.kt"
SERVERS  = J + "ui/servers/ServersFragment.kt"
MEDITOR  = J + "ui/awg/MasqueEditorActivity.kt"
MAIN     = J + "ui/MainActivity.kt"
SPLIT    = J + "ui/split/SplitAppsActivity.kt"
WAVE     = J + "ui/widget/SpeedWaveView.kt"
PING     = J + "util/PingTester.kt"
LEAK     = J + "util/LeakTester.kt"
AI       = J + "ai/AIServerSelector.kt"
APPL     = J + "storage/AppListLoader.kt"
WORKER   = J + "worker/OpenSourcesRefreshWorker.kt"
RES      = "app/src/main/res/"

RULES: list = []

# ══════════════════════════════════════════════════════════════════════════
#  1) MASQUE → majburiy h2 (WiFi + mobil internet)
# ══════════════════════════════════════════════════════════════════════════

def _builtin_h2(text: str):
    """Faqat MASQUE bloklarida: Network=h2, MTU=1280 (AMNEZIA tegilmaydi)."""
    n = 0
    pat = re.compile(r'(val\s+MASQUE_\d+\s*:\s*String\s*=\s*""")(.*?)(""")', re.S)

    def fix(m):
        nonlocal n
        body = m.group(2)
        b2 = re.sub(r"(?m)^([ \t]*)Network[ \t]*=[ \t]*\S+[ \t]*$",
                    r"\1Network = h2", body)
        b2 = re.sub(r"(?m)^([ \t]*)MTU[ \t]*=[ \t]*\d+[ \t]*$",
                    r"\1MTU = 1280", b2)
        if b2 != body:
            n += 1
        return m.group(1) + b2 + m.group(3)

    return pat.sub(fix, text), n


RULES.append(Rule("builtin: MASQUE Network=h2 + MTU=1280", "h2", BUILTIN,
                  "PATCH-H2-BUILTIN", kind="func", fn=_builtin_h2))

RULES += [
    F("parseMasque: network=h2 + MTU<=1280", "h2", CFG, 'o.put("network", "h2")',
      '''o.put("mtu", mtu)
if (sni.isNotEmpty()) o.put("sni", sni)''',
      '''o.put("mtu", mtu.coerceAtMost(1280))
// ★ PATCH-H2: MASQUE doim h2 (TCP/443) — WiFi va mobil internetda ishlaydi.
//   h3 = QUIC/UDP — ko'p mobil operatorlarda bloklanadi/yutiladi.
o.put("network", "h2")
if (sni.isNotEmpty()) o.put("sni", sni)'''),

    F("buildMasqueConfig: MTU 1280 + server direct + IPv6 sozlamasi", "h2", CFG,
      "MASQUE h2: hasV6",
      '''val proxy = parseMasque(rawConf, false)
val hasV6 = proxy.has("ipv6") && proxy.optString("ipv6").isNotEmpty()
android.util.Log.i("NurVPN-CFG", "MASQUE hasV6=$hasV6 ipv6=${proxy.optString("ipv6")}")
return assemble(proxy, false, dns, null, !hasV6,
tunMtu = 1400, tunV6 = hasV6, stack = "gvisor")''',
      '''val proxy = parseMasque(rawConf, ipv6Blocked)
val hasV6 = !ipv6Blocked && proxy.has("ipv6") && proxy.optString("ipv6").isNotEmpty()
android.util.Log.i("NurVPN-CFG", "MASQUE h2: hasV6=$hasV6 blockV6=$ipv6Blocked")
// ★ PATCH-H2/NET: server manzili direct qoidaga uzatiladi (loop yo'q),
//   MTU 1280 — mobil tarmoqda fragmentatsiya bo'lmasin.
return assemble(proxy, false, dns,
    proxy.optString("server").takeIf { it.isNotEmpty() },
    !hasV6,
    dnsLeakProtect = true,
    tunMtu = 1280, tunV6 = hasV6, stack = "gvisor")'''),

    L("assemble: MASQUE(h2) uchun DNS TCP orqali", "h2", CFG,
      '"shadowtls", "masque")',
      '"shadowsocksr", "anytls", "shadowtls")',
      '"shadowsocksr", "anytls", "shadowtls", "masque")'),

    L("MasqueEditor: default h2", "h2", MEDITOR, 'networkEt.setText("h2")   // ★ PATCH-H2',
      'networkEt.setText(m["network"] ?: "h3")',
      'networkEt.setText("h2")   // ★ PATCH-H2'),

    L("MasqueEditor: buildRaw Network=h2", "h2", MEDITOR, 'sb.append("Network = h2\\n")',
      'sb.append("Network = ").append(networkEt.text.toString().trim()).append(\'\\n\')',
      'sb.append("Network = h2\\\\n")   // ★ PATCH-H2: majburiy'),

    L("MasqueEditor: MTU 576..1280", "h2", MEDITOR, "coerceIn(576, 1280)",
      'sb.append("MTU = ").append(mtuEt.text.toString().trim()).append(\'\\n\')',
      'sb.append("MTU = ").append((mtuEt.text.toString().trim().toIntOrNull() '
      '?: 1280).coerceIn(576, 1280)).append(\'\\\\n\')'),

    F("MasqueEditor: Network maydoni qulflanadi", "h2", MEDITOR, "PATCH-H2: Network maydoni",
      '''preview = findViewById(R.id.preview)
if (AWGEditorBus.configs.isEmpty()) {''',
      '''preview = findViewById(R.id.preview)
// ★ PATCH-H2: Network maydoni qulflangan — faqat h2
networkEt.setText("h2")
networkEt.isEnabled = false
networkEt.isFocusable = false
if (AWGEditorBus.configs.isEmpty()) {'''),

    F("YAML MASQUE -> conf: Network=h2", "h2", SERVERS, '"Network = h2\\n" +',
      '''"SNI = " + sni + "\\n" +
"Profile = cloudflare\\n"''',
      '''"SNI = " + sni + "\\n" +
"Network = h2\\n" +
"Profile = cloudflare\\n"'''),

    # ── Eski saqlangan configlar migratsiyasi ──
    F("AWGStore: changed flag", "h2", AWGSTORE, "var changed = false",
      "val out = ArrayList<AWGConfig>()",
      '''val out = ArrayList<AWGConfig>()
var changed = false'''),

    F("AWGStore: load'da h2 normalizatsiya", "h2", AWGSTORE, "normalizeMasqueH2(origRaw)",
      'val c = AWGConfig(o.optString("raw"))',
      '''val origRaw = o.optString("raw")
val normRaw = normalizeMasqueH2(origRaw)
if (normRaw != origRaw) changed = true
val c = AWGConfig(normRaw)'''),

    F("AWGStore: migratsiyani diskka yozish", "h2", AWGSTORE, "PATCH-H2: migratsiya",
      '''} catch (ignored: Throwable) {}
return out''',
      '''} catch (ignored: Throwable) {}
// ★ PATCH-H2: migratsiya natijasini diskka yozamiz
if (changed) runCatching { save(ctx, out) }
return out'''),

    F("AWGStore: normalizeMasqueH2 helper", "h2", AWGSTORE, "fun normalizeMasqueH2",
      "fun load(ctx: Context): MutableList<AWGConfig> {",
      r'''/** ★ PATCH-H2: MASQUE confni majburan h2 + MTU<=1280 ga keltirish. */
private fun normalizeMasqueH2(raw: String?): String? {
    if (raw.isNullOrEmpty()) return raw
    val isMasque = !raw.contains("[Interface]") && !raw.contains("[Peer]") &&
        raw.contains("PrivateKey") && raw.contains("PublicKey") &&
        (raw.contains("Server") || raw.contains("Endpoint"))
    if (!isMasque) return raw
    var out = raw.replace(Regex("""(?im)^([ \t]*)Network[ \t]*=[ \t]*\S+[ \t]*$""")) { m ->
        m.groupValues[1] + "Network = h2"
    }
    if (!Regex("""(?im)^[ \t]*Network[ \t]*=""").containsMatchIn(out)) {
        out = out.trimEnd() + "\nNetwork = h2\n"
    }
    out = out.replace(Regex("""(?im)^([ \t]*)MTU[ \t]*=[ \t]*(\d+)[ \t]*$""")) { m ->
        val v = m.groupValues[2].toIntOrNull() ?: 1280
        m.groupValues[1] + "MTU = " + v.coerceIn(576, 1280)
    }
    return out
}
fun load(ctx: Context): MutableList<AWGConfig> {'''),

    # ── libbox "network" kalitini bilmasa — xavfsiz fallback ──
    L("Service: cfgJson var", "h2", SVC, "var cfgJson = cfg.toString(2)",
      "val cfgJson = cfg.toString(2)", "var cfgJson = cfg.toString(2)"),

    F("Service: checkConfig fallback (network'siz)", "h2", SVC, "stripMasqueNetwork(cfg)",
      '''try {
Libbox.checkConfig(cfgJson)
} catch (t: Throwable) {
throw Exception("Config xato: ${t.message}")
}''',
      '''try {
    Libbox.checkConfig(cfgJson)
} catch (t: Throwable) {
    // ★ PATCH-H2: yadro "network" kalitini bilmasa — olib tashlab qayta sinaymiz
    if (stripMasqueNetwork(cfg)) {
        cfgJson = cfg.toString(2)
        try {
            Libbox.checkConfig(cfgJson)
            Log.w(TAG, "MASQUE: 'network' qo'llab-quvvatlanmadi — profile default")
        } catch (t2: Throwable) {
            throw Exception("Config xato: ${t2.message}")
        }
    } else {
        throw Exception("Config xato: ${t.message}")
    }
}'''),

    F("Service: stripMasqueNetwork helper", "h2", SVC, "fun stripMasqueNetwork",
      "private fun describeConfig(cfg: org.json.JSONObject): String {",
      '''/** ★ PATCH-H2: MASQUE outbound'dan 'network' kalitini olib tashlash. */
private fun stripMasqueNetwork(cfg: JSONObject): Boolean {
    var changed = false
    for (key in listOf("outbounds", "endpoints")) {
        val arr = cfg.optJSONArray(key) ?: continue
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") == "masque" && o.has("network")) {
                o.remove("network")
                changed = true
            }
        }
    }
    return changed
}
private fun describeConfig(cfg: org.json.JSONObject): String {'''),
]

# Stringlar (h3 yozuvlari chalkashtirmasligi uchun)
for loc in ["values/", "values-uz/", "values-ru/", "values-en/"]:
    p = RES + loc + "strings.xml"
    RULES += [
        L(f"{loc}: MASQUE h3(4pda) -> h2", "h2", p, "MASQUE h2 (4pda)",
          "MASQUE h3 (4pda)", "MASQUE h2 (4pda)", count=0, optional=True),
        L(f"{loc}: MASQUE h3(Alt) -> h2", "h2", p, "MASQUE h2 (Alt)",
          "MASQUE h3 (Alt)", "MASQUE h2 (Alt)", count=0, optional=True),
        L(f"{loc}: WARP h3 -> h2", "h2", p, "Cloudflare WARP h2 ·",
          "Cloudflare WARP h3 ·", "Cloudflare WARP h2 ·", count=0, optional=True),
        L(f"{loc}: network hint", "h2", p, "Network: h2",
          "Network (h3 | h2)", "Network: h2", count=0, optional=True),
    ]

# ══════════════════════════════════════════════════════════════════════════
#  2) IPv4 / IPv6 / DNS leak
# ══════════════════════════════════════════════════════════════════════════
RULES += [
    F("Service: IPv6-only + effectiveIpv6Blocked helper", "net", SVC,
      "fun effectiveIpv6Blocked",
      "private fun pickPhysicalNetwork(): android.net.Network? {",
      '''/** ★ PATCH-NET6: mobil (NAT64/DNS64) IPv6-only tarmoqni aniqlash. */
private fun isIpv6OnlyNetwork(): Boolean {
    return try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val net = underlyingNetwork ?: pickPhysicalNetwork() ?: return false
        val lp = cm.getLinkProperties(net) ?: return false
        var v4 = false
        var v6 = false
        for (la in lp.linkAddresses) {
            val a = la.address ?: continue
            if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
            if (a is java.net.Inet4Address) v4 = true
            else if (a is java.net.Inet6Address) {
                val b = a.address
                if (b.isNotEmpty() && (b[0].toInt() and 0xE0) == 0x20) v6 = true
            }
        }
        !v4 && v6
    } catch (t: Throwable) { false }
}

/** ★ PATCH-NET6: IPv6 bloklash — faqat tarmoqda IPv4 ham bo'lsa. */
private fun effectiveIpv6Blocked(): Boolean {
    if (!IPv6Blocker.isBlocked(this)) return false
    val only6 = isIpv6OnlyNetwork()
    if (only6) Log.w(TAG, "IPv6-only tarmoq: IPv6 bloklanmaydi (internet uchun)")
    return !only6
}
private fun pickPhysicalNetwork(): android.net.Network? {'''),

    F("connectLocked: IPv6 siyosati (config)", "net", SVC, "PATCH-NET6-CFG",
      "val ipv6Blocked = IPv6Blocker.isBlocked(this)",
      "val ipv6Blocked = effectiveIpv6Blocked()   // ★ PATCH-NET6-CFG"),

    F("openTun: IPv6 siyosati (TUN)", "net", SVC, "PATCH-NET6-TUN",
      "val ipv6Blocked = IPv6Blocker.isBlocked(appCtx)",
      "val ipv6Blocked = effectiveIpv6Blocked()   // ★ PATCH-NET6-TUN"),

    F("openTun: DNS faqat haqiqiy IPv4", "net", SVC, "val dnsV4 = dnsPref.trim()",
      '''if (dnsPref.isNotEmpty()) {
runCatching { builder.addDnsServer(dnsPref) }
.onFailure { Log.w(TAG, "addDnsServer v4 xato: ${it.message}") }
}''',
      '''// ★ PATCH-DNS: hostname/bo'sh qiymat sing-box'ni yiqitadi va leak beradi
val dnsV4 = dnsPref.trim()
if (SingBoxConfig.isIpAddress(dnsV4) && !dnsV4.contains(":")) {
    runCatching { builder.addDnsServer(dnsV4) }
        .onFailure { Log.w(TAG, "addDnsServer v4 xato: ${it.message}") }
} else {
    runCatching { builder.addDnsServer("1.1.1.1") }
}'''),

    F("openTun: IPv6 sink manzil (leak yo'q)", "net", SVC, "PATCH-V6: sink ULA",
      '''runCatching { builder.addRoute("::", 0) }
.onFailure { Log.w(TAG, "addRoute v6 xato: ${it.message}") }
}
dnsLeakEnabled -> {''',
      '''// ★ PATCH-V6: sink ULA manzil + ::/0 → IPv6 TUN ichida yo'qoladi.
//   Manzil bo'lmasa Android IPv6 ni ccmni/rmnet orqali LEAK qiladi.
runCatching { builder.addAddress("fdfe:dcba:9876::1", 126) }
runCatching { builder.addRoute("::", 0) }
    .onFailure { Log.w(TAG, "addRoute v6 xato: ${it.message}") }
<}
<dnsLeakEnabled -> {'''),

    R("LeakTester: rmnet = mobil interfeys (VPN emas)", "net", LEAK,
      "PATCH-LEAK: rmnet",
      r'\s*\|\|\s*n\.contains\("rmnet"\)',
      '  // ★ PATCH-LEAK: rmnet — mobil interfeys, VPN emas'),

    F("LeakTester: haqiqiy DNS tekshiruvi", "net", LEAK, "PATCH-DNS: haqiqiy",
      '''r.dns = try {
InetAddress.getByName("1.1.1.1").hostAddress ?: "—"
} catch (e: Exception) { "—" }''',
      '''// ★ PATCH-DNS: IP literal hech narsani tekshirmaydi — nom resolve qilamiz
r.dns = try {
    InetAddress.getAllByName("cloudflare.com")
        .firstOrNull()?.hostAddress ?: "—"
} catch (e: Exception) { "—" }'''),
]

# ══════════════════════════════════════════════════════════════════════════
#  3) Xotira leak
# ══════════════════════════════════════════════════════════════════════════
RULES += [
    F("Home: View keshlari tozalanadi", "leak", HOME, "PATCH-LEAK: View keshlari",
      "homeRecycler = null; homeAdapter = null; homeHeaderView = null; footerView = null",
      '''homeRecycler = null; homeAdapter = null; homeHeaderView = null; footerView = null
// ★ PATCH-LEAK: View keshlari Context/Activity'ni ushlab turardi
pingViews.clear()
rowCache.clear()
homeSelectionBar = null; homeSelCountText = null
awgArrow = null; awgCount = null
serverProto = null
refreshScheduled = false
pingUpdateScheduled = false'''),

    F("PingTester: activeBatcher", "leak", PING, "private var activeBatcher",
      "private val runId = AtomicInteger(0)",
      '''private val runId = AtomicInteger(0)
/** ★ PATCH-LEAK: joriy batcher (cancel'da to'xtatiladi). */
@Volatile private var activeBatcher: Batcher? = null'''),

    F("PingTester: Batcher(myRun)", "leak", PING, "private val myRun: Int",
      '''private class Batcher(
private val listener: Listener,
private val total: Int
) {''',
      '''private class Batcher(
    private val listener: Listener,
    private val total: Int,
    private val myRun: Int
) {'''),

    F("PingTester: flushTask runId guard + stop()", "leak", PING, "fun stop()",
      '''private val flushTask = object : Runnable {
override fun run() {
flush()
// Navbatda hali ish bo'lsa — yana rejalashtiramiz
if (!flushed.get()) main.postDelayed(this, BATCH_INTERVAL_MS)
}
}''',
      '''private val flushTask = object : Runnable {
    override fun run() {
        // ★ PATCH-LEAK: bekor qilingan yurish abadiy qayta ishlanmaydi
        if (runId.get() != myRun) { stop(); return }
        flush()
        // Navbatda hali ish bo'lsa — yana rejalashtiramiz
        if (!flushed.get()) main.postDelayed(this, BATCH_INTERVAL_MS)
    }
}
/** ★ PATCH-LEAK: handler navbatini butunlay to'xtatish. */
fun stop() {
    flushed.set(true)
    main.removeCallbacks(flushTask)
    pending.clear()
}'''),

    F("PingTester: batcher myRun + eskisini to'xtatish", "leak", PING,
      "Batcher(listener, total, myRun)",
      "val batcher = Batcher(listener, total)",
      '''activeBatcher?.stop()
val batcher = Batcher(listener, total, myRun)
activeBatcher = batcher'''),

    F("PingTester: cancel() batcher'ni to'xtatadi", "leak", PING, "activeBatcher?.stop()\n",
      '''fun cancel() {
runId.incrementAndGet()
Log.i(TAG, "cancel: joriy ping bekor qilindi")
}''',
      '''fun cancel() {
    runId.incrementAndGet()
    activeBatcher?.stop()
    activeBatcher = null
    Log.i(TAG, "cancel: joriy ping bekor qilindi")
}'''),

    F("AppListLoader: umumiy executor", "leak", APPL, "private val exec",
      "object AppListLoader {",
      '''object AppListLoader {
    // ★ PATCH-LEAK: har chaqiruvda yangi thread pool yaratilmaydi
    private val exec = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 20L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>())'''),

    L("AppListLoader: exec ishlatiladi", "leak", APPL, "exec.execute {",
      "Executors.newSingleThreadExecutor().execute {", "exec.execute {"),

    F("SplitApps: applicationContext + lifecycle", "leak", SPLIT, "PATCH-LEAK: applicationContext",
      '''AppListLoader.loadAsync(this) { list ->
all = list ?: ArrayList()''',
      '''// ★ PATCH-LEAK: Activity fon oqimida ushlanmaydi
AppListLoader.loadAsync(applicationContext) { list ->
    if (isFinishing || isDestroyed) return@loadAsync
    all = list ?: ArrayList()'''),

    F("MainActivity: handler/fragment keshi tozalanadi", "leak", MAIN,
      "PATCH-LEAK: handler",
      "stateReceiver = null",
      '''stateReceiver = null
// ★ PATCH-LEAK: handler navbatlari va fragment keshi tozalanadi
restartHandler.removeCallbacksAndMessages(null)
refreshHandler.removeCallbacksAndMessages(null)
mainHandler.removeCallbacksAndMessages(null)
scrollMemory.clear()
homeFragment = null; serversFragment = null; settingsFragment = null'''),
]

# ══════════════════════════════════════════════════════════════════════════
#  4) Batareya / CPU optimizatsiya
# ══════════════════════════════════════════════════════════════════════════
RULES += [
    F("SpeedWave: frameDiv", "batt", WAVE, "private var frameDiv",
      "private var frameCount = 0L",
      '''private var frameCount = 0L
// ★ PATCH-BATT: kadr bo'luvchisi — 60 fps → 20 fps
private var frameDiv = 0'''),

    F("SpeedWave: 20 fps (trafik yo'q / VPN off)", "batt", WAVE, "PATCH-BATT: trafik yo'q",
      '''override fun doFrame(frameTimeNanos: Long) {
if (!running || !animationEnabled) return
val dt = if (lastFrameNanos == 0L) 1f / 60f''',
      '''override fun doFrame(frameTimeNanos: Long) {
    if (!running || !animationEnabled) return
    // ★ PATCH-BATT: trafik yo'q / VPN off → 20 fps (3x kam CPU/GPU)
    val div = if (connected && currentSpeed > SPEED_THRESHOLD) 1 else 3
    if (++frameDiv < div) {
        if (running && animationEnabled && isAttachedToWindow && isShown && windowVisible) {
            choreographer.postFrameCallback(this)
        }
        return
    }
    frameDiv = 0
    val dt = if (lastFrameNanos == 0L) 1f / 60f'''),

    R("SpeedWave: auto-disconnect 240 kadr", "batt", WAVE,
      "AUTO_DISCONNECT_FRAMES = 240",
      r"AUTO_DISCONNECT_FRAMES\s*=\s*600[^\n]*",
      "AUTO_DISCONNECT_FRAMES = 240  // ★ PATCH-BATT: ~12s @ 20fps"),

    F("Home: ticker faqat VPN yoniq", "batt", HOME, "PATCH-BATT: ticker",
      '''} else {
timerText?.text = "00:00:00"
resetTrafficCounter()
}
ui.postDelayed(this, 1000)''',
      '''} else {
    timerText?.text = "00:00:00"
    resetTrafficCounter()
}
// ★ PATCH-BATT: ticker faqat VPN yoniq va tab ko'rinib turganda
if (a.isRunning && !isHidden) ui.postDelayed(this, 1000)
else tickerRunning = false'''),

    F("Home: refresh ticker'ni qayta ishga tushiradi", "batt", HOME,
      "PATCH-BATT: ticker faqat ulanishda",
      '''if (a.connectStart == 0L) a.connectStart = System.currentTimeMillis()
startPulse()''',
      '''if (a.connectStart == 0L) a.connectStart = System.currentTimeMillis()
// ★ PATCH-BATT: ticker faqat ulanishda qayta ishga tushadi
if (!tickerRunning && !isHidden) { tickerRunning = true; ui.post(ticker) }
startPulse()'''),

    F("Home: yashirin tabda animatsiya to'xtaydi", "batt", HOME, "PATCH-BATT: yashirin tab",
      '''if (hidden) {
android.util.Log.i("NurVPN-DBG", "HomeFragment: yashirildi — scroll saqlanadi")
persistScrollPosition()
} else {
android.util.Log.i("NurVPN-DBG", "HomeFragment: ko'rsatildi — scroll tiklanadi")
restoreScrollPosition()
}''',
      '''if (hidden) {
    android.util.Log.i("NurVPN-DBG", "HomeFragment: yashirildi — scroll saqlanadi")
    persistScrollPosition()
    // ★ PATCH-BATT: yashirin tabda ticker/pulse/animatsiya ishlamaydi
    tickerRunning = false
    ui.removeCallbacks(ticker)
    stopPulse()
    speedWave?.setAnimationEnabled(false)
} else {
    android.util.Log.i("NurVPN-DBG", "HomeFragment: ko'rsatildi — scroll tiklanadi")
    restoreScrollPosition()
    speedWave?.setAnimationEnabled(true)
    refresh()
}'''),

    F("PingTester: bo'sh thread'lar 30s'da o'ladi", "batt", PING,
      "allowCoreThreadTimeOut(true)",
      "private val pool: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT)",
      '''// ★ PATCH-BATT/LEAK: bo'sh thread'lar 30s dan keyin tugatiladi
private val pool: ExecutorService = java.util.concurrent.ThreadPoolExecutor(
    MAX_CONCURRENT, MAX_CONCURRENT, 30L, TimeUnit.SECONDS,
    java.util.concurrent.LinkedBlockingQueue<Runnable>()
).apply { allowCoreThreadTimeOut(true) }'''),

    R("PingTester: batch 150ms -> 250ms", "batt", PING, "BATCH_INTERVAL_MS = 250L",
      r"BATCH_INTERVAL_MS\s*=\s*150L", "BATCH_INTERVAL_MS = 250L"),

    F("AI: pendingMetrics + bitta Handler", "batt", AI, "val pendingMetrics",
      '''val results = java.util.Collections.synchronizedList(
ArrayList<Pair<ServerItem, Double>>(candidates.size))''',
      '''val results = java.util.Collections.synchronizedList(
    ArrayList<Pair<ServerItem, Double>>(candidates.size))
// ★ PATCH-BATT: metrics diskka har ping'da emas — oxirida bir marta
val pendingMetrics = java.util.Collections.synchronizedList(
    ArrayList<com.nurvpn.app.core.ServerMetrics>())
val main = Handler(Looper.getMainLooper())'''),

    F("AI: metrics navbatga", "batt", AI, "pendingMetrics.add(m)",
      '''if (p > 0) m.recordPing(p, true, now)
else m.recordPing(0, false, now)
MetricsStore.save(ctx, m)''',
      '''if (p > 0) m.recordPing(p, true, now)
else m.recordPing(0, false, now)
pendingMetrics.add(m)   // ★ PATCH-BATT: diskka keyinroq, bir marta'''),

    F("AI: UI post har 5-natijada", "batt", AI, "PATCH-BATT: UI'ga har 5",
      '''val done = completed.incrementAndGet()
Handler(Looper.getMainLooper()).post {
listener.onServerScored(si, score)
if (done % 5 == 0 || done == total) {
Log.i("NurVPN-AI", "AI progress: $done/$total")
}
}''',
      '''val done = completed.incrementAndGet()
// ★ PATCH-BATT: UI'ga har 5-natijada (200 post emas)
if (done % 5 == 0 || done == total) {
    main.post {
        listener.onServerScored(si, score)
        Log.i("NurVPN-AI", "AI progress: $done/$total")
    }
}'''),

    F("AI: pool shutdownNow + metrics bir marta", "batt", AI, "PATCH-BATT: metrics'ni",
      '''pool.shutdown()
try { pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
catch (_: Throwable) {}''',
      '''pool.shutdown()
try {
    if (!pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) pool.shutdownNow()
} catch (_: Throwable) {}
// ★ PATCH-BATT: metrics'ni BIR MARTA yozamiz (200 apply() emas)
for (m in pendingMetrics) MetricsStore.save(ctx, m)'''),

    F("Worker: quvvat kam bo'lsa keyinga surish", "batt", WORKER, "setRequiresBatteryNotLow",
      '''val constraints = Constraints.Builder()
.setRequiredNetworkType(NetworkType.CONNECTED)
.build()''',
      '''val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    // ★ PATCH-BATT: quvvat kam bo'lsa yangilash keyinga suriladi
    .setRequiresBatteryNotLow(true)
    .build()'''),
]

# ══════════════════════════════════════════════════════════════════════════
#  MOTOR
# ══════════════════════════════════════════════════════════════════════════

def apply_rule(root: str, r: Rule, dry: bool, backup: bool) -> str:
    path = os.path.join(root, r.path)
    if not os.path.isfile(path):
        return "MISSING" if not r.optional else "SKIP"
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()

    if r.marker and r.marker in text:
        return "SKIP"

    if r.kind == "func":
        new_text, n = r.fn(text)
    elif r.kind == "raw":
        new_text, n = re.subn(r.pattern, r.repl, text, count=r.count, flags=r.flags)
    else:
        pat = re.compile(r"^(?P<ind>[ \t]*)" + flex(r.old), re.M)
        new_text, n = pat.subn(lambda m: render(m, r.new), text, count=1)

    if n == 0 or new_text == text:
        return "SKIP" if r.optional else "FAIL"

    if not dry:
        if backup:
            b = os.path.join(root, BACKUP_DIR, r.path)
            os.makedirs(os.path.dirname(b), exist_ok=True)
            if not os.path.exists(b):
                shutil.copy2(path, b)
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(new_text)
    return "OK"


def revert(root: str):
    broot = os.path.join(root, BACKUP_DIR)
    if not os.path.isdir(broot):
        print("Backup topilmadi:", broot)
        return 1
    n = 0
    for dp, _, fns in os.walk(broot):
        for fn in fns:
            src = os.path.join(dp, fn)
            rel = os.path.relpath(src, broot)
            dst = os.path.join(root, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
            print("  ↩", rel)
            n += 1
    print(f"Qaytarildi: {n} fayl")
    return 0


VERIFY = [
    (BUILTIN,  r"Network\s*=\s*h3",              False, "MASQUE h3 qolmagan"),
    (CFG,      r'o\.put\("network", "h2"\)',     True,  "parseMasque network=h2"),
    (CFG,      r'"shadowtls", "masque"\)',       True,  "MASQUE DNS TCP"),
    (AWGSTORE, r"normalizeMasqueH2",             True,  "h2 migratsiya"),
    (SVC,      r"effectiveIpv6Blocked",          True,  "IPv6-only guard"),
    (SVC,      r'addAddress\("fdfe:dcba:9876::1"', True, "IPv6 sink (leak yo'q)"),
    (SVC,      r"stripMasqueNetwork",            True,  "checkConfig fallback"),
    (LEAK,     r'n\.contains\("rmnet"\)',        False, "rmnet VPN emas"),
    (HOME,     r"pingViews\.clear\(\)",          True,  "View kesh tozalash"),
    (PING,     r"activeBatcher",                 True,  "batcher cancel"),
    (AI,       r"pendingMetrics",                True,  "metrics batch"),
    (WAVE,     r"frameDiv",                      True,  "20 fps"),
]


def verify(root: str):
    print("\n── TEKSHIRUV ──")
    bad = 0
    for rel, pat, want, label in VERIFY:
        p = os.path.join(root, rel)
        if not os.path.isfile(p):
            print(f"  ?  {label}: fayl yo'q")
            continue
        found = re.search(pat, open(p, encoding="utf-8").read()) is not None
        ok = (found == want)
        print(f"  {'✔' if ok else '✘'}  {label}")
        bad += 0 if ok else 1
    return bad


def main():
    ap = argparse.ArgumentParser(description="NurVPN h2 + leak + batareya patch")
    ap.add_argument("--root", default=".", help="loyiha ildizi (settings.gradle joyi)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--revert", action="store_true")
    ap.add_argument("--no-backup", action="store_true")
    ap.add_argument("--only", default="", help="h2,net,leak,batt")
    a = ap.parse_args()
    root = os.path.abspath(a.root)

    if a.revert:
        return revert(root)

    groups = {g.strip() for g in a.only.split(",") if g.strip()}
    rules = [r for r in RULES if not groups or r.group in groups]

    print(f"NurVPN patch → {root}")
    print(f"Rejim: {'DRY-RUN' if a.dry_run else 'YOZISH'} | guruhlar: "
          f"{','.join(sorted(groups)) or 'hammasi'} | {len(rules)} qoida\n")

    stat = {"OK": 0, "SKIP": 0, "FAIL": 0, "MISSING": 0}
    icon = {"OK": "✔", "SKIP": "•", "FAIL": "✘", "MISSING": "?"}
    for r in rules:
        s = apply_rule(root, r, a.dry_run, not a.no_backup)
        stat[s] += 1
        print(f" {icon[s]} [{r.group:4}] {r.name}" + ("" if s == "OK" else f"  ({s})"))

    print(f"\nOK={stat['OK']}  SKIP={stat['SKIP']}  "
          f"FAIL={stat['FAIL']}  MISSING={stat['MISSING']}")

    bad = 0 if a.dry_run else verify(root)
    if stat["FAIL"] or stat["MISSING"] or bad:
        print("\n⚠️  Ba'zi qoidalar mos kelmadi — fayl versiyasi boshqa bo'lishi mumkin.")
        print("    Backup: " + os.path.join(root, BACKUP_DIR))
        return 1

    print("""
✅ TAYYOR. Keyingi qadamlar:
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:assembleRelease

Qurilmada tekshirish:
   1) MASQUE 4/6/7 (avval h3) → WiFi va mobil internetda ulanadi
   2) Sozlamalar → IPv6 bloklash ON → Leak test: IPv6 "bloklangan"
   3) Mobil IPv6-only APN → internet ishlaydi (log: "IPv6-only tarmoq")
   4) Home tab'ni yashirib, logcat: NurVPN-SCROLL / ticker jim
   5) Ping → Cancel → 150ms'lik handler navbati to'xtaydi
""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
