package com.eta.afb;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * 反诈上报阻断（AFB）
 *
 * 目标进程：com.coloros.phonemanager（含 :remote / :scan / :card 子进程）
 *
 * 实测链路（PhoneManager 17.1.6 / ColorOS16，未混淆）：
 *   aivoicecalldetect.utils.AiDetectStatistics  -> 反诈埋点封装（开关、事件计数、推送、误报反馈）
 *   aivoicecalldetect.utils.AiVoiceDetectDCSUtils.m(...) / .n(Context)
 *                                               -> 云端 DCS 上报（号码、包名、通话/检测时长、判定码）
 *   com.oplus.nearx.track.TrackApi.E(appId,event,map)  appId="20092" 为最终事件出口
 *
 * 保留不动（本地功能）：
 *   RiskWarningUtilsKt.insertFraudDetectSecurityEvent   -> 本地反诈记录落库
 *   FraudDetectNotificationHelperKt.showRiskNotification-> 本地风险通知
 *   FraudRiskDialogActivity / AiVoiceDetectForegroundService / SecurityEventStatisticsVM
 *                                                       -> 本地弹窗、检测服务、记录页
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AntiFraudBlock";
    private static final String PKG = "com.coloros.phonemanager";

    /** appId：手机管家遥测 */
    private static final String TRACK_APP_ID = "20092";

    /** 逃生阀：存在该文件则全部 hook 空转 */
    private static final String KILL_SWITCH = "/data/local/tmp/afb.disable";

    private static boolean sDisabled = false;

    /** 反诈相关事件名前缀（实测 + 前瞻匹配） */
    private static final String[] BLOCK_PREFIX = {
            "anti_fraud",
            "ai_detect",
            "fraud",
    };

    /** 精确事件名（实测存在于 AiDetectStatistics.*） */
    private static final Set<String> BLOCK_EXACT = new HashSet<String>();

    static {
        BLOCK_EXACT.add("anti_fraud_switches");
        BLOCK_EXACT.add("anti_fraud_events_count");
        BLOCK_EXACT.add("anti_fraud_master_switche");
        BLOCK_EXACT.add("anti_fraud_handle_event");
        BLOCK_EXACT.add("anti_fraud_false_report_event");
        BLOCK_EXACT.add("ai_detect_result_event");
        BLOCK_EXACT.add("ai_detect_voip_app_outside_lists");
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (lp.packageName == null || !lp.packageName.startsWith(PKG)) return;

        sDisabled = new File(KILL_SWITCH).exists();
        log("loaded in " + lp.packageName + (sDisabled ? " (kill switch ON, bypass)" : ""));

        // 1) 反诈埋点封装：所有 static void 方法全部短路（a() 返回 String，自动跳过）
        hookAllStaticVoid(lp.classLoader,
                "com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics", null);

        // 2) 云端 DCS 上报：只打断 m(...) 与 n(Context)
        Set<String> dcs = new HashSet<String>();
        dcs.add("m");
        dcs.add("n");
        hookAllStaticVoid(lp.classLoader,
                "com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils", dcs);

        // 3) 最终出口兜底：TrackApi.E(appId=20092, 事件名, map) 命中反诈事件直接吞掉
        hookTrackApiSink(lp.classLoader);

        // 4) 本地链路不挂钩（仅日志留痕，便于核对）
        log("kept local: insertFraudDetectSecurityEvent / showRiskNotification / FraudRiskDialogActivity");
    }

    /** 挂钩指定类里所有 static void 方法；names 为 null 表示不按名字过滤 */
    private void hookAllStaticVoid(ClassLoader cl, String clsName, final Set<String> names) {
        try {
            Class<?> c = XposedHelpers.findClass(clsName, cl);
            int hit = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != void.class) continue;
                if (names != null && !names.contains(m.getName())) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return null;
                    }
                });
                hit++;
                log("hook " + clsName + "#" + m.getName() + "/" + m.getParameterTypes().length);
            }
            log("class " + clsName + " -> " + hit + " method(s) silenced");
        } catch (Throwable t) {
            log("hook class failed: " + clsName + " : " + t);
        }
    }

    /** TrackApi.E(String appId, String event, Map params) */
    private void hookTrackApiSink(ClassLoader cl) {
        final String cls = "com.oplus.nearx.track.TrackApi";
        try {
            Class<?> c = XposedHelpers.findClass(cls, cl);
            int hit = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!"E".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 3) continue;
                if (ps[0] != String.class || ps[1] != String.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (sDisabled) return;
                        try {
                            Object appId = p.args[0];
                            Object ev = p.args[1];
                            if (!(ev instanceof String)) return;
                            if (!TRACK_APP_ID.equals(String.valueOf(appId))) return;
                            if (isBlockedEvent((String) ev)) {
                                p.setResult(null);
                                log("blocked track event: " + ev);
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                });
                hit++;
            }
            log("TrackApi.E -> " + hit + " sink hooked");
        } catch (Throwable t) {
            log("hook TrackApi failed: " + t);
        }
    }

    private static boolean isBlockedEvent(String ev) {
        if (ev == null || ev.isEmpty()) return false;
        if (BLOCK_EXACT.contains(ev)) return true;
        String low = ev.toLowerCase();
        for (String p : BLOCK_PREFIX) if (low.startsWith(p)) return true;
        return false;
    }

    private static void log(String s) {
        try {
            XposedBridge.log(TAG + ": " + s);
        } catch (Throwable ignore) {
        }
    }
}
