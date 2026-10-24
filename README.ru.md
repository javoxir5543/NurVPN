# NurVPN

**Современный VPN-клиент для Android** — Kotlin, Material 3, стиль INCY.

[English](README.md) | [O'zbekcha](README.uz.md) | [Русский](README.ru.md)

![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet?logo=kotlin)
![Android](https://img.shields.io/badge/Android-7.0%2B-green?logo=android)
![License](https://img.shields.io/badge/License-GPLv3-blue)
![Version](https://img.shields.io/badge/version-1.8-blue)

---

> **Примечание:** Я **вообще не разработчик** — у меня просто **интерес к этой сфере**. Этот проект создан с помощью **DeepSeek AI** (и других AI-ассистентов) для обучения и личного использования. В коде могут быть ошибки — улучшения приветствуются!

---

## Возможности

- **18 протоколов**: VLESS Reality, VLESS, VMess, Trojan, Shadowsocks, Hysteria, Hysteria2, TUIC, WireGuard, AmneziaWG 1.x/2.x/3.x, MASQUE, AnyTLS, ShadowTLS, SSH, SOCKS, HTTP/HTTPS, NaiveProxy
- **Современный UI** — Material 3, тёмно-зелёный + лайм
- **Трафик в реальном времени** — вверх/вниз Б/с
- **3 языка**: узбекский, русский, английский
- **Светлая/тёмная тема**
- **Избранное** — для быстрого подключения
- **Пинг-тест** — TCP + ICMP
- **Split Tunneling** — белый/чёрный список
- **Защита от DNS и IPv6 утечек**
- **QR-сканер** — ссылки и AWG-конфиги
- **Открытые источники** — 10+ бесплатных подписок (можно полностью отключить)
- **AI Selector** — автовыбор лучшего сервера
- **Пауза/Продолжить** — панель уведомлений
- **Quick Settings Tile** — центр управления
- **Crypt1 Декодер** — расшифровка зашифрованных подписок `incy://crypt1/...`
- **Deep Links** — схемы `incy://`, `crypt1://`, `nurvpn://`
- **Биометрическая блокировка** — защита отпечатком/лицом
- **HWID** — идентификация устройства в стиле incy
- **Имя провайдера** — автоматически из заголовка `profile-title`

---

## 📸 Скриншоты

<p align="center">
  <img src="docs/screenshots/01_home_off.jpg" width="220">
  <img src="docs/screenshots/02_home_on.jpg" width="220">
  <img src="docs/screenshots/03_servers.jpg" width="220">
</p>

<p align="center">
  <img src="docs/screenshots/04_settings.jpg" width="220">
  <img src="docs/screenshots/05_security.jpg" width="220">
  <img src="docs/screenshots/06_sources.jpg" width="220">
</p>

<p align="center">
  <img src="docs/screenshots/07_sources2.jpg" width="220">
</p>

---

## Установка

### Скачать APK

1. Перейдите в **Releases**
2. Скачайте **nurvpn-v1.9-arm64-v8a.apk** (современные телефоны, ~64 МБ)
3. Установите

### Сборка из исходников

    git clone https://github.com/javoxir5543/NurVPN.git
    cd NurVPN
    ./gradlew assembleRelease

**Примечание:** `libbox.aar` уже включён в `app/libs/`. Для release-сборки создайте `keystore.properties` со своим signing config.

---

## Использование

1. Откройте приложение
2. **Настройки → Открытые источники** → включите бесплатную подписку
3. Или **+ Добавить** → введите ссылку VLESS/VMess/AWG
4. **Главная** → выберите сервер
5. **Play** → VPN подключается

---

## Технологии

- **Язык**: Kotlin
- **UI**: Material 3, View Binding
- **Ядро**: sing-box v1.14.3-lx.18 (libbox.aar)
- **QR**: ZXing
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Архитектура**: только arm64-v8a

---

## 🔐 Подпись

- **SHA-256**: `5C:07:01:E8:92:65:C8:55:66:60:0E:E1:1C:88:49:60:33:9F:3B:17:25:C3:52:29:0C:46:A6:4F:99:2D:47:B6`
- **Signer**: `CN=NurVPN Developer, OU=Development, O=NurVPN, L=Andijan, ST=Andijan, C=UZ`

---

## 📝 История изменений

### v1.8 (2026-10-15)

- **Система HWID** (incy-style) — salt + Build.* → SHA-256 → 30 символов
- **VLESS REALITY**: `key_share: hybrid`, `fingerprint: chrome`
- **libbox.aar пересобран** — sing-box v1.14.3-lx.18
- **Исправления MASQUE** — MTU 1360 (#1, #2), 1420 (#3)
- **Split Tunneling** — выбранные приложения сверху
- **YAML парсер** — поддержка flat proxies
- **Имя провайдера** — из заголовка `profile-title`
- **AWG Config** — исправлен чекбокс
- **Переводы** — UZ и RU полностью (370 строк)
- **Новый keystore** (обновление безопасности)

### v1.8

- Первый публичный релиз

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

**GNU General Public License v3.0** — подробнее: [LICENSE](LICENSE)

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

---

## 📦 F-Droid Репозиторий

Добавьте официальный репозиторий для автообновлений:

**URL репозитория:** `https://javoxir5543.github.io/nurvpn-repo/repo`

### Как добавить:

1. Откройте **F-Droid** или **Neo Store**
2. **Настройки** → **Репозитории** → **Добавить репозиторий**
3. Введите URL выше
4. Нажмите **Добавить**
5. **Обновления** → появится **NurVPN**

Или прямая ссылка: [F-Droid репозиторий](https://javoxir5543.github.io/nurvpn-repo/repo)

---

## 🚊 Документация кода

Все файлы проекта задокументированы в формате MD:

**📂 [docs/code/](docs/code)** — 111 файлов

Состав:
- `app/src/main/java/` — Kotlin код (backend, парсеры, UI, сервисы)
- `app/src/main/res/` — XML ресурсы (layouts, strings, drawables)
- `app/build.gradle` + `proguard-rules.pro`
- Корневые Gradle-файлы (build, settings, properties)

Каждый файл — заголовок и блок кода. Можно читать прямо на GitHub.
