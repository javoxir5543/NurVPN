from pathlib import Path
import shutil
from datetime import datetime

root = Path('/home/pc/NurVPN')
xml_path = root / 'app/src/main/res/layout/item_home_card_header.xml'
kt_path = root / 'app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt'
bak_dir = root / '.fix-backups'
bak_dir.mkdir(exist_ok=True)
timestamp = int(datetime.now().timestamp())

print("=== FIX: To'g'ri dizaynni qo'llash ===")

# 1. XML faylini siz yuborgan kod bilan almashtirish
if xml_path.exists():
    shutil.copy(xml_path, bak_dir / f"item_home_card_header.xml.{timestamp}.bak")
    print("[XML] Backup yaratildi.")

    new_xml = '''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="10dp"
    android:background="@drawable/bg_card"
    android:clickable="true"
    android:focusable="true"
    android:padding="16dp"
    android:orientation="vertical">

    <!-- 1. Sarlavha -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <FrameLayout
            android:layout_width="44dp"
            android:layout_height="44dp"
            android:background="@drawable/bg_icon_box">
            <TextView
                android:id="@+id/card_icon"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="center"
                android:textSize="20sp"/>
        </FrameLayout>

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="12dp"
            android:orientation="vertical">
            <TextView
                android:id="@+id/card_title"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:maxLines="1"
                android:ellipsize="end"
                android:textColor="@color/text_primary"
                android:textSize="16sp"
                android:textStyle="bold"/>
            <TextView
                android:id="@+id/card_count"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@color/text_tertiary"
                android:textSize="12sp"/>
        </LinearLayout>

        <LinearLayout
            android:id="@+id/card_info_row"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:background="@drawable/bg_chip"
            android:paddingStart="10dp"
            android:paddingEnd="10dp"
            android:paddingTop="6dp"
            android:paddingBottom="6dp">
            <TextView
                android:id="@+id/card_info_days"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@color/accent"
                android:textSize="11sp"
                android:textStyle="bold"/>
            <TextView
                android:id="@+id/card_info_traffic"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:textColor="@color/text_secondary"
                android:textSize="11sp"/>
        </LinearLayout>
    </LinearLayout>

    <ProgressBar
        android:id="@+id/card_info_progress"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="4dp"
        android:layout_marginTop="12dp"
        android:progressTint="@color/accent"/>

    <!-- 2. Tugmalar -->
    <LinearLayout
        android:id="@+id/card_info_btn_row"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:orientation="horizontal">
        <TextView
            android:id="@+id/card_info_btn_support"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="8dp"
            android:background="@drawable/bg_chip"
            android:paddingStart="12dp"
            android:paddingEnd="12dp"
            android:paddingTop="6dp"
            android:paddingBottom="6dp"
            android:textColor="@color/accent"
            android:textSize="12sp"
            android:textStyle="bold"/>
        <TextView
            android:id="@+id/card_info_btn_web"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="@drawable/bg_chip"
            android:paddingStart="12dp"
            android:paddingEnd="12dp"
            android:paddingTop="6dp"
            android:paddingBottom="6dp"
            android:textColor="@color/accent"
            android:textSize="12sp"
            android:textStyle="bold"/>
    </LinearLayout>

    <!-- 3. E'lon va Tugmalar -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:orientation="horizontal"
        android:gravity="end|center_vertical">

        <LinearLayout
            android:id="@+id/info_panel"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:background="#14210f"
            android:orientation="horizontal"
            android:padding="12dp"
            android:visibility="gone"> <!-- Kotlin buni boshqaradi -->

            <View
                android:layout_width="4dp"
                android:layout_height="match_parent"
                android:layout_marginEnd="10dp"
                android:background="#e8c820"/>

            <TextView
                android:id="@+id/card_info_announce"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:textColor="#c8d6c0"
                android:textSize="12sp"
                android:lineSpacingExtra="2dp"/>
        </LinearLayout>

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:gravity="center"
            android:layout_marginStart="8dp">

            <TextView
                android:id="@+id/card_ping"
                android:layout_width="32dp"
                android:layout_height="32dp"
                android:gravity="center"
                android:textSize="16sp"
                android:textColor="@color/accent"
                android:text="📶"/>
            <TextView
                android:id="@+id/card_settings"
                android:layout_width="32dp"
                android:layout_height="32dp"
                android:layout_marginTop="4dp"
                android:gravity="center"
                android:textSize="16sp"
                android:textColor="@color/accent"
                android:text="⚙"/>
            <TextView
                android:id="@+id/card_arrow"
                android:layout_width="32dp"
                android:layout_height="32dp"
                android:layout_marginTop="4dp"
                android:gravity="center"
                android:textSize="16sp"
                android:textColor="@color/text_tertiary"
                android:text="⬇"/>
        </LinearLayout>
    </LinearLayout>

    <TextView
        android:id="@+id/card_info_links"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:textColor="@color/text_tertiary"
        android:textSize="13sp"/>

</LinearLayout>
'''
    xml_path.write_text(new_xml.strip() + '\n', encoding='utf-8')
    print("[XML] Dizayn muvaffaqiyatli yozildi.")

# 2. Kotlin kodini to'g'rilash (Eng muhim qism!)
if kt_path.exists():
    shutil.copy(kt_path, bak_dir / f"HomeFragment.kt.{timestamp}.bak")
    kt = kt_path.read_text(encoding='utf-8')
    
    # 2.1. infoPanel o'zgaruvchisini topish yoki yaratish
    if "val infoPanel =" not in kt:
        kt = kt.replace(
            "val announceTv = itemView.findViewById<TextView>(R.id.card_info_announce)",
            "val infoPanel = itemView.findViewById<View>(R.id.info_panel)\n        val announceTv = itemView.findViewById<TextView>(R.id.card_info_announce)"
        )
    
    # 2.2. E'lon mantiqini tuzatish (announceTv o'rniga infoPanel ni yashirish)
    kt = kt.replace("announceTv.visibility = View.GONE", "infoPanel.visibility = View.GONE")
    kt = kt.replace("announceTv.visibility = View.VISIBLE", "infoPanel.visibility = View.VISIBLE")
    
    kt_path.write_text(kt, encoding='utf-8')
    print("[Kotlin] E'lon mantiqi to'g'rilandi (infoPanel yashiriladi).")

print("=== TAYYOR! ===")
