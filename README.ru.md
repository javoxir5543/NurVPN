# NurVPN

**Современный VPN-клиент для Android** — Kotlin, Material 3, стиль INCY.

[English](README.md) | [O'zbekcha](README.uz.md) | [Русский](README.ru.md)

![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet?logo=kotlin)
![Android](https://img.shields.io/badge/Android-7.0%2B-green?logo=android)
![License](https://img.shields.io/badge/License-MIT-yellow)

---

> **Примечание:** Я **вообще не разработчик** — у меня просто **интерес к этой сфере**. Этот проект создан с помощью **DeepSeek AI** (и других AI-ассистентов) для обучения и личного использования. В коде могут быть ошибки — улучшения приветствуются!

---

## Возможности

- **6+ протоколов**: VLESS Reality, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AmneziaWG
- **Современный UI** — Material 3, тёмно-зелёный + лайм
- **Трафик в реальном времени** — вверх/вниз Б/с
- **3 языка**: узбекский, русский, английский
- **Светлая/тёмная тема**
- **Избранное** — для быстрого подключения
- **Пинг-тест** — TCP + ICMP
- **Split Tunneling** — белый/чёрный список
- **Защита от DNS и IPv6 утечек**
- **QR-сканер** — ссылки и AWG-конфиги
- **Открытые источники** — 10+ бесплатных подписок
- **AI Selector** — автовыбор лучшего сервера
- **Пауза/Продолжить** — панель уведомлений
- **Quick Settings Tile** — центр управления

---


## 📸 Скриншоты

| Home | Servers | Settings |
|:---:|:---:|:---:|
| ![Home](docs/screenshots/01_home.jpg) | ![Servers](docs/screenshots/02_servers.jpg) | ![Settings](docs/screenshots/03_settings.jpg) |

| Security | Open Sources | More Sources |
|:---:|:---:|:---:|
| ![Security](docs/screenshots/04_security.jpg) | ![Sources](docs/screenshots/05_sources.jpg) | ![More](docs/screenshots/06_sources2.jpg) |

---

## Установка

### Скачать APK

1. Перейдите в **Releases**
2. Скачайте **nurvpn-v1.1.0-arm64-v8a.apk** (современные телефоны)
3. Или **armeabi-v7a.apk** (старые телефоны)
4. Установите

### Сборка из исходников

    git clone https://github.com/javoxir5543/NurVPN.git
    cd NurVPN

    chmod +x scripts/get-libbox.sh
    ./scripts/get-libbox.sh
    ./gradlew assembleRelease

---

## Использование

1. Откройте приложение
2. **Настройки -> Открытые источники** -> включите бесплатную подписку
3. Или **+ Добавить** -> введите ссылку VLESS/VMess/AWG
4. **Главная** -> выберите сервер
5. **Play** -> VPN подключается

---

## Технологии

- **Язык**: Kotlin
- **UI**: Material 3, View Binding
- **Ядро**: sing-box (libbox.aar)
- **QR**: ZXing
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)

---

## Вклад

Pull request'ы и issue приветствуются!

1. Форкните
2. Создайте ветку
3. Закоммитьте
4. Запушьте
5. Откройте Pull Request

---

## Лицензия

**MIT License** — подробнее: [LICENSE](LICENSE)

---

## Автор

**Javohir** — [@javoxir5543](https://github.com/javoxir5543)

> **Не разработчик** — просто **интерес к этой сфере**. Создано с помощью **DeepSeek AI**.

---

## Благодарности

- sing-box — VPN ядро
- ZXing — QR-сканер
- **DeepSeek AI** — основной помощник
- Владельцам открытых подписок

Если полезно — поставьте звезду!
