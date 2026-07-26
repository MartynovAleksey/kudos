# kudos

`kudos` — лёгкий stateless Spring Boot API для работы с HDFS, Kyuubi/Spark SQL, HBase и Apache Ozone от имени пользователя, прошедшего LDAP-аутентификацию.

В репозитории также находится полностью контейнеризированный одноузловой тестовый стенд с FreeIPA и Kerberos. По умолчанию стенд запускает официальный Hue из Docker Hub на порту `8082` как визуальный и поведенческий эталон. Собираемый из исходников Hue временно выключен и доступен только через профиль `hue`.

> **Важно:** логин `admin` и пароль `K8SparkAdmin2026Secure!` являются публичными демонстрационными реквизитами этого репозитория. Они сложнее стандартного тестового пароля и не требуют смены после развёртывания стенда, но не должны использоваться в production.

## Состояние функциональности

Приложение предоставляет:

- LDAP-аутентификацию через HTTP Basic;
- выполнение Spark SQL через Kerberized Kyuubi по Hive JDBC;
- просмотр HDFS через WebHDFS с SPNEGO;
- обращение к HBase через нативный `hbase-client` и Kerberos RPC;
- просмотр Apache Ozone через `ofs://` и Kerberos;
- отдельный неизменённый UI Hue из Docker Hub для сравнения интерфейса.

Ролевой и объектной авторизации в Java-приложении пока нет. Все API, кроме health endpoint, требуют успешной LDAP-аутентификации, а обращения к кластерным сервисам выполняются с Kerberos identity этого же пользователя.

## Быстрый запуск тестового окружения

### Требования

- macOS на Intel или Apple Silicon M1/M2/M3/M4;
- актуальный Docker Desktop с Docker Compose v2;
- рекомендуется не менее 12 ГБ памяти, доступной Docker Desktop;
- рекомендуется около 20 ГБ свободного дискового пространства для образов, build cache и named volumes;
- свободные порты из таблицы ниже.

Для запуска только Docker-окружения Java и Maven на хосте не нужны. Для локального запуска `mvn test` требуются JDK 21 и Maven 3.9+.

На Apple Silicon Docker Desktop должен уметь запускать `linux/amd64`-образы. Поддержка эмуляции обычно включена по умолчанию. Kyuubi, Hadoop, Ozone и официальный Hue работают как `linux/amd64`; FreeIPA, HBase и Java-приложение собираются под архитектуру хоста.

### Первый запуск

Выполните из Terminal:

```bash
cd k8spark-ui
chmod +x scripts/*.sh
./scripts/run-test-env.sh -d
```

Скрипт:

1. проверяет доступность Docker Desktop;
2. определяет архитектуру Mac;
3. удаляет только устаревшие локальные образы `freeipa` и `app`, собранные под другую архитектуру;
4. сбрасывает `DOCKER_DEFAULT_PLATFORM` только для этого запуска;
5. использует локальную Docker-конфигурацию без macOS credential helper;
6. выполняет `docker compose up --build --remove-orphans`.

Первый запуск скачивает базовые образы и Maven-зависимости и может занять 10–30 минут. Этап `Downloading Maven dependencies` выводит полный Maven progress и не является зависанием. Инициализация нового FreeIPA realm обычно занимает ещё 3–5 минут.

Следите за состоянием контейнеров:

```bash
docker compose ps
```

Основные сервисы должны перейти в состояние `healthy`, а `hue-reference` — в состояние `running`. Java-приложение создаётся только после успешных health checks FreeIPA, Kyuubi, HDFS, HBase и Ozone.

Проверьте доступные HTTP endpoints:

```bash
curl --fail --silent --resolve app.test.local:8443:127.0.0.1 \
  --cacert <(docker compose exec -T freeipa cat /shared/ca.crt) \
  https://app.test.local:8443/actuator/health
curl --fail --silent --location http://localhost:8082/ | grep -i hue
```

Ожидаемый health response:

```json
{"status":"UP"}
```

### Обычный перезапуск

Named volumes сохраняют realm, keytabs и данные сервисов:

```bash
cd k8spark-ui
docker compose down --remove-orphans
./scripts/run-test-env.sh -d
```

### Остановка

```bash
cd k8spark-ui
docker compose down --remove-orphans
```

### Полный сброс тестового стенда

Следующая команда безвозвратно удаляет тестовый FreeIPA realm, keytabs и данные HDFS, HBase и Ozone:

```bash
cd k8spark-ui
docker compose down --volumes --remove-orphans
./scripts/run-test-env.sh -d
```

Используйте полный сброс после несовместимого изменения FreeIPA bootstrap или формата данных сервисов. Для обычного рестарта удалять volumes не нужно.

### Логи и диагностика старта

```bash
docker compose logs --tail=200 freeipa
docker compose logs --tail=200 kyuubi
docker compose logs --tail=200 hdfs
docker compose logs --tail=200 hbase
docker compose logs --tail=200 ozone
docker compose logs --tail=200 app
docker compose logs --tail=200 hue-reference
```

Для непрерывного просмотра добавьте `--follow`, например:

```bash
docker compose logs --follow --tail=100 ozone
```

## Автоматическое тестирование

### Unit и Spring context tests

```bash
cd k8spark-ui
mvn test
```

### Полный функциональный Kerberos-тест

После старта контейнеров выполните:

```bash
cd k8spark-ui
./scripts/test-kerberos-services.sh
```

Скрипт проверяет не только TCP-порты, но и реальные data paths от имени `admin@TEST.LOCAL`:

- LDAP bind и получение Kerberos TGT по паролю;
- Kyuubi JDBC SQL;
- WebHDFS SPNEGO create/read/delete;
- Ozone Kerberos put/get и OFS;
- HBase Kerberos RPC create/put/get/drop;
- Spring Boot health endpoint;
- страницу Docker Hub Hue reference.

Успешный прогон завершается сообщением:

```text
All Kerberos functional checks passed.
```

### Десять циклов рестарта

```bash
cd k8spark-ui
./scripts/test-start-cycles.sh 10
```

Каждый цикл сохраняет named volumes, останавливает и заново запускает уже собранные образы, ждёт health checks, проверяет опубликованные порты и выполняет полный `test-kerberos-services.sh`. Число циклов можно изменить положительным целым аргументом.

### Нагрузочные данные для HBase-браузера

`hbase-loadgen.sh` создаёт через API приложения таблицы с сильно разным размером, чтобы вручную проверить UI на масштабе: много таблиц (фильтр и скролл списка), таблицы с большим числом строк (курсорная пагинация) и «широкие» строки с тысячами колонок (горизонтальный скролл и ограничение числа видимых колонок). Масштаб параметризуется; значения по умолчанию — представительная матрица, отрабатывающая за несколько минут на одноузловом стенде.

```bash
cd k8spark-ui
./scripts/hbase-loadgen.sh              # засеять представительную матрицу
MODE=clean ./scripts/hbase-loadgen.sh   # удалить всё, что создал генератор
```

Литеральные 5000 таблиц × до 100000 строк — это сотни млн ячеек и не рекомендуется на одноузловом Docker HBase; при необходимости масштаб поднимается переменными `MANY_TABLES`, `ROW_SIZES`, `COL_SIZES`.

## Ручное тестирование каждого компонента

Все команды этого раздела выполняются из корня проекта при работающем окружении:

```bash
cd k8spark-ui
docker compose ps
```

### 1. FreeIPA: LDAP и Kerberos

Проверьте получение TGT по паролю, содержимое credential cache и LDAP bind:

```bash
docker compose exec -T \
  -e TEST_ADMIN_PASSWORD='K8SparkAdmin2026Secure!' \
  freeipa bash -lc '
    set -euo pipefail
    kdestroy 2>/dev/null || true
    printf "%s\n" "$TEST_ADMIN_PASSWORD" | kinit admin@TEST.LOCAL
    klist
    ldapwhoami -x \
      -H ldap://freeipa.test.local \
      -D uid=admin,cn=users,cn=accounts,dc=test,dc=local \
      -w "$TEST_ADMIN_PASSWORD"
  '
```

Ожидается principal `admin@TEST.LOCAL` в `klist` и LDAP DN пользователя `admin` в выводе `ldapwhoami`.

Приложение keytab не использует — оно получает TGT пользователя по паролю при входе (см. «Модель безопасности»). Отдельно проверьте, что KDC выдаёт билет по keytab из тома `kerberos-shared`, которым пользуются ручные клиентские тесты сервисов ниже:

```bash
docker compose exec -T freeipa bash -lc '
  set -euo pipefail
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  klist -s
  echo "Kerberos keytab login: OK"
'
```

### 2. Kyuubi: JDBC SQL с Kerberos

```bash
docker compose exec -T kyuubi bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  /opt/kyuubi/bin/beeline \
    -u "jdbc:hive2://kyuubi.test.local:10009/default;principal=kyuubi/kyuubi.test.local@TEST.LOCAL" \
    --silent=true \
    --showHeader=false \
    --outputformat=csv2 \
    -e "SELECT 40 + 2 AS result"
'
```

Ожидаемая строка результата:

```text
42
```

Это реальный Hive JDBC handshake с сервисным principal Kyuubi, а не проверка открытого порта.

### 3. HDFS 3.4.2: WebHDFS SPNEGO create/read/delete

Проверка выполняется именно через HTTP WebHDFS. Флаг `--negotiate` включает SPNEGO, а `--location-trusted` сохраняет Kerberos authentication при redirect от NameNode к DataNode.

```bash
docker compose exec -T hdfs bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL

  printf "hdfs-webhdfs-spnego-ok\n" >/tmp/k8spark-hdfs-in
  base=http://hdfs.test.local:9870/webhdfs/v1

  curl --fail --silent --negotiate -u : \
    -X PUT "$base/user/admin?op=MKDIRS"

  curl --fail --silent --location-trusted --negotiate -u : \
    -X PUT \
    --upload-file /tmp/k8spark-hdfs-in \
    "$base/user/admin/k8spark-manual-test?op=CREATE&overwrite=true"

  curl --fail --silent --location-trusted --negotiate -u : \
    "$base/user/admin/k8spark-manual-test?op=OPEN"

  curl --fail --silent --negotiate -u : \
    -X DELETE \
    "$base/user/admin/k8spark-manual-test?op=DELETE"
'
```

В выводе чтения должна присутствовать строка:

```text
hdfs-webhdfs-spnego-ok
```

### 4. Apache Ozone 2.0.0: Kerberos Object Store put/get

```bash
docker compose exec -T ozone bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  export OZONE_CONF_DIR=/opt/hadoop/etc/hadoop
  export HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
  export HADOOP_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  export OZONE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL

  ozone sh volume create /k8sparkmanual --user=admin 2>/dev/null || true
  ozone sh bucket create /k8sparkmanual/files 2>/dev/null || true

  printf "ozone-object-store-ok\n" >/tmp/k8spark-ozone-in
  rm -f /tmp/k8spark-ozone-out
  ozone sh key put \
    /k8sparkmanual/files/manual-key \
    /tmp/k8spark-ozone-in
  ozone sh key get \
    /k8sparkmanual/files/manual-key \
    /tmp/k8spark-ozone-out
  cmp /tmp/k8spark-ozone-in /tmp/k8spark-ozone-out
  cat /tmp/k8spark-ozone-out
  ozone sh key delete /k8sparkmanual/files/manual-key
'
```

Ожидается строка `ozone-object-store-ok` и нулевой exit code.

### 5. Apache Ozone: OFS put/read/delete с Kerberos

Следующая проверка использует Hadoop-compatible `ofs://`, то есть тот же интерфейс, через который работает `OzoneService` Java-приложения:

```bash
docker compose exec -T ozone bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  export OZONE_CONF_DIR=/opt/hadoop/etc/hadoop
  export HADOOP_CONF_DIR=/opt/hadoop/etc/hadoop
  export HADOOP_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  export OZONE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL

  ozone sh volume create /k8sparkmanual --user=admin 2>/dev/null || true
  ozone sh bucket create /k8sparkmanual/files 2>/dev/null || true

  printf "ozone-ofs-ok\n" >/tmp/k8spark-ofs-in
  ozone fs -fs ofs://ozone.test.local/ \
    -put -f /tmp/k8spark-ofs-in /k8sparkmanual/files/manual-ofs-key
  ozone fs -fs ofs://ozone.test.local/ \
    -cat /k8sparkmanual/files/manual-ofs-key
  ozone fs -fs ofs://ozone.test.local/ \
    -rm -skipTrash /k8sparkmanual/files/manual-ofs-key
'
```

Ожидаемая строка:

```text
ozone-ofs-ok
```

Тестовый стенд использует один Ozone DataNode, поэтому клиентская и серверная конфигурация задаёт одноузловую репликацию. Такая схема предназначена только для локальных тестов и не является production-топологией.

### 6. HBase 2.6.2: Kerberos RPC put/get

Команды ниже используют нативный HBase RPC client. Перед тестом остаток одноимённой таблицы удаляется, после теста таблица также очищается.

```bash
docker compose exec -T hbase bash -lc '
  set -euo pipefail
  export KRB5_CONFIG=/shared/krb5.conf
  export HBASE_HOME=/opt/hbase
  export HBASE_CONF_DIR=/opt/hbase/conf
  export HBASE_OPTS="-Djava.security.krb5.conf=/shared/krb5.conf"
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL

  printf "disable '\''k8spark_manual'\''\ndrop '\''k8spark_manual'\''\n" \
    | /opt/hbase/bin/hbase shell -n >/dev/null 2>&1 || true

  printf "create '\''k8spark_manual'\'', '\''d'\''\nput '\''k8spark_manual'\'', '\''row1'\'', '\''d:value'\'', '\''hbase-kerberos-rpc-ok'\''\nget '\''k8spark_manual'\'', '\''row1'\''\ndisable '\''k8spark_manual'\''\ndrop '\''k8spark_manual'\''\n" \
    | /opt/hbase/bin/hbase shell -n
'
```

В результате `get` должна появиться строка:

```text
value=hbase-kerberos-rpc-ok
```

### 7. Spring Boot API с LDAP и user Kerberos identity

Приложение работает только по HTTPS на `app.test.local:8443`. Сохраните CA стенда и добавьте его к каждому запросу через `--resolve`/`--cacert`:

```bash
docker compose exec -T freeipa cat /shared/ca.crt > /tmp/k8spark-ca.crt
resolve=(--resolve app.test.local:8443:127.0.0.1 --cacert /tmp/k8spark-ca.crt)
```

Health endpoint открыт без аутентификации:

```bash
curl --fail --silent "${resolve[@]}" https://app.test.local:8443/actuator/health
```

SQL через Kyuubi:

```bash
curl --fail --silent "${resolve[@]}" \
  -u 'admin:K8SparkAdmin2026Secure!' \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT 40 + 2 AS result"}' \
  https://app.test.local:8443/api/sql
```

Просмотр HDFS через WebHDFS:

```bash
curl --fail --silent --get "${resolve[@]}" \
  -u 'admin:K8SparkAdmin2026Secure!' \
  --data-urlencode 'path=/' \
  https://app.test.local:8443/api/hdfs
```

Список HBase tables:

```bash
curl --fail --silent "${resolve[@]}" \
  -u 'admin:K8SparkAdmin2026Secure!' \
  https://app.test.local:8443/api/hbase/tables
```

Просмотр Ozone OFS:

```bash
curl --fail --silent --get "${resolve[@]}" \
  -u 'admin:K8SparkAdmin2026Secure!' \
  --data-urlencode 'path=/' \
  https://app.test.local:8443/api/ozone
```

Для каждого защищённого запроса Spring Security выполняет LDAP bind, затем тем же паролем получает TGT пользователя и выполняет клиентский вызов в `UGI.doAs` под этим билетом. Keytab приложения при этом не используется.

### 8. Официальный Hue из Docker Hub

Reference Hue не заменяет Java-приложение и не участвует в функциональных тестах хранилищ. Он нужен для визуального и поведенческого сравнения дальнейшей реализации UI.

Откройте в браузере:

```text
http://localhost:8082/
```

Или проверьте HTTP из Terminal:

```bash
curl --fail --silent --location http://localhost:8082/ | grep -i hue
```

Compose собирает тонкий image layer от `gethue/hue:latest`. Единственное изменение — замена Python wheel `polars` на официальный `polars-lts-cpu` той же версии, поскольку обычный x86_64 wheel завершает процесс с `SIGILL` под Rosetta на Apple Silicon. Код Hue, Mako templates, CSS и JavaScript не изменяются.

## Архитектура Java-приложения

### Технологии и зависимости

- Java 21;
- Spring Boot 3.5.8;
- Spring Web и Jakarta Validation;
- Spring Security LDAP;
- Hadoop client 3.4.2;
- HBase client 2.6.2 для Hadoop 3;
- Hive JDBC standalone 4.0.1;
- Maven 3.9.11 в build stage;
- Eclipse Temurin 21 JRE в runtime stage.

Приложение stateless: пользовательские сессии, Kerberos tickets и результаты запросов не сохраняются в локальной базе.

### Путь запроса и модель безопасности

Приложение не хранит собственных Kerberos-реквизитов. При входе оно получает по паролю пользователя его личный ticket-granting ticket — тот же обмен, что делает `kinit`, — и ведёт все обращения к сервисам от имени этого билета. Авторизацию выполняют сами сервисы по identity в билете, поэтому отдельного слоя авторизации в приложении нет и быть не должно.

1. Клиент передаёт LDAP credentials через HTTP Basic либо форму входа UI.
2. `LdapSecurityConfig` выполняет bind по шаблону FreeIPA DN `uid={0},cn=users,cn=accounts`.
3. Тем же паролем `KerberosTicketService` через `Krb5LoginModule` получает TGT пользователя. Если KDC отказывает — вход не проходит, даже когда LDAP bind успешен: сессия без билета всё равно не смогла бы обратиться ни к одному сервису.
4. Билет живёт в `KerberosAuthentication` внутри сессии и никогда не пишется на диск и не сериализуется, поэтому не переживает рестарт приложения.
5. `KerberosExecutor` берёт `Subject` из сессии и оборачивает вызов в `UserGroupInformation.getUGIFromSubject(subject).doAs(...)`.
6. Вызов HDFS, Kyuubi, HBase или Ozone выполняется от имени этого пользователя; сервис применяет к нему свою авторизацию.
7. Срок жизни сессии ограничен сроком действия билета: по его истечении `TicketExpiryFilter` завершает сессию, а при выходе `KerberosTicketCleanup` уничтожает билет. Подробнее — в разделе «Срок жизни сессии».

Endpoint health открыт без логина. Страницы UI закрыты формой входа с редиректом. `/api` отвечает на анонимный запрос `401` с Basic-challenge — так простые скрипты и `curl` работают по HTTP Basic, — но при этом использует уже существующую сессию: браузер, вошедший через форму, обращается к `/api` по cookie сессии и второго входа не запрашивает. Обе цепочки построены раздельно в `LdapSecurityConfig`, чтобы выбор способа челленджа не зависел от заголовка `Accept`.

Контейнеру приложения смонтирован только том с `krb5.conf`, TLS-keystore и CA — сервисные keytabs кластера в него не попадают вовсе. Даже при компрометации процесса приложения нет keytab, которым можно было бы аутентифицироваться за сервис.

### Получение и хранение Kerberos-билета

**Получение.** Приложение не имеет собственного keytab и получает билет по паролю пользователя — тем же обменом с KDC, что делает `kinit`. Логика в [`KerberosTicketService`](src/main/java/com/k8spark/ui/security/KerberosTicketService.java):

1. Формируется principal из шаблона `k8spark.cluster.kerberos-principal` (`{user}` → LDAP-логин), например `admin@TEST.LOCAL`.
2. Создаётся пустой `javax.security.auth.Subject` и `LoginContext` с именем `kudos` поверх стандартного `com.sun.security.auth.module.Krb5LoginModule`. Модуль настроен программно (не через `jaas.conf`) со следующими опциями:
   - `useKeyTab=false`, `storeKey=false` — работаем без keytab, ключи не сохраняются;
   - `useTicketCache=false` — локальный кэш билетов (`/tmp/krb5cc_*`, `KRB5CCNAME`) не читается и не пишется: единственный вход — предъявленный пароль;
   - `doNotPrompt=false` — недостающие данные берутся из `CallbackHandler`;
   - `refreshKrb5Config=true` — конфигурация перечитывается на каждый вход.
3. `CallbackHandler` отдаёт principal в `NameCallback` и пароль в `PasswordCallback` (как `char[]`, из памяти).
4. `context.login()` выполняет AS-REQ к KDC (адрес и realm — из `krb5.conf`, путь задан флагом `-Djava.security.krb5.conf=/run/secrets-k8spark/krb5.conf`). При успехе TGT кладётся в `Subject` как приватный credential типа `KerberosTicket`; при отказе KDC бросается `LoginException`, и вход в приложение не проходит.
5. Из TGT считывается `getEndTime()` — момент истечения; он возвращается вместе с `Subject` в записи `IssuedTicket`.

**Хранение.** Билет живёт только в оперативной памяти процесса и только на время сессии пользователя:

- `Subject` (с `KerberosTicket` внутри) кладётся в [`KerberosAuthentication`](src/main/java/com/k8spark/ui/security/KerberosAuthentication.java) — это `Authentication` Spring Security, попадающий в `SecurityContext` и, далее, в серверную `HttpSession`.
- Поле `subject` объявлено `transient`, поэтому оно **никогда не сериализуется**: билет не может утечь в персистентное или распределённое хранилище сессий и существует исключительно в heap данного экземпляра приложения. Сессии — in-memory, никакой БД.
- На диск не пишется ничего: нет keytab, нет ticket cache (`useTicketCache=false`), нет credential cache-файла.
- Пароль не сохраняется: `KerberosAuthentication.getCredentials()` возвращает `null`. Хранится только производный от него билет.
- Билет не разделяется между пользователями и сессиями — у каждой сессии свой `Subject`.
- Использование: [`KerberosExecutor`](src/main/java/com/k8spark/ui/service/KerberosExecutor.java) берёт `Subject` из `SecurityContext` и выполняет обращение к сервису в `UserGroupInformation.getUGIFromSubject(subject).doAs(...)`.
- Из-за in-memory/`transient` хранения билет **не переживает рестарт приложения**: после перезапуска пользователю нужно войти заново. Срок жизни сессии ограничен сроком действия билета, а при выходе или истечении билет уничтожается (`KerberosTicket.destroy()`) — см. «Срок жизни сессии».

### Срок жизни сессии

Сессия не должна жить дольше билета: после его истечения билет отвергается всеми сервисами, и вошедший пользователь всё равно ничего не смог бы сделать. Поэтому:

- При входе `KerberosTicketService` считывает время истечения TGT (`KerberosTicket.getEndTime()`) и кладёт его в `KerberosAuthentication`.
- `TicketExpiryFilter` на каждом запросе сверяет это время: как только билет истёк, он уничтожает билет, завершает сессию и очищает контекст. Страницу перенаправляет на `/login?expired`, вызов `/api` отклоняет `401` (без `WWW-Authenticate`, чтобы в браузере не всплыло нативное окно Basic).
- Внизу справа на каждом экране идёт обратный отсчёт до истечения билета. Он засеян числом секунд, посчитанным на сервере, поэтому не зависит от расхождения часов в браузере; за пять минут до конца окрашивается в янтарный, а по достижении нуля сам выполняет выход.
- Выход (`GET /logout` — ссылка «Sign out» в сайдбаре и авто-выход по таймеру) уничтожает билет через `KerberosTicketCleanup` и инвалидирует сессию.

### HTTPS

Приложение работает только по HTTPS на порту `8443`. Сертификат для `app.test.local` выпускает CA этого realm во время bootstrap FreeIPA и публикует как PKCS12-keystore; клиенты проверяют его по `/shared/ca.crt` (в контейнере приложения — `/run/secrets-k8spark/ca.crt`). Из Terminal:

```bash
cd k8spark-ui
docker compose exec -T freeipa cat /shared/ca.crt > /tmp/k8spark-ca.crt
curl --fail --silent \
  --resolve app.test.local:8443:127.0.0.1 \
  --cacert /tmp/k8spark-ca.crt \
  https://app.test.local:8443/actuator/health
```

Реквизиты в keystore (`changeit`) — демонстрационные и относятся только к тестовому стенду.

### Логирование

Логирование настроено в `logback-spring.xml`: вывод в консоль (виден через `docker compose logs app`) и параллельно в rolling-файл `${k8spark.logging.dir}/kudos.log` (по умолчанию `logs/`, в контейнере — `/var/log/kudos`, смонтирован как volume `app-logs`). Ротация — по 50 MB, до 14 архивов, суммарно ≤ 2 GB. Уровни настраиваются через `logging.level.<package>` (например `logging.level.com.k8spark.ui: DEBUG`), путь — через `k8spark.logging.dir`.

### Аудит

Каждый запрос к `/api/**` записывается как audit-событие (JSON: время, пользователь, метод, путь, HTTP-статус). События всегда пишутся в отдельный rolling-файл `${k8spark.logging.dir}/audit.log` (`AuditInterceptor` → `AuditService` → logger `audit`). Дополнительно события можно публиковать в Kafka: установите `k8spark.audit.kafka-enabled: true`, `k8spark.audit.kafka-topic` и `spring.kafka.bootstrap-servers`. По умолчанию Kafka выключена, поэтому стенд не требует брокера. Ошибка публикации в Kafka не влияет на сам запрос.

### Основные классы

| Файл | Назначение |
| --- | --- |
| `K8SparkUiApplication.java` | Spring Boot entrypoint. |
| `LdapSecurityConfig.java` | LDAP bind, получение TGT при входе, раздельные цепочки для `/api` (Basic + сессия) и UI (форма), выход и завершение сессии по истечении билета. |
| `WebConfig.java` | Раздача вендорённых стилей и шрифтов Hue плюс собственных ассетов UI. |
| `ClusterProperties.java` | Типизированные адреса сервисов и шаблон principal пользователя. |
| `AppConfig.java` | Подключение `ClusterProperties` к Spring context. |
| `KerberosTicketService.java` | Получение TGT пользователя по паролю через `Krb5LoginModule` и считывание срока его действия. |
| `KerberosExecutor.java` | Выполнение вызова под билетом пользователя из сессии через `UGI.doAs`. |
| `TicketExpiryFilter.java` | Завершение сессии в момент истечения билета: страница → `/login?expired`, `/api` → `401`. |
| `KerberosTicketCleanup.java` | Уничтожение билета пользователя при выходе или истечении срока. |
| `TicketModelAdvice.java` | Передача экранам остатка времени билета для обратного отсчёта. |
| `KyuubiService.java` | JDBC connection, выполнение SQL и преобразование `ResultSet` в JSON rows. |
| `HdfsService.java` | WebHDFS `LISTSTATUS` и `OPEN` через `KerberosAuthenticator` и SPNEGO. |
| `HbaseService.java` | Полный HBase-браузер: жизненный цикл таблиц, управление column families, scan с фильтрами, история версий ячейки, мутации строк/ячеек и CSV bulk upload. |
| `OzoneService.java` | Hadoop `FileSystem` для URI `ofs://`, listing и чтение объекта. |
| `ClusterController.java` | JSON endpoints под `/api`. |
| `UiController.java` | Страницы UI: Editor, Files, Ozone, HBase и форма входа. |

### HTTP API

| Метод и path | Назначение | Тело или параметр |
| --- | --- | --- |
| `GET /actuator/health` | Spring health | Без аутентификации. |
| `POST /api/sql` | Kyuubi SQL, строки как объекты | JSON `{"sql":"SELECT 1"}`. |
| `POST /api/sql/execute` | Kyuubi SQL, колонки и строки отдельно | JSON `{"sql":"SELECT 1"}`, не более 1000 строк. |
| `GET /api/hdfs` | WebHDFS `LISTSTATUS` как есть | Query parameter `path`, по умолчанию `/`. |
| `GET /api/hdfs/list` | Разобранный listing HDFS | Query parameter `path`, по умолчанию `/`. |
| `GET /api/hdfs/preview` | Первые 64 КБ файла | Query parameter `path`. |
| `GET /api/hbase/tables` | Список таблиц и их состояние (enabled) | Без параметров. |
| `GET /api/hbase/describe` | Column families таблицы и их свойства | Query parameter `table`. |
| `GET /api/hbase/regions` | Регионы таблицы и границы ключей | Query parameter `table`. |
| `GET /api/hbase/scan` | Scan строк | `table`, `start`, `prefix`, `limit`, `columns`, `filter` (HBase filter string). |
| `GET /api/hbase/autocomplete` | Row keys по префиксу | `table`, `prefix`, `limit`. |
| `GET /api/hbase/row` | Одна строка | `table`, `row`, `columns`. |
| `GET /api/hbase/cell/versions` | История версий ячейки | `table`, `row`, `column`, `versions`. |
| `POST /api/hbase/table/create` | Создать таблицу | JSON `{table, families:[…]}`. |
| `POST /api/hbase/table/{enable,disable,truncate,delete}` | Управление таблицей | JSON `{table[, preserveSplits]}`. |
| `POST /api/hbase/family/{add,modify,delete}` | Управление column family | JSON `{table, family}`. |
| `POST /api/hbase/row` | Записать/обновить ячейки строки | JSON `{table, row, cells}`. |
| `POST /api/hbase/row/delete` | Удалить строку | JSON `{table, row}`. |
| `POST /api/hbase/cell/delete` | Удалить ячейки | JSON `{table, row, columns}`. |
| `POST /api/hbase/cell/upload` | Загрузить бинарное значение ячейки | Multipart `file` + query `table`, `row`, `column`. |
| `POST /api/hbase/bulk` | Bulk upload из CSV | Multipart `file` + query `table`. |
| `GET /api/ozone` | Список OFS paths | Query parameter `path`, по умолчанию `/`. |
| `GET /api/ozone/list` | Разобранный listing Ozone | Query parameter `path`, по умолчанию `/`. |
| `GET /api/ozone/preview` | Первые 64 КБ объекта | Query parameter `path`. |

### Собственный UI

Приложение отдаёт экраны по адресу `https://app.test.local:8443/`, повторяющие формы референсного Hue 4:

| Path | Экран | Источник данных |
| --- | --- | --- |
| `/editor` | Query Editor | Kyuubi через HiveServer2 JDBC. |
| `/filebrowser` | File Browser | HDFS через WebHDFS. |
| `/ozone` | Ozone Browser | Ozone через `ofs://`. |
| `/hbase` | HBase Browser (полный аналог Hue: таблицы, families, scan/поиск, ячейки и версии, мутации, bulk upload; курсорная пагинация строк, фильтр списка таблиц; деструктивные действия требуют ввода подтверждающего слова) | HBase через нативный RPC. |
| `/jobs` | Spark Jobs | Spark History Server через REST API. |
| `/jobs/{applicationId}` | Spark UI приложения | Проксированный UI history server. |
| `/spark-ui/**` | Прокси на Spark History Server | Тот же history server, но за сессией приложения. |

Экран `/jobs` показывает Spark-приложения, которые Kyuubi запускал на каждое соединение: имя, идентификатор, пользователя, время старта, длительность и версию Spark.

Экран открывается на собственных запусках вошедшего пользователя за последнюю неделю — как job browser в Hue. Поле поиска предзаполнено логином и ищет по имени приложения, идентификатору, пользователю и времени старта; чтобы увидеть чужие запуски, поле достаточно очистить. Диапазон дат переключается между днём, неделей, месяцем и произвольным периодом, любая колонка сортируется, размер страницы выбирается из 10/25/50/100.

Нижняя граница диапазона передаётся в history server параметром `minDate`, поэтому лишние приложения не выкачиваются. Верхняя граница произвольного периода применяется в браузере: на экране в любом случае лежит уже загруженная страница.

Имя и идентификатор приложения кликабельны и открывают его Spark UI внутри интерфейса приложения. Страница `/jobs/{applicationId}` встраивает проксированный UI и даёт кнопку скачивания event-логов. В строке списка та же кнопка доступна отдельно.

Прокси на `/spark-ui/**` передаёт запросы history server и добавляет заголовок `X-Forwarded-Context`, поэтому Spark сам строит все ссылки с этим префиксом и переписывать HTML не требуется. Побочный полезный эффект: UI history server, который сам по себе открыт, через приложение доступен только аутентифицированному пользователю.

Вход выполняется формой на `/login` теми же LDAP-реквизитами. Для UI создаётся сессия, а `/api` продолжает принимать HTTP Basic, поэтому примеры с `curl` выше работают без изменений.

Оболочка собрана из вендорённых стилей Hue (`hue.css`, `cui.css`, `bootstrap2.css`, `login.css`, Font Awesome и Roboto) в `src/main/resources/hue-upstream/desktop/static`. Левый сайдбар Hue 4 стилизуется изнутри её JavaScript-бандла, который не вендорится, поэтому его размеры и цвета воспроизведены в `src/main/resources/app-static/k8spark.css` по значениям, снятым с эталонного контейнера. Логотипы и товарные знаки Hue не воспроизводятся.

### Конфигурационный файл приложения

Все настройки `k8spark.cluster` и LDAP задаются в одном файле — [`config/application.yml`](config/application.yml). Он монтируется в контейнер read-only как `/etc/kudos/application.yml` и подключается через `SPRING_CONFIG_ADDITIONAL_LOCATION`, поэтому переопределяет значения по умолчанию из `src/main/resources/application.yml`.

Изменение адреса сервиса не требует пересборки образа:

```bash
cd k8spark-ui
docker compose restart app
```

| Свойство | Назначение |
| --- | --- |
| `k8spark.cluster.webhdfs-url` | WebHDFS endpoint для File Browser. |
| `k8spark.cluster.kyuubi-url` | JDBC URL Kyuubi для Query Editor. |
| `k8spark.cluster.hbase-quorum` | ZooKeeper quorum для HBase client. |
| `k8spark.cluster.ozone-ofs-uri` | Корень `ofs://` для Ozone Browser. |
| `k8spark.cluster.ozone-conf-dir` | Каталог с `core-site.xml` и `ozone-site.xml` Ozone. |
| `k8spark.cluster.spark-history-url` | Spark History Server для экрана Jobs. |
| `k8spark.cluster.kerberos-principal` | Шаблон principal пользователя, `{user}` подставляется из LDAP-логина. TGT для этого principal получается по паролю при входе; keytab не используется. |

Эти значения намеренно не задаются переменными окружения в `compose.yaml`: в Spring Boot переменные окружения приоритетнее внешнего конфигурационного файла, и забытая переменная молча перекрыла бы правку в файле.

### Spark event logs и history server

Kyuubi запускает Spark engine на каждое соединение и пишет его event log в Ozone:

```text
ofs://ozone.test.local/spark/eventlogs
```

Volume `spark` и bucket `eventlogs` создаёт `docker/ozone/start-ozone` при старте, а `spark-history` читает тот же каталог. Java-приложение показывает список приложений на экране `/jobs`, забирая его из REST API history server по SPNEGO.

Jar `ozone-filesystem-hadoop3` копируется в образы Kyuubi и Spark History из того же образа `apache/ozone:2.0.0`, на котором работает кластер, поэтому клиент и серверы не могут разойтись по версиям.

### Сборка Java image

`docker/app/Dockerfile` использует multi-stage build:

1. копирует только `pom.xml` и заранее скачивает Maven dependencies для эффективного Docker cache;
2. копирует `src` и собирает executable Spring Boot JAR;
3. переносит только JAR в минимальный Temurin 21 JRE runtime image.

Сервис работает только по HTTPS и публикует порт `8443`. Сертификат для `app.test.local` выпускает CA этого realm при bootstrap FreeIPA.

## Детальное описание тестового окружения

### Контейнеры и порты

| Compose service | Версия или base image | Host ports | Роль |
| --- | --- | --- | --- |
| `freeipa` | FreeIPA 4.13.1, Rocky Linux 9 | `88/tcp+udp`, `389`, `464/tcp+udp`, `636` | LDAP, Kerberos KDC, service principals и keytabs. |
| `kyuubi` | `apache/kyuubi:1.10.1-spark` | `10009`, `10099` | Kerberized HiveServer2/JDBC, Spark SQL `local[*]` и встроенный web UI. |
| `hdfs` | `apache/hadoop:3.4.2` | `9870` | NameNode, DataNode и SPNEGO WebHDFS. |
| `hbase` | Apache HBase 2.6.2, Temurin 17 | `9090`, `16010` | Embedded ZooKeeper, HMaster, RegionServer, secure Thrift и master UI. |
| `ozone` | `apache/ozone:2.0.0` | `9862`, `9874` | SCM, OM, DataNode, Object Store и OFS. |
| `spark-history` | `apache/spark:4.1.0` | `18080` | Spark History Server, читает event-логи из Ozone. |
| `app` | Java 21 / Spring Boot | `8443` | kudos integration API и UI, только HTTPS. |
| `hue-reference` | `gethue/hue:latest` | `8082` | Официальный Docker Hub Hue для сравнения UI. |

HDFS использует replication factor `1`. Ozone также настроен на одноузловую репликацию. HBase хранит данные в локальном filesystem внутри named volume. Это компактный функциональный стенд, а не production deployment.

### Порядок запуска и health checks

Compose dependency graph:

1. `freeipa` устанавливает realm и выполняет `bootstrap-test-identities.service`;
2. после healthy FreeIPA параллельно запускаются `kyuubi`, `hdfs`, `hbase` и `ozone`;
3. каждый сервис ждёт свои keytabs и запускает daemon processes;
4. `app` стартует только после healthy всех пяти инфраструктурных сервисов;
5. `hue-reference` независим и запускается по умолчанию;
6. `hue` запускается только с профилем `hue` и зависит от всего Kerberos-стенда.

Health checks проверяют bootstrap FreeIPA, наличие keytabs, daemon processes и service ports. Полноценные операции чтения и записи выполняются отдельным `test-kerberos-services.sh`.

### Realm, principals и keytabs

Тестовый realm:

```text
TEST.LOCAL
```

Демонстрационные реквизиты нового realm:

| Назначение | Identity | Пароль |
| --- | --- | --- |
| LDAP/Kerberos test user | `admin` / `admin@TEST.LOCAL` | `K8SparkAdmin2026Secure!` |
| FreeIPA Directory Manager | `cn=Directory Manager` | `DirectoryManager1` |

Оба пароля находятся в `compose.yaml` и systemd bootstrap unit в открытом виде и допустимы только для изолированного локального стенда.

FreeIPA bootstrap создаёт:

| Principal | Keytab в volume `kerberos-shared` | Потребитель |
| --- | --- | --- |
| `admin@TEST.LOCAL` | `admin.keytab` | Java API и ручные клиентские тесты. |
| `kyuubi/kyuubi.test.local@TEST.LOCAL` | `kyuubi.keytab` | Kyuubi server. |
| `nn/hdfs.test.local@TEST.LOCAL` | `nn-hdfs.test.local.keytab` | HDFS NameNode. |
| `dn/hdfs.test.local@TEST.LOCAL` | `dn-hdfs.test.local.keytab` | HDFS DataNode. |
| `HTTP/hdfs.test.local@TEST.LOCAL` | `HTTP-hdfs.test.local.keytab` | WebHDFS SPNEGO. |
| `om/ozone.test.local@TEST.LOCAL` | `om-ozone.test.local.keytab` | Ozone Manager. |
| `scm/ozone.test.local@TEST.LOCAL` | `scm-ozone.test.local.keytab` | Storage Container Manager. |
| `dn/ozone.test.local@TEST.LOCAL` | `dn-ozone.test.local.keytab` | Ozone DataNode. |
| `HTTP/ozone.test.local@TEST.LOCAL` | `HTTP-ozone.test.local.keytab` | Ozone HTTP service identity. |
| `hbase/hbase.test.local@TEST.LOCAL` | `hbase-hbase.test.local.keytab` | HMaster, RegionServer и Thrift. |
| `HTTP/hbase.test.local@TEST.LOCAL` | `HTTP-hbase.test.local.keytab` | HBase HTTP service identity. |

`bootstrap-test-identities` также создаёт общий `/shared/krb5.conf`. Keytabs не попадают в Git: они генерируются при первом старте и хранятся в Docker named volume. Infrastructure containers монтируют его как `/shared`, а Java-приложение — read-only как `/run/keytabs`.

Для совместимости Java 21 application client и Java 8 в Kyuubi test image service keytabs используют AES-SHA1 enctypes. Production enctypes должны определяться политикой вашего KDC и поддерживаемыми JDK.

### Named volumes

| Volume | Содержимое |
| --- | --- |
| `freeipa-data` | FreeIPA realm, LDAP database, KDC и PKI state. |
| `freeipa-run` | Writable `/run` для systemd и 389 Directory Server. |
| `freeipa-tmp` | Writable `/tmp` FreeIPA с поддержкой необходимых filesystem semantics. |
| `kerberos-shared` | `krb5.conf`, user keytab и service keytabs. |
| `hdfs-data` | NameNode metadata и DataNode blocks. |
| `hbase-data` | HBase и embedded ZooKeeper state. |
| `ozone-data` | SCM, OM, Ratis и DataNode state. |

FreeIPA использует `cgroup: host`, потому что внутри контейнера работает systemd. `privileged` намеренно не используется.

### Конфигурационные каталоги

```text
src/main/java/com/k8spark/ui/     Java application code
src/main/resources/              Spring configuration and vendored UI resources
src/test/                        Spring tests
docker/app/                      Java multi-stage image
docker/freeipa/                  FreeIPA image and identity bootstrap
docker/kyuubi/                   Kerberos Kyuubi/Spark configuration
docker/hdfs/                     Kerberos HDFS 3.4.2 configuration
docker/hbase/                    Kerberos HBase 2.6.2 configuration
docker/ozone/                    Kerberos Ozone 2.0.0 configuration
docker/hue-reference/            Thin compatibility layer over Docker Hub Hue
scripts/run-test-env.sh          Cross-platform environment launcher
scripts/test-kerberos-services.sh Functional Kerberos data-path tests
scripts/test-start-cycles.sh      Repeated restart and regression tests
scripts/hbase-loadgen.sh          Seed HBase at scale to exercise the browser UI
compose.yaml                     Services, volumes, ports and dependencies
```

## Особенности macOS Intel и Apple Silicon

### Почему нельзя глобально задавать `DOCKER_DEFAULT_PLATFORM`

На M1/M2/M3/M4 значение `DOCKER_DEFAULT_PLATFORM=linux/amd64` заставляет Compose ожидать amd64 даже от multi-arch images, которые проект собирает нативно. Результат — ошибка вида:

```text
image ... was found but its platform (linux/arm64) does not match the specified platform (linux/amd64)
```

`run-test-env.sh` снимает эту переменную для Compose, а `compose.yaml` задаёт `platform: linux/amd64` только тем сервисам, которым это действительно нужно.

Проверить глобальную настройку shell:

```bash
echo "${DOCKER_DEFAULT_PLATFORM-<not set>}"
```

### Docker Desktop Keychain `-67674`

Если Docker CLI не может обратиться к macOS credential helper, public image pull может завершиться ошибкой Keychain. Скрипты проекта используют `docker/.docker-config` без credential store только для public pulls/builds и не изменяют Docker Desktop login пользователя.

Всегда предпочитайте:

```bash
./scripts/run-test-env.sh -d
```

вместо ручного `docker compose build`, если ранее появлялась ошибка `-67674`.

### Официальный Hue на Apple Silicon

Docker Hub Hue распространяется как amd64 image. Под Rosetta обычный `polars` wheel использует неподдерживаемую CPU instruction и может завершиться с `SIGILL`. `docker/hue-reference/Dockerfile` заменяет только его на `polars-lts-cpu`; UI Hue остаётся upstream.

## Известные ограничения

- Стенд одноузловой и не моделирует отказоустойчивый production cluster.
- Логины, пароли и generated keytabs предназначены только для локального demo.
- Браузер не получает Kerberos ticket из LDAP-пароля автоматически. Java API использует смонтированный user keytab.
- Для production вместо долговечных user keytabs нужен безопасный credential broker или короткоживущие delegated credentials.
- В приложении пока нет ролевой, row-level или object-level авторизации.
- CSRF-защита включена для страниц UI, но отключена для `/api`, чтобы сохранить документированные вызовы через `curl`. Для сессии в браузере это означает реальный CSRF-риск на state-changing вызовах `/api`; в production такой набор недопустим.
- `gethue/hue:latest` намеренно используется только как подвижный reference; production build должен закреплять digest.
- UI и REST API Spark History Server работают без аутентификации. Spark 4 отдаёт их через Jetty на `jakarta.servlet`, а SPNEGO-фильтр Hadoop до сих пор реализует `javax.servlet.Filter`, поэтому Spark отклоняет его при старте, и jakarta-сборки этого фильтра не существует. Чтение event-логов из Ozone при этом выполняется под Kerberos-identity history server. Открыт только web endpoint на порту `18080`.
- Встроенный UI Kyuubi на порту `10099` защищён SPNEGO. Браузер без настроенного Kerberos получит `401`; из Terminal он доступен через `curl --negotiate -u :` после `kinit`.

## Проверка после изменения конфигурации

Минимальный обязательный набор перед передачей изменений:

```bash
cd k8spark-ui
mvn test
./scripts/run-test-env.sh -d
./scripts/test-kerberos-services.sh
./scripts/test-start-cycles.sh 10
```

Изменение считается безопасным для тестового окружения только после успешного реального create/read/delete или put/get на каждом Kerberos data path, а не только после статуса `healthy`.
