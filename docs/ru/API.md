# Magic V2Ray API

[English](../API.md) | [Tiếng Việt](../vi/API.md) | [Русский](./API.md) | [简体中文](../zh-CN/API.md) | [繁體中文（台灣）](../zh-TW/API.md) | [繁體中文（香港）](../zh-HK/API.md)

[← README](README.md)

---

## Deep Link для провайдеров

Magic V2Ray регистрирует схемы `magicv2ray://` и `v2rayng://`. Продавцы VPN могут размещать такие ссылки на своём сайте или в сообщениях бота, чтобы клиенты могли настроить всё одним нажатием. Ссылки, открытые за пределами приложения, перед добавлением чего-либо запрашивают подтверждение пользователя; напрямую импортирует только ссылка «Синхронизация» внутри собственного мини-браузера провайдера.

### Добавление самого провайдера: `magicv2ray://add-provider`

```
magicv2ray://add-provider?url=<сайт>&remark=<отображаемое имя>
```

| Параметр | Обязательный | Описание |
|----------|--------------|----------|
| `url` | да | Сайт провайдера. `https://` добавляется автоматически, если отсутствует; принимаются только сайты `https`. |
| `remark` | нет | Название категории (также работает `name`). По умолчанию — имя хоста сайта. Если имя занято, добавляется `(2)`, `(3)`... |

Пример (значения нужно URL-кодировать):

```
magicv2ray://add-provider?url=vless4gtiktok.duckdns.org&remark=Free%20Server%20VLESS
```

После подтверждения пользователем приложение создаёт категорию, получает `<title>` и значок сайта и открывает сайт в мини-браузере. `add-privider` принимается как псевдоним `add-provider`.

### Синхронизация узлов с сайта провайдера

Внутри мини-браузера ссылка следующего вида импортирует подписку в **категорию этого провайдера** (узлы заменяются, URL сохраняется для Reload / автообновления) и возвращает пользователя в приложение:

```
v2rayng://install-config?url=<URL-кодированный URL подписки>
```

`v2rayng://install-sub?...`, а также `magicv2ray://install-config?...` / `magicv2ray://install-sub?...` работают так же. Заметки для авторов сайтов:

- Кодируйте URL подписки с помощью `encodeURIComponent` (он должен начинаться с `http(s)://`). Некодированный `&` обрежет URL.
- Размещайте ссылку за обычной кнопкой или `<a href>`; присвоение `location.href = ...` в JavaScript тоже работает.
- Ссылки на другие домены (вход через Telegram, страницы оплаты...), а также `tg:`, `mailto:`, `tel:` открываются за пределами мини-браузера. Остальные схемы блокируются.

### Добавление подписки без категории провайдера

Вне мини-браузера (в любом браузере или приложении) те же ссылки install после подтверждения пользователем добавляют обычную подписку:

```
v2rayng://install-config?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
magicv2ray://install-sub?url=https%3A%2F%2Fexample.com%2Fsub%2Ftoken
```

### Пример кода для сайта провайдера

```html
<a href="magicv2ray://add-provider?url=example.com&remark=My%20VPN">Добавить в Magic V2Ray</a>

<script>
  const sub = 'https://example.com/sub/USER_TOKEN';
  document.getElementById('sync').href =
    'v2rayng://install-config?url=' + encodeURIComponent(sub);
</script>
<a id="sync">Синхронизация</a>
```

---

## Подключение из других приложений и сайтов

Любое приложение или сайт может попросить Magic V2Ray на время направить трафик через указанный сервер. Этот сервер **временный**: ваши собственные узлы не затрагиваются, а после завершения соединения возвращается ранее выбранный вами узел (и запускается снова, если до этого работал).

| Способ | Формат |
|---|---|
| Ссылка | `magicv2ray://connect?uri=<urlencoded uri>&name=<имя>&exclude=<package,package>` |
| Ссылка | `magicv2ray://disconnect` |
| Нативное приложение | `startActivity` с action `com.huskydg.magicv2ray.action.CONNECT` / `.DISCONNECT` и extras `uri`, `name`, `exclude` |
| Нативное приложение | `sendBroadcast` с тем же action и extras (при connect показывается уведомление «нажмите для проверки», так как Android 10+ запрещает открывать окно подтверждения из фона) |

- **Локальный SOCKS5**: отправьте `uri=socks://127.0.0.1:4311`. Новый механизм не нужен — узлы SOCKS уже поддерживаются.
- **Отображаемое имя** (чип статуса, уведомление, плитка: `RUNNING - <имя>`): параметр `name`, затем имя вызывающего приложения, затем remark из uri, затем его хост. Это лишь подпись, и никакого доверия на её основании не возникает.
- **Защита от петель**: приложение, которое обслуживает порт SOCKS5 и выходит в интернет через это же устройство, нужно исключить из VPN, иначе его трафик зациклится на нём самом. Передайте `exclude=<package>`; проверенный нативный вызывающий, обслуживающий локальный адрес, исключается автоматически. В режиме non-root используется список disallowed-apps VPN, в режиме root uid добавляется в цепочку iptables `XRAY_EXCLUDE_APP`. И то и другое действует только пока длится соединение, независимо от переключателя «Исключить приложения».
- **Завершение**: `magicv2ray://disconnect`, кнопка в уведомлении, плитка быстрых настроек, кнопки «Стоп» / «Отключить» в приложении. Перезагрузка устройства также завершает сессию.
- **Безопасность**: любой сайт может отправить ссылку `connect`, поэтому каждое подключение подтверждается, при этом показываются полный uri, вызывающий и исключённые приложения. Вызывающий считается «проверенным» только если его сообщил Android (никогда не подделываемый `EXTRA_REFERRER`) и он не является браузером. Для нешифрованного узла VLESS / Trojan сохраняется прежнее предупреждение. Вкладка **Права доступа** перечисляет приложения с режимом «всегда доверять»; эта опция предлагается только для явных intent от проверенных нативных приложений (никогда для ссылок и браузеров) и привязана к сертификату подписи приложения. Даже доверенное приложение подтверждается для нешифрованных узлов и для исключений, отличных от него самого.

### События информации и состояния (API level 1)

Приложения могут узнавать, что запущено, и получать уведомление, когда прокси **отключается** (или подключается), независимо от причины: приложение-инициатор, плитка, кнопка в уведомлении, сбой, root-служба.

**1. Info** (версия, поддерживаемый API, текущее состояние)

```java
// A) ContentProvider: синхронно, без UI, можно вызывать из любого потока
Bundle b = getContentResolver().call(Uri.parse("content://com.huskydg.magicv2ray.api"), "getInfo", null, null);
String json = b.getString("json");        // полный JSON, см. ниже
int apiLevel = b.getInt("apiLevel");      // также: versionName, versionCode, mode, connected, known
// "getState" возвращает только состояние. query(content://com.huskydg.magicv2ray.api/info | /state) возвращает одну строку.

// B) Ordered broadcast: ответ — JSON (result data) + extras json / versionName / apiLevel
Intent i = new Intent("com.huskydg.magicv2ray.action.GET_INFO").setPackage("com.huskydg.magicv2ray");
sendOrderedBroadcast(i, null, new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent r) { String json = getResultData(); }   // null = не установлено
}, null, Activity.RESULT_CANCELED, null, null);
```

Android 11+: добавьте `<queries><package android:name="com.huskydg.magicv2ray"/></queries>` в манифест вызывающего приложения.

```json
{ "app": "Magic V2Ray", "package": "com.huskydg.magicv2ray", "versionName": "v2.5-alpha", "versionCode": 192, "mode": "root",
  "api": { "level": 3,
           "actions": ["com.huskydg.magicv2ray.action.CONNECT", "...DISCONNECT", "...GET_INFO"],
           "schemes": ["magicv2ray://connect", "magicv2ray://disconnect"],
           "events": ["com.huskydg.magicv2ray.event.STATE_CHANGED"],
           "extras": ["uri", "name", "exclude", "overrides", "overridesUri", "assets"],
           "overrides": ["traffic", "network", "exclude", "routing", "hosts", "assets"],
           "provider": { "authority": "com.huskydg.magicv2ray.api", "methods": ["getInfo", "getState", "getNodes", "getActiveNode", "getNodeAccess"], "uris": ["..."] },
           "features": ["connect", "disconnect", "overrides", "exclude", "info", "state_events", "state_provider", "nodes", "node_access_request", "connect_freedom", "connect_chain", "connect_nodeid", "overrides_unlimited", "overrides_uri", "custom_assets", "connect_policygroup"] },
  "state": { "connected": true, "known": true, "mode": "root",
             "session": { "active": true, "name": "My server", "caller": "com.example.app", "startedAt": 1731670000000 } } }
```

| Поле | Значение |
|---|---|
| `versionName`, `versionCode` | Установленная версия Magic V2Ray |
| `mode` | `root` или `nonroot` |
| `api.level` | Увеличивается, когда в API появляется что-то, что вызывающим приложениям нужно проверять |
| `api.actions`, `schemes`, `extras`, `overrides` | Поддерживаемые actions, deep link, extras и группы `overrides` |
| `api.events`, `api.provider` | Рассылаемые события и способ доступа к provider |
| `api.features` | Список возможностей для проверки по отдельности |
| `state.connected` | Запущен ли прокси |
| `state.known` | `false`, если в режиме root состояние ещё не удалось прочитать (нет root-доступа, служба не запущена); тогда `connected` — просто значение `false` по умолчанию |
| `state.session` | Текущая внешняя сессия (имя, вызывающий, время начала); без адреса сервера и без настроек |

Проверяйте `api.level` или имя в `features`, а не версию приложения. В возвращаемых данных никогда нет адреса сервера и настроек.

**2. События состояния**: broadcast `com.huskydg.magicv2ray.event.STATE_CHANGED`

| Extra | Значение |
|---|---|
| `state` / `connected` | `"connected"` / `"disconnected"` и `true` / `false` |
| `reason` | `started`, `session_started`, `session_ended` (внешняя сессия завершена), `stopped` (обычная остановка), `error` (ошибка движка, потеря разрешения VPN), `crashed` |
| `session`, `sessionName`, `caller` | Связана ли внешняя сессия / её имя / пакет приложения, начавшего её |
| `mode` | `root` или `nonroot` |
| `time`, `seq` | Время (epoch ms) и порядковый номер, растёт для каждого процесса Magic V2Ray |

```java
registerReceiver(new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
        if (!i.getBooleanExtra("connected", false)) {
            onMagicV2RayDisconnected(i.getStringExtra("reason"));
        }
    }
}, new IntentFilter("com.huskydg.magicv2ray.event.STATE_CHANGED"), Context.RECEIVER_EXPORTED);
```

- **Кто получает**: receiver, зарегистрированные во время работы (`registerReceiver`), в любом приложении получают это как обычный broadcast; приложение — владелец сессии может получить одно и то же событие дважды, поэтому отсеивайте дубликаты по `seq` + `time`. Приложению, начавшему внешнюю сессию, событие также отправляется **напрямую**, поэтому receiver, объявленный в манифесте, тоже сработает. Для сессий, начатых через `sendBroadcast` на Android ниже 14, Magic V2Ray не знает, какое приложение вызвало, — используйте receiver, зарегистрированный во время работы, или observer ниже.
- **Observer вместо receiver**: `registerContentObserver(Uri.parse("content://com.huskydg.magicv2ray.api/state"), false, observer)`, а при срабатывании прочитайте `getState`.
- **Момент уведомления**: отключение сообщается, только если через 2 секунды движок всё ещё остановлен, поэтому перезапуск или перезагрузка конфигурации (смена узла, смена настроек) не считаются отключением. Завершение внешней сессии (`session_ended`) сообщается сразу. Неудачный запуск, когда прокси до этого не работал, события не создаёт.
- **Режим root**: root-служба вызывает `am broadcast ... ENGINE_CHANGED` для приложения, приложение перечитывает реальное состояние и только потом рассылает событие. Содержимому этого broadcast не доверяют, поэтому поддельное сообщение от другого приложения лишь заставит повторно проверить состояние. Так же ловятся сбои и команды остановки из shell.
- **Ограничение**: в режиме non-root, если процесс Magic V2Ray убит системой, VPN умирает вместе с ним и ни одно событие отправить нельзя. Если это важно, периодически вызывайте `getState`.
- **Приватность**: события и info содержат только состояние — без адреса сервера, uri узла и настроек.

### Узлы и специальные connect-uri (API level 2, только для приложений)

`api.level` равен `2`, когда присутствуют features `nodes`, `node_access_request`, `connect_freedom`, `connect_chain` и `connect_nodeid`.

**1. Разрешение.** Приложение может читать узлы только после согласия пользователя. Вызывающий — это пакет, который Android сообщает для самого вызова, поэтому сайт, ссылка или broadcast никогда не прочитают узлы, и никто не может выдать себя за другое приложение. Пользователь выбирает, **что** видит приложение и **на какой срок**:

| Выбор | Значение |
|---|---|
| Анонимные данные (по умолчанию) | имя, протокол и id; узел выглядит как `nodeid://<id>` вместо `vless://...`. Без сервера, порта и учётных данных. |
| Полные данные узлов | настоящие ссылки узлов (`vless://...`), включая серверы и учётные данные. |
| Разрешить один раз | действует 10 минут, только в памяти. |
| Всегда разрешать | хранится до отзыва на вкладке **Доступ**; привязано к сертификату подписи приложения. |
| Отмена | ничего не выдаётся. |

```java
// 1) ask. status: ok | permission_required | unknown_caller | unavailable
Uri api = Uri.parse("content://com.huskydg.magicv2ray.api");
Bundle b = getContentResolver().call(api, "getNodes", null, null);
if ("permission_required".equals(b.getString("status"))) {
    // 2) the permission dialog: MUST be startActivityForResult (that is how the caller is verified)
    Intent i = new Intent("com.huskydg.magicv2ray.action.REQUEST_NODE_ACCESS").setPackage("com.huskydg.magicv2ray");
    i.putExtra("level", "full");          // optional: what the app would like; the dialog still starts on "anonymous"
    startActivityForResult(i, 42);        // result: granted, level ("anonymous" | "full"), persistent
}
// 3) read (after RESULT_OK, or any time later)
String json = getContentResolver().call(api, "getNodes", null, null).getString("json");
```

| Метод | Возвращает |
|---|---|
| `getNodeAccess` | `granted`, `level`, `persistent`; никогда не показывает диалог |
| `getNodes` | `json` = `{level, activeId, nodes:[{id, name, protocol, uri, (только полный: category, address, port)}]}`, а также `count`, `activeId` |
| `getActiveNode` | `json` = `{connected, known, source, session?, node}`, а также `connected`, `source` |

`source`: `session` (приложение временно задало узел), `selected` (выбор самого пользователя) или `direct` (встроенный узел без прокси). Анонимный читатель никогда не видит uri узла, который задало приложение и который не входит в узлы пользователя: он приходит как `<scheme>://hidden`. Цепочка всегда выглядит как `chain://localhost/?hopList=...`.

**2. Connect-uri.** `uri` запроса connect (ссылка, intent или broadcast) может быть также:

| uri | Значение |
|---|---|
| `freedom://localhost` | встроенный узел «без прокси»: xray как чистый маршрутизатор, все правила маршрутизации продолжают действовать |
| `chain://localhost/?hopList=<urlencode(JSON array of node uris)>` | цепочка прокси из 2–16 узлов: `["vless://...", "vmess://...", "socks://...", ...]`. Первый элемент устройство набирает напрямую, каждый следующий узел туннелируется через предыдущий, последний — выходной. |
| `nodeid://<id>` | один из собственных узлов пользователя, сам по себе или как звено цепочки. Только для проверенного приложения, которому разрешено читать узлы (любой уровень), поэтому приложение с анонимным доступом может использовать узел, которого никогда не видит. |

Обычное подтверждение сохраняется: оно показывает протоколы цепочки (или «напрямую»), а первый узел определяет, нужно ли исключить приложение из VPN (у `freedom://` нет сервера, на который мог бы замкнуться трафик).

### Использование из Termux (или любого shell)

Shell не может зарегистрировать receiver и без root не может обратиться к provider, поэтому используется `am`. Приведённые ниже команды следуют из принципа работы API; команда info и цикл опроса зависят от того, что `am` в Termux выводит результат broadcast так же, как системный `am`.

```sh
# Info: `am broadcast` — это ordered broadcast, ответ выводится в виде data="<json>"
am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray
# С root можно обратиться к provider напрямую:
su -c 'content call --uri content://com.huskydg.magicv2ray.api --method getInfo'

# Connect / disconnect (connect по-прежнему подтверждается пользователем)
am start -a com.huskydg.magicv2ray.action.CONNECT -p com.huskydg.magicv2ray \
  --es uri 'socks://127.0.0.1:5353' --es name 'Termux' --es exclude com.termux
am start -a com.huskydg.magicv2ray.action.DISCONNECT -p com.huskydg.magicv2ray
```

Shell не получает `STATE_CHANGED`, поэтому опрашивайте info. Этот скрипт проверяет API level, а затем сообщает о каждом изменении, пока Magic V2Ray не отключится:

```sh
#!/data/data/com.termux/files/usr/bin/sh
info() { am broadcast -a com.huskydg.magicv2ray.action.GET_INFO -p com.huskydg.magicv2ray 2>/dev/null \
         | sed -n 's/.*data="\(.*\)".*/\1/p'; }
J=$(info)
[ -n "$J" ] || { echo "Magic V2Ray не установлен или слишком старый (нет API level 1)"; exit 1; }
echo "$J" | jq -r '"Magic V2Ray \(.versionName), API \(.api.level), \(.mode)"'
last=
while sleep 2; do
  c=$(info | jq -r '.state.connected')
  [ "$c" = "$last" ] || { echo "connected=$c"; last=$c; }
  [ "$c" = "false" ] && break
done
echo "Magic V2Ray отключён"
```

Примечания: начиная с Android 10, для запуска `am start` из фона Termux нужно разрешение «Поверх других приложений». Сможет ли Magic V2Ray определить Termux как вызывающего (и предложить «Всегда доверять»), зависит от того, что Android сообщает для `am`; неизвестный вызывающий всегда подтверждается. Установите `jq` командой `pkg install jq`.

### Временные настройки (`overrides`)

Помимо узла, `connect` может содержать параметр `overrides`, который **временно изменяет** настройки трафика, настройки сети, исключённые приложения, маршрутизацию и пользовательские hosts на время соединения. При наличии `overrides` параметр `uri` необязателен (тогда используется выбранный вами узел).

- Ссылка: `magicv2ray://connect?uri=...&overrides=<base64url от JSON>`; intent / broadcast: extra `overrides` — это строка JSON.
- Принимаются только ключи из белого списка с правильным типом и диапазоном значений, всё остальное отбрасывается (см. `ExternalSession.sanitizeOverrides`). Нельзя переопределить: `exposeSocks*`, профессиональный режим, язык / тему. Правила маршрутизации принимают в `outboundTag` только `proxy`, `direct` или `block`.

```json
{
  "traffic": { "mux": true, "mtu": 1400, "directDns": "quic+local://223.5.5.5" },
  "network": { "networkMode": 1, "allowTether": false, "bypassIfaces": ["tailscale0"], "bypassVpnService": true },
  "exclude": { "apps": ["com.example.app"], "mode": "add", "enabled": true },
  "routing": { "domainStrategy": "IPIfNonMatch", "mode": "prepend",
               "rules": [ { "domain": "geosite:google", "outboundTag": "proxy", "remarks": "temp" },
                          { "process": ["com.example.app"], "outboundTag": "direct", "remarks": "app direct" },
                           { "sourceIP": "192.168.43.0/24", "sourcePort": "1000-2000", "outboundTag": "block", "remarks": "hotspot clients" } ] },
  "hosts":   { "mode": "merge", "entries": { "example.com": "1.2.3.4", "b.com": ["::1", "10.0.0.1"] } }
}
```

**Приватность: временные настройки НИКОГДА не сохраняются.**

| Режим | Где хранится сессия | Куда никогда не записывается |
|---|---|---|
| Root | `/mnt/runtime/magicv2ray/run/override/` (tmpfs, исчезает после перезагрузки) | `/data/adb/magic_v2ray` |
| Non-root | `cache/override/` | `files/data/` (даже `config.v2.json`) |

- Движок читает `config.v2.json`, `settings.base64`, `excludelist.txt` и `bypassIface.txt` из каталога сессии **вместо** ваших одноимённых файлов; ваши файлы только читаются, но не изменяются. Отключение = удаление каталога сессии и перезапуск движка с вашей собственной конфигурацией.
- Ожидающий подтверждения запрос хранится только в памяти; сессия хранит лишь метаданные (имя, вызывающий, список исключений), но не сервер и не изменённые значения.
- Сессия **не возобновляется после перезагрузки** (root: `/mnt/runtime` очищается, а служба чистит каталог при запуске; non-root: VpnService имеет режим `START_NOT_STICKY`, и устаревшая сессия отбрасывается при открытии приложения). Приложению, которому она нужна снова, необходимо отправлять `connect` после каждой загрузки. Временная сессия также не меняет флаг `enabled`, определяющий, что возобновится при загрузке.
- Запрос с `overrides` подтверждается (в диалоге перечислено, что изменится). Приложение, которому выбрано **Всегда разрешать**, **больше не спрашивается при изменении настроек**: новые или изменённые `overrides` применяются без запроса. Ссылкам и браузерам нельзя доверять никогда. Запросы каждого приложения и принятые решения записываются в его журнал действий (вкладка разрешений доступа, кнопка журнала на карточке приложения).

---

### Неограниченные правила и hosts, свои файлы `.dat` (API level 3, только для приложений)

`api.level` равен `3`, когда есть возможности `overrides_unlimited`, `overrides_uri` и `custom_assets`. Всё описанное выше работает как раньше.

**1. Без ограничений на правила и hosts.** `routing.rules` и `hosts.entries` принимают любое число записей, у каждого host — любое число адресов, а `domain` / `ip` правила может быть строкой через запятую **или JSON-массивом строк** (длинный список проще записать массивом). Остаётся только предел по размеру: intent не вмещает больше примерно 1 МБ, а память телефона ограничена:

| Как передаются `overrides` | Предел |
|---|---|
| extra `overrides` / deep link | 256 КБ JSON (как раньше, но количество записей уже не важно) |
| `overridesUri` (файл) | 64 МБ JSON |

Пределы сообщаются в `getInfo`, раздел `api.limits`. Отбрасываются только некорректные записи (правило с `outboundTag`, отличным от `proxy` / `direct` / `block`, неверный домен или адрес), но никогда из-за длины списка.

**2. `overridesUri`: `overrides` в файле.** `content://` URI файла с тем же JSON, что и `overrides`. Если он указан, он **заменяет** extra `overrides`. Ссылки и браузеры его использовать не могут.

**3. `assets`: свои базы `.dat`.** `geoip.dat`, `geosite.dat` и любые другие файлы, доступные через `ext:<файл>.dat:<тег>`, можно передать на время подключения. Extra — JSON-массив `[{"name": "geoip.dat", "uri": "content://..."}, ...]`.

- `name` — простое имя файла, оканчивающееся на `.dat` (буквы, цифры, `.`, `_`, `-`). Файлы, которые вы **не** передали, продолжают работать: движок сначала видит ваши `assets`, а для остальных имён — базы пользователя, поэтому при передаче только `geosite.dat` файл `geoip.dat` остаётся прежним.
- Каждый файл проверяется при получении запроса (он должен быть похож на гео-базу, а не на HTML-страницу или zip). Не более 32 файлов, по 256 МБ каждый и 512 МБ всего; в режиме root они лежат в ОЗУ на время сессии.
- Файлы заменяются, а не объединяются: ваш `geosite.dat` используется **вместо** пользовательского для всех правил `geosite:`, включая собственные правила маршрутизации пользователя.

```
{ "uri": "freedom://localhost", "assets": [ {"name": "geosite.dat", "uri": "content://..."} ] }
```

**Выдача доступа к файлам.** Принимаются только `content://` URI, и каждый из них должен быть также в `ClipData` intent с `FLAG_GRANT_READ_URI_PERMISSION`; URI, не выданный таким образом, отклоняется. Используйте `startActivity` или `sendBroadcast` с действием `CONNECT`; shell (Termux, `adb`) не может выдавать URI и поэтому не может отправлять файлы, как и deep link.

```java
// Files you own, shared through your FileProvider (or any ContentProvider that honours URI grants)
Uri ov    = FileProvider.getUriForFile(this, getPackageName() + ".files", overridesJsonFile);
Uri geoip = FileProvider.getUriForFile(this, getPackageName() + ".files", geoipDatFile);
Uri site  = FileProvider.getUriForFile(this, getPackageName() + ".files", geositeDatFile);

JSONArray assets = new JSONArray()
    .put(new JSONObject().put("name", "geoip.dat").put("uri", geoip.toString()))
    .put(new JSONObject().put("name", "geosite.dat").put("uri", site.toString()));

Intent i = new Intent("com.huskydg.magicv2ray.action.CONNECT").setPackage("com.huskydg.magicv2ray");
i.putExtra("uri", "freedom://localhost");                 // optional, as before
i.putExtra("overridesUri", ov.toString());                // the same JSON as `overrides`, from a file
i.putExtra("assets", assets.toString());
ClipData clip = ClipData.newRawUri("mv", ov);              // EVERY uri used above must be in the ClipData
clip.addItem(new ClipData.Item(geoip));
clip.addItem(new ClipData.Item(site));
i.setClipData(clip);
i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
startActivity(i);                                         // or sendBroadcast(i): the request then waits for a tap
```

**Конфиденциальность и согласие.**

- Свои базы меняют то, что совпадает с каждым правилом `geoip:` / `geosite:` / `ext:`, поэтому диалог подтверждения говорит об этом прямо и перечисляет каждый файл с размером и началом SHA-256. Ничего не применяется без ответа пользователя, кроме приложения с «всегда разрешать», которое больше не спрашивается, какие бы `overrides` и файлы оно ни присылало.
- Запрос ждёт не более 10 минут. Пока он ждёт, файлы лежат в приватном кэше приложения, проверенные и хешированные; **отказ, ошибка или тайм-аут удаляют их сразу**.
- После согласия они переносятся в каталог сессии (root: tmpfs в `/mnt/runtime`, исчезает при перезагрузке; non-root: каталог кэша) и **никогда** не пишутся в каталог данных или в `location_assets` пользователя. Отключение, перезагрузка или сбой удаляют их, и движок возвращается к базам пользователя.
- Если файл не читается или не проходит проверку, ничего не подключается, а пользователь видит причину.

**Проверка:** `getInfo` -> `api.level >= 3` или `"custom_assets"` в `api.features`.

---

### Группа политик как узел: `policygroup://` (API level 3)

Возможность `connect_policygroup`. `uri` подключения может быть **группой политик, заданной самим запросом**: Xray распределяет весь трафик между несколькими узлами одной из своих стратегий балансировщика. Работает как `chain://`, но узлы здесь — альтернативы, а не последовательные звенья.

```
policygroup://localhost/?type=<стратегия>&members=<urlencode(JSON-массив uri узлов)>
```

| Часть | Значение |
|---|---|
| `type` | `random` (по умолчанию), `roundRobin`, `leastPing`, `leastLoad` |
| `members` | от 2 до 256 URI узлов (`vless://`, `trojan://`, `ss://`, ...) или `nodeid://<id>` |

- Участниками не могут быть `chain://`, `freedom://` и другая группа. Участники `nodeid://` подчиняются тому же правилу, что и в цепочке: их может использовать только проверенное приложение с доступом к узлам; настоящий узел пользователя виден в диалоге, но приложению не возвращается.
- `leastPing` и `leastLoad` включают observatory Xray (проверки `https://www.google.com/generate_204`); если все участники считаются недоступными, запасным служит первый.
- Группа существует только в этой сессии. Она не сохраняется, не появляется среди узлов и групп пользователя и не может с ними конфликтовать. Диалог подтверждения показывает стратегию и число узлов; участники VLESS или Trojan без шифрования вызывают обычное предупреждение. `overrides`, `exclude` и `assets` работают как с любым другим узлом.
- Для правила «приложение обслуживает локальный SOCKS5» берётся участник, указывающий на это устройство, поэтому вызывающее приложение автоматически исключается из VPN.

```java
JSONArray members = new JSONArray()
    .put("vless://...@a.example.com:443?security=reality&...#A")
    .put("trojan://...@b.example.com:443?security=tls#B")
    .put("nodeid://3f9c2a");                      // the user's own node: needs node access (see Nodes)

String uri = "policygroup://localhost/?type=leastPing&members=" + Uri.encode(members.toString());

Intent i = new Intent("com.huskydg.magicv2ray.action.CONNECT").setPackage("com.huskydg.magicv2ray");
i.putExtra("uri", uri);
startActivity(i);
```

Проверка через `getInfo`: `api.connectUris` содержит эту форму, `api.features` — `connect_policygroup`. В `activeNode` группа возвращается со своими участниками (обезличенными для приложений без полного доступа к узлам).
