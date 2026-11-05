# AntiFraudBlock · 反诈上报阻断

一个只做两件事的 LSPosed 模块：**掐断手机管家 AI 反诈的遥测/云端上报，并阻断本地应用列表与网络测速数据外发；本地风险提醒、病毒扫描照常工作。**

作用域只有 `com.coloros.phonemanager`（ColorOS / OPPO 手机管家），不碰系统框架、不碰其他应用。

```text
com.coloros.phonemanager 17.7.2 · ColorOS 15 · Android 15 · PGEM10（Find X6 Pro）  ← v2.3 实机
com.coloros.phonemanager 17.1.6 · ColorOS 16 · Android 16 · RMX5200                ← v2.0 原始基线
```

---

## 为什么单独做模块，不并进 LuckyTool

| 理由 | 具体 |
|---|---|
| 作用域不同 | LuckyTool 的 scope 表全是 ColorOS 包，没有 `com.miui.*`；它的页面是 Compose 硬编码页（`ScopePageRegistry` 里 51 页固定排序），每加一页都要动注册表 + 功能树 + 字符串 |
| 上游会变 | LuckyTool 一更新，本地改动就要重新对齐 `ScopePageSpec` / `HookAppFeatureProvider`，冲突成本高 |
| 功能边界清晰 | 本模块只有"阻断上报"一条链路，出问题能整包关掉，不影响 LuckyTool 的其他增强 |

HyperCeiler / Cemiuiler / MIUI_NoGuard 那套（`com.miui.guardprovider`、`com.miui.analytics.onetrack.*`、`trustservice` 的 `mrmd`）是纯小米命名空间，在 ColorOS 上**一个 class 都不存在**，没法照搬。

---

## 实测链路（不是推测，全部从本机 APK 反出）

APK：`/product/priv-app/PhoneManager/PhoneManager.apk`，v17.1.6，5 个 dex，**类名未混淆**。

| 层 | 真实类 / 方法 | 干什么 | 本模块 |
|---|---|---|---|
| 反诈埋点封装 | `aivoicecalldetect.utils.AiDetectStatistics`<br>`b/c/d/e/g/h/i/j`（static void） | 主开关点击、事件计数、推送触达、误报反馈、VOIP 白名单外应用 | **拦截** |
| 云端 DCS 上报 | `aivoicecalldetect.utils.AiVoiceDetectDCSUtils`<br>`m(int,String,boolean,String,String)` / `n(Context)` | 上传号码、包名、通话时长、检测时长、判定码 | **拦截** |
| 事件最终出口 | `com.oplus.nearx.track.TrackApi.E(String appId, String event, Map)`<br>appId = `"20092"` | 所有手机管家遥测的公共出口 | **按事件名拦截** |
| 本地记录落库 | `RiskWarningUtilsKt.insertFraudDetectSecurityEvent` | 反诈记录页的数据来源 | 保留 |
| 本地风险通知 | `FraudDetectNotificationHelperKt.showRiskNotification` | 通知栏诈骗提醒 | 保留 |
| 本地风险弹窗 | `FraudRiskDialogActivity` | 通话中/事后弹窗 | 保留 |
| 本地检测服务 | `AiVoiceDetectForegroundService` | 通话反诈检测本身 | 保留 |
| 本地记录页 | `SecurityEventStatisticsVM` / `SecurityEventStatisticsActivity` | 读本地 DB，不走 TrackApi | 保留 |

被拦的事件名（实测存在于 `AiDetectStatistics` 各 suspend lambda 里的 `vf.l.r(ctx,"20092",<event>,map)`）：

```text
anti_fraud_switches              anti_fraud_events_count
anti_fraud_master_switche        anti_fraud_handle_event
anti_fraud_false_report_event    ai_detect_result_event
ai_detect_voip_app_outside_lists
```

外加前缀兜底：`anti_fraud*` / `ai_detect*` / `fraud*`，用于接住后续版本新增的事件名。

### 17.7.2 的变化（v2.1 适配）

17.7.2 把埋点门面从 `vf.l` 换成 `ad.z`，最终出口换成另一套 SDK：

```text
17.1.6: AiDetectStatistics -> vf.l.r(...) -> com.oplus.nearx.track.TrackApi.E(appId, event, map)
17.7.2: AiDetectStatistics -> ad.z.t(...) -> com.oplus.drs.track.TrackApi.getInstance(20092L)
                                            .track("20092", event, map)
```

同时 `AiVoiceDetectDCSUtils` 的 `m`/`n` 恢复成 `uploadDetectInfo`/`uploadSwitches`。
v2.1 的 hook 点因此改为：

| 层 | 类/方法 | 说明 |
|---|---|---|
| 埋点封装 | `aivoicecalldetect.utils.AiDetectStatistics` 全部 `static void` | 运行期 20 个 static void（jadx 可见 16 个 public，另有合成桥接） |
| DCS | `AiVoiceDetectDCSUtils.{uploadDetectInfo,uploadSwitches,m,n}` | 新旧方法名同时匹配 |
| 总出口（新） | `com.oplus.drs.track.TrackWrapper.track` / `TrackApi.track` 的 6 个重载 | 按 eventId 前缀拦截 |
| 总出口（旧） | `com.oplus.nearx.track.TrackApi.E` | 兼容 17.1.x |

新增拦截的事件名前缀：`suspicious_fraud`、`ai_deep_fake`、`dynamic_risk_behavior`、
`nfc_intercept_blacklist`、`sg_event_`、`sg_privace_`、`sg_start_mode`（安全守护 appId `2018101`）。

> 17.7.2 起，手机管家的反诈数据还有两条**不走 TrackApi** 的出口：通话音频/人脸的云端推理
> （`CloudDetectClient.detectSync` → `odin-inference-cn.allawntech.com`），以及用户主动的
> 反馈/举报上传。前者可用严格模式 `touch /data/local/tmp/afb.strict` 掐断；后者保留。

### 应用列表 / 网络数据（v2.2）

| 出口 | 处理 |
|---|---|
| 云查杀/广告识别 SDK `nm.a`（`IScanner`）的 `actionScanPackages` / `adRuleScanPackages` / `riskScanPackages` / `riskScanApks` / `scanPackages` / `scanApks` / `scanPackagesInfoFromUser` / `riskScanPackagesInfoFromUser` / `featureScan` | 把包名 `Set/Map/List` 参数替换为空集合，调用链继续但服务器收不到本地应用列表；本地扫描照常 |
| `ad_identify` / `risk_app_installed` / `risk_app_scanned` / `accessibility_package_name_statics` / `sys_maint_remove_pkgname` / `cloudscan_abnormal_info` | DRS 出口按事件名拦截 |
| `network_detect_finish_event`（网络类型、测速/诊断结果、平均下载速度） | DRS 出口按事件名拦截 |
| `netusage` 逐应用流量 | 仅本机 `NetworkStatsManager` + 卡片展示，未发现上传 |

实测：手动扫描时 `blanked app list: riskScanPackages (90 entries)`、`riskScanApks (7 entries)`，
本地仍完成 126/97 项扫描；详情见 `docs/agent/05-应用列表与网络数据-补充适配.md`。

### 独立开关页面（v2.4）

模块自带一个设置页，四项拦截各自独立开关，改完 **≤1 秒生效**，不用重启手机管家：

| 开关 | 关掉后放行的内容 |
|---|---|
| 反诈与安全守护上报 | `AiDetectStatistics` / `AiVoiceDetectDCSUtils` 埋点、`anti_fraud*` / `ai_detect*` / `sg_*` 事件 |
| 本地应用列表 | 云查杀/广告识别的包名集合、`risk_app_*` / `ad_identify` 等事件 |
| 网络检测数据 | `network_detect_finish_event` |
| 云端音频 / 人脸推理 | `CloudDetectClient.detectSync`（默认关，即不拦截） |

顶部总开关关掉 = 所有 hook 直接透传（等同停用模块）。

三个入口任选：

```bash
adb shell am start -n com.eta.afb/.SettingsActivity   # 命令行
# 桌面图标「反诈上报阻断」
# LSPosed 管理器 → 模块 → 模块设置
```

### 配置通道：按 libxposed service / api 102 官方约定

| 角色 | 用法 |
|---|---|
| 模块 App（可写） | `XposedServiceHelper.registerListener()` 拿 binder → `XposedService#getRemotePreferences("afb").edit()` |
| 手机管家进程（只读） | `XposedInterface#getRemotePreferences("afb")` + `registerOnSharedPreferenceChangeListener` |
| 命令行逃生阀 | `/data/local/tmp/afb.disable` 等文件，优先级最高、只能放行 |

manifest 里显式声明了 `io.github.libxposed.service.XposedProvider`
（authority `com.eta.afb.XposedService`，`exported="true"`）——手写 aapt2 构建没有
manifest merger，provider 必须自己写；`io.github.libxposed:service` 与 `interface`
两个 aar 的 classes.jar 都要进 dex（缺 interface 会在 `onBinderReceived` 抛
`NoClassDefFoundError: IXposedService$Stub`）。

框架没推 binder 时自动降级：页面另写一份
`/storage/emulated/0/Android/media/com.eta.afb/afb.conf` 快照，hook 侧轮询兜底。

### 三层为什么都拦

- 只拦 `AiDetectStatistics` → 漏掉 `SimCallDetectManager` / `VoipCallDetectTriggerReceiver` 直接调 `AiVoiceDetectDCSUtils.m(...)` 的那条（这条带**手机号**，最敏感）。
- 只拦 `AiVoiceDetectDCSUtils` → 漏掉 `ExaminationStatisticsUtilsKt` 直接调 `.n(context)` 与 `TrackApi.E` 的场景。
- 三层都拦 + sink 兜底 → 新版加了新埋点入口也能被事件名前缀接住。

---

## 本地功能为什么不受影响

`SecurityEventStatisticsVM` 的数据来自本地 DB（`insertFraudDetectSecurityEvent` → `records.e`），`FraudDetectNotificationHelperKt` 直接发通知，`FraudRiskDialogActivity` 直接起 Activity —— 这三条都**不经过** `TrackApi.E`，也不看 `AiDetectStatistics` 的返回值。

所以：**手机照常弹诈骗提醒、照常在记录页留历史，只是不再往云端和统计平台上报。**

---

## 安装

1. 安装 `out/AntiFraudBlock-2.4.apk`（`./build.ps1` 或 `./build.sh` 生成）；
   仓库内 `release/AntiFraudBlock-2.4.apk` 是同一版本，签名与 2.0 正式包不同，二选一即可
2. LSPosed 里启用，作用域勾 **手机管家（com.coloros.phonemanager）**
3. 打开模块图标（或 `am start -n com.eta.afb/.SettingsActivity`）确认四个开关都是开的
4. 强停手机管家后重新打开（`am force-stop com.coloros.phonemanager`）

命令行装 + 验证：

```bash
adb install -r AntiFraudBlock-2.4.apk

# LSPosed 2.2.0 起有 lspctl，可直接核对状态
/data/adb/modules/zygisk_lsposed/lspctl module show com.eta.afb
#   API: min=101 target=102 / Type: modern / Static scope: yes / User 0: enabled
/data/adb/modules/zygisk_lsposed/lspctl scope list com.eta.afb
#   0  com.coloros.phonemanager

# 看是否真的挂上
logcat -s AntiFraudBlock:*
```

正常输出：

```text
I AntiFraudBlock: module loaded, api=102 framework=LSPosed properties=0x7
I AntiFraudBlock: attach com.coloros.phonemanager | enabled=true fraud=true applist=true network=true strict=false src=remote
I AntiFraudBlock: com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics -> 20 method(s) hooked
I AntiFraudBlock: com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils -> 2 method(s) hooked
I AntiFraudBlock: com.oplus.nearx.track.TrackApi.E -> 0 sink hooked          ← 17.7.2 旧出口不存在，正常
I AntiFraudBlock: com.oplus.drs.track.TrackWrapper.track -> 6 sink hooked
I AntiFraudBlock: com.oplus.drs.track.TrackApi.track -> 0 sink hooked        ← 继承方法，正常
I AntiFraudBlock: cloudscan app-list -> 13 method(s) hooked                  ← 应用列表出口
I AntiFraudBlock: CloudDetectClient.detectSync hooked (strict switch)
I AntiFraudBlock: kept local: insertFraudDetectSecurityEvent / showRiskNotification / FraudRiskDialogActivity
I AntiFraudBlock: hit silence statistics AiDetectStatistics#h/3      ← 真实命中
I AntiFraudBlock: blocked track event: risk_app_scanned [drs.TrackWrapper]
I AntiFraudBlock: blanked app list: riskScanPackages (90 entries)
I AntiFraudBlock: settings changed: ... applist=false src=remote     ← 页面上关掉应用列表
```

`src=` 是开关来源：`remote`（LSPosed RemotePreferences，官方通道）> `snapshot`（框架未连接时的文件降级）> `default`。

### 逃生阀

```bash
touch /data/local/tmp/afb.disable     # 本次注入全部空转
am force-stop com.coloros.phonemanager
```

删掉文件再强停一次即恢复阻断。用来快速做 A/B 对照。

单独放行应用列表（反诈拦截仍然生效，用于临时恢复云查杀）：

```bash
touch /data/local/tmp/afb.allow-applist
am force-stop com.coloros.phonemanager
```

v2.4 起这些文件**不用重启**也生效（轮询 1s）：`afb.disable`、`afb.allow-applist`、`afb.allow-fraud`、`afb.allow-network`、`afb.strict`。它们只做「放行」叠加，不覆盖页面里的远程配置。

---

## 自测：确认拦截真的生效

```bash
./selftest.sh            # 只检查装配与本地功能（不动屏幕）
./selftest.sh --active   # 额外打开反诈页产生真实事件
```

脚本在设备上 root shell 里跑（`adb shell sh /path/selftest.sh`），做三段检查，全过退出码 0：

**A. 装配** —— 检查模块已安装；`lspctl` 支持 `module show/scope list` 时顺带核对启用与作用域（2.2.1 起不支持则跳过）；强停手机管家后冷启动，轮询 logcat 最多 15 秒，核对各 hook 是否报告就位。

**B. 拦截** —— 打开 `SecurityHomeActivity`，统计 `hit silence` 与 `blocked track event` 行数。实测样例：

```text
OK   命中 1 次拦截
     11-04 14:16:54.390  7216  7216 I AntiFraudBlock: hit silence com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics#h/3
     sink 层吞掉的 TrackApi 事件：2 条
     11-06 00:12:03.114  ... I AntiFraudBlock: blocked track event: suspicious_fraud_event
```

`sink 层 = 0` 也正常：源头层已经短路时，`TrackApi` 兜底层没机会触发；
只有手机管家用新入口（如 `ad.z.p0`）直接发事件时才会出现 `blocked track event`。

**C. 本地功能** —— 反诈页在任务栈存活、crash 缓冲区无手机管家记录、本地反诈配置文件仍在。

### 两个真实踩过的坑（已在脚本里修掉）

| 坑 | 症状 | 修法 |
|---|---|---|
| LSPosed 把模块日志镜像成 `LSPosedFramework` 行 | `grep -c` 把命中数算成 2 倍 | 计数前加 `grep -v LSPosedFramework` |
| 冷启动 + 日志落盘有延迟，固定 `sleep 6` 不够 | 偶发 **A 段全 FAIL**，但 B 段能看到 `hit silence`（自相矛盾） | 改成轮询到出现 `kept local` 或超时 15 秒 |

---

## 构建

```bash
./build.sh          # Linux/macOS -> out/AntiFraudBlock-2.4.apk
./build.ps1         # Windows/PowerShell -> out/AntiFraudBlock-2.4.apk
```

需要 `javac(11+)`、`aapt2`、`d8`、`zipalign`、`apksigner`、`android.jar(platform 34/35/36 任一)`。
`libxposed api 102` 首次构建自动从 Maven Central 拉（约 30KB）。

> Windows 上注意两点：SDK 里没有 `android-34` 时脚本会自动选 `android-35/36`；
> PowerShell 5.1 会拆坏 `-classpath "a;b"`，`build.ps1` 用 `javac @build2\javac.rsp` 规避。

### 构建模式与签名（重要）

```bash
./build.sh                  # 开发构建 → out/（不进 git）
./build.sh release          # 发布构建 → release/（进 git，随仓库分发）
```

`release/` 里的包建议用**正式密钥**签名，密钥不入库。发布构建显式指定：

```bash
AFB_KS=~/afb-release.jks AFB_KS_PASS=xxx AFB_KS_ALIAS=afb AFB_KS_KEYPASS=xxx ./build.sh release
```

没指定 `AFB_KS` 就跑 `release` 时，脚本会**拒绝执行**并在 stderr 提示；确实要用 debug 证书覆盖，加 `AFB_KS_ALLOW_DEBUG=1`。

| 产物 | 证书 DN | 证书 SHA-256 |
|---|---|---|
| `release/AntiFraudBlock-2.0.apk`（历史正式包） | `CN=AntiFraudBlock, O=ETA, C=CN` | `46d15cd9…32d5c1` |
| `release/AntiFraudBlock-2.4.apk`（当前） | `C=US, O=Android, CN=Android Debug` | `b53cf35c…c75f` |
| `./build.sh` 默认 | `C=US, O=Android, CN=Android Debug` | `b0d023e8…36faa2` |

> 2.4 发布包用 **debug 证书**（正式密钥不在本机）。它和 2.0 正式包**签名不同，不能互相覆盖**：
> 装过 2.0 的机器先 `adb uninstall com.eta.afb` 再装 2.4，重装后回 LSPosed 重新勾作用域。

目录：

```text
module/                       libxposed modern 模块源（当前版本）
  src/com/eta/afb/MainHook.java         hook 本体（4 类拦截，按开关放行）
  src/com/eta/afb/SettingsActivity.java 设置页（仿手机管家卡片风格）
  src/com/eta/afb/AfbApp.java           Application：接收 XposedService binder（官方 service 102）
  src/com/eta/afb/SettingsReader.java   hook 侧只读视图（remote prefs + 变更监听 + 逃生阀）
  src/com/eta/afb/AfbPrefs.java         开关键名、远程 group、降级快照路径
  AndroidManifest.xml
  META-INF/xposed/{module.prop,scope.list,java_init.list}
  res/{values/strings.xml,drawable/ic_launcher_*,mipmap-anydpi-v26/ic_launcher.xml}
legacy/v1.0-xposed82/         最初试的 legacy Xposed API 82 版本（被 LSPosed 判为
                              非 modern 模块、默认 disabled，已废弃，仅留档）
release/                      已签名发布产物（入库）
out/                          开发构建产物（.gitignore）
build.sh                      构建脚本（Unix）
build.ps1                     构建脚本（Windows / PowerShell）
push.sh                       推送脚本（qufz 部署密钥）
selftest.sh                   上机自测（装配 / 拦截 / 本地功能三段核对）
docs/REVERSING.md             逆向取证记录
```

---

## 版本

- **v2.4**（当前）配置改成 libxposed service 102 官方通道：模块 App 用 `XposedService#getRemotePreferences("afb")` 写，宿主侧用 `XposedInterface#getRemotePreferences` 读并注册变更监听；manifest 补 `XposedProvider`，dex 打进 `service` / `interface` 两个 aar（缺 interface 会 NoClassDefFound）。框架没连上时降级读设备快照。
- **v2.3** 四项拦截拆成独立开关 + 模块内设置页，≤1 秒生效、无需重启；新增桌面图标与 LSPosed「模块设置」入口。- **v2.2** 在 v2.1 基础上阻断应用列表/网络数据外发：云查杀 `IScanner(nm.a)` 的包名集合
  替换为空集合（13 个重载），新增 `ad_identify`、`risk_app_installed`、`risk_app_scanned`、
  `accessibility_package_name_statics`、`sys_maint_remove_pkgname`、`cloudscan_abnormal_info`、
  `network_detect_finish_event` 事件拦截。本地扫描、短信/通话反诈不变。
- **v2.1** 适配 17.7.2：新增 `com.oplus.drs.track.TrackApi.track` 总出口、DCS 新方法名
  `uploadDetectInfo/uploadSwitches`、扩展反诈/安全守护事件前缀；新增严格模式
  （`/data/local/tmp/afb.strict`）拦截 `CloudDetectClient.detectSync` 的音频/人脸云推理。
- **v2.0** libxposed API 102 / `XposedModule` modern 形态。LSPosed 自动识别、`xposedscope` 由 `META-INF/xposed/scope.list` 静态声明。
- **v1.0**（废弃）legacy `IXposedHookLoadPackage` + `api-82.jar`。装上去 LSPosed 认成 legacy 模块，`User 0: disabled`，需手动启用且 hook 不生效。留作对照，别用。

---

## 已知边界

- 已在 RMX5200 / ColorOS 16 / 手机管家 17.1.6 与 PGEM10 / ColorOS 15 / 手机管家 17.7.2 上对过代码；
  ColorOS 版本差异会导致类名变动 —— 类名变了就看 `logcat -s AntiFraudBlock:*` 的 `hook class failed`，
  用 jadx 重新对一遍 `aivoicecalldetect.utils.*` 与 `com.oplus.drs.track.*`。
- 拦的是**手机管家自身**的 TrackApi/DCS 上报。以下链路在作用域外，本模块默认不拦：
  - `com.android.mms` → `com.coloros.securityguard` 的短信正文+号码（安全事件）；
  - `com.coloros.securityguard` → `ilighthouse.apps.coloros.com` 的跨设备同步；
  - `com.android.contacts` 经 `com.ted.number` 的号码查询。
- 云端音频/人脸推理（`CloudDetectClient.detectSync`）默认保留，因为那是检测功能本身；
  要一并掐断用严格模式。
- 应用列表拦截会**让云查杀/广告识别拿不到云端结果**（本地引擎仍扫描，UI 可能显示"未发现风险"）；
  灰产/广告同步也可能报 sync fail，属预期副作用。
- 短信/通话反诈的本地检测、提醒、记录不受影响；`anti_fraud*` / `ai_detect*` 被拦的是埋点，
  不是功能本身。
- 手机管家后续版本如果改走 native 上报或换 SDK，需要重新逆向，本模块的 java 层 hook 会失效
  （但不会导致崩溃，只是拦不住）。

---

## 参考

- [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) — 小米系同类实现（`FuckMiuiUpload` / `DisableMrm` / `DisableReport`），思路参考，代码不通用
- [MIUI_NoGuard](https://github.com/gykkuo/MIUI_NoGuard) — 最早的 guardsprovider + analytics 拦截，纯 MIUI
- [libxposed API](https://github.com/libxposed/api) — 本模块用的 modern 模块接口

---

## License

MIT
