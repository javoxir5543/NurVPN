#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kill-switch avtomatik sinovi (xavfsiz — tarmoqni O'ZGARTIRMAYDI).

Faqat:
  1. VPN ulaydi (adb orqali servisga intent)
  2. VPN'ni o'chiradi
  3. Har bosqichda `tun`/`10.255.255.1`/routing'ni o'lchaydi

TARMOQ SOZLAMALARIGA TEGMAYDI (WiFi/mobil o'chirilmaydi).
"""

import html
import json
import os
import re
import subprocess
import sys
import time

DEV = os.environ.get("DEV", "GYGMUWKZ5HUONJIB")
PKG = "com.nurvpn.app"
SVC = "com.nurvpn.app/.service.NurVpnService"


def sh(cmd, timeout=20):
    try:
        r = subprocess.run(["adb", "-s", DEV, "shell", cmd],
                           capture_output=True, text=True, timeout=timeout)
        return r.stdout.replace("\r", "").strip()
    except Exception as e:
        return "ERR:%s" % e


def state():
    """Bir zumdagi holatni o'lchaydi."""
    addrs = sh("ip -o addr show 2>/dev/null | grep -oE '(10\\.255\\.255\\.1|172\\.19\\.0\\.1|fd00::1)' | sort -u | tr '\\n' ','")
    vpn = sh("dumpsys connectivity 2>/dev/null | grep -c 'VPN CONNECTED'")
    ks = sh("logcat -d -s NurVPN-Svc 2>/dev/null | grep -c 'KILL-SWITCH FAOLLASHDI'")
    if "10.255.255.1" in addrs:
        s = "★BLOK"
    elif "172.19.0.1" in addrs:
        s = "vpn"
    else:
        s = "★★BO'SHLIQ★★"
    return s, addrs, vpn, ks


def read_configs():
    """Qurilmadan config'larni o'qiydi."""
    raw = sh("run-as %s cat shared_prefs/awg.xml 2>/dev/null" % PKG, timeout=30)
    m = re.search(r'<string name="configs">(.*?)</string>', raw, re.S)
    if not m:
        return []
    try:
        return json.loads(html.unescape(m.group(1)))
    except Exception as e:
        print("  ! config o'qish xato: %s" % e)
        return []


def connect(cfg):
    """VPN ulaydi."""
    import base64
    name = cfg.get("name", "test")
    typ = (cfg.get("type") or "masque").lower()
    if typ == "awg":
        key, val = "awg_conf", cfg.get("raw", "")
        proto = "awg"
    else:
        key, val = "link", cfg.get("raw", "")
        proto = "xray"
    b = base64.b64encode(val.encode()).decode()
    sh("am start-foreground-service -n %s --es %s '%s' --es name '%s' --es protocol '%s' --ez force true"
       % (SVC, key, val.replace("'", ""), name, proto), timeout=20)
    return True


def stop():
    sh("am startservice -n %s --ez stop true" % SVC, timeout=20)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "--monitor"

    if mode == "--list":
        for c in read_configs():
            print("  %-14s %-22s %s" % (c.get("name", "?")[:14],
                                        c.get("endpoint", "?")[:22],
                                        c.get("type", "?")))
        return 0

    if mode == "--monitor":
        # Faqat kuzatish — HECH NARSA qilmaydi
        secs = int(sys.argv[2]) if len(sys.argv) > 2 else 30
        print("  kuzatish %d s (hech narsa bosilmaydi)" % secs)
        prev = None
        for i in range(secs):
            s, addrs, vpn, ks = state()
            mark = "" if s == prev else "  ← O'ZGARDI"
            print("  %s %-16s addrs=[%s] vpn=%s ks=%s%s"
                  % (time.strftime("%H:%M:%S"), s, addrs, vpn, ks, mark))
            prev = s
            time.sleep(1)
        return 0

    if mode == "--self-test":
        print("  DEV=%s" % DEV)
        print("  adb: %s" % sh("echo ok"))
        cfgs = read_configs()
        print("  config: %d" % len(cfgs))
        s, a, v, k = state()
        print("  holat: %s addrs=[%s]" % (s, a))
        ok = sh("echo ok") == "ok" and len(cfgs) > 0
        print("  " + ("✅ TAYYOR" if ok else "❌ MUAMMO"))
        return 0 if ok else 1

    print("  ishlatish: --list | --monitor [sek] | --self-test")
    return 1


if __name__ == "__main__":
    sys.exit(main())
