from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt')
t = p.read_text(encoding='utf-8')

# ═══ Eski blok (3094-3098 atrofi) ═══
# Diqqat: bu blok 3081-3091 dagi if blokidan keyin keladi
old_block = '''                if (jsonServers.isNotEmpty()) {
                    activity?.runOnUiThread {
                        val a2 = activity as? MainActivity ?: return@runOnUiThread
                        var added = 0
                        for (si in jsonServers) {
                            if (a2.servers.any { it.link == si.link }) continue
                            si.encrypted = wasEncrypted
                            a2.servers.add(si)
                            added++
                        }
                        ServerStore.save(a2, a2.servers)

                        // ★ YANGI: userinfo saqlash (JSON branch)
                        val subUpd = a2.subscriptions.find { it.id == subForJson.id }'''

new_block = '''                if (jsonServers.isNotEmpty()) {
                    activity?.runOnUiThread {
                        val a2 = activity as? MainActivity ?: return@runOnUiThread
                        var added = 0
                        for (si in jsonServers) {
                            if (a2.servers.any { it.link == si.link }) continue
                            si.encrypted = wasEncrypted
                            a2.servers.add(si)
                            added++
                        }
                        ServerStore.save(a2, a2.servers)

                        // ★ YANGI: userinfo saqlash (JSON branch)
                        val subUpd = a2.subscriptions.find { it.id == subForJson.id }'''

if old_block in t:
    # Faqat else branch qo'shamiz (if blokidan keyin)
    # 3081-3091 blokning yopilishi va 3094 orasiga else qo'shamiz
    old_else_anchor = '''                    }
                }

                val jsonServers = parseJsonConfigsWithSub(trimmedBody, subForJson.id)'''
    
    # Aslida 3078-3091 orasida else qo'shish kerak. 
    # Bu murakkab, chunki if blokning ichida nested blok bor.
    # Eng oddiy: 3094 qator atrofidagi kodni o'rab, null check qo'shish.
    pass

# ═══ Boshqa yondashuv: 3094 qatorni null-safe qilish ═══
old_subref = '''                        val subUpd = a2.subscriptions.find { it.id == subForJson.id }'''

# ═══ Aslida eng yaxshi: 3030-3035 ni o'zgartirmaslik, 
# lekin 3081 dan keyin else qo'shish ═══

# Avval 3081-3091 blokni topamiz
old_server_block = '''                if (jsonServers.isNotEmpty()) {
                    activity?.runOnUiThread {'''

# Va 3094 qator atrofidagi kodni topamiz
# Muammo: 3081-3091 blok tugagandan keyin 3094 keladi
# Lekin 3094 subForJson ga murojaat qiladi - bu har doim mavjud

# Eng yaxshi yechim: 3094 qatorni quyidagicha o'zgartirish:
# subForJson.id -> subForJson?.id ?: return

# Lekin biz 3030-3035 ni ham tekshirishimiz kerak
# Aslida muammo: subForJson bo'sh bo'lsa ham yaratiladi

# Yechim: 3081 da else qo'shish. Lekin bu murakkab.
# Osonroq: 3094 ni null-safe qilish va 3030-3035 ni tuzatish.

# Avval 3030-3035 ni topamiz
old_sub_create = '''                // subId yaratamiz yoki mavjudni olamiz
                val subIdForJson = "sub_" + System.currentTimeMillis().toString(36)
                var subForJson = a.subscriptions.find { it.url == url }
                if (subForJson == null) {
                    subForJson = Subscription(subIdForJson, url, headerTitle ?: subName ?: extractSubTitle(url))
                    a.subscriptions.add(subForJson)
                }'''

new_sub_create = '''                // subId yaratamiz yoki mavjudni olamiz
                val subIdForJson = "sub_" + System.currentTimeMillis().toString(36)
                var subForJson = a.subscriptions.find { it.url == url }
                if (subForJson == null) {
                    subForJson = Subscription(subIdForJson, url, headerTitle ?: subName ?: extractSubTitle(url))
                    a.subscriptions.add(subForJson)
                }
                // Agar bu JSON faqat AWG bo'lsa - obuna yaratilmaydi (keyinroq tekshiriladi)'''

if old_sub_create in t:
    bak = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")
    
    t = t.replace(old_sub_create, new_sub_create, 1)
    print("✅ 3030-3035: izoh qo'shildi")
    
    # Endi 3094 atrofidagi kodni null-safe qilamiz
    # subForJson.id -> subForJson?.id ?: return@runOnUiThread
    # Lekin bu ham murakkab.
    # Eng yaxshi: 3094 qatorni va keyingi kodni if ichiga olish.
    
    # 3081 da else qo'shamiz (eng oddiy yechim)
    old_if_start = '''                if (jsonServers.isNotEmpty()) {
                    activity?.runOnUiThread {
                        val a2 = activity as? MainActivity ?: return@runOnUiThread
                        var added = 0
                        for (si in jsonServers) {
                            if (a2.servers.any { it.link == si.link }) continue
                            si.encrypted = wasEncrypted
                            a2.servers.add(si)
                            added++
                        }
                        ServerStore.save(a2, a2.servers)

                        // ★ YANGI: userinfo saqlash (JSON branch)
                        val subUpd = a2.subscriptions.find { it.id == subForJson.id }
                        if (subUpd != null) {
                            subUpd.lastUpdated = System.currentTimeMillis()
                            subUpd.serverCount = jsonServers.size
                            localUserInfo?.let { info ->
                                subUpd.userInfo = info
                            }
                        }
                    }
                }'''
    
    new_if_start = '''                if (jsonServers.isNotEmpty()) {
                    activity?.runOnUiThread {
                        val a2 = activity as? MainActivity ?: return@runOnUiThread
                        var added = 0
                        for (si in jsonServers) {
                            if (a2.servers.any { it.link == si.link }) continue
                            si.encrypted = wasEncrypted
                            a2.servers.add(si)
                            added++
                        }
                        ServerStore.save(a2, a2.servers)

                        // ★ YANGI: userinfo saqlash (JSON branch)
                        val subUpd = a2.subscriptions.find { it.id == subForJson.id }
                        if (subUpd != null) {
                            subUpd.lastUpdated = System.currentTimeMillis()
                            subUpd.serverCount = jsonServers.size
                            localUserInfo?.let { info ->
                                subUpd.userInfo = info
                            }
                        }
                    }
                } else {
                    // ★ FIX: Agar serverlar bo'sh bo'lsa (masalan, faqat AWG JSON), 
                    // bo'sh obunani o'chiramiz
                    if (wgAdded > 0) {
                        a.subscriptions.remove(subForJson)
                        SubscriptionStore.save(a, a.subscriptions)
                        android.util.Log.i("NurVPN-DBG",
                            "Faqat AWG JSON: bo'sh obuna o'chirildi (${subForJson.name})")
                    }
                    activity?.runOnUiThread {
                        Toast.makeText(c,
                            getString(R.string.toast_added_count, wgAdded) + " (AWG)",
                            Toast.LENGTH_SHORT).show()
                    }
                }'''
    
    if old_if_start in t:
        t = t.replace(old_if_start, new_if_start, 1)
        print("✅ 3081-3091: else branch qo'shildi (bo'sh obuna o'chiriladi)")
    else:
        print("⚠ 3081-3091 naqshi topilmadi")
    
    p.write_text(t, encoding='utf-8')
    print("✅ Fayl saqlandi")
else:
    print("⚠ 3030-3035 naqshi topilmadi")
    print("Kodni qo'lda tekshiring:")
    for i, line in enumerate(t.split('\n'), 1):
        if 'subIdForJson' in line:
            print(f"  {i}: {line}")
