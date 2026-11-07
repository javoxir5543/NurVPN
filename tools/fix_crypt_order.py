from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

# Eski blok (xato tartib)
old_block = '''        // ═══ YANGI: to'g'ridan-to'g'ri obuna manzili (http/https) ═══
        if (pending.startsWith("http://") || pending.startsWith("https://")) {
            loadSubDialog(pending, wasEncrypted = false)
            return
        }

        // ★ TUZATISH #5: ODDIY proxy linki (vless://, vmess://, trojan:// ...)
        // Avval bu yerda to'g'ridan-to'g'ri Crypt1Decoder.decode() chaqirilardi
        // va oddiy (shifrlanmagan) link uchun "decode_failed" xatosi chiqardi.
        // Endi avval oddiy link ekanini tekshiramiz.
        val plainLinks = com.nurvpn.app.parser.SubscriptionLinkExtractor
            .extract(pending)
        if (plainLinks.isNotEmpty()) {
            android.util.Log.i("NurVPN-DEEPLINK",
                "Oddiy link → ${plainLinks.size} ta, to'g'ridan-to'g'ri qo'shiladi")
            val a = activity as? MainActivity ?: return
            var added = 0
            for (l in plainLinks) {
                val si = com.nurvpn.app.parser.ServerLinkParser.parse(l)
                if (si != null) { a.servers.add(si); added++ }
            }
            if (added > 0) {
                com.nurvpn.app.storage.ServerStore.save(a, a.servers)
                rebuildServerCards(force = true)
                android.widget.Toast.makeText(ctx,
                    ctx.getString(R.string.toast_added_count, added),
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(ctx,
                    ctx.getString(R.string.toast_invalid_link),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        val decoded = Crypt1Decoder.decode(ctx, pending)
        if (decoded == null) {
            android.widget.Toast.makeText(ctx,
                ctx.getString(R.string.encrypted_decode_failed),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }

        android.util.Log.i("NurVPN-DEEPLINK",
            "Decoded → ${decoded.first.take(60)} (name=${decoded.second})")

        loadSubDialog(decoded.first, wasEncrypted = true)
    }'''

# Yangi blok (to'g'ri tartib: crypt → http → plain)
new_block = '''        // ═══════════════════════════════════════════════════════════════
        //  ★ FIX: ANIQLIK TARTIBI (specificity order)
        //
        //  1) crypt1:// / nurvpn:// / incy://crypt1/ — eng aniq prefiks
        //  2) http(s):// — obuna manzili
        //  3) vless://, vmess://, ... — oddiy proxy linklar
        //
        //  Avval "http → extract → decode" tartibida edi. extract()
        //  crypt1:// ni ham "oddiy proxy" deb qabul qilardi va decode()
        //  ga yetib bormasdi → shifrlangan obuna buzilardi.
        // ═══════════════════════════════════════════════════════════════

        // ─── 1) SHIFRLANGAN (crypt1:// va h.k.) ───
        if (Crypt1Decoder.isCryptLink(pending)) {
            android.util.Log.i("NurVPN-DEEPLINK", "crypt1 link aniqlandi")
            val decoded = Crypt1Decoder.decode(ctx, pending)
            if (decoded == null) {
                android.widget.Toast.makeText(ctx,
                    ctx.getString(R.string.encrypted_decode_failed),
                    android.widget.Toast.LENGTH_LONG).show()
                return
            }
            android.util.Log.i("NurVPN-DEEPLINK",
                "Decoded → ${decoded.first.take(60)} (name=${decoded.second})")
            loadSubDialog(decoded.first, wasEncrypted = true)
            return
        }

        // ─── 2) HTTP/HTTPS OBUNA ───
        if (pending.startsWith("http://") || pending.startsWith("https://")) {
            loadSubDialog(pending, wasEncrypted = false)
            return
        }

        // ─── 3) ODDIY PROXY LINKLAR (vless://, vmess://, trojan:// ...) ───
        val plainLinks = com.nurvpn.app.parser.SubscriptionLinkExtractor
            .extract(pending)
        if (plainLinks.isNotEmpty()) {
            android.util.Log.i("NurVPN-DEEPLINK",
                "Oddiy link → ${plainLinks.size} ta, to'g'ridan-to'g'ri qo'shiladi")
            val a = activity as? MainActivity ?: return
            var added = 0
            for (l in plainLinks) {
                val si = com.nurvpn.app.parser.ServerLinkParser.parse(l)
                if (si != null) { a.servers.add(si); added++ }
            }
            if (added > 0) {
                com.nurvpn.app.storage.ServerStore.save(a, a.servers)
                rebuildServerCards(force = true)
                android.widget.Toast.makeText(ctx,
                    ctx.getString(R.string.toast_added_count, added),
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(ctx,
                    ctx.getString(R.string.toast_invalid_link),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        // ─── 4) HECH NARSA MOS KELMADI ───
        android.widget.Toast.makeText(ctx,
            ctx.getString(R.string.encrypted_decode_failed),
            android.widget.Toast.LENGTH_LONG).show()
    }'''

if old_block in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    t = t.replace(old_block, new_block, 1)
    p.write_text(t, encoding='utf-8')
    print("✅ checkPendingDeepLink tartibi tuzatildi:")
    print("   Avval: http → extract → crypt (buzilgan)")
    print("   Keyin: crypt → http → extract (to'g'ri)")
elif "ANIQLIK TARTIBI (specificity order)" in t:
    print("✓ allaqachon tuzatilgan")
else:
    print("⚠ Naqsh topilmadi. Avval grep bilan tekshiring:")
    print("   grep -n 'checkPendingDeepLink' app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt")
