from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/java/com/nurvpn/app/util/WarpMasque.kt')
if not p.exists():
    print("❌ Fayl topilmadi")
    exit()

bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
shutil.copy(p, bak_dir / f"WarpMasque.kt.{int(datetime.now().timestamp())}.bak")

t = p.read_text(encoding='utf-8')

# 1. Yangi readTlsRecord va readFully funksiyalarini qo'shish
old_serve = "        private fun serve() {"
new_funcs = """        private fun readTlsRecord(inp: java.io.InputStream): ByteArray {
            val head = readFully(inp, 5)
            val len = ((head[3].toInt() and 0xFF) shl 8) or (head[4].toInt() and 0xFF)
            val rest = readFully(inp, len)
            return head + rest
        }

        private fun readFully(inp: java.io.InputStream, n: Int): ByteArray {
            val out = ByteArray(n); var off = 0
            while (off < n) {
                val k = inp.read(out, off, n - off)
                if (k < 0) throw java.io.IOException("oqim tugadi")
                off += k
            }
            return out
        }

        private fun serve() {"""

if 'private fun readTlsRecord' not in t:
    t = t.replace(old_serve, new_funcs, 1)

# 2. Eski xato read() blokini to'g'rilash
old_read = """                val buf = ByteArray(16384)
                val n = inp.read(buf)
                if (n > 0) {
                    var i = 0
                    while (i < n) {
                        val len = minOf(Random.nextInt(8, 25), n - i)
                        out.write(buf, i, len); out.flush()
                        i += len
                        if (i < n) Thread.sleep(Random.nextLong(8, 21))
                    }
                    pipe(inp, out)
                }"""

new_read = """                try {
                    val clientHello = readTlsRecord(inp)
                    var i = 0
                    while (i < clientHello.size) {
                        val len = minOf(Random.nextInt(8, 25), clientHello.size - i)
                        out.write(clientHello, i, len)
                        out.flush()
                        i += len
                        if (i < clientHello.size) Thread.sleep(Random.nextLong(8, 21))
                    }
                    pipe(inp, out)
                } catch (ignored: Throwable) {}"""

if old_read in t:
    t = t.replace(old_read, new_read, 1)
    print("✅ FragmentProxy dagi read() xatosi tuzatildi (readTlsRecord qo'shildi).")
else:
    print("⚠ Eski read() bloki topilmadi. Kod allaqachon o'zgargan bo'lishi mumkin.")

p.write_text(t, encoding='utf-8')
