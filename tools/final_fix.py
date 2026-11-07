#!/usr/bin/env python3
"""NurVPN v1.12.0 — FINAL FIX"""
import shutil
from pathlib import Path
from datetime import datetime

ROOT = Path("/home/pc/NurVPN")

def backup(p):
    if not p.exists(): return
    b = p.with_suffix(p.suffix + f".bak.{int(datetime.now().timestamp())}")
    shutil.copy(p, b)
    print(f"   📦 {b.name}")

print("═" * 60)
print("  FIX #1 — ScrollView → NestedScrollView")
print("═" * 60)

hx = ROOT / "app/src/main/res/layout/fragment_home.xml"
if hx.exists():
    t = hx.read_text(encoding="utf-8")
    if "<ScrollView" in t and "NestedScrollView" not in t[:500]:
        t = t.replace(
            '<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"',
            '<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"',
            1
        )
        t = t.replace("</ScrollView>", "</androidx.core.widget.NestedScrollView>", 1)
        backup(hx)
        hx.write_text(t, encoding="utf-8")
        print("   ✅ ScrollView → NestedScrollView")
    else:
        print("   ✓ allaqachon tuzatilgan yoki ScrollView yo'q")

print()
print("═" * 60)
print("  FIX #2 — Servers nestedScrollingEnabled=false")
print("═" * 60)

sx = ROOT / "app/src/main/res/layout/fragment_servers.xml"
if sx.exists():
    t = sx.read_text(encoding="utf-8")
    if 'android:nestedScrollingEnabled="true"' in t:
        t = t.replace(
            'android:nestedScrollingEnabled="true"',
            'android:nestedScrollingEnabled="false"'
        )
        backup(sx)
        sx.write_text(t, encoding="utf-8")
        print("   ✅ nestedScrollingEnabled → false")
    else:
        print("   ✓ allaqachon false yoki topilmadi")

print()
print("═" * 60)
print("  FIX #3 — Version bump")
print("═" * 60)

bg = ROOT / "app/build.gradle"
if not bg.exists():
    bg = ROOT / "app/build.gradle.kts"
if bg.exists():
    t = bg.read_text(encoding="utf-8")
    ch = False
    if "versionCode 19" in t:
        t = t.replace("versionCode 19", "versionCode 22", 1); ch = True
        print("   ✅ versionCode 19 → 22")
    if 'versionName "1.11.1"' in t:
        t = t.replace('versionName "1.11.1"', 'versionName "1.12.0"', 1); ch = True
        print('   ✅ versionName "1.11.1" → "1.12.0"')
    if ch:
        backup(bg)
        bg.write_text(t, encoding="utf-8")

print()
print("═" * 60)
print("  TAYYOR")
print("═" * 60)
