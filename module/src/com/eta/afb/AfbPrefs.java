package com.eta.afb;

import android.content.SharedPreferences;

import java.io.File;

import io.github.libxposed.service.XposedService;

/**
 * 开关定义：设置页写、手机管家进程内的 hook 读。
 *
 * 官方通道（libxposed service / api 102）：
 *   - App 侧  XposedService#getRemotePreferences("afb")  可读可写
 *   - 宿主侧  XposedInterface#getRemotePreferences("afb") 只读 + 变更监听
 *
 * 降级通道（框架没有推送 binder 时）：
 *   - /storage/emulated/0/Android/media/com.eta.afb/afb.conf（页面写的快照）
 *   - /data/local/tmp/afb.disable 等旧逃生阀（只能放行，永远可用）
 */
public final class AfbPrefs {

    /** 远程配置 group 名；两端必须一致 */
    public static final String GROUP = "afb";

    /** 总开关：关闭则所有 hook 直接透传 */
    public static final String K_ENABLED = "master_enabled";
    /** 反诈 / 安全守护埋点与 DCS 上报 */
    public static final String K_FRAUD = "block_fraud";
    /** 本地应用列表上传（云查杀 / 广告识别） */
    public static final String K_APPLIST = "block_applist";
    /** 网络检测数据上报 */
    public static final String K_NETWORK = "block_network";
    /** 云端音频 / 人脸推理上传 */
    public static final String K_STRICT = "strict_cloud";

    public static final String[] ALL_KEYS = {
            K_ENABLED, K_FRAUD, K_APPLIST, K_NETWORK, K_STRICT
    };

    /** 降级快照文件（页面写，手机管家可读） */
    public static final File SNAPSHOT = new File(
            "/storage/emulated/0/Android/media/com.eta.afb/afb.conf");

    /** 旧版逃生阀（命令行始终有效） */
    public static final File KILL_SWITCH = new File("/data/local/tmp/afb.disable");
    public static final File ALLOW_APPLIST = new File("/data/local/tmp/afb.allow-applist");
    public static final File ALLOW_FRAUD = new File("/data/local/tmp/afb.allow-fraud");
    public static final File ALLOW_NETWORK = new File("/data/local/tmp/afb.allow-network");
    public static final File STRICT_SWITCH = new File("/data/local/tmp/afb.strict");

    /** 默认值：除了云端推理，其余全拦 */
    public static boolean defaultOf(String key) {
        return !K_STRICT.equals(key);
    }

    /**
     * 官方写通道。框架未连接 / 不具备 PROP_CAP_REMOTE 时返回 null。
     * 只能在模块 App 进程调用（宿主进程内 getRemotePreferences 是只读的）。
     */
    public static SharedPreferences remote() {
        XposedService s = AfbApp.service();
        if (s == null) return null;
        try {
            return s.getRemotePreferences(GROUP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 页面里展示的“逃生阀是否生效”清单 */
    public static String[] activeOverrides() {
        StringBuilder sb = new StringBuilder();
        if (KILL_SWITCH.exists()) sb.append("afb.disable（全部放行）");
        if (ALLOW_FRAUD.exists()) sb.append(sb.length() > 0 ? "、" : "").append("afb.allow-fraud");
        if (ALLOW_APPLIST.exists()) sb.append(sb.length() > 0 ? "、" : "").append("afb.allow-applist");
        if (ALLOW_NETWORK.exists()) sb.append(sb.length() > 0 ? "、" : "").append("afb.allow-network");
        if (STRICT_SWITCH.exists()) sb.append(sb.length() > 0 ? "、" : "").append("afb.strict（云端推理拒绝）");
        if (sb.length() == 0) return new String[0];
        return sb.toString().split("、");
    }

    private AfbPrefs() {
    }
}
