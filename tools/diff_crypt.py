from pathlib import Path
import subprocess

OLD_DIR = Path("/media/pc/8GB10/NurVPN")
NEW_DIR = Path("/home/pc/NurVPN")

print("═══════════════════════════════════════════════════════════")
print("  ESKI VA YANGI LOYIHA TAQQOSLAMASI (crypt1)")
print("═══════════════════════════════════════════════════════════")

# 1. Crypt1Decoder faylini topish
old_decoder = list(OLD_DIR.glob("**/Crypt1Decoder.kt"))
new_decoder = list(NEW_DIR.glob("**/Crypt1Decoder.kt"))

if old_decoder and new_decoder:
    print(f"\n📄 Crypt1Decoder.kt topildi.")
    print(f"   Eski: {old_decoder[0].relative_to(OLD_DIR)}")
    print(f"   Yangi: {new_decoder[0].relative_to(NEW_DIR)}")
    
    # Fayllarni solishtirish
    res = subprocess.run(["diff", "-u", str(old_decoder[0]), str(new_decoder[0])], capture_output=True, text=True)
    if res.stdout:
        print("\n⚠️ FARQLAR (Eski -> Yangi):")
        # Faqat birinchi 80 qatorni ko'rsatamiz
        for line in res.stdout.splitlines()[:80]:
            if line.startswith("+") and not line.startswith("+++"):
                print(f"  \033[92m{line}\033[0m") # Yashil (qo'shilgan)
            elif line.startswith("-") and not line.startswith("---"):
                print(f"  \033[91m{line}\033[0m") # Qizil (o'chirilgan)
            elif line.startswith("@@"):
                print(f"  \033[96m{line}\033[0m") # Moviy (kontekst)
    else:
        print("✅ Crypt1Decoder.kt fayllari bir xil.")
else:
    print("⚠️ Crypt1Decoder.kt topilmadi.")

# 2. DeepLink handling (handleDeepLink) taqqoslash
print("\n═══════════════════════════════════════════════════════════")
print("  MainActivity: handleDeepLink (crypt qismi)")
print("═══════════════════════════════════════════════════════════")

def get_crypt_lines(file_path):
    if not file_path.exists(): return []
    lines = file_path.read_text(encoding="utf-8").splitlines()
    result = []
    capture = False
    for i, line in enumerate(lines):
        if "isCryptLink" in line or "Crypt1Decoder" in line or "crypt1://" in line:
            capture = True
        if capture:
            result.append(f"{i+1}: {line.strip()}")
            if "}" in line and "if" not in line: # blok tugashi
                capture = False
    return result

old_main = list(OLD_DIR.glob("**/MainActivity.kt"))
new_main = list(NEW_DIR.glob("**/MainActivity.kt"))

if old_main and new_main:
    print("\nESKI KOD (crypt1 bilan ishlash):")
    old_crypt = get_crypt_lines(old_main[0])
    for l in old_crypt[:20]: print(f"  {l}")
    
    print("\nYANGI KOD (crypt1 bilan ishlash):")
    new_crypt = get_crypt_lines(new_main[0])
    for l in new_crypt[:20]: print(f"  {l}")
