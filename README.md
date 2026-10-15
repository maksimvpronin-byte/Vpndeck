# VpnDeck

Семейный VPN-клиент для Android: вход по логину и паролю, конфиг приходит с сервера,
через VPN идут только выбранные приложения.

```
Телефон (VpnDeck)                         Сервер
  вход: адрес + логин + пароль  ──HTTPS:8448──▶  vpndeck-server  ──читает──▶ /opt/xray-reality-8443/config.json
  получает параметры VLESS Reality ◀──────────┘
  sing-box (libbox) + VpnService ═══VLESS+Reality:8443═══▶ Xray (docker xray-reality-8443)
```

- **server/** — `vpndeck-server`, Go без внешних зависимостей. Берёт параметры прямо из конфига Xray:
  Xray остаётся единственным источником правды, сменил ключи там — телефоны подхватят сами (раз в 6 ч или при подключении).
- **android/** — нативный Kotlin + Compose. Ядро — sing-box 1.14.3 (libbox), собранное только с нужным:
  VLESS, Reality/uTLS, gVisor-стек. Раздельное туннелирование делает сам Android (`addAllowedApplication`):
  трафик остальных приложений в туннель не попадает вообще.
- **scripts/build-libbox.sh** — сборка ядра в `android/app/libs/libbox.aar`.

## Сервер

### Новый сервер с нуля

Нужен Linux с Docker и root по SSH; порты 8443/tcp (VPN) и 8448/tcp (вход) свободны.

```bash
# 1. Xray VLESS+Reality со свежими ключами (на сервере)
scp server/deploy/setup-xray.sh root@<IP>:/root/ && ssh root@<IP> bash /root/setup-xray.sh

# 2. vpndeck-server (с Mac)
server/deploy/deploy.sh root@<IP>

# 3. Первичная настройка (на сервере)
vpndeck-server init -host <IP> -xray /opt/xray-reality-8443/config.json
read -s P && echo "$P" | vpndeck-server passwd family
systemctl enable --now vpndeck-server
vpndeck-server fingerprint     # этот ключ телефон покажет при первом входе
```

Если на сервере уже есть свой Xray с VLESS+Reality, шаг 1 пропустить и передать его конфиг в `-xray`.
Подробная инструкция с проверками — в [CLAUDE.md](CLAUDE.md).

### Обслуживание

Обновить бинарник: `server/deploy/deploy.sh root@<IP>`. На сервере:

| Что | Команда |
|---|---|
| Сменить пароль (все устройства разлогинятся) | `read -s P && echo "$P" \| vpndeck-server passwd family` |
| Добавить ещё аккаунт | то же с другим именем |
| Проверить, что конфиг Xray читается | `vpndeck-server check` |
| Отпечаток ключа (сверить с экраном входа) | `vpndeck-server fingerprint` |
| Логи входов | `journalctl -u vpndeck-server -f` |
| Разлогинить всех | поменять `token_secret` в `/etc/vpndeck/server.json` |
| Удалить | `systemctl disable --now vpndeck-server && rm -rf /etc/vpndeck /usr/local/bin/vpndeck-server /etc/systemd/system/vpndeck-server.service` |

Не удаляйте `/etc/vpndeck/tls.crt` и `tls.key`: телефоны запомнили этот ключ и при смене покажут
предупреждение «Ключ сервера изменился». Ключи Reality меняются в конфиге Xray (`docker restart xray-reality-8443`),
телефоны подхватят их сами при следующем обновлении профиля.

После 5 неверных паролей с одного IP вход блокируется на 15 минут.

## Сборка приложения

Нужно: Go, JDK 21 (`brew install openjdk@21`), Android SDK (`brew install --cask android-commandlinetools`,
затем platform 37.1, build-tools, NDK 28.0.13004108).

```bash
scripts/build-libbox.sh            # ядро → android/app/libs/libbox.aar
cd android && ./gradlew assembleRelease
# APK: android/app/build/outputs/apk/release/vpndeck-*-universal-release.apk
```

Релиз подписывается ключом из `android/keystore/` (пароли — в `android/keystore.properties`).
**Сохраните оба файла в надёжном месте**: обновления приложения ставятся поверх только с тем же ключом.

## Для пользователей

1. Установить APK, открыть, ввести адрес сервера, логин и пароль, нажать «Доверять» на ключе сервера.
2. Вкладка «Приложения» — отметить, что пускать через VPN (Telegram, YouTube…).
3. Нажать кнопку подключения.
4. Для стабильности: «Разрешить работу в фоне» и «Постоянная VPN» в настройках Android
   («Блокировать соединения без VPN» — не включать).

## Лицензия

Ядро sing-box и части кода из sing-box-for-android распространяются под GPL-3.0,
поэтому приложение тоже GPL-3.0.
