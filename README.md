# k8spark-ui

`k8spark-ui` — JBUEDE stateless Spring Boot API YJS L_EJNH M HDFS, Kyuubi/Spark SQL, HBase D Apache Ozone JN DZaID KJJCGJY_NaJS, KLJUaYUaUJ LDAP-_ONaINDPDE_SDH.

V LaKJGDNJLDD N_EVa I_RJYDNMS KJJIJMNCH EJINaEIaLDGDLJY_IIHE JYIJOGJJYJE NaMNJYHE MNaIY M FreeIPA D Kerberos. PJ OZJJT_IDH MNaIY G_KOME_aN JPDSD_JCIHE Hue DG Docker Hub I_ KJLNO `8082` E_E YDGO_JCIHE D KJYaYaITaMEDE CN_JJI. SJEDL_aZHE DG DMRJYIDEJY Hue YLaZaIIJ YHEJHTaI D YJMNOKaI NJJCEJ TaLaG KLJPDJC `hue`.

> **V_VIJ:** JJUDI `admin` D K_LJJC `K8SparkAdmin2026Secure!` SYJSHNMS KOEJDTIHZD YaZJIMNL_SDJIIHZD LaEYDGDN_ZD CNJUJ LaKJGDNJLDS. OID MJJVIaa MN_IY_LNIJUJ NaMNJYJUJ K_LJJS D Ia NLaEOHN MZaIH KJMJa L_GYBLNHY_IDS MNaIY_, IJ Ia YJJVIH DMKJJCGJY_NCMS Y production.

## SJMNJSIDa POIESDJI_JCIJMND

PLDJJVaIDa KLaYJMN_YJSaN:

- LDAP-_ONaINDPDE_SDH TaLaG HTTP Basic;
- YHKJJIaIDa Spark SQL TaLaG Kerberized Kyuubi KJ Hive JDBC;
- KLJMZJNL HDFS TaLaG WebHDFS M SPNEGO;
- JEL_FaIDa E HBase TaLaG I_NDYIHE `hbase-client` D Kerberos RPC;
- KLJMZJNL Apache Ozone TaLaG `ofs://` D Kerberos;
- JNYaJCIHE IaDGZaIBIIHE UI Hue DG Docker Hub YJS ML_YIaIDS DINaLPaEM_.

RJJaYJE D JEKaENIJE _YNJLDG_SDD Y Java-KLDJJVaIDD KJE_ IaN. VMa API, ELJZa health endpoint, NLaEOHN OMKaUIJE LDAP-_ONaINDPDE_SDD, _ JEL_FaIDS E EJ_MNaLIHZ MaLYDM_Z YHKJJISHNMS M Kerberos identity CNJUJ Va KJJCGJY_NaJS.

## BHMNLHE G_KOME NaMNJYJUJ JELOVaIDS

### TLaEJY_IDS

- macOS I_ Intel DJD Apple Silicon M1/M2/M3/M4;
- _ENO_JCIHE Docker Desktop M Docker Compose v2;
- LaEJZaIYOaNMS Ia ZaIaa 12 GB K_ZSND, YJMNOKIJE Docker Desktop;
- LaEJZaIYOaNMS JEJJJ 20 GB MYJEJYIJUJ YDMEJYJUJ KLJMNL_IMNY_ YJS JEL_GJY, build cache D named volumes;
- MYJEJYIHa KJLNH DG N_EJDSH IDVa.

DJS G_KOME_ NJJCEJ Docker-JELOVaIDS Java D Maven I_ RJMNa Ia IOVIH. DJS JJE_JCIJUJ G_KOME_ `mvn test` NLaEOHNMS JDK 21 D Maven 3.9+.

N_ Apple Silicon Docker Desktop YJJVaI OZaNC G_KOME_NC `linux/amd64`-JEL_GH. PJYYaLVE_ CZOJSSDD JEHTIJ YEJHTaI_ KJ OZJJT_IDH. Kyuubi, Hadoop, Ozone D JPDSD_JCIHE Hue L_EJN_HN E_E `linux/amd64`; FreeIPA, HBase D Java-KLDJJVaIDa MJEDL_HNMS KJY _LRDNaENOLO RJMN_.

### PaLYHE G_KOME

VHKJJIDNa DG Terminal:

```bash
cd k8spark-ui
chmod +x scripts/*.sh
./scripts/run-test-env.sh -d
```

SELDKN:

1. KLJYaLSaN YJMNOKIJMNC Docker Desktop;
2. JKLaYaJSaN _LRDNaENOLO Mac;
3. OY_JSaN NJJCEJ OMN_LaYUDa JJE_JCIHa JEL_GH `freeipa` D `app`, MJEL_IIHa KJY YLOUOH _LRDNaENOLO;
4. MEL_MHY_aN `DOCKER_DEFAULT_PLATFORM` NJJCEJ YJS CNJUJ G_KOME_;
5. DMKJJCGOaN JJE_JCIOH Docker-EJIPDUOL_SDH EaG macOS credential helper;
6. YHKJJISaN `docker compose up --build --remove-orphans`.

PaLYHE G_KOME ME_TDY_aN E_GJYHa JEL_GH D Maven-G_YDMDZJMND D ZJVaN G_ISNC 10–30 ZDION. HN_K `Downloading Maven dependencies` YHYJYDN KJJIHE Maven progress D Ia SYJSaNMS G_YDM_IDaZ. IIDSD_JDG_SDS IJYJUJ FreeIPA realm JEHTIJ G_IDZ_aN aFB 3–5 ZDION.

SJaYDNa G_ MJMNJSIDaZ EJINaEIaLJY:

```bash
docker compose ps
```

OMIJYIHa MaLYDMH YJJVIH KaLaEND Y MJMNJSIDa `healthy`, _ `hue-reference` — Y MJMNJSIDa `running`. Java-KLDJJVaIDa MJGY_BNMS NJJCEJ KJMJa OMKaUIHR health checks FreeIPA, Kyuubi, HDFS, HBase D Ozone.

PLJYaLCNa YJMNOKIHa HTTP endpoints:

```bash
curl --fail --silent --resolve app.test.local:8443:127.0.0.1 \
  --cacert <(docker compose exec -T freeipa cat /shared/ca.crt) \
  https://app.test.local:8443/actuator/health
curl --fail --silent --location http://localhost:8082/ | grep -i hue
```

OVDY_aZHE health response:

```json
{"status":"UP"}
```

### OEHTIHE KaLaG_KOME

Named volumes MJRL_ISHN realm, keytabs D Y_IIHa MaLYDMJY:

```bash
cd k8spark-ui
docker compose down --remove-orphans
./scripts/run-test-env.sh -d
```

### OMN_IJYE_

```bash
cd k8spark-ui
docker compose down --remove-orphans
```

### PJJIHE MELJM NaMNJYJUJ MNaIY_

SJaYOHF_S EJZ_IY_ EaGYJGYL_NIJ OY_JSaN NaMNJYHE FreeIPA realm, keytabs D Y_IIHa HDFS, HBase D Ozone:

```bash
cd k8spark-ui
docker compose down --volumes --remove-orphans
./scripts/run-test-env.sh -d
```

IMKJJCGOENa KJJIHE MELJM KJMJa IaMJYZaMNDZJUJ DGZaIaIDS FreeIPA bootstrap DJD PJLZ_N_ Y_IIHR MaLYDMJY. DJS JEHTIJUJ LaMN_LN_ OY_JSNC volumes Ia IOVIJ.

### LJUD D YD_UIJMNDE_ MN_LN_

```bash
docker compose logs --tail=200 freeipa
docker compose logs --tail=200 kyuubi
docker compose logs --tail=200 hdfs
docker compose logs --tail=200 hbase
docker compose logs --tail=200 ozone
docker compose logs --tail=200 app
docker compose logs --tail=200 hue-reference
```

DJS IaKLaLHYIJUJ KLJMZJNL_ YJE_YCNa `--follow`, I_KLDZaL:

```bash
docker compose logs --follow --tail=100 ozone
```

## AYNJZ_NDTaMEJa NaMNDLJY_IDa

### Unit D Spring context tests

```bash
cd k8spark-ui
mvn test
```

### PJJIHE POIESDJI_JCIHE Kerberos-NaMN

PJMJa MN_LN_ EJINaEIaLJY YHKJJIDNa:

```bash
cd k8spark-ui
./scripts/test-kerberos-services.sh
```

SELDKN KLJYaLSaN Ia NJJCEJ TCP-KJLNH, IJ D La_JCIHa data paths JN DZaID `admin@TEST.LOCAL`:

- LDAP bind D KJJOTaIDa Kerberos TGT KJ K_LJJH;
- Kyuubi JDBC SQL;
- WebHDFS SPNEGO create/read/delete;
- Ozone Kerberos put/get D OFS;
- HBase Kerberos RPC create/put/get/drop;
- Spring Boot health endpoint;
- MNL_IDSO Docker Hub Hue reference.

UMKaUIHE KLJUJI G_YaLU_aNMS MJJEFaIDaZ:

```text
All Kerberos functional checks passed.
```

### DaMSNC SDEJJY LaMN_LN_

```bash
cd k8spark-ui
./scripts/test-start-cycles.sh 10
```

K_VYHE SDEJ MJRL_ISaN named volumes, JMN_I_YJDY_aN D G_IJYJ G_KOME_aN OVa MJEL_IIHa JEL_GH, VYBN health checks, KLJYaLSaN JKOEJDEJY_IIHa KJLNH D YHKJJISaN KJJIHE `test-kerberos-services.sh`. CDMJJ SDEJJY ZJVIJ DGZaIDNC KJJJVDNaJCIHZ SaJHZ _LUOZaINJZ.

### N_ULOGJTIHa Y_IIHa YJS HBase-EL_OGaL_

`hbase-loadgen.sh` MJGY_BN TaLaG API KLDJJVaIDS N_EJDSH M MDJCIJ L_GIHZ L_GZaLJZ, TNJEH YLOTIOH KLJYaLDNC UI I_ Z_MUN_Ea: ZIJUJ N_EJDS (PDJCNL D MELJJJ MKDME_), N_EJDSH M EJJCUDZ TDMJJZ MNLJE (EOLMJLI_S K_UDI_SDS) D «UDLJEDa» MNLJED M NHMST_ZD EJJJIJE (UJLDGJIN_JCIHE MELJJJ D JUL_IDTaIDa TDMJ_ YDYDZHR EJJJIJE). M_MUN_E K_L_ZaNLDGOaNMS; GI_TaIDS KJ OZJJT_IDH — KLaYMN_YDNaJCI_S Z_NLDS_, JNL_E_NHY_HF_S G_ IaMEJJCEJ ZDION I_ JYIJOGJJYJZ MNaIYa.

```bash
cd k8spark-ui
./scripts/hbase-loadgen.sh              # G_MaSNC KLaYMN_YDNaJCIOH Z_NLDSO
MODE=clean ./scripts/hbase-loadgen.sh   # OY_JDNC YMB, TNJ MJGY_J UaIaL_NJL
```

LDNaL_JCIHa 5000 N_EJDS × YJ 100000 MNLJE — CNJ MJNID ZJI STaaE D Ia LaEJZaIYOaNMS I_ JYIJOGJJYJZ Docker HBase; KLD IaJERJYDZJMND Z_MUN_E KJYIDZ_aNMS KaLaZaIIHZD `MANY_TABLES`, `ROW_SIZES`, `COL_SIZES`.

## ROTIJa NaMNDLJY_IDa E_VYJUJ EJZKJIaIN_

VMa EJZ_IYH CNJUJ L_GYaJ_ YHKJJISHNMS DG EJLIS KLJaEN_ KLD L_EJN_HFaZ JELOVaIDD:

```bash
cd k8spark-ui
docker compose ps
```

### 1. FreeIPA: LDAP D Kerberos

PLJYaLCNa KJJOTaIDa TGT KJ K_LJJH, MJYaLVDZJa credential cache D LDAP bind:

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

OVDY_aNMS principal `admin@TEST.LOCAL` Y `klist` D LDAP DN KJJCGJY_NaJS `admin` Y YHYJYa `ldapwhoami`.

PLDJJVaIDa keytab Ia DMKJJCGOaN — JIJ KJJOT_aN TGT KJJCGJY_NaJS KJ K_LJJH KLD YRJYa (MZ. «MJYaJC EaGJK_MIJMND»). ONYaJCIJ KLJYaLCNa, TNJ KDC YHY_BN EDJaN KJ keytab DG NJZ_ `kerberos-shared`, EJNJLHZ KJJCGOHNMS LOTIHa EJDaINMEDa NaMNH MaLYDMJY IDVa:

```bash
docker compose exec -T freeipa bash -lc '
  set -euo pipefail
  kdestroy 2>/dev/null || true
  kinit -kt /shared/admin.keytab admin@TEST.LOCAL
  klist -s
  echo "Kerberos keytab login: OK"
'
```

### 2. Kyuubi: JDBC SQL M Kerberos

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

OVDY_aZ_S MNLJE_ LaGOJCN_N_:

```text
42
```

HNJ La_JCIHE Hive JDBC handshake M MaLYDMIHZ principal Kyuubi, _ Ia KLJYaLE_ JNELHNJUJ KJLN_.

### 3. HDFS 3.4.2: WebHDFS SPNEGO create/read/delete

PLJYaLE_ YHKJJISaNMS DZaIIJ TaLaG HTTP WebHDFS. FJ_U `--negotiate` YEJHT_aN SPNEGO, _ `--location-trusted` MJRL_ISaN Kerberos authentication KLD redirect JN NameNode E DataNode.

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

V YHYJYa TNaIDS YJJVI_ KLDMONMNYJY_NC MNLJE_:

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

OVDY_aNMS MNLJE_ `ozone-object-store-ok` D IOJaYJE exit code.

### 5. Apache Ozone: OFS put/read/delete M Kerberos

SJaYOHF_S KLJYaLE_ DMKJJCGOaN Hadoop-compatible `ofs://`, NJ aMNC NJN Va DINaLPaEM, TaLaG EJNJLHE L_EJN_aN `OzoneService` Java-KLDJJVaIDS:

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

OVDY_aZ_S MNLJE_:

```text
ozone-ofs-ok
```

TaMNJYHE MNaIY DMKJJCGOaN JYDI Ozone DataNode, KJCNJZO EJDaINME_S D MaLYaLI_S EJIPDUOL_SDS G_Y_BN JYIJOGJJYOH LaKJDE_SDH. T_E_S MRaZ_ KLaYI_GI_TaI_ NJJCEJ YJS JJE_JCIHR NaMNJY D Ia SYJSaNMS production-NJKJJJUDaE.

### 6. HBase 2.6.2: Kerberos RPC put/get

KJZ_IYH IDVa DMKJJCGOHN I_NDYIHE HBase RPC client. PaLaY NaMNJZ JMN_NJE JYIJDZBIIJE N_EJDSH OY_JSaNMS, KJMJa NaMN_ N_EJDS_ N_EVa JTDF_aNMS.

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

V LaGOJCN_Na `get` YJJVI_ KJSYDNCMS MNLJE_:

```text
value=hbase-kerberos-rpc-ok
```

### 7. Spring Boot API M LDAP D user Kerberos identity

PLDJJVaIDa L_EJN_aN NJJCEJ KJ HTTPS I_ `app.test.local:8443`. SJRL_IDNa CA MNaIY_ D YJE_YCNa aUJ E E_VYJZO G_KLJMO TaLaG `--resolve`/`--cacert`:

```bash
docker compose exec -T freeipa cat /shared/ca.crt > /tmp/k8spark-ca.crt
resolve=(--resolve app.test.local:8443:127.0.0.1 --cacert /tmp/k8spark-ca.crt)
```

Health endpoint JNELHN EaG _ONaINDPDE_SDD:

```bash
curl --fail --silent "${resolve[@]}" https://app.test.local:8443/actuator/health
```

SQL TaLaG Kyuubi:

```bash
curl --fail --silent "${resolve[@]}" \
  -u 'admin:K8SparkAdmin2026Secure!' \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT 40 + 2 AS result"}' \
  https://app.test.local:8443/api/sql
```

PLJMZJNL HDFS TaLaG WebHDFS:

```bash
curl --fail --silent --get "${resolve[@]}" \
  -u 'admin:K8SparkAdmin2026Secure!' \
  --data-urlencode 'path=/' \
  https://app.test.local:8443/api/hdfs
```

SKDMJE HBase tables:

```bash
curl --fail --silent "${resolve[@]}" \
  -u 'admin:K8SparkAdmin2026Secure!' \
  https://app.test.local:8443/api/hbase/tables
```

PLJMZJNL Ozone OFS:

```bash
curl --fail --silent --get "${resolve[@]}" \
  -u 'admin:K8SparkAdmin2026Secure!' \
  --data-urlencode 'path=/' \
  https://app.test.local:8443/api/ozone
```

DJS E_VYJUJ G_FDFBIIJUJ G_KLJM_ Spring Security YHKJJISaN LDAP bind, G_NaZ NaZ Va K_LJJaZ KJJOT_aN TGT KJJCGJY_NaJS D YHKJJISaN EJDaINMEDE YHGJY Y `UGI.doAs` KJY CNDZ EDJaNJZ. Keytab KLDJJVaIDS KLD CNJZ Ia DMKJJCGOaNMS.

### 8. OPDSD_JCIHE Hue DG Docker Hub

Reference Hue Ia G_ZaISaN Java-KLDJJVaIDa D Ia OT_MNYOaN Y POIESDJI_JCIHR NaMN_R RL_IDJDF. OI IOVaI YJS YDGO_JCIJUJ D KJYaYaITaMEJUJ ML_YIaIDS Y_JCIaEUaE La_JDG_SDD UI.

ONELJENa Y EL_OGaLa:

```text
http://localhost:8082/
```

IJD KLJYaLCNa HTTP DG Terminal:

```bash
curl --fail --silent --location http://localhost:8082/ | grep -i hue
```

Compose MJEDL_aN NJIEDE image layer JN `gethue/hue:latest`. EYDIMNYaIIJa DGZaIaIDa — G_ZaI_ Python wheel `polars` I_ JPDSD_JCIHE `polars-lts-cpu` NJE Va YaLMDD, KJMEJJCEO JEHTIHE x86_64 wheel G_YaLU_aN KLJSaMM M `SIGILL` KJY Rosetta I_ Apple Silicon. KJY Hue, Mako templates, CSS D JavaScript Ia DGZaISHNMS.

## ALRDNaENOL_ Java-KLDJJVaIDS

### TaRIJJJUDD D G_YDMDZJMND

- Java 21;
- Spring Boot 3.5.8;
- Spring Web D Jakarta Validation;
- Spring Security LDAP;
- Hadoop client 3.4.2;
- HBase client 2.6.2 YJS Hadoop 3;
- Hive JDBC standalone 4.0.1;
- Maven 3.9.11 Y build stage;
- Eclipse Temurin 21 JRE Y runtime stage.

PLDJJVaIDa stateless: KJJCGJY_NaJCMEDa MaMMDD, Kerberos tickets D LaGOJCN_NH G_KLJMJY Ia MJRL_ISHNMS Y JJE_JCIJE E_Ga.

### PONC G_KLJM_ D ZJYaJC EaGJK_MIJMND

PLDJJVaIDa Ia RL_IDN MJEMNYaIIHR Kerberos-LaEYDGDNJY. PLD YRJYa JIJ KJJOT_aN KJ K_LJJH KJJCGJY_NaJS aUJ JDTIHE ticket-granting ticket — NJN Va JEZaI, TNJ YaJ_aN `kinit`, — D YaYBN YMa JEL_FaIDS E MaLYDM_Z JN DZaID CNJUJ EDJaN_. AYNJLDG_SDH YHKJJISHN M_ZD MaLYDMH KJ identity Y EDJaNa, KJCNJZO JNYaJCIJUJ MJJS _YNJLDG_SDD Y KLDJJVaIDD IaN D EHNC Ia YJJVIJ.

1. KJDaIN KaLaY_BN LDAP credentials TaLaG HTTP Basic JDEJ PJLZO YRJY_ UI.
2. `LdapSecurityConfig` YHKJJISaN bind KJ U_EJJIO FreeIPA DN `uid={0},cn=users,cn=accounts`.
3. TaZ Va K_LJJaZ `KerberosTicketService` TaLaG `Krb5LoginModule` KJJOT_aN TGT KJJCGJY_NaJS. EMJD KDC JNE_GHY_aN — YRJY Ia KLJRJYDN, Y_Va EJUY_ LDAP bind OMKaUaI: MaMMDS EaG EDJaN_ YMB L_YIJ Ia MZJUJ_ EH JEL_NDNCMS ID E JYIJZO MaLYDMO.
4. BDJaN VDYBN Y `KerberosAuthentication` YIONLD MaMMDD D IDEJUY_ Ia KDUaNMS I_ YDME D Ia MaLD_JDGOaNMS, KJCNJZO Ia KaLaVDY_aN LaMN_LN KLDJJVaIDS.
5. `KerberosExecutor` EaLBN `Subject` DG MaMMDD D JEJL_TDY_aN YHGJY Y `UserGroupInformation.getUGIFromSubject(subject).doAs(...)`.
6. VHGJY HDFS, Kyuubi, HBase DJD Ozone YHKJJISaNMS JN DZaID CNJUJ KJJCGJY_NaJS; MaLYDM KLDZaISaN E IaZO MYJH _YNJLDG_SDH.
7. SLJE VDGID MaMMDD JUL_IDTaI MLJEJZ YaEMNYDS EDJaN_: KJ aUJ DMNaTaIDD `TicketExpiryFilter` G_YaLU_aN MaMMDH, _ KLD YHRJYa `KerberosTicketCleanup` OIDTNJV_aN EDJaN. PJYLJEIaa — Y L_GYaJa «SLJE VDGID MaMMDD».

Endpoint health JNELHN EaG JJUDI_. SNL_IDSH UI G_ELHNH PJLZJE YRJY_ M LaYDLaENJZ. `/api` JNYaT_aN I_ _IJIDZIHE G_KLJM `401` M Basic-challenge — N_E KLJMNHa MELDKNH D `curl` L_EJN_HN KJ HTTP Basic, — IJ KLD CNJZ DMKJJCGOaN OVa MOFaMNYOHFOH MaMMDH: EL_OGaL, YJUaYUDE TaLaG PJLZO, JEL_F_aNMS E `/api` KJ cookie MaMMDD D YNJLJUJ YRJY_ Ia G_KL_UDY_aN. OEa SaKJTED KJMNLJaIH L_GYaJCIJ Y `LdapSecurityConfig`, TNJEH YHEJL MKJMJE_ TaJJaIYV_ Ia G_YDMaJ JN G_UJJJYE_ `Accept`.

KJINaEIaLO KLDJJVaIDS MZJINDLJY_I NJJCEJ NJZ M `krb5.conf`, TLS-keystore D CA — MaLYDMIHa keytabs EJ_MNaL_ Y IaUJ Ia KJK_Y_HN YJYMa. D_Va KLD EJZKLJZaN_SDD KLJSaMM_ KLDJJVaIDS IaN keytab, EJNJLHZ ZJVIJ EHJJ EH _ONaINDPDSDLJY_NCMS G_ MaLYDM.

### PJJOTaIDa D RL_IaIDa Kerberos-EDJaN_

**PJJOTaIDa.** PLDJJVaIDa Ia DZaaN MJEMNYaIIJUJ keytab D KJJOT_aN EDJaN KJ K_LJJH KJJCGJY_NaJS — NaZ Va JEZaIJZ M KDC, TNJ YaJ_aN `kinit`. LJUDE_ Y [`KerberosTicketService`](src/main/java/com/k8spark/ui/security/KerberosTicketService.java):

1. FJLZDLOaNMS principal DG U_EJJI_ `k8spark.cluster.kerberos-principal` (`{user}` → LDAP-JJUDI), I_KLDZaL `admin@TEST.LOCAL`.
2. SJGY_BNMS KOMNJE `javax.security.auth.Subject` D `LoginContext` M DZaIaZ `k8spark-ui` KJYaLR MN_IY_LNIJUJ `com.sun.security.auth.module.Krb5LoginModule`. MJYOJC I_MNLJaI KLJUL_ZZIJ (Ia TaLaG `jaas.conf`) MJ MJaYOHFDZD JKSDSZD:
   - `useKeyTab=false`, `storeKey=false` — L_EJN_aZ EaG keytab, EJHTD Ia MJRL_ISHNMS;
   - `useTicketCache=false` — JJE_JCIHE ECU EDJaNJY (`/tmp/krb5cc_*`, `KRB5CCNAME`) Ia TDN_aNMS D Ia KDUaNMS: aYDIMNYaIIHE YRJY — KLaYKSYJaIIHE K_LJJC;
   - `doNotPrompt=false` — IaYJMN_HFDa Y_IIHa EaLONMS DG `CallbackHandler`;
   - `refreshKrb5Config=true` — EJIPDUOL_SDS KaLaTDNHY_aNMS I_ E_VYHE YRJY.
3. `CallbackHandler` JNY_BN principal Y `NameCallback` D K_LJJC Y `PasswordCallback` (E_E `char[]`, DG K_ZSND).
4. `context.login()` YHKJJISaN AS-REQ E KDC (_YLaM D realm — DG `krb5.conf`, KONC G_Y_I PJ_UJZ `-Djava.security.krb5.conf=/run/secrets-k8spark/krb5.conf`). PLD OMKaRa TGT EJ_YBNMS Y `Subject` E_E KLDY_NIHE credential NDK_ `KerberosTicket`; KLD JNE_Ga KDC ELJM_aNMS `LoginException`, D YRJY Y KLDJJVaIDa Ia KLJRJYDN.
5. IG TGT MTDNHY_aNMS `getEndTime()` — ZJZaIN DMNaTaIDS; JI YJGYL_F_aNMS YZaMNa M `Subject` Y G_KDMD `IssuedTicket`.

**KL_IaIDa.** BDJaN VDYBN NJJCEJ Y JKaL_NDYIJE K_ZSND KLJSaMM_ D NJJCEJ I_ YLaZS MaMMDD KJJCGJY_NaJS:

- `Subject` (M `KerberosTicket` YIONLD) EJ_YBNMS Y [`KerberosAuthentication`](src/main/java/com/k8spark/ui/security/KerberosAuthentication.java) — CNJ `Authentication` Spring Security, KJK_Y_HFDE Y `SecurityContext` D, Y_Jaa, Y MaLYaLIOH `HttpSession`.
- PJJa `subject` JEKSYJaIJ `transient`, KJCNJZO JIJ **IDEJUY_ Ia MaLD_JDGOaNMS**: EDJaN Ia ZJVaN ONaTC Y KaLMDMNaINIJa DJD L_MKLaYaJBIIJa RL_IDJDFa MaMMDE D MOFaMNYOaN DMEJHTDNaJCIJ Y heap Y_IIJUJ CEGaZKJSL_ KLDJJVaIDS. SaMMDD — in-memory, IDE_EJE BD.
- N_ YDME Ia KDUaNMS IDTaUJ: IaN keytab, IaN ticket cache (`useTicketCache=false`), IaN credential cache-P_EJ_.
- P_LJJC Ia MJRL_ISaNMS: `KerberosAuthentication.getCredentials()` YJGYL_F_aN `null`. KL_IDNMS NJJCEJ KLJDGYJYIHE JN IaUJ EDJaN.
- BDJaN Ia L_GYaJSaNMS ZaVYO KJJCGJY_NaJSZD D MaMMDSZD — O E_VYJE MaMMDD MYJE `Subject`.
- IMKJJCGJY_IDa: [`KerberosExecutor`](src/main/java/com/k8spark/ui/service/KerberosExecutor.java) EaLBN `Subject` DG `SecurityContext` D YHKJJISaN JEL_FaIDa E MaLYDMO Y `UserGroupInformation.getUGIFromSubject(subject).doAs(...)`.
- IG-G_ in-memory/`transient` RL_IaIDS EDJaN **Ia KaLaVDY_aN LaMN_LN KLDJJVaIDS**: KJMJa KaLaG_KOME_ KJJCGJY_NaJH IOVIJ YJEND G_IJYJ. SLJE VDGID MaMMDD JUL_IDTaI MLJEJZ YaEMNYDS EDJaN_, _ KLD YHRJYa DJD DMNaTaIDD EDJaN OIDTNJV_aNMS (`KerberosTicket.destroy()`) — MZ. «SLJE VDGID MaMMDD».

### SLJE VDGID MaMMDD

SaMMDS Ia YJJVI_ VDNC YJJCUa EDJaN_: KJMJa aUJ DMNaTaIDS EDJaN JNYaLU_aNMS YMaZD MaLYDM_ZD, D YJUaYUDE KJJCGJY_NaJC YMB L_YIJ IDTaUJ Ia MZJU EH MYaJ_NC. PJCNJZO:

- PLD YRJYa `KerberosTicketService` MTDNHY_aN YLaZS DMNaTaIDS TGT (`KerberosTicket.getEndTime()`) D EJ_YBN aUJ Y `KerberosAuthentication`.
- `TicketExpiryFilter` I_ E_VYJZ G_KLJMa MYaLSaN CNJ YLaZS: E_E NJJCEJ EDJaN DMNBE, JI OIDTNJV_aN EDJaN, G_YaLU_aN MaMMDH D JTDF_aN EJINaEMN. SNL_IDSO KaLaI_KL_YJSaN I_ `/login?expired`, YHGJY `/api` JNEJJISaN `401` (EaG `WWW-Authenticate`, TNJEH Y EL_OGaLa Ia YMKJHJJ I_NDYIJa JEIJ Basic).
- VIDGO MKL_Y_ I_ E_VYJZ CEL_Ia DYBN JEL_NIHE JNMTBN YJ DMNaTaIDS EDJaN_. OI G_MaSI TDMJJZ MaEOIY, KJMTDN_IIHZ I_ MaLYaLa, KJCNJZO Ia G_YDMDN JN L_MRJVYaIDS T_MJY Y EL_OGaLa; G_ KSNC ZDION YJ EJIS_ JEL_UDY_aNMS Y SIN_LIHE, _ KJ YJMNDVaIDD IOJS M_Z YHKJJISaN YHRJY.
- VHRJY (`GET /logout` — MMHJE_ «Sign out» Y M_EYE_La D _YNJ-YHRJY KJ N_EZaLO) OIDTNJV_aN EDJaN TaLaG `KerberosTicketCleanup` D DIY_JDYDLOaN MaMMDH.

### HTTPS

PLDJJVaIDa L_EJN_aN NJJCEJ KJ HTTPS I_ KJLNO `8443`. SaLNDPDE_N YJS `app.test.local` YHKOME_aN CA CNJUJ realm YJ YLaZS bootstrap FreeIPA D KOEJDEOaN E_E PKCS12-keystore; EJDaINH KLJYaLSHN aUJ KJ `/shared/ca.crt` (Y EJINaEIaLa KLDJJVaIDS — `/run/secrets-k8spark/ca.crt`). IG Terminal:

```bash
cd k8spark-ui
docker compose exec -T freeipa cat /shared/ca.crt > /tmp/k8spark-ca.crt
curl --fail --silent \
  --resolve app.test.local:8443:127.0.0.1 \
  --cacert /tmp/k8spark-ca.crt \
  https://app.test.local:8443/actuator/health
```

RaEYDGDNH Y keystore (`changeit`) — YaZJIMNL_SDJIIHa D JNIJMSNMS NJJCEJ E NaMNJYJZO MNaIYO.

### LJUDLJY_IDa

LJUDLJY_IDa I_MNLJaIJ Y `logback-spring.xml`: YHYJY Y EJIMJJC (YDYaI TaLaG `docker compose logs app`) D K_L_JJaJCIJ Y rolling-P_EJ `${k8spark.logging.dir}/k8spark-ui.log` (KJ OZJJT_IDH `logs/`, Y EJINaEIaLa — `/var/log/k8spark-ui`, MZJINDLJY_I E_E volume `app-logs`). RJN_SDS — KJ 50 MB, YJ 14 _LRDYJY, MOZZ_LIJ ≤ 2 GB. ULJYID I_MNL_DY_HNMS TaLaG `logging.level.<package>` (I_KLDZaL `logging.level.com.k8spark.ui: DEBUG`), KONC — TaLaG `k8spark.logging.dir`.

### AOYDN

K_VYHE G_KLJM E `/api/**` G_KDMHY_aNMS E_E audit-MJEHNDa (JSON: YLaZS, KJJCGJY_NaJC, ZaNJY, KONC, HTTP-MN_NOM). SJEHNDS YMaUY_ KDUONMS Y JNYaJCIHE rolling-P_EJ `${k8spark.logging.dir}/audit.log` (`AuditInterceptor` → `AuditService` → logger `audit`). DJKJJIDNaJCIJ MJEHNDS ZJVIJ KOEJDEJY_NC Y Kafka: OMN_IJYDNa `k8spark.audit.kafka-enabled: true`, `k8spark.audit.kafka-topic` D `spring.kafka.bootstrap-servers`. PJ OZJJT_IDH Kafka YHEJHTaI_, KJCNJZO MNaIY Ia NLaEOaN ELJEaL_. OUDEE_ KOEJDE_SDD Y Kafka Ia YJDSaN I_ M_Z G_KLJM.

### OMIJYIHa EJ_MMH

| F_EJ | N_GI_TaIDa |
| --- | --- |
| `K8SparkUiApplication.java` | Spring Boot entrypoint. |
| `LdapSecurityConfig.java` | LDAP bind, KJJOTaIDa TGT KLD YRJYa, L_GYaJCIHa SaKJTED YJS `/api` (Basic + MaMMDS) D UI (PJLZ_), YHRJY D G_YaLUaIDa MaMMDD KJ DMNaTaIDD EDJaN_. |
| `WebConfig.java` | R_GY_T_ YaIYJLBIIHR MNDJaE D ULDPNJY Hue KJHM MJEMNYaIIHR _MMaNJY UI. |
| `ClusterProperties.java` | TDKDGDLJY_IIHa _YLaM_ MaLYDMJY D U_EJJI principal KJJCGJY_NaJS. |
| `AppConfig.java` | PJYEJHTaIDa `ClusterProperties` E Spring context. |
| `KerberosTicketService.java` | PJJOTaIDa TGT KJJCGJY_NaJS KJ K_LJJH TaLaG `Krb5LoginModule` D MTDNHY_IDa MLJE_ aUJ YaEMNYDS. |
| `KerberosExecutor.java` | VHKJJIaIDa YHGJY_ KJY EDJaNJZ KJJCGJY_NaJS DG MaMMDD TaLaG `UGI.doAs`. |
| `TicketExpiryFilter.java` | Z_YaLUaIDa MaMMDD Y ZJZaIN DMNaTaIDS EDJaN_: MNL_IDS_ → `/login?expired`, `/api` → `401`. |
| `KerberosTicketCleanup.java` | UIDTNJVaIDa EDJaN_ KJJCGJY_NaJS KLD YHRJYa DJD DMNaTaIDD MLJE_. |
| `TicketModelAdvice.java` | PaLaY_T_ CEL_I_Z JMN_NE_ YLaZaID EDJaN_ YJS JEL_NIJUJ JNMTBN_. |
| `KyuubiService.java` | JDBC connection, YHKJJIaIDa SQL D KLaJEL_GJY_IDa `ResultSet` Y JSON rows. |
| `HdfsService.java` | WebHDFS `LISTSTATUS` D `OPEN` TaLaG `KerberosAuthenticator` D SPNEGO. |
| `HbaseService.java` | PJJIHE HBase-EL_OGaL: VDGIaIIHE SDEJ N_EJDS, OKL_YJaIDa column families, scan M PDJCNL_ZD, DMNJLDS YaLMDE STaEED, ZON_SDD MNLJE/STaaE D CSV bulk upload. |
| `OzoneService.java` | Hadoop `FileSystem` YJS URI `ofs://`, listing D TNaIDa JEKaEN_. |
| `ClusterController.java` | JSON endpoints KJY `/api`. |
| `UiController.java` | SNL_IDSH UI: Editor, Files, Ozone, HBase D PJLZ_ YRJY_. |

### HTTP API

| MaNJY D path | N_GI_TaIDa | TaJJ DJD K_L_ZaNL |
| --- | --- | --- |
| `GET /actuator/health` | Spring health | BaG _ONaINDPDE_SDD. |
| `POST /api/sql` | Kyuubi SQL, MNLJED E_E JEKaENH | JSON `{"sql":"SELECT 1"}`. |
| `POST /api/sql/execute` | Kyuubi SQL, EJJJIED D MNLJED JNYaJCIJ | JSON `{"sql":"SELECT 1"}`, Ia EJJaa 1000 MNLJE. |
| `GET /api/hdfs` | WebHDFS `LISTSTATUS` E_E aMNC | Query parameter `path`, KJ OZJJT_IDH `/`. |
| `GET /api/hdfs/list` | R_GJEL_IIHE listing HDFS | Query parameter `path`, KJ OZJJT_IDH `/`. |
| `GET /api/hdfs/preview` | PaLYHa 64 KB P_EJ_ | Query parameter `path`. |
| `GET /api/hbase/tables` | SKDMJE N_EJDS D DR MJMNJSIDa (enabled) | BaG K_L_ZaNLJY. |
| `GET /api/hbase/describe` | Column families N_EJDSH D DR MYJEMNY_ | Query parameter `table`. |
| `GET /api/hbase/regions` | RaUDJIH N_EJDSH D UL_IDSH EJHTaE | Query parameter `table`. |
| `GET /api/hbase/scan` | Scan MNLJE | `table`, `start`, `prefix`, `limit`, `columns`, `filter` (HBase filter string). |
| `GET /api/hbase/autocomplete` | Row keys KJ KLaPDEMO | `table`, `prefix`, `limit`. |
| `GET /api/hbase/row` | OYI_ MNLJE_ | `table`, `row`, `columns`. |
| `GET /api/hbase/cell/versions` | IMNJLDS YaLMDE STaEED | `table`, `row`, `column`, `versions`. |
| `POST /api/hbase/table/create` | SJGY_NC N_EJDSO | JSON `{table, families:[…]}`. |
| `POST /api/hbase/table/{enable,disable,truncate,delete}` | UKL_YJaIDa N_EJDSaE | JSON `{table[, preserveSplits]}`. |
| `POST /api/hbase/family/{add,modify,delete}` | UKL_YJaIDa column family | JSON `{table, family}`. |
| `POST /api/hbase/row` | Z_KDM_NC/JEIJYDNC STaEED MNLJED | JSON `{table, row, cells}`. |
| `POST /api/hbase/row/delete` | UY_JDNC MNLJEO | JSON `{table, row}`. |
| `POST /api/hbase/cell/delete` | UY_JDNC STaEED | JSON `{table, row, columns}`. |
| `POST /api/hbase/cell/upload` | Z_ULOGDNC EDI_LIJa GI_TaIDa STaEED | Multipart `file` + query `table`, `row`, `column`. |
| `POST /api/hbase/bulk` | Bulk upload DG CSV | Multipart `file` + query `table`. |
| `GET /api/ozone` | SKDMJE OFS paths | Query parameter `path`, KJ OZJJT_IDH `/`. |
| `GET /api/ozone/list` | R_GJEL_IIHE listing Ozone | Query parameter `path`, KJ OZJJT_IDH `/`. |
| `GET /api/ozone/preview` | PaLYHa 64 KB JEKaEN_ | Query parameter `path`. |

### SJEMNYaIIHE UI

PLDJJVaIDa JNY_BN CEL_IH KJ _YLaMO `https://app.test.local:8443/`, KJYNJLSHFDa PJLZH LaPaLaIMIJUJ Hue 4:

| Path | HEL_I | IMNJTIDE Y_IIHR |
| --- | --- | --- |
| `/editor` | Query Editor | Kyuubi TaLaG HiveServer2 JDBC. |
| `/filebrowser` | File Browser | HDFS TaLaG WebHDFS. |
| `/ozone` | Ozone Browser | Ozone TaLaG `ofs://`. |
| `/hbase` | HBase Browser (KJJIHE _I_JJU Hue: N_EJDSH, families, scan/KJDME, STaEED D YaLMDD, ZON_SDD, bulk upload; EOLMJLI_S K_UDI_SDS MNLJE, PDJCNL MKDME_ N_EJDS; YaMNLOENDYIHa YaEMNYDS NLaEOHN YYJY_ KJYNYaLVY_HFaUJ MJJY_) | HBase TaLaG I_NDYIHE RPC. |
| `/jobs` | Spark Jobs | Spark History Server TaLaG REST API. |
| `/jobs/{applicationId}` | Spark UI KLDJJVaIDS | PLJEMDLJY_IIHE UI history server. |
| `/spark-ui/**` | PLJEMD I_ Spark History Server | TJN Va history server, IJ G_ MaMMDaE KLDJJVaIDS. |

HEL_I `/jobs` KJE_GHY_aN Spark-KLDJJVaIDS, EJNJLHa Kyuubi G_KOME_J I_ E_VYJa MJaYDIaIDa: DZS, DYaINDPDE_NJL, KJJCGJY_NaJS, YLaZS MN_LN_, YJDNaJCIJMNC D YaLMDH Spark.

HEL_I JNELHY_aNMS I_ MJEMNYaIIHR G_KOME_R YJUaYUaUJ KJJCGJY_NaJS G_ KJMJaYIHH IaYaJH — E_E job browser Y Hue. PJJa KJDME_ KLaYG_KJJIaIJ JJUDIJZ D DFaN KJ DZaID KLDJJVaIDS, DYaINDPDE_NJLO, KJJCGJY_NaJH D YLaZaID MN_LN_; TNJEH OYDYaNC TOVDa G_KOMED, KJJa YJMN_NJTIJ JTDMNDNC. DD_K_GJI Y_N KaLaEJHT_aNMS ZaVYO YIBZ, IaYaJaE, ZaMSSaZ D KLJDGYJJCIHZ KaLDJYJZ, JHE_S EJJJIE_ MJLNDLOaNMS, L_GZaL MNL_IDSH YHEDL_aNMS DG 10/25/50/100.

NDVISS UL_IDS_ YD_K_GJI_ KaLaY_BNMS Y history server K_L_ZaNLJZ `minDate`, KJCNJZO JDUIDa KLDJJVaIDS Ia YHE_TDY_HNMS. VaLRISS UL_IDS_ KLJDGYJJCIJUJ KaLDJY_ KLDZaISaNMS Y EL_OGaLa: I_ CEL_Ia Y JHEJZ MJOT_a JaVDN OVa G_ULOVaII_S MNL_IDS_.

IZS D DYaINDPDE_NJL KLDJJVaIDS EJDE_EaJCIH D JNELHY_HN aUJ Spark UI YIONLD DINaLPaEM_ KLDJJVaIDS. SNL_IDS_ `/jobs/{applicationId}` YMNL_DY_aN KLJEMDLJY_IIHE UI D Y_BN EIJKEO ME_TDY_IDS event-JJUJY. V MNLJEa MKDME_ N_ Va EIJKE_ YJMNOKI_ JNYaJCIJ.

PLJEMD I_ `/spark-ui/**` KaLaY_BN G_KLJMH history server D YJE_YJSaN G_UJJJYJE `X-Forwarded-Context`, KJCNJZO Spark M_Z MNLJDN YMa MMHJED M CNDZ KLaPDEMJZ D KaLaKDMHY_NC HTML Ia NLaEOaNMS. PJEJTIHE KJJaGIHE CPPaEN: UI history server, EJNJLHE M_Z KJ MaEa JNELHN, TaLaG KLDJJVaIDa YJMNOKaI NJJCEJ _ONaINDPDSDLJY_IIJZO KJJCGJY_NaJH.

VRJY YHKJJISaNMS PJLZJE I_ `/login` NaZD Va LDAP-LaEYDGDN_ZD. DJS UI MJGY_BNMS MaMMDS, _ `/api` KLJYJJV_aN KLDIDZ_NC HTTP Basic, KJCNJZO KLDZaLH M `curl` YHUa L_EJN_HN EaG DGZaIaIDE.

OEJJJTE_ MJEL_I_ DG YaIYJLBIIHR MNDJaE Hue (`hue.css`, `cui.css`, `bootstrap2.css`, `login.css`, Font Awesome D Roboto) Y `src/main/resources/hue-upstream/desktop/static`. LaYHE M_EYE_L Hue 4 MNDJDGOaNMS DGIONLD aB JavaScript-E_IYJ_, EJNJLHE Ia YaIYJLDNMS, KJCNJZO aUJ L_GZaLH D SYaN_ YJMKLJDGYaYaIH Y `src/main/resources/app-static/k8spark.css` KJ GI_TaIDSZ, MISNHZ M CN_JJIIJUJ EJINaEIaL_. LJUJNDKH D NJY_LIHa GI_ED Hue Ia YJMKLJDGYJYSNMS.

### KJIPDUOL_SDJIIHE P_EJ KLDJJVaIDS

VMa I_MNLJEED `k8spark.cluster` D LDAP G_Y_HNMS Y JYIJZ P_EJa — [`config/application.yml`](config/application.yml). OI ZJINDLOaNMS Y EJINaEIaL read-only E_E `/etc/k8spark-ui/application.yml` D KJYEJHT_aNMS TaLaG `SPRING_CONFIG_ADDITIONAL_LOCATION`, KJCNJZO KaLaJKLaYaJSaN GI_TaIDS KJ OZJJT_IDH DG `src/main/resources/application.yml`.

IGZaIaIDa _YLaM_ MaLYDM_ Ia NLaEOaN KaLaMEJLED JEL_G_:

```bash
cd k8spark-ui
docker compose restart app
```

| SYJEMNYJ | N_GI_TaIDa |
| --- | --- |
| `k8spark.cluster.webhdfs-url` | WebHDFS endpoint YJS File Browser. |
| `k8spark.cluster.kyuubi-url` | JDBC URL Kyuubi YJS Query Editor. |
| `k8spark.cluster.hbase-quorum` | ZooKeeper quorum YJS HBase client. |
| `k8spark.cluster.ozone-ofs-uri` | KJLaIC `ofs://` YJS Ozone Browser. |
| `k8spark.cluster.ozone-conf-dir` | K_N_JJU M `core-site.xml` D `ozone-site.xml` Ozone. |
| `k8spark.cluster.spark-history-url` | Spark History Server YJS CEL_I_ Jobs. |
| `k8spark.cluster.kerberos-principal` | C_EJJI principal KJJCGJY_NaJS, `{user}` KJYMN_YJSaNMS DG LDAP-JJUDI_. TGT YJS CNJUJ principal KJJOT_aNMS KJ K_LJJH KLD YRJYa; keytab Ia DMKJJCGOaNMS. |

HND GI_TaIDS I_ZaLaIIJ Ia G_Y_HNMS KaLaZaIIHZD JELOVaIDS Y `compose.yaml`: Y Spring Boot KaLaZaIIHa JELOVaIDS KLDJLDNaNIaa YIaUIaUJ EJIPDUOL_SDJIIJUJ P_EJ_, D G_EHN_S KaLaZaII_S ZJJT_ KaLaELHJ_ EH KL_YEO Y P_EJa.

### Spark event logs D history server

Kyuubi G_KOME_aN Spark engine I_ E_VYJa MJaYDIaIDa D KDUaN aUJ event log Y Ozone:

```text
ofs://ozone.test.local/spark/eventlogs
```

Volume `spark` D bucket `eventlogs` MJGY_BN `docker/ozone/start-ozone` KLD MN_LNa, _ `spark-history` TDN_aN NJN Va E_N_JJU. Java-KLDJJVaIDa KJE_GHY_aN MKDMJE KLDJJVaIDE I_ CEL_Ia `/jobs`, G_EDL_S aUJ DG REST API history server KJ SPNEGO.

Jar `ozone-filesystem-hadoop3` EJKDLOaNMS Y JEL_GH Kyuubi D Spark History DG NJUJ Va JEL_G_ `apache/ozone:2.0.0`, I_ EJNJLJZ L_EJN_aN EJ_MNaL, KJCNJZO EJDaIN D MaLYaLH Ia ZJUON L_GJENDMC KJ YaLMDSZ.

### SEJLE_ Java image

`docker/app/Dockerfile` DMKJJCGOaN multi-stage build:

1. EJKDLOaN NJJCEJ `pom.xml` D G_L_Iaa ME_TDY_aN Maven dependencies YJS CPPaENDYIJUJ Docker cache;
2. EJKDLOaN `src` D MJEDL_aN executable Spring Boot JAR;
3. KaLaIJMDN NJJCEJ JAR Y ZDIDZ_JCIHE Temurin 21 JRE runtime image.

SaLYDM L_EJN_aN NJJCEJ KJ HTTPS D KOEJDEOaN KJLN `8443`. SaLNDPDE_N YJS `app.test.local` YHKOME_aN CA CNJUJ realm KLD bootstrap FreeIPA.

## DaN_JCIJa JKDM_IDa NaMNJYJUJ JELOVaIDS

### KJINaEIaLH D KJLNH

| Compose service | VaLMDS DJD base image | Host ports | RJJC |
| --- | --- | --- | --- |
| `freeipa` | FreeIPA 4.13.1, Rocky Linux 9 | `88/tcp+udp`, `389`, `464/tcp+udp`, `636` | LDAP, Kerberos KDC, service principals D keytabs. |
| `kyuubi` | `apache/kyuubi:1.10.1-spark` | `10009`, `10099` | Kerberized HiveServer2/JDBC, Spark SQL `local[*]` D YMNLJaIIHE web UI. |
| `hdfs` | `apache/hadoop:3.4.2` | `9870` | NameNode, DataNode D SPNEGO WebHDFS. |
| `hbase` | Apache HBase 2.6.2, Temurin 17 | `9090`, `16010` | Embedded ZooKeeper, HMaster, RegionServer, secure Thrift D master UI. |
| `ozone` | `apache/ozone:2.0.0` | `9862`, `9874` | SCM, OM, DataNode, Object Store D OFS. |
| `spark-history` | `apache/spark:4.1.0` | `18080` | Spark History Server, TDN_aN event-JJUD DG Ozone. |
| `app` | Java 21 / Spring Boot | `8443` | k8spark-ui integration API D UI, NJJCEJ HTTPS. |
| `hue-reference` | `gethue/hue:latest` | `8082` | OPDSD_JCIHE Docker Hub Hue YJS ML_YIaIDS UI. |

HDFS DMKJJCGOaN replication factor `1`. Ozone N_EVa I_MNLJaI I_ JYIJOGJJYOH LaKJDE_SDH. HBase RL_IDN Y_IIHa Y JJE_JCIJZ filesystem YIONLD named volume. HNJ EJZK_ENIHE POIESDJI_JCIHE MNaIY, _ Ia production deployment.

### PJLSYJE G_KOME_ D health checks

Compose dependency graph:

1. `freeipa` OMN_I_YJDY_aN realm D YHKJJISaN `bootstrap-test-identities.service`;
2. KJMJa healthy FreeIPA K_L_JJaJCIJ G_KOME_HNMS `kyuubi`, `hdfs`, `hbase` D `ozone`;
3. E_VYHE MaLYDM VYBN MYJD keytabs D G_KOME_aN daemon processes;
4. `app` MN_LNOaN NJJCEJ KJMJa healthy YMaR KSND DIPL_MNLOENOLIHR MaLYDMJY;
5. `hue-reference` IaG_YDMDZ D G_KOME_aNMS KJ OZJJT_IDH;
6. `hue` G_KOME_aNMS NJJCEJ M KLJPDJaZ `hue` D G_YDMDN JN YMaUJ Kerberos-MNaIY_.

Health checks KLJYaLSHN bootstrap FreeIPA, I_JDTDa keytabs, daemon processes D service ports. PJJIJSaIIHa JKaL_SDD TNaIDS D G_KDMD YHKJJISHNMS JNYaJCIHZ `test-kerberos-services.sh`.

### Realm, principals D keytabs

TaMNJYHE realm:

```text
TEST.LOCAL
```

DaZJIMNL_SDJIIHa LaEYDGDNH IJYJUJ realm:

| N_GI_TaIDa | Identity | P_LJJC |
| --- | --- | --- |
| LDAP/Kerberos test user | `admin` / `admin@TEST.LOCAL` | `K8SparkAdmin2026Secure!` |
| FreeIPA Directory Manager | `cn=Directory Manager` | `DirectoryManager1` |

OE_ K_LJJS I_RJYSNMS Y `compose.yaml` D systemd bootstrap unit Y JNELHNJZ YDYa D YJKOMNDZH NJJCEJ YJS DGJJDLJY_IIJUJ JJE_JCIJUJ MNaIY_.

FreeIPA bootstrap MJGY_BN:

| Principal | Keytab Y volume `kerberos-shared` | PJNLaEDNaJC |
| --- | --- | --- |
| `admin@TEST.LOCAL` | `admin.keytab` | Java API D LOTIHa EJDaINMEDa NaMNH. |
| `kyuubi/kyuubi.test.local@TEST.LOCAL` | `kyuubi.keytab` | Kyuubi server. |
| `nn/hdfs.test.local@TEST.LOCAL` | `nn-hdfs.test.local.keytab` | HDFS NameNode. |
| `dn/hdfs.test.local@TEST.LOCAL` | `dn-hdfs.test.local.keytab` | HDFS DataNode. |
| `HTTP/hdfs.test.local@TEST.LOCAL` | `HTTP-hdfs.test.local.keytab` | WebHDFS SPNEGO. |
| `om/ozone.test.local@TEST.LOCAL` | `om-ozone.test.local.keytab` | Ozone Manager. |
| `scm/ozone.test.local@TEST.LOCAL` | `scm-ozone.test.local.keytab` | Storage Container Manager. |
| `dn/ozone.test.local@TEST.LOCAL` | `dn-ozone.test.local.keytab` | Ozone DataNode. |
| `HTTP/ozone.test.local@TEST.LOCAL` | `HTTP-ozone.test.local.keytab` | Ozone HTTP service identity. |
| `hbase/hbase.test.local@TEST.LOCAL` | `hbase-hbase.test.local.keytab` | HMaster, RegionServer D Thrift. |
| `HTTP/hbase.test.local@TEST.LOCAL` | `HTTP-hbase.test.local.keytab` | HBase HTTP service identity. |

`bootstrap-test-identities` N_EVa MJGY_BN JEFDE `/shared/krb5.conf`. Keytabs Ia KJK_Y_HN Y Git: JID UaIaLDLOHNMS KLD KaLYJZ MN_LNa D RL_ISNMS Y Docker named volume. Infrastructure containers ZJINDLOHN aUJ E_E `/shared`, _ Java-KLDJJVaIDa — read-only E_E `/run/keytabs`.

DJS MJYZaMNDZJMND Java 21 application client D Java 8 Y Kyuubi test image service keytabs DMKJJCGOHN AES-SHA1 enctypes. Production enctypes YJJVIH JKLaYaJSNCMS KJJDNDEJE Y_UaUJ KDC D KJYYaLVDY_aZHZD JDK.

### Named volumes

| Volume | SJYaLVDZJa |
| --- | --- |
| `freeipa-data` | FreeIPA realm, LDAP database, KDC D PKI state. |
| `freeipa-run` | Writable `/run` YJS systemd D 389 Directory Server. |
| `freeipa-tmp` | Writable `/tmp` FreeIPA M KJYYaLVEJE IaJERJYDZHR filesystem semantics. |
| `kerberos-shared` | `krb5.conf`, user keytab D service keytabs. |
| `hdfs-data` | NameNode metadata D DataNode blocks. |
| `hbase-data` | HBase D embedded ZooKeeper state. |
| `ozone-data` | SCM, OM, Ratis D DataNode state. |

FreeIPA DMKJJCGOaN `cgroup: host`, KJNJZO TNJ YIONLD EJINaEIaL_ L_EJN_aN systemd. `privileged` I_ZaLaIIJ Ia DMKJJCGOaNMS.

### KJIPDUOL_SDJIIHa E_N_JJUD

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

## OMJEaIIJMND macOS Intel D Apple Silicon

### PJTaZO IaJCGS UJJE_JCIJ G_Y_Y_NC `DOCKER_DEFAULT_PLATFORM`

N_ M1/M2/M3/M4 GI_TaIDa `DOCKER_DEFAULT_PLATFORM=linux/amd64` G_MN_YJSaN Compose JVDY_NC amd64 Y_Va JN multi-arch images, EJNJLHa KLJaEN MJEDL_aN I_NDYIJ. RaGOJCN_N — JUDEE_ YDY_:

```text
image ... was found but its platform (linux/arm64) does not match the specified platform (linux/amd64)
```

`run-test-env.sh` MIDZ_aN CNO KaLaZaIIOH YJS Compose, _ `compose.yaml` G_Y_BN `platform: linux/amd64` NJJCEJ NaZ MaLYDM_Z, EJNJLHZ CNJ YaEMNYDNaJCIJ IOVIJ.

PLJYaLDNC UJJE_JCIOH I_MNLJEEO shell:

```bash
echo "${DOCKER_DEFAULT_PLATFORM-<not set>}"
```

### Docker Desktop Keychain `-67674`

EMJD Docker CLI Ia ZJVaN JEL_NDNCMS E macOS credential helper, public image pull ZJVaN G_YaLUDNCMS JUDEEJE Keychain. SELDKNH KLJaEN_ DMKJJCGOHN `docker/.docker-config` EaG credential store NJJCEJ YJS public pulls/builds D Ia DGZaISHN Docker Desktop login KJJCGJY_NaJS.

VMaUY_ KLaYKJTDN_ENa:

```bash
./scripts/run-test-env.sh -d
```

YZaMNJ LOTIJUJ `docker compose build`, aMJD L_Iaa KJSYJSJ_MC JUDEE_ `-67674`.

### OPDSD_JCIHE Hue I_ Apple Silicon

Docker Hub Hue L_MKLJMNL_ISaNMS E_E amd64 image. PJY Rosetta JEHTIHE `polars` wheel DMKJJCGOaN IaKJYYaLVDY_aZOH CPU instruction D ZJVaN G_YaLUDNCMS M `SIGILL`. `docker/hue-reference/Dockerfile` G_ZaISaN NJJCEJ aUJ I_ `polars-lts-cpu`; UI Hue JMN_BNMS upstream.

## IGYaMNIHa JUL_IDTaIDS

- SNaIY JYIJOGJJYJE D Ia ZJYaJDLOaN JNE_GJOMNJETDYHE production cluster.
- LJUDIH, K_LJJD D generated keytabs KLaYI_GI_TaIH NJJCEJ YJS JJE_JCIJUJ demo.
- BL_OGaL Ia KJJOT_aN Kerberos ticket DG LDAP-K_LJJS _YNJZ_NDTaMED. Java API DMKJJCGOaN MZJINDLJY_IIHE user keytab.
- DJS production YZaMNJ YJJUJYaTIHR user keytabs IOVaI EaGJK_MIHE credential broker DJD EJLJNEJVDYOFDa delegated credentials.
- V KLDJJVaIDD KJE_ IaN LJJaYJE, row-level DJD object-level _YNJLDG_SDD.
- CSRF-G_FDN_ YEJHTaI_ YJS MNL_IDS UI, IJ JNEJHTaI_ YJS `/api`, TNJEH MJRL_IDNC YJEOZaINDLJY_IIHa YHGJYH TaLaG `curl`. DJS MaMMDD Y EL_OGaLa CNJ JGI_T_aN La_JCIHE CSRF-LDME I_ state-changing YHGJY_R `/api`; Y production N_EJE I_EJL IaYJKOMNDZ.
- `gethue/hue:latest` I_ZaLaIIJ DMKJJCGOaNMS NJJCEJ E_E KJYYDVIHE reference; production build YJJVaI G_ELaKJSNC digest.
- UI D REST API Spark History Server L_EJN_HN EaG _ONaINDPDE_SDD. Spark 4 JNY_BN DR TaLaG Jetty I_ `jakarta.servlet`, _ SPNEGO-PDJCNL Hadoop YJ MDR KJL La_JDGOaN `javax.servlet.Filter`, KJCNJZO Spark JNEJJISaN aUJ KLD MN_LNa, D jakarta-MEJLED CNJUJ PDJCNL_ Ia MOFaMNYOaN. CNaIDa event-JJUJY DG Ozone KLD CNJZ YHKJJISaNMS KJY Kerberos-identity history server. ONELHN NJJCEJ web endpoint I_ KJLNO `18080`.
- VMNLJaIIHE UI Kyuubi I_ KJLNO `10099` G_FDFBI SPNEGO. BL_OGaL EaG I_MNLJaIIJUJ Kerberos KJJOTDN `401`; DG Terminal JI YJMNOKaI TaLaG `curl --negotiate -u :` KJMJa `kinit`.

## PLJYaLE_ KJMJa DGZaIaIDS EJIPDUOL_SDD

MDIDZ_JCIHE JESG_NaJCIHE I_EJL KaLaY KaLaY_TaE DGZaIaIDE:

```bash
cd k8spark-ui
mvn test
./scripts/run-test-env.sh -d
./scripts/test-kerberos-services.sh
./scripts/test-start-cycles.sh 10
```

IGZaIaIDa MTDN_aNMS EaGJK_MIHZ YJS NaMNJYJUJ JELOVaIDS NJJCEJ KJMJa OMKaUIJUJ La_JCIJUJ create/read/delete DJD put/get I_ E_VYJZ Kerberos data path, _ Ia NJJCEJ KJMJa MN_NOM_ `healthy`.
