package com.eta.afb;

import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;

import io.github.libxposed.api.XposedInterface;

/**
 * hook 侧的开关视图，遵循 libxposed api 102 的官方约定：
 *
 *   - 配置本体 = 远程首选项 group {@link AfbPrefs#GROUP}；
 *     宿主进程内 {@link XposedInterface#getRemotePreferences(String)} 只读，
 *     模块 App 侧用 {@code XposedService#getRemotePreferences} 写。
 *   - 变更感知 = {@code registerOnSharedPreferenceChangeListener}（框架推送），
 *     再叠一轮低频轮询兜底。
 *   - 命令行走 {@link AfbPrefs#KILL_SWITCH} 等逃生阀，优先级最高。
 *   - 框架不可用（无 remote 能力）时降级读页面写的快照文件。
 */
final class SettingsReader {

    static final int CAT_FRAUD = 1;
    static final int CAT_APPLIST = 2;
    static final int CAT_NETWORK = 4;

    private static final long POLL_MS = 1000L;
    private static final String TAG = "AntiFraudBlock";

    private final XposedInterface xi;

    private SharedPreferences remote;
    private boolean inited;
    private boolean remoteOk;

    // 基础值（远程配置）
    private boolean enabled = true;
    private boolean fraud = true;
    private boolean applist = true;
    private boolean network = true;
    private boolean strict = false;

    // 逃生阀（只放行，不收紧；strict 例外，只收紧）
    private boolean oKill;
    private boolean oFraud;
    private boolean oApplist;
    private boolean oNetwork;
    private boolean oStrict;

    private long stamp = Long.MIN_VALUE;
    private String source = "default";
    private String lastReport;

    SettingsReader(XposedInterface xi) {
        this.xi = xi;
    }

    void init() {
        if (inited) return;
        inited = true;
        try {
            remote = xi.getRemotePreferences(AfbPrefs.GROUP);
        } catch (Throwable t) {
            remote = null;
            safeLog("remote preferences unavailable, fallback to snapshot: " + t);
        }
        if (remote != null) {
            remoteOk = true;
            try {
                remote.registerOnSharedPreferenceChangeListener(
                        new SharedPreferences.OnSharedPreferenceChangeListener() {
                            @Override
                            public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                                loadRemote("push:" + key);
                            }
                        });
            } catch (Throwable t) {
                safeLog("remote listener unavailable: " + t);
            }
            loadRemote("init");
        } else {
            loadSnapshot("init");
        }
        refreshOverrides();
        report(false);
    }

    // ------------------------------------------------------------------ 对外

    boolean blocked(int cat) {
        poll();
        if (!effectiveEnabled() || cat == 0) return false;
        if ((cat & CAT_FRAUD) != 0 && fraud && !oFraud) return true;
        if ((cat & CAT_APPLIST) != 0 && applist && !oApplist) return true;
        if ((cat & CAT_NETWORK) != 0 && network && !oNetwork) return true;
        return false;
    }

    boolean strictCloud() {
        poll();
        return effectiveEnabled() && (strict || oStrict);
    }

    boolean masterEnabled() {
        poll();
        return effectiveEnabled();
    }

    String describe() {
        return "enabled=" + effectiveEnabled()
                + " fraud=" + (fraud && !oFraud)
                + " applist=" + (applist && !oApplist)
                + " network=" + (network && !oNetwork)
                + " strict=" + (strict || oStrict)
                + " src=" + source;
    }

    void refreshForce() {
        stamp = Long.MIN_VALUE;
        poll();
    }

    // ------------------------------------------------------------------ 内部

    private void poll() {
        if (!inited) init();
        long now = SystemClock.uptimeMillis();
        if (stamp != Long.MIN_VALUE && now - stamp < POLL_MS) return;
        stamp = now;
        if (remoteOk) {
            loadRemote("poll");
        } else {
            loadSnapshot("poll");
        }
        refreshOverrides();
        report(true);
    }

    private boolean effectiveEnabled() {
        return enabled && !oKill;
    }

    private void loadRemote(String why) {
        if (remote == null) return;
        try {
            boolean e = remote.getBoolean(AfbPrefs.K_ENABLED, true);
            boolean f = remote.getBoolean(AfbPrefs.K_FRAUD, true);
            boolean a = remote.getBoolean(AfbPrefs.K_APPLIST, true);
            boolean n = remote.getBoolean(AfbPrefs.K_NETWORK, true);
            boolean s = remote.getBoolean(AfbPrefs.K_STRICT, false);
            enabled = e;
            fraud = f;
            applist = a;
            network = n;
            strict = s;
            source = "remote";
            report(!"init".equals(why));
        } catch (Throwable t) {
            safeLog("remote read failed: " + t);
        }
    }

    /** 框架不可用时的降级：页面写的 key=0/1 快照 */
    private void loadSnapshot(String why) {
        File f = AfbPrefs.SNAPSHOT;
        if (!f.isFile()) {
            source = "default";
            return;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[512];
            int n = 0;
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            if (n <= 0) return;
            boolean any = parse(new String(buf, 0, n, "UTF-8"));
            source = any ? "snapshot" : "default";
            report(!"init".equals(why));
        } catch (Throwable t) {
            safeLog("snapshot read failed: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private boolean parse(String text) {
        boolean any = false;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            boolean v = line.substring(eq + 1).trim().startsWith("1");
            if (AfbPrefs.K_ENABLED.equals(k)) {
                enabled = v;
                any = true;
            } else if (AfbPrefs.K_FRAUD.equals(k)) {
                fraud = v;
                any = true;
            } else if (AfbPrefs.K_APPLIST.equals(k)) {
                applist = v;
                any = true;
            } else if (AfbPrefs.K_NETWORK.equals(k)) {
                network = v;
                any = true;
            } else if (AfbPrefs.K_STRICT.equals(k)) {
                strict = v;
                any = true;
            }
        }
        return any;
    }

    private void refreshOverrides() {
        oKill = AfbPrefs.KILL_SWITCH.exists();
        oFraud = AfbPrefs.ALLOW_FRAUD.exists();
        oApplist = AfbPrefs.ALLOW_APPLIST.exists();
        oNetwork = AfbPrefs.ALLOW_NETWORK.exists();
        oStrict = AfbPrefs.STRICT_SWITCH.exists();
    }

    private void report(boolean changed) {
        String s = describe();
        if (s.equals(lastReport)) return;
        lastReport = s;
        safeLog((changed ? "settings changed: " : "settings loaded: ") + s);
    }

    private void safeLog(String s) {
        try {
            xi.log(4, TAG, s);
        } catch (Throwable ignore) {
        }
    }
}
