## v2.0 — 反诈上报阻断首个正式版

针对 **ColorOS / OPPO 手机管家**（`com.coloros.phonemanager`）的 AI 反诈遥测阻断模块。
只在 `RMX5200 / ColorOS 16 / Android 16 / 手机管家 17.1.6` 实测。

### 做了什么

拦截三层上报，保留全部本地反诈功能：

| 层 | 目标 | 处理 |
|---|---|---|
| 反诈埋点封装 | `aivoicecalldetect.utils.AiDetectStatistics`（9 个 static void） | 全部短路 |
| 云端 DCS 上报 | `aivoicecalldetect.utils.AiVoiceDetectDCSUtils.m/n` | 全部短路 |
| 事件最终出口 | `com.oplus.nearx.track.TrackApi.E(appId="20092", …)` | 按事件名拦截 |

**保留不动**：本地风险通知、风险弹窗、反诈记录落库、记录页、前台检测服务。
换句话说：手机照常弹诈骗提醒、照常在记录页留历史，只是不再往云端和统计平台上报。

### 安装

1. 下载 `AntiFraudBlock-2.0.apk`
2. LSPosed 启用，作用域勾 **手机管家（com.coloros.phonemanager）**
3. `am force-stop com.coloros.phonemanager` 后重新打开

```bash
adb install -r AntiFraudBlock-2.0.apk
logcat -s AntiFraudBlock:*
```

### 校验

| | 值 |
|---|---|
| APK | `AntiFraudBlock-2.0.apk` |
| 大小 | 21190 字节 |
| MD5 | `8bb53c1b77e3ea93a8b30dbad8853707` |
| 签名 | `CN=AntiFraudBlock, O=ETA, C=CN` |
| 证书 SHA-256 | `46d15cd9…32d5c1` |
| 包名 / 版本 | `com.eta.afb` / versionCode 2, versionName 2.0 |

> 从 `release/` 装的包和 `./build.sh` 重建出来的包**签名不同**（重建版是 Android Debug 证书），不能互相覆盖安装，换装前先 `adb uninstall com.eta.afb`。

### 逃生阀

```bash
touch /data/local/tmp/afb.disable
am force-stop com.coloros.phonemanager
```

### 实测日志

```text
I AntiFraudBlock: module loaded, api=102 framework=LSPosed
I AntiFraudBlock: attach com.coloros.phonemanager
I AntiFraudBlock: com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics -> 9 method(s) silenced
I AntiFraudBlock: com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils -> 2 method(s) silenced
I AntiFraudBlock: TrackApi.E -> 1 sink hooked
I AntiFraudBlock: hit silence ...AiDetectStatistics#h/3
```

完整逆向取证见仓库 [`docs/REVERSING.md`](https://github.com/yooma1/qufz/blob/main/docs/REVERSING.md)：
包名定位、方法表、7 个被拦事件名、43 处 `TrackApi.E` 调用统计、构建期 7 个坑。
