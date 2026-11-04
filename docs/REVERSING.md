# 逆向取证记录

本文件记录 AntiFraudBlock 关键结论的**实际命令与输出**，供后续版本核对。
环境：RMX5200 / ColorOS 16 / Android 16 / 手机管家 17.1.6 (versionCode 1700001006)。

---

## 1. 定位反诈组件

```bash
$ pm path com.coloros.phonemanager
package:/product/priv-app/PhoneManager/PhoneManager.apk

$ dumpsys package com.coloros.phonemanager | grep -iE "aivoicecalldetect|Service:|Provider:"
  ...AntiFraudSeqDetailsActivity
  ...antifraudhome.SecurityHomeActivity
  ...aivoicecalldetect.trigger.SimCallDetectTriggerService  permission android.permission.BIND_INCALL_SERVICE
  ...aivoicecalldetect.service.AiVoiceDetectForegroundService
  ...aivoicecalldetect.provider.AiVoiceDetectProvider
  ...aivoicecalldetect.provider.FeedbackFileLogProvider

$ dumpsys package com.coloros.phonemanager | grep versionName
    versionName=17.1.6
```

APK 91,221,783 字节，md5 `775016f8a1c350b64212d7f632bdd060`，5 个 dex：

```bash
unzip -o -q pm.apk -d x 'classes*.dex'
# classes.dex   9,237,144
# classes2.dex  7,056,700
# classes3.dex  5,611,244
# classes4.dex  9,891,016
# classes5.dex  9,502,500
```

反诈代码全在 **classes4.dex**（`AiDetectStatistics` 出现 21 次，其余 dex 为 0）。
反编译：

```bash
jadx -d out --show-bad-code -j 4 x/classes4.dex    # 3263 个类，55 个反编译错误（不影响目标）
```

---

## 2. 反诈埋点封装 `AiDetectStatistics`

方法表（`baksmali` 之后的 `.method` 行，未混淆）：

```text
.method public static final a(ZZZ)Ljava/lang/String;
.method public static final b(Landroid/content/Context;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
.method public static final c(Landroid/content/Context;Ljava/lang/String;Z)V
.method public static d(Landroid/content/Context;ILjava/lang/String;Ljava/lang/String;)V
.method public static final e(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Z)V
.method public static synthetic f(Landroid/content/Context;ILjava/lang/String;Ljava/lang/String;)V
.method public static final g(Landroid/content/Context;Ljava/lang/String;)V
.method public static final h(Landroid/content/Context;II)V
.method public static final i(Landroid/content/Context;Z)V
.method public static final j(Landroid/content/Context;)V
```

`a(ZZZ)` 返回 String（事件名字符串），**不能拦**，否则调用方拿到 null。
其余 9 个都是 `static void`，全部是上报入口 → 本模块按 `static && returnType==void` 批量拦截。

每个上报方法最后都走同一条链：

```java
// AiDetectStatistics$reportAntiFraudEventsCount$1.invokeSuspend
vf.l.r(this.$app, "20092", "anti_fraud_events_count", mapI);
// AiDetectStatistics$reportAntiFraudSwitches$1
vf.l.r(this.$app, "20092", "anti_fraud_switches", mapI);
// AiDetectStatistics$reportAntiFraudMasterSwitchClick$1
vf.l.r(this.$app, "20092", "anti_fraud_master_switche", mapH);   // 注意原代码拼写就是 switche
```

`vf.l` 的反编译类头注释是 `compiled from: Statistics.java`，是独立混淆出来的遥测门面类。

---

## 3. 云端 DCS 上报 `AiVoiceDetectDCSUtils`

```text
.method public static m(ILjava/lang/String;ZLjava/lang/String;Ljava/lang/String;)V   # uploadDetectInfo（混淆后为 m）
.method public static final n(Landroid/content/Context;)V                            # uploadSwitches（混淆后为 n）
```

调用方（`grep -rn "AiVoiceDetectDCSUtils;->"`）：

```text
./d4/com/oplus/phonemanager/aivoicecalldetect/detect/SimCallDetectManager.smali:2800
./d4/com/oplus/phonemanager/aivoicecalldetect/detect/VoipCallDetectManager.smali:6190
./d4/com/oplus/phonemanager/examination/ExaminationStatisticsUtilsKt.smali:655
```

上传字段（`AiVoiceDetectDCSUtils$uploadDetectInfo$1$2` → `vf.l.d(...12 个参数...)` → `TrackApi.E("20092","ai_detect_result_event",map)`）：

```text
package                 被测 App 包名
detect_dur / call_dur   检测时长 / 通话时长（秒）
detect_result           判定结果码
fraud_detect_time       诈骗检测耗时
fraud_result            诈骗判定结果
synthetic_config_version
fraud_detect_code / voice_detect_code   判定码 JSON
voice_detect_start / fraud_detect_start
time                    通话/检测标识（电话号码相关的 callId 映射）
```

号码本身在 `$phoneNumber` 里参与 `records.e.s(...)` 落本地库；`uploadDetectInfo` 那段把 `callId`/`voipCallId` 映射成 `AiVoiceDetectDCSUtils.c(callId)` 的时间戳塞进 `time` 字段。
**这是整条链里最敏感的一跳**，所以单独拦。

---

## 4. 最终出口 `TrackApi.E`

```bash
grep -rn "TrackApi;->E(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V" --include=*.smali . | wc -l
43
```

签名（`d3/com/oplus/nearx/track/TrackApi.smali`）：

```text
.method public final E(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V
```

第一个参数是 appId，手机管家固定 `"20092"`；45 处调用里 43 处走这个口。
`sig` 里 `ContextManager.a.a(20092L).E("20092", <event>, map)` 是同一个东西的 Kotlin 门面。

反诈相关事件名（全部实测，非猜测）：

```text
anti_fraud_switches
anti_fraud_events_count
anti_fraud_master_switche
anti_fraud_handle_event
anti_fraud_false_report_event
ai_detect_result_event
ai_detect_voip_app_outside_lists
```

同 appId 下**不拦**的（避免误伤普通遥测）：`QL_advice`、`classify_num_id`、`morning_scan_end`、`bg_virus_scan`、`desktop_card_quantity`、`page_exposure_event`、`function_complete_event` 等。

---

## 5. 本地功能确认未受影响

| 本地能力 | 类 | 数据来源 | 是否经过 TrackApi.E |
|---|---|---|---|
| 风险通知 | `notification.FraudDetectNotificationHelperKt.showRiskNotification` | 直接 `NotificationManager` | 否 |
| 风险弹窗 | `dialog.FraudRiskDialogActivity` | Activity 直接起 | 否 |
| 记录入库 | `utils.RiskWarningUtilsKt.insertFraudDetectSecurityEvent$2` | `records.e` 本地 DB | 否（`integration update skipped` 分支只是可选回调） |
| 记录页 | `antifraudrecords.viewmodel.SecurityEventStatisticsVM` | `repository.b` 读本地 DB | 否 |
| 检测服务 | `service.AiVoiceDetectForegroundService` | 常驻前台服务 | 否 |

> 唯一注意：`insertFraudDetectSecurityEvent$2` 里有一句 `insertFraudDetectSecurityEvent: integration update skipped` —— 那是给 **别的模块（如安全中心）同步**用的可选回调，跳过它不影响本地记录与通知，本模块未拦截该路径。

---

## 6. 非小米链路（避免照搬的错误示范）

本机**不存在**小米 XSOF，实测：

```bash
$ pm list packages | grep -iE "xsof|xiaomi|miui"
package:com.xiaomi.mico          # 米家 App
package:com.miui.weather2        # 小米天气（用户装的）
package:com.xiaomi.smarthome     # 米家
package:com.mc.xiaomi1           # 第三方
```

无 `com.miui.securitycenter`、无 `com.xiaomi.security.xsof.*`、无 `com.miui.analytics`、无 `trustservice`。
HyperCeiler 的 `FuckMiuiUpload` / `DisableMrm` / `DisableReport` 三件套在这里一个类都命中不了。

---

## 7. 构建期踩过的坑

| 坑 | 现象 | 解法 |
|---|---|---|
| LSPosed 不认 legacy 模块 | 装 v1.0 后 `lspctl module show` 显示 `User 0: disabled`，手动启用也不 hook | 改用 **libxposed API 102 modern 模块**：`extends XposedModule` + `META-INF/xposed/{module.prop,scope.list,java_init.list}`，不再用 `assets/xposed_init` + `meta-data` |
| `android-36/37` 的 android.jar 不能被 Debian 版 aapt2 解析 | `RES_TABLE_TYPE_TYPE entry offsets overlap actual entry data` | 用 `platforms/android-34/android.jar` |
| `aapt2 link` 报 `does not override an existing resource` | 编译 `values/arrays.xml` 时报错 | 加 `--auto-add-overlay` |
| `javac -bootclasspath` 与 `-target 11` 冲突 | `option --boot-class-path not allowed with target 11` | 用 `--release 11 -classpath android.jar:classes.jar` |
| build-tools 里的 `zipalign`/`aapt2` 是 x86-64 ELF | `Exec format error` | 用 `/usr/bin/zipalign`、`/usr/bin/aapt2`（Debian 版） |
| `libxposed api` 不在 api.xposed.info | 那个地址只有 legacy 的 `api-82.jar` | Maven Central：`https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar` |
