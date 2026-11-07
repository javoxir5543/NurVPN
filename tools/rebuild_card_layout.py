from pathlib import Path
import shutil
from datetime import datetime

p = Path('/home/pc/NurVPN/app/src/main/res/layout/item_home_card_header.xml')

# Xavfsiz zaxira nusxa
bak_dir = Path('/home/pc/NurVPN/.fix-backups')
bak_dir.mkdir(exist_ok=True)
bak = bak_dir / f"item_home_card_header.xml.{int(datetime.now().timestamp())}.bak"
if p.exists():
    shutil.copy(p, bak)
    print(f"📦 Backup: {bak.name}")

# HTML dizayni asosida yozilgan yangi XML (ConstraintLayout yordamida)
new_xml = '''<?xml version="1.0" encoding="utf-8"?>
<!-- FIX157: HTML dizayni asosida to'liq qayta qurildi -->
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="10dp"
    android:background="@drawable/bg_card"
    android:clickable="true"
    android:focusable="true"
    android:backgroundTint="?android:attr/selectableItemBackground"
    android:padding="16dp">

    <!-- 1. Icon -->
    <FrameLayout
        android:id="@+id/card_icon_box"
        android:layout_width="44dp"
        android:layout_height="44dp"
        android:background="@drawable/bg_icon_box"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent">

        <TextView
            android:id="@+id/card_icon"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:textSize="20sp"
            android:text="📡"/>
    </FrameLayout>

    <!-- 2. Title va Subtitle -->
    <TextView
        android:id="@+id/card_title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="12dp"
        android:maxLines="1"
        android:ellipsize="end"
        android:textColor="@color/text_primary"
        android:textSize="16sp"
        android:textStyle="bold"
        app:layout_constraintStart_toEndOf="@id/card_icon_box"
        app:layout_constraintTop_toTopOf="@id/card_icon_box"
        app:layout_constraintEnd_toStartOf="@id/meta_container"/>

    <TextView
        android:id="@+id/card_count"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="12dp"
        android:textColor="@color/text_tertiary"
        android:textSize="12sp"
        app:layout_constraintStart_toEndOf="@id/card_icon_box"
        app:layout_constraintTop_toBottomOf="@id/card_title" />

    <!-- 3. Meta Container (Kun va Trafik) -->
    <LinearLayout
        android:id="@+id/meta_container"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:background="@drawable/bg_chip"
        android:paddingHorizontal="10dp"
        android:paddingVertical="6dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="@id/card_icon_box">

        <TextView
            android:id="@+id/card_info_days"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/accent"
            android:textSize="11sp"
            android:textStyle="bold"
            android:text="📅 Осталось 1 дн."/>

        <TextView
            android:id="@+id/card_info_traffic"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:textColor="@color/text_secondary"
            android:textSize="11sp"
            android:text="0 B / 50.00 GB"/>
    </LinearLayout>

    <!-- 4. Support va Web Tugmalari -->
    <LinearLayout
        android:id="@+id/btn_row"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:orientation="horizontal"
        app:layout_constraintTop_toBottomOf="@id/card_icon_box">

        <TextView
            android:id="@+id/card_info_btn_support"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="8dp"
            android:background="@drawable/bg_chip"
            android:paddingHorizontal="12dp"
            android:paddingVertical="6dp"
            android:textColor="@color/accent"
            android:textSize="12sp"
            android:textStyle="bold"
            android:text="🔧 Поддержка"/>

        <TextView
            android:id="@+id/card_info_btn_web"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="@drawable/bg_chip"
            android:paddingHorizontal="12dp"
            android:paddingVertical="6dp"
            android:textColor="@color/accent"
            android:textSize="12sp"
            android:textStyle="bold"
            android:text="🌐 Сайт"/>
    </LinearLayout>

    <!-- 5. Info Panel (Sariq chegara va E'lon) -->
    <LinearLayout
        android:id="@+id/info_panel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:background="#14210f"
        android:orientation="horizontal"
        android:padding="12dp"
        app:layout_constraintTop_toBottomOf="@id/btn_row">
        
        <!-- Sariq chegara effekti uchun chap tomonda yupqa View -->
        <View
            android:layout_width="4dp"
            android:layout_height="match_parent"
            android:layout_marginEnd="10dp"
            android:background="#e8c820"/>

        <!-- E'lon matni -->
        <TextView
            android:id="@+id/card_info_announce"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textColor="#c8d6c0"
            android:textSize="12sp"
            android:lineSpacingExtra="2dp"
            android:text="Выключите VPN и нажмите 🔄, если не работает и подключитесь к другой локации."/>

        <!-- O'ng tomondagi vertikal tugmalar (Ping, Sozlamalar, Strelka) -->
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="match_parent"
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

    <!-- 6. "X ta server - ochish uchun bosing" matni -->
    <TextView
        android:id="@+id/card_info_links"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:gravity="center"
        android:textColor="@color/text_tertiary"
        android:textSize="12sp"
        android:text="49 серверов — нажмите чтобы раскрыть"
        app:layout_constraintTop_toBottomOf="@id/info_panel"/>

</androidx.constraintlayout.widget.ConstraintLayout>
'''

p.write_text(new_xml, encoding='utf-8')
print("✅ Layout HTML dizayni asosida to'liq qayta yozildi!")
