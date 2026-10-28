# VpnDeck

Семейный VPN-клиент для Android: вход по логину и паролю, конфиг приходит с сервера,
через VPN идут только выбранные приложения.

```
Телефон (VpnDeck)                         Сервер
  вход: адрес + логин + пароль  ──HTTPS:8448──▶  vpndeck-server  ──читает──▶ /opt/xray-reality-8443/config.json
  получает параметры VLESS Reality ◀──────────┘
  sing-box (libbox) + VpnService ═══VLESS+Reality:443═══▶ Xray (docker xray-reality-8443)
```

- **server/** — `vpndeck-server`, Go без внешних зависимостей. Берёт параметры прямо из конфига Xray:
  Xray остаётся единственным источником правды, сменил ключи там — телефоны подхватят сами (раз в 6 ч или при подключении).
- **android/** — нативный Kotlin + Compose. Ядро — sing-box 1.14.3 (libbox), собранное только с нужным:
  VLESS, Reality/uTLS, gVisor-стек. Раздельное туннелирование делает сам Android (`addAllowedApplication`):
  трафик остальных приложений в туннель не попадает вообще.
- **scripts/build-libbox.sh** — сборка ядра в `android/app/libs/libbox.aar`.

## Сервер

### Новый сервер с нуля

Нужен Linux с Docker и root по SSH; порты 443/tcp (VPN) и 8448/tcp (вход) свободны.

```bash
# 1. Подобрать сайт для маскировки среди соседей по подсети (на сервере)
scp server/deploy/find-sni.py root@<IP>:/root/ && ssh root@<IP> python3 /root/find-sni.py

# 2. Xray VLESS+Reality со свежими ключами (на сервере)
scp server/deploy/setup-xray.sh root@<IP>:/root/ && ssh root@<IP> XRAY_SNI=<сайт> bash /root/setup-xray.sh

# 3. vpndeck-server (с Mac)
server/deploy/deploy.sh root@<IP>

# 4. Первичная настройка (на сервере)
vpndeck-server init -host <IP> -xray /opt/xray-reality-8443/config.json
read -s P && echo "$P" | vpndeck-server passwd family
systemctl enable --now vpndeck-server
vpndeck-server fingerprint     # этот ключ телефон покажет при первом входе
```

Если на сервере уже есть свой Xray с VLESS+Reality, шаги 1–2 пропустить и передать его конфиг в `-xray`.
Подробная инструкция с проверками — в [CLAUDE.md](CLAUDE.md).

### Обслуживание

Обновить бинарник: `server/deploy/deploy.sh root@<IP>`. На сервере:

| Что | Команда |
|---|---|
| Сменить пароль (все устройства разлогинятся) | `read -s P && echo "$P" \| vpndeck-server passwd family` |
| Добавить ещё аккаунт | то же с другим именем |
| Проверить, что конфиг Xray читается | `vpndeck-server check` |
| Отпечаток ключа (сверить с экраном входа) | `vpndeck-server fingerprint` |
| Список устройств | `vpndeck-server devices` |
| Отключить / вернуть устройство | `vpndeck-server devices revoke <id>` / `devices restore <id>` |
| Логи входов | `journalctl -u vpndeck-server -f` |
| Разлогинить всех | поменять `token_secret` в `/etc/vpndeck/server.json` |
| Удалить | `systemctl disable --now vpndeck-server && rm -rf /etc/vpndeck /usr/local/bin/vpndeck-server /etc/systemd/system/vpndeck-server.service` |

Не удаляйте `/etc/vpndeck/tls.crt` и `tls.key`: телефоны запомнили этот ключ и при смене покажут
предупреждение «Ключ сервера изменился». Ключи Reality меняются в конфиге Xray (`docker restart xray-reality-8443`),
телефоны подхватят их сами при следующем обновлении профиля.

После 5 неверных паролей с одного IP вход блокируется на 15 минут.

`deploy.sh` при каждом запуске заменяет unit-файл systemd. Свои настройки (например, `ReadWritePaths`
для конфига Xray в другом месте) кладите в drop-in — он переживает обновления:
`systemctl edit vpndeck-server` → `[Service]` / `ReadWritePaths=/путь/к/xray`.

Токены старых версий приложения (v1) бессрочные. Срок можно ограничить: `"legacy_token_max_age_days": 90`
в `/etc/vpndeck/server.json` — по истечении телефон попросит войти заново.

### Отдельные устройства

С 0.1.6 у каждой установки приложения может быть свой UUID и токен. Включение — на VPN-сервере:

```bash
vpndeck-server devices enable xray-reality-8443   # имя контейнера Xray, его перезапускает сервер
vpndeck-server devices                            # ID, аккаунт, модель, дата, статус
vpndeck-server devices revoke <device-id>         # убрать UUID из Xray и отозвать токены
vpndeck-server devices restore <device-id>        # вернуть с новым UUID; на телефоне войти заново
```

Приложение само переводит существующую сессию на отдельный UUID — через несколько секунд после
подключения, повторный ввод пароля не нужен. ID своего телефона есть в отчёте диагностики.
Серверы без этой функции по-прежнему поддерживаются. При регистрации, отзыве и возврате устройства
перезапускается только выбранный контейнер Xray; у его клиентов возможен короткий разрыв соединений.

Отозванный телефон увидит «Это устройство отключено на сервере». Устройство — это установка, а не
человек: чтобы закрыть доступ человеку, смените ещё и пароль его аккаунта. Если на одном телефоне войти
в другой аккаунт, для него заведётся отдельное устройство.

Для совместимости общий UUID старых APK (до 0.1.6) сохраняется: им пользуются телефоны, ещё не
получившие свой UUID. Когда все телефоны обновятся, общего клиента можно удалить из конфига Xray
(`vpndeck-server check` предупредит, что его нет) — UUID устройств при этом никому не раздаются.

Список устройств и параметры Xray хранятся только на сервере. Записи в JSON и операции
CLI/API сериализованы; при ошибке перезапуска Xray его конфиг восстанавливается, а порядок и
оформление ключей в нём сохраняются. Unit-файл разрешает запись только в `/etc/vpndeck` и
`/opt/xray-reality-8443`; для другого пути — drop-in (см. выше).

## Сборка приложения

Нужно: Go, JDK 21 (`brew install openjdk@21`), Android SDK (`brew install --cask android-commandlinetools`,
затем platform 37.1, build-tools, NDK 28.0.13004108).

```bash
scripts/build-libbox.sh            # ядро → android/app/libs/libbox.aar
cd android && ./gradlew assembleRelease
# APK: android/app/build/outputs/apk/release/vpndeck-*-universal-release.apk
```

Проверки (их же запускает CI до сборки подписанных APK):

```bash
(cd server && go test -race ./... && go vet ./...)
(cd android && ./gradlew testDebugUnitTest lintRelease)
```

Формат профиля — общий для сервера и приложения: образец лежит в `server/testdata/profile.json`,
его проверяют тесты обеих сторон.

Выпуск версии — через GitHub Actions: `scripts/release.sh 0.1.4 "что нового"` ставит тег, сборка и подпись
идут в GitHub, получается черновик релиза; после публикации приложение на телефонах обновится само.
Что менялось — в [CHANGELOG.md](CHANGELOG.md).

Релиз подписывается ключом из `android/keystore/` (пароли — в `android/keystore.properties`); для GitHub
он же лежит в секретах репо `ANDROID_KEYSTORE_BASE64` и `ANDROID_KEYSTORE_PASSWORD`.
**Сохраните оба файла в надёжном месте**: обновления приложения ставятся поверх только с тем же ключом.

## Для пользователей

1. Установить APK, открыть, ввести адрес сервера, логин и пароль, нажать «Доверять» на ключе сервера.
2. Вкладка «Приложения» — отметить, что пускать через VPN (Telegram, YouTube…).
3. Нажать кнопку подключения.
4. Для стабильности: «Разрешить работу в фоне» и «Постоянная VPN» в настройках Android
   («Блокировать соединения без VPN» — не включать).

### Надёжность и диагностика

- Отключение отменяет незавершённое подключение (и из плитки в шторке). При недоступности API
  используется сохранённый профиль; ответ 401 завершает сессию — экран входа покажет причину,
  а изменившийся ключ сервера останавливает VPN.
- Главный экран различает проверку, рабочий туннель и восстановление соединения.
  Проверки используют несколько независимых адресов по HTTPS; достаточно одного доступного.
- «Диагностика → Проверить соединение» проверяет API и работающий туннель. Отчёт
  можно скопировать: он содержит версию, ID устройства, результаты и время обновления
  профиля. Пароль, токен, UUID, адрес сервера и сырые логи в отчёт не включаются.
- Сессия не переносится на новый телефон при переносе данных Android: там нужно войти заново.

## Лицензия

Ядро sing-box и части кода из sing-box-for-android распространяются под GPL-3.0,
поэтому приложение тоже GPL-3.0.
