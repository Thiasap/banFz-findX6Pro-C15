package com.eta.afb;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.error.HookFailedError;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 反诈上报阻断（AntiFraudBlock / libxposed API 102 版）
 *
 * 作用域：com.coloros.phonemanager（含 :remote / :scan / :card 等子进程）
 *
 * 版本适配说明（v2.1）：
 *   - PhoneManager 17.7.2 把埋点门面从 vf.l 换成 ad.z，最终出口从
 *     com.oplus.nearx.track.TrackApi.E(...) 换成 com.oplus.drs.track.TrackApi
 *     （继承 TrackWrapper）的 track(String eventGroup, String eventId, ...)。
 *     旧版只 hook E，在 17.7.2 上一个都命中不了 —— 本版两代出口都挂。
 *   - AiVoiceDetectDCSUtils 的混淆方法名 m/n 在 17.7.2 恢复成
 *     uploadDetectInfo / uploadSwitches，本版按两套名字同时匹配。
 *   - 反诈相关事件名新增 suspicious_fraud_event / ai_deep_fake_* /
 *     dynamic_risk_behavior_event / nfc_intercept_blacklist_event /
 *     sg_*（安全守护 appId 2018101）；前缀表已扩展。
 *
 * v2.3：开关拆成 4 个独立项（反诈 / 应用列表 / 网络检测 / 云端推理），
 *   hook 一律常驻，运行期读 {@link SettingsReader} 决定放行还是拦截，
 *   由模块内的设置页（SettingsActivity）或 adb 逃生阀实时控制，无需重启。
 *
 * 默认保留：本地风险通知 / 弹窗 / 记录页 / 检测服务 / 本地证据加密存储。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "AntiFraudBlock";
    private static final String PKG = "com.coloros.phonemanager";

    private static final String CLOUD_SCAN_IFACE = "com.oplus.cloud.cloudscan.IScanner";

    private SettingsReader settings;

    /** 每个 label / 事件只打一次日志，避免刷爆 logcat */
    private final Set<String> logged = Collections.synchronizedSet(new HashSet<String>());

    /** 反诈 / 安全守护类事件前缀（小写匹配） */
    private static final String[] FRAUD_PREFIX = {
            "anti_fraud",              // 反诈首页/开关/误报/证据点击
            "ai_detect",               // AI 通话检测结果、跳过原因、VOIP 白名单
            "fraud",                   // 兜底：fraud_* 事件
            "suspicious_fraud",        // 可疑诈骗应用（带包名/版本/签名 MD5）
            "ai_deep_fake",            // AI 换脸检测开关与结果
            "dynamic_risk_behavior",   // 跨场景防诈：风险行为信号
            "nfc_intercept_blacklist", // NFC 支付拦截黑名单
            "sg_event_",               // 安全守护：事件发生/本人处理/监护人处理
            "sg_privace_",             // 安全守护：隐私弹窗/设置
            "sg_start_mode"            // 安全守护：启动方式
    };

    /** 本地应用列表类事件前缀 */
    private static final String[] APPLIST_PREFIX = {
            "accessibility_package",   // 无障碍服务包名列表（pkg_list）
            "cloudscan_abnormal",      // 云查杀异常信息（可能带包名）
            "risk_app_",               // risk_app_installed / risk_app_scanned
            "ad_identify",             // 广告识别：包名 + 版本 + 签名 MD5
            "sys_maint_remove_pkg"     // 清理/卸载包名
    };

    /** 网络检测类事件前缀 */
    private static final String[] NETWORK_PREFIX = {
            "network_detect"           // network_detect_finish_event
    };

    /** 17.7.2 里 DCS 上传方法已恢复可读名；17.1.6 是混淆后的 m/n */
    private static final Set<String> DCS_METHODS = new HashSet<>(Arrays.asList(
            "uploadDetectInfo", "uploadSwitches", "m", "n"
    ));

    /**
     * 云查杀/广告识别会把这些方法里的包名集合（Set/Map/List）上传到 OPPO 云端。
     * 这些方法的第 3 个参数（或因重载在第 2 个）就是包名集合；这里替换成空集合，
     * 让流程继续走但服务器收不到本地应用列表。本地扫描引擎不受影响。
     */
    private static final Set<String> CLOUD_SCAN_UPLOAD_METHODS = new HashSet<>(Arrays.asList(
            "actionScanPackages",
            "adRuleScanPackages",
            "riskScanPackages",
            "riskScanApks",
            "scanPackages",
            "scanApks",
            "scanPackagesInfoFromUser",
            "riskScanPackagesInfoFromUser",
            "featureScan"
    ));

    public MainHook() {
        super();
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        settings = new SettingsReader(this);
        log("module loaded, api=" + getApiVersion() + " framework=" + getFrameworkName()
                + " properties=0x" + Long.toHexString(getFrameworkProperties()));
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (pkg == null || !pkg.startsWith(PKG)) return;
        if (settings == null) settings = new SettingsReader(this);

        settings.init();
        log("attach " + pkg + " | " + settings.describe());

        ClassLoader cl = param.getDefaultClassLoader();

        // 1) 反诈埋点封装：所有 static void 全短路（返回 String 的辅助方法不动）
        hookStaticVoid(cl, "com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics",
                null, "statistics");

        // 2) DCS 云端上报：旧版 m/n，17.7.2 的 uploadDetectInfo/uploadSwitches
        hookStaticVoid(cl, "com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils",
                DCS_METHODS, "dcs");

        // 3) 两代最终出口
        hookLegacyTrackSink(cl);   // 17.1.x: com.oplus.nearx.track.TrackApi.E
        hookDrsTrackSink(cl);      // 17.7.x: com.oplus.drs.track.{TrackWrapper,TrackApi}.track
        // 4) 应用列表：云查杀 / 广告识别的包名集合
        hookCloudScanUploads(cl);
        // 5) 云端音频 / 人脸推理
        hookCloudDetect(cl);

        log("kept local: insertFraudDetectSecurityEvent / showRiskNotification / FraudRiskDialogActivity / EvidenceStorageManager");
    }

    // ------------------------------------------------------------------ hooks

    private void hookStaticVoid(ClassLoader cl, String clsName, Set<String> names, final String tag) {
        try {
            Class<?> c = Class.forName(clsName, false, cl);
            Method[] ms = c.getDeclaredMethods();
            int hit = 0;
            for (Method m : ms) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != void.class) continue;
                if (names != null && !names.contains(m.getName())) continue;
                final String label = tag + " " + shortName(clsName) + "#" + m.getName()
                        + "/" + m.getParameterTypes().length;
                hookSilence(m, label, SettingsReader.CAT_FRAUD);
                hit++;
            }
            log(clsName + " -> " + hit + " method(s) hooked");
        } catch (Throwable t) {
            log("hook class failed: " + clsName + " : " + t);
        }
    }

    /** 旧出口：com.oplus.nearx.track.TrackApi.E(String appId, String event, Map) */
    private void hookLegacyTrackSink(ClassLoader cl) {
        final String cls = "com.oplus.nearx.track.TrackApi";
        try {
            Class<?> c = Class.forName(cls, false, cl);
            int hit = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!"E".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 3) continue;
                if (ps[0] != String.class || ps[1] != String.class) continue;
                hookEventSink(m, "nearx.E");
                hit++;
            }
            log(cls + ".E -> " + hit + " sink hooked");
        } catch (Throwable t) {
            log("hook nearx TrackApi failed: " + t);
        }
    }

    /** 新出口：com.oplus.drs.track.TrackWrapper.track(String,String[,Map|JSONObject|Callback]) */
    private void hookDrsTrackSink(ClassLoader cl) {
        for (String cls : new String[]{
                "com.oplus.drs.track.TrackWrapper",
                "com.oplus.drs.track.TrackApi"}) {
            try {
                Class<?> c = Class.forName(cls, false, cl);
                int hit = 0;
                for (Method m : c.getDeclaredMethods()) {
                    if (!"track".equals(m.getName())) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length < 2 || ps.length > 4) continue;
                    if (ps[0] != String.class || ps[1] != String.class) continue;
                    hookEventSink(m, "drs." + shortName(cls));
                    hit++;
                }
                log(cls + ".track -> " + hit + " sink hooked");
            } catch (Throwable t) {
                log("hook drs " + cls + " failed: " + t);
            }
        }
    }

    /**
     * 应用列表外发：云查杀/广告识别 SDK（nm.a，唯一 IScanner 实现）把包名集合
     * 放进 Set/Map/List 参数后上传。这里把这些集合替换成空集合，保留调用链与回调。
     */
    private void hookCloudScanUploads(ClassLoader cl) {
        final String cls = "nm.a";
        try {
            Class<?> c = Class.forName(cls, false, cl);
            Class<?> iface = Class.forName(CLOUD_SCAN_IFACE, false, cl);
            if (!iface.isAssignableFrom(c)) {
                log("cloudscan: " + cls + " is not an IScanner, skip");
                return;
            }
            Method[] ms = c.getDeclaredMethods();
            int hit = 0;
            for (Method m : ms) {
                if (!CLOUD_SCAN_UPLOAD_METHODS.contains(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                int idx = -1;
                for (int i = 0; i < ps.length; i++) {
                    if (Map.class.isAssignableFrom(ps[i]) || Collection.class.isAssignableFrom(ps[i])) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) continue;
                hookCloudScanMethod(m, idx, ps[idx], m.getName());
                hit++;
            }
            log("cloudscan app-list -> " + hit + " method(s) hooked");
        } catch (Throwable t) {
            log("hook cloudscan failed: " + t);
        }
    }

    private void hookCloudScanMethod(final Method m, final int idx, final Class<?> type, final String name) {
        try {
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!settings.blocked(SettingsReader.CAT_APPLIST)) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    if (idx >= args.length) return chain.proceed();
                    Object orig = args[idx];
                    int size = 0;
                    if (orig instanceof Collection) size = ((Collection<?>) orig).size();
                    else if (orig instanceof Map) size = ((Map<?, ?>) orig).size();
                    if (size <= 0) return chain.proceed();
                    if (Map.class.isAssignableFrom(type)) args[idx] = Collections.emptyMap();
                    else if (Set.class.isAssignableFrom(type)) args[idx] = Collections.emptySet();
                    else if (List.class.isAssignableFrom(type)) args[idx] = Collections.emptyList();
                    else if (Collection.class.isAssignableFrom(type)) args[idx] = Collections.emptyList();
                    else return chain.proceed();
                    logOnce("applist:" + name, "blanked app list: " + name + " (" + size + " entries)");
                    return chain.proceed(args);
                }
            });
        } catch (HookFailedError e) {
            log("hook cloudscan method failed " + name + " : " + e);
        } catch (Throwable t) {
            log("hook cloudscan method error " + name + " : " + t);
        }
    }

    /** 云端音频/人脸推理：CloudDetectClient.detectSync(String, byte[], String, long) */
    private void hookCloudDetect(ClassLoader cl) {
        final String cls = "com.oplus.phonemanager.aivoicecalldetect.utils.CloudDetectClient";
        try {
            Class<?> c = Class.forName(cls, false, cl);
            Method m = c.getDeclaredMethod("detectSync", String.class, byte[].class, String.class, long.class);
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!settings.strictCloud()) return chain.proceed();
                    logOnce("strict:cloud", "strict: cloud audio/face inference denied");
                    return Boolean.FALSE;
                }
            });
            log("CloudDetectClient.detectSync hooked (strict switch)");
        } catch (Throwable t) {
            log("hook CloudDetectClient failed: " + t);
        }
    }

    /**
     * 静默：命中即吞掉返回值、不执行原方法（用于无返回值的埋点函数）。
     */
    private void hookSilence(Executable exe, final String label, final int cat) {
        try {
            hook(exe).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!settings.blocked(cat)) return chain.proceed();
                    logOnce("silence:" + label, "hit " + label);
                    return null;
                }
            });
        } catch (HookFailedError e) {
            log("hook failed " + label + " : " + e);
        } catch (Throwable t) {
            log("hook error " + label + " : " + t);
        }
    }

    /** 埋点出口：按事件名归类后决定放行/拦截 */
    private void hookEventSink(Executable exe, final String where) {
        try {
            hook(exe).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object ev = chain.getArg(1);
                    int cat = (ev instanceof String) ? categoryOf((String) ev) : 0;
                    if (cat == 0 || !settings.blocked(cat)) return chain.proceed();
                    logOnce("ev:" + ev, "blocked track event: " + ev + " [" + where + "]");
                    return null;
                }
            });
        } catch (HookFailedError e) {
            log("hook failed sink " + where + " : " + e);
        } catch (Throwable t) {
            log("hook error sink " + where + " : " + t);
        }
    }

    // ------------------------------------------------------------------ utils

    static int categoryOf(String ev) {
        if (ev == null || ev.isEmpty()) return 0;
        String low = ev.toLowerCase(Locale.ROOT);
        int cat = 0;
        if (startsWithAny(low, FRAUD_PREFIX)) cat |= SettingsReader.CAT_FRAUD;
        if (startsWithAny(low, APPLIST_PREFIX)) cat |= SettingsReader.CAT_APPLIST;
        if (startsWithAny(low, NETWORK_PREFIX)) cat |= SettingsReader.CAT_NETWORK;
        return cat;
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    private static String shortName(String cls) {
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }

    private void logOnce(String key, String msg) {
        if (logged.add(key)) log(msg);
    }

    private void log(String s) {
        try {
            Log.i(TAG, s);
        } catch (Throwable ignore) {
        }
        try {
            this.log(4, TAG, s);
        } catch (Throwable ignore) {
        }
    }
}
