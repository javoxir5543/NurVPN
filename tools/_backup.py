#!/usr/bin/env python3
"""
Umumiy yordamchi: xavfsiz zaxira nusxa.

⚠️ MUHIM SABOQ: zaxira faylini manba daraxti ICHIGA yozish MUMKIN EMAS.
   `app/src/main/res/values/strings.xml.fix6.bak` yozilganda Gradle
   resurs merge qiluvchisi yiqildi:
       "The file name must end with .xml"
   AndroidManifest.xml.fix5.bak ham xavfli.

   Shuning uchun barcha zaxiralar loyiha ildizidagi `.fix-backups/`
   papkasiga (git-ignored) yoziladi.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKUP_DIR = ROOT / ".fix-backups"


def safe_backup(path: Path, tag: str) -> Path:
    """`path` ni `.fix-backups/` ga zaxiralaydi va zaxira yo'lini qaytaradi."""
    BACKUP_DIR.mkdir(exist_ok=True)
    try:
        rel = path.relative_to(ROOT)
    except ValueError:
        rel = Path(path.name)

    # app/src/main/res/values/strings.xml -> main__res__values__strings.xml
    name = str(rel).replace("app/src/", "", 1).replace("/", "__")
    dest = BACKUP_DIR / f"{name}.{tag}.bak"

    if not dest.exists():
        dest.write_text(path.read_text(encoding="utf-8"), encoding="utf-8")
        print(f"   zaxira: .fix-backups/{dest.name}")
    return dest
