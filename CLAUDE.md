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

Контракт API: `POST /api/v1/login {username,password}` → `{token, profile}`;
`GET /api/v1/profile` (Bearer) → profile. Формат profile — `server/xray.go` (`VlessServer`)
и `android/.../data/Profile.kt`; менять только синхронно.

## Структура

| Путь | Что |
|---|---|
| `server/*.go` | vpndeck-server + тесты (`go test ./...`) |
| `server/deploy/deploy.sh` | сборка под linux и установка/обновление бинарника по SSH (запуск с Mac) |
| `server/deploy/setup-xray.sh` | создание Xray VLESS+Reality со свежими ключами (запуск на сервере) |
| `server/deploy/find-sni.py` | поиск сайта для маскировки Reality среди соседей по подсети (запуск на сервере) |
| `server/deploy/vpndeck-server.service` | systemd-юнит |
| `scripts/build-libbox.sh` | сборка ядра в `android/app/libs/libbox.aar` (качает исходники в `.deps/`) |
| `android/` | приложение; APK в `android/app/build/outputs/apk/` |

В git **нет**: APK, `libbox.aar`, бинарников, `.deps/`, `dist/`, ключа подписи
(`android/keystore/`, `android/keystore.properties`), `local.properties`.

## Окружение сборки (Mac пользователя)

- Go (есть), JDK 21: `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- Android SDK: `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
  (platform 37.1, build-tools 36.0.0, NDK 28.0.13004108, emulator + образ android-36 arm64)
- gomobile — форк `github.com/sagernet/gomobile@v0.1.13` (ставит build-libbox.sh)
- Связь с GitHub/Google медленная и рвётся: `git clone` повторять, крупные загрузки — в фоне.

```bash
(cd server && go test ./...)
scripts/build-libbox.sh                      # ~1–2 мин, нужен перед первой сборкой APK
cd android && ./gradlew assembleRelease      # подпись — из keystore.properties
```

Если `android/keystore.properties` нет (новая машина) — release подпишется debug-ключом,
и **обновление поверх уже установленных телефонов не встанет**. Ключ хранится у пользователя
отдельно; спроси его, прежде чем генерировать новый.

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
- `nc` в Android (toybox) рвёт соединение при EOF stdin: держать stdin открытым
  `(printf '…'; sleep 3) | nc host 80`.
- Соединения Xray видны только в netns контейнера (Docker делает DNAT):
  `nsenter -t $(docker inspect -f '{{.State.Pid}}' xray-reality-8443) -n ss -tn`.
- Логи ядра на телефоне: `cache/box.log` (debug-сборка — уровень debug, `adb shell run-as com.vpndeck cat cache/box.log`;
  release — только warn, и run-as недоступен).
- Тест на эмуляторе: после `adb install -r` VpnDeck сам переподключается (MY_PACKAGE_REPLACED),
  следующий тап по кнопке его **выключит**. Ресивер STOP не экспортирован — `am broadcast` из adb не сработает.
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
