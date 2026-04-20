# CRM WhatsApp — Руководство по запуску

Полнофункциональная система CRM для работы с WhatsApp через Android-агент.

## Архитектура

```
WhatsApp (Android) ←→ Android Agent APK ←→ Backend (Go + Postgres + Redis) ←→ Web UI (React)
```

## 🚦 Статус сервисов

| Сервис          | URL                                    | Статус         |
| --------------- | -------------------------------------- | -------------- |
| Backend API     | http://localhost:8080                  | Docker         |
| Web UI          | http://localhost:5173                  | Vite dev       |
| Postgres        | localhost:5432                         | Docker         |
| Redis           | localhost:6379                         | Docker         |

Логин оператора: `admin@crm.local` / `admin123`

## 📱 Установка Android-агента

**APK:** `android-agent/app/build/outputs/apk/debug/app-debug.apk` (~11 МБ)

### Способ 1: через ADB (USB или Wi-Fi)

1. Включите на телефоне: **Настройки → О телефоне → 7 тапов по номеру сборки** (включить режим разработчика)
2. **Настройки → Для разработчиков → Отладка по USB** (или Беспроводная отладка)
3. Подключите телефон к ПК по USB
4. Установите APK:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "android-agent\app\build\outputs\apk\debug\app-debug.apk"
```

### Способ 2: напрямую

Скопируйте APK на телефон (через USB, Google Drive, Telegram) → откройте → разрешите установку из неизвестных источников → установите.

## 🔑 Разрешения на телефоне

После установки приложения CRM Agent откройте его и **выдайте все разрешения**:

1. **Доступ к уведомлениям** — чтобы читать уведомления WhatsApp
   - Настройки → Специальные возможности → Доступ к уведомлениям → CRM Agent ✅
2. **Спецвозможности (Accessibility)** — чтобы отправлять ответы
   - Настройки → Спецвозможности → CRM Agent → Включить
3. **Отключить оптимизацию батареи** для CRM Agent
   - Настройки → Приложения → CRM Agent → Батарея → Без ограничений
4. **Уведомления** (Android 13+) — дать разрешение при первом запуске

## 🌐 Сеть

APK собран с адресом backend: `http://192.168.1.84:8080`

**Важно:** телефон и ПК должны быть в одной Wi-Fi сети.
Если IP-адрес ПК изменится, пересоберите APK:

```powershell
cd android-agent
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug `
  -PBACKEND_WS_URL="ws://<IP>:8080/ws/agent" `
  -PBACKEND_API_URL="http://<IP>:8080" `
  -PDEVICE_TOKEN="<JWT-ТОКЕН>"
```

## 🔁 Быстрые команды

```powershell
# Запустить backend
docker compose up -d

# Запустить фронтенд
cd frontend; npm run dev

# Остановить всё
docker compose down

# Посмотреть логи backend
docker logs -f crm_backend

# Пересобрать APK
cd android-agent
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

## 📡 Регистрация нового устройства

```powershell
# 1. Залогиниться оператором
$login = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/login" `
  -Method Post -ContentType "application/json" `
  -Body '{"email":"admin@crm.local","password":"admin123"}'

# 2. Зарегистрировать устройство
$dev = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/devices/register" `
  -Method Post -ContentType "application/json" `
  -Headers @{Authorization = "Bearer $($login.token)"} `
  -Body '{"name":"Мой телефон","phone_number":"+79001234567"}'

Write-Host "Device ID: $($dev.device.id)"
Write-Host "Token:     $($dev.token)"
```

## 🎯 Проверка end-to-end

1. Откройте https://localhost:5173 → войдите как `admin@crm.local` / `admin123`
2. Откройте раздел «Устройства» — должно появиться устройство со статусом **Онлайн** после установки APK
3. На телефоне откройте WhatsApp, получите сообщение от контакта
4. В веб-интерфейсе в разделе «Чаты» должен появиться новый диалог — **в реальном времени**
5. Ответьте через веб — сообщение отправится через агент на телефоне

## 🛠️ Troubleshooting

| Проблема | Решение |
|----------|---------|
| Устройство offline | Проверить, что телефон в той же Wi-Fi сети. Проверить разрешение «доступ к уведомлениям». Открыть приложение CRM Agent. |
| Сообщения не приходят | Проверить уведомление WhatsApp реально появляется. Включить Accessibility Service для CRM Agent. |
| Ответ не отправляется | Проверить Accessibility Service. WhatsApp должен быть в списке поддерживаемых приложений. |
| Web UI не подключается | Проверить `docker ps` — backend должен быть `healthy`. |
| Нельзя установить APK | Разрешить установку из неизвестных источников для браузера/Проводника. |
