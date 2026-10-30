# VpnDeck — инструкция для Claude

Семейный VPN: Android-клиент (Kotlin + sing-box) с входом по логину/паролю и
раздельным туннелированием по приложениям, плюс серверная часть (Xray + vpndeck-server).
Некоммерческий, APK ставится вручную, не через Play.

## Как устроено

```
Телефон (VpnDeck)
  ├─① HTTPS :8448 ──▶ vpndeck-server ──читает──▶ конфиг Xray
  └─② VLESS+Reality :443 ──▶ Xray (Docker) ──▶ интернет
```

- **Xray** (`ghcr.io/xtls/xray-core`, контейнер `xray-reality-8443`) — сам VPN. Конфиг
  `/opt/xray-reality-8443/config.json` — **единственный источник правды** о ключах.
- **vpndeck-server** (`server/`, Go, только stdlib) — вход и выдача параметров. На каждый
  запрос перечитывает конфиг Xray и вычисляет публичный ключ Reality из приватного.
  Свой конфиг — `/etc/vpndeck/server.json` (перечитывается на каждый запрос, рестарт не нужен).
  Самоподписанный TLS; телефон пинит SPKI при первом входе (как SSH).
- **Android** (`android/`) — Compose UI, `VpnDeckService` (VpnService + PlatformInterface libbox),
  ядро — sing-box **1.14.3** из `scripts/build-libbox.sh` (теги: `with_gvisor,with_utls`).
  Конфиг sing-box генерирует `ConfigBuilder.kt` (проверен `sing-box check`).
  `TunnelWatchdog.kt` — сторож: раз в 4 мин и через 5 с после смены сети шлёт через туннель
  HTTPS-проверки (`TunnelProbe.kt`: gstatic, cloudflare, firefox detectportal — достаточно одной);
  если ни одна не проходит — запрашивает профиль напрямую (мимо туннеля)
  и делает reload. Если и сервер входа недоступен — ничего не перезапускает, ретраи с бэкоффом до 15 мин.

Контракт API: `POST /api/v1/login {username,password,device_id,device_name}` → `{token, profile}`;
`GET /api/v1/profile` (Bearer) → profile; `POST /api/v1/device {device_id,device_name}` (Bearer v1)
→ `{token, profile}` с токеном v2 и отдельным UUID устройства (404 — сервер без устройств).
Отказ — 401 с `{"error": …}`: `invalid_credentials`, `unauthorized`, `device_revoked`,
`device_other_account` (статус всегда 401, чтобы старые APK вели себя как раньше).
Формат profile — `server/xray.go` (`VlessServer`) и `android/.../data/Profile.kt`; менять только
синхронно, вместе с образцом `server/testdata/profile.json` (его проверяют тесты обеих сторон).

## Структура

| Путь | Что |
|---|---|
| `server/*.go` | vpndeck-server + тесты (`go test ./...`) |
| `server/testdata/profile.json` | образец профиля — контракт сервера и приложения, проверяют тесты обеих сторон |
| `server/deploy/deploy.sh` | сборка под linux и установка/обновление бинарника по SSH (запуск с Mac) |
| `server/deploy/setup-xray.sh` | создание Xray VLESS+Reality со свежими ключами (запуск на сервере) |
| `server/deploy/find-sni.py` | поиск сайта для маскировки Reality среди соседей по подсети (запуск на сервере) |
| `server/deploy/vpndeck-server.service` | systemd-юнит |
| `scripts/build-libbox.sh` | сборка ядра в `android/app/libs/libbox.aar` (качает исходники в `.deps/`) |
| `scripts/release.sh` | выпуск версии: поднять версию, тег с «что нового», пуш → сборку делает GitHub Actions |
| `scripts/verify-apks.sh` | проверка APK: все ABI, версия, подпись релизным ключом |
| `.github/workflows/release.yml` | тег `v*` → сборка ядра и APK, подпись ключом из секретов, **черновик** релиза |
| `android/` | приложение; APK в `android/app/build/outputs/apk/` |
| `CHANGELOG.md` | что менялось по версиям; текст «что нового» для `release.sh` — оттуда |

В git **нет**: APK, `libbox.aar`, бинарников, `.deps/`, `dist/`, ключа подписи
(`android/keystore/`, `android/keystore.properties`), `local.properties`.

## Окружение сборки (Mac пользователя)

- Go (есть), JDK 21: `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- Android SDK: `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
  (platform 37.1, build-tools 36.0.0, NDK 28.0.13004108, emulator + образ android-36 arm64)
- gomobile — форк `github.com/sagernet/gomobile@v0.1.13` (ставит build-libbox.sh)
- Связь с GitHub/Google медленная и рвётся: `git clone` повторять, крупные загрузки — в фоне.

```bash
(cd server && go test -race ./... && go vet ./...)
scripts/build-libbox.sh                      # ~1–2 мин, нужен перед первой сборкой APK
(cd android && ./gradlew testDebugUnitTest lintRelease assembleRelease)   # подпись — из keystore.properties
```

Сервер требует Go 1.26+ (`server/go.mod`; более старый Go сам скачает нужный). Ядро в CI собирается
отдельным Go 1.25 — его версию меняй только вместе с `SING_BOX_VERSION`.

Если `android/keystore.properties` нет (новая машина) — release подпишется debug-ключом,
и **обновление поверх уже установленных телефонов не встанет**. Ключ хранится у пользователя
отдельно; спроси его, прежде чем генерировать новый.

## Выпуск версии и самообновление

Как в TerminalDeck/KubeDeck: тег собирает GitHub, публикация — отдельным шагом и только по просьбе пользователя.

```bash
scripts/release.sh 0.1.4 "Что нового"          # поднимает версию, тег с этим текстом, пушит
gh run watch                                   # сборка ~10 мин (ядро кэшируется)
gh release view v0.1.4 --json assets           # 4 APK: arm64-v8a, armeabi-v7a, x86_64, universal
gh release edit v0.1.4 --draft=false --latest  # публикация → телефоны обновятся
```

- Секреты репо: `ANDROID_KEYSTORE_BASE64` (keystore в base64) и `ANDROID_KEYSTORE_PASSWORD`
  (store и key пароль одинаковые, alias `vpndeck`). Ставит их пользователь сам — пароли в чужие сервисы я не ввожу.
  Без секретов тег не соберётся (ошибка), а ручной запуск соберёт тестовые APK с debug-подписью.
- Локальная сборка (`./gradlew assembleRelease`) по-прежнему работает для тестов, но в релиз не идёт.
- Версия — в `android/version.properties`; `versionCode` вычисляется (`0.1.4` → `104`).
  Версия 0.1.2 была собрана со старой схемой (`versionCode=3`) — всё новее неё заведомо больше.
- Приложение (`update/Updater.kt`) с 0.1.3 само смотрит `releases/latest` репо `BuildConfig.UPDATE_REPO`
  (при открытии — не чаще раза в 6 ч, из VPN-сервиса — раз в сутки с уведомлением), качает APK своей ABI
  (`vpndeck-<ver>-<abi>-release.apk`, иначе `universal`), проверяет пакет/версию/**подпись** и ставит через
  PackageInstaller. Первый раз Android спросит разрешение «установка неизвестных приложений» и подтверждение;
  на Android 12+ дальше может ставить без вопросов (`USER_ACTION_NOT_REQUIRED`).
- `verify-apks.sh` (в CI) не пропустит APK, подписанный не релизным ключом (`EXPECTED_CERT`): такой телефоны не примут.
- Проверка апдейтера без выпуска фейковой версии: собрать релизной подписью сборку «чуть старше»
  опубликованной — для опубликованной `0.1.4` это `./gradlew assembleRelease -PvpndeckVersion=0.1.3.9`
  (код 103 < 104), поставить на эмулятор и нажать «Проверить обновления».

## Развёртывание на новом сервере

Всё, что меняет сервер, — только после явного «да» пользователя. Перед началом посмотри,
что на сервере уже есть (`docker ps`, `ss -tulpn`): порты 443 и 8448 должны быть свободны,
иначе выбери другие (`XRAY_PORT=…`, `init -listen :…`) — но 443 предпочтителен (см. «Подводные камни»).

1. Доступ: `ssh root@<IP>` по ключу. Нужен Docker (`docker --version`); если нет —
   предложи пользователю поставить (`curl -fsSL https://get.docker.com | sh`), не ставь молча.
2. Маскировка: `scp server/deploy/find-sni.py root@<IP>:/root/ && ssh root@<IP> python3 /root/find-sni.py`.
   Выбрать сайт с живым контентом (не шаблон-заглушку, не панель) и показать пользователю кандидатов.
3. Xray: скопировать и запустить `server/deploy/setup-xray.sh` с выбранным сайтом
   ```bash
   scp server/deploy/setup-xray.sh root@<IP>:/root/ && ssh root@<IP> XRAY_SNI=<сайт> bash /root/setup-xray.sh
   ```
   Скрипт откажется работать, если конфиг уже есть (защита от случайной смены ключей).
   Если на сервере уже есть свой Xray с VLESS+Reality — этот шаг пропустить и указать его конфиг в `-xray`.
4. vpndeck-server: `server/deploy/deploy.sh root@<IP>`, затем на сервере
   ```bash
   vpndeck-server init -host <IP> -xray /opt/xray-reality-8443/config.json
   read -s P && echo "$P" | vpndeck-server passwd family
   systemctl enable --now vpndeck-server
   vpndeck-server check && vpndeck-server fingerprint
   vpndeck-server devices enable xray-reality-8443   # отдельный UUID на каждый телефон
   ```
   Пароль генерируй сам (`xxxx-xxxx-xxxx` из `abcdefghjkmnpqrstuvwxyz23456789`), передай
   пользователю в чате, в файлы репозитория не пиши.
5. Файрвол: если активен ufw/iptables — открыть 443/tcp и 8448/tcp.
6. Проверка с Mac **с негативным контролем** (см. «Подводные камни»): логин curl'ом
   (`-k`), затем sing-box CLI с mixed-inbound на параметрах из профиля → `curl -x socks5h://…`;
   потом то же с испорченным `public_key` — должно падать с `reality verification failed`.
   CLI собирается так: `cd .deps/sing-box && go build -tags with_gvisor,with_utls,badlinkname,tfogo_checklinkname0 -ldflags=-checklinkname=0 -o ../sing-box-cli ./cmd/sing-box`.
   Плюс: `curl --resolve <сайт>:443:<IP> https://<сайт>/` должен показать настоящий сайт маскировки.
7. Телефоны: в приложении «Выйти» → войти с новым адресом; появится диалог нового ключа.
8. Попроси пользователя проверить **на мобильном интернете** (не Wi-Fi) — именно там DPI.

Удаление vpndeck-server: `systemctl disable --now vpndeck-server && rm -rf /etc/vpndeck /usr/local/bin/vpndeck-server /etc/systemd/system/vpndeck-server.service`.

## Подводные камни (уже наступали)

- **Сам VpnDeck должен быть в `include_package`.** Стек `mixed`: TCP обрабатывает системный
  стек, ответы приложениям уходят из нашего процесса; если наш UID не в туннеле — они теряются
  (DNS при этом работает, т.к. UDP идёт через gVisor, — сбивает с толку). Поэтому свои запросы
  к API `ApiClient` шлёт через `NetworkMonitor.defaultNetwork` (мимо туннеля).
- **`getInterfaces()` пропускает VPN-сети и упавшие интерфейсы.** Иначе умирающий tun при
  переподключении бросает ENODEV, ядро теряет весь список и ничего не маршрутизирует.
- **Мобильные операторы РФ (МегаФон, 2026) режут Reality с маскировкой под большой бренд** на IP хостинга:
  TCP устанавливается, а TLS ClientHello до сервера не доходит (на сервере видны SYN/ACK, но ни одного пакета с данными).
  На Wi-Fi при этом работает. Лечение — маскировка под реальный сайт из той же подсети (`find-sni.py`) и порт 443.
  Подсети VPN-хостингов забиты чужими Reality «под cloudflare/amd/twitch» — именно этот шаблон и ловят.
- **Хостинг может резать входящий IPv4-UDP** (текущий режет всё, кроме ответов DNS): звонки Telegram/WhatsApp
  через VPN не работают. Проверка: STUN-запрос с сервера (`stun.cloudflare.com:3478`) и tcpdump на внешнем интерфейсе.
  IPv6-UDP там работает. Новый хостинг проверять на это до переезда.
- **Xray в Docker запускать с `--user 0:0`**: конфиг 0600 root, а образ по умолчанию nonroot → `permission denied`.
- **Смена параметров Xray (порт, маскировка, ключи):** с версии 0.1.1 приложение при каждом включении VPN
  сначала запрашивает свежий профиль (таймаут 5 с, иначе — кэш), так что достаточно выключить/включить VPN.
  Версия 0.1.0 держала кэш до 6 часов — на ней нужно «Выйти» → войти.
- **Не перегенерировать `/etc/vpndeck/tls.{crt,key}`** — все телефоны увидят «ключ изменился».
- **Проверка «IP совпал с сервером» ничего не доказывает**: Mac пользователя сам выходит в
  интернет через VPN-сервер. Только негативный контроль или логи ядра (`outbound/vless[proxy]`).
- **Проверки туннеля — только HTTPS с корректным сертификатом.** `msftconnecttest.com` по HTTPS отдаёт
  чужой сертификат (Akamai) и не проходит никогда; новые адреса проверяй `curl -s -o /dev/null -w '%{http_code}'`.
- **Не сигналить «сессия сменилась» через `CancellationException`.** Брошенная из цикла сторожа или
  обновления профиля, она молча завершает корутину. `refreshOrCached` возвращает `null`.
- **Миграция на отдельный UUID (`POST /api/v1/device`) может перезапустить Xray** — дольше 5 с таймаута
  подключения. Поэтому при подключении только `GET /profile`, миграция — в фоне через 5 с.
- **`deploy.sh` перезаписывает unit-файл.** Свои правки на сервере — только drop-in (`systemctl edit vpndeck-server`).
- `nc` в Android (toybox) рвёт соединение при EOF stdin: держать stdin открытым
  `(printf '…'; sleep 3) | nc host 80`.
- Соединения Xray видны только в netns контейнера (Docker делает DNAT):
  `nsenter -t $(docker inspect -f '{{.State.Pid}}' xray-reality-8443) -n ss -tn`.
- Логи ядра на телефоне: `cache/box.log` (debug-сборка — уровень debug, `adb shell run-as com.vpndeck cat cache/box.log`;
  release — только warn, и run-as недоступен).
- Тест на эмуляторе: после `adb install -r` VpnDeck сам переподключается (MY_PACKAGE_REPLACED),
  следующий тап по кнопке его **выключит**. Ресивер STOP не экспортирован — `am broadcast` из adb не сработает.
- Проверка восстановления без боевого сервера: поднять на Mac тестовый стенд — `setup-xray.sh` с
  `XRAY_DIR=.deps/… XRAY_PORT=18443 XRAY_NAME=…`, vpndeck-server с `init -host 10.0.2.2 -listen 0.0.0.0:18448`
  (эмулятор видит Mac как 10.0.2.2), сессию в debug-сборку положить через `run-as … shared_prefs/vpndeck.xml`.
  Сменить параметры Xray на ходу, `adb shell svc wifi disable/enable` → в logcat `watchdog: … recovered`.
  В локальном Docker маскировка `www.microsoft.com` не работала (EOF), `www.cloudflare.com` — работала.
- Chrome на эмуляторе требует принять условия — для тестов отмечай `com.android.shell` и гоняй `nc`.

## Правила работы с этим репо

- Коммиты без строки `Co-Authored-By` — пользователь хочет, чтобы коммиты были только от него.
- Перед изменением чего-либо на сервере — спросить. Конфиг Xray и чужие сервисы на сервере не трогать без запроса.
- Ничего секретного в репо: пароли, `token_secret`, ключи Reality, UUID, keystore.

## Текущая установка

Адрес и детали рабочего сервера в репозиторий не пишутся (репо публичное): они в памяти
Claude для этого проекта, иначе — спросить пользователя. На сервере кроме VpnDeck работают
и другие сервисы пользователя — их не трогать. Контейнер Xray исторически называется
`xray-reality-8443` и лежит в `/opt/xray-reality-8443/`, хотя слушает уже 443.

## Устройства (с 0.1.6)

- Устройство — случайный ID установки, не средство авторизации. Токены v2 привязаны к аккаунту, хешу
  пароля, устройству и его UUID. Токен v1, с которым сессия перешла на устройство, привязывается к нему
  же и отзывается вместе с ним. Запись отозванного устройства не удаляется (держит этот токен мёртвым),
  но в лимит 100 не входит. После `devices restore` прежние токены v1/v2 остаются недействительными:
  `legacy_token_hash` сохраняется с `legacy_token_revoked`, телефон входит заново по паролю.
- Управление устройствами меняет только `clients` выбранного VLESS+Reality inbound (порядок ключей
  конфига сохраняется) и перезапускает указанный контейнер; ключи Reality/TLS не меняются. При ошибке — rollback.
- Общий UUID — первый клиент inbound, **не** `vpndeck-device-*`; если общего нет, сессии без устройства
  получают ошибку, а не чужой UUID.
- Один телефон — свой ID на каждый аккаунт (`Store.deviceIdFor`): на `device_other_account`
  приложение заводит новый ID и повторяет запрос один раз.
- Порядок запуска защищён отменой startJob и проверкой wantConnected после запроса. STOP-ресивер
  регистрируется до fetch. `CancellationException` не превращать в ошибку сети.
- Сессия (`shared_prefs/vpndeck.xml`) исключена из бэкапа и переноса на другой телефон (`data_extraction_rules.xml`).

История изменений — `CHANGELOG.md`.
