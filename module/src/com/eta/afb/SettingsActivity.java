package com.eta.afb;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块设置页（仿手机管家「设置」卡片风格）。
 *
 * 每个拦截项一个独立开关，改动同时写入：
 *   - /data/local/tmp/afb/conf/*   （手机管家进程直接读，≤1 秒生效）
 *   - 模块自身 SharedPreferences    （LSPosed 远程首选项通道，兜底）
 *
 * 入口：桌面图标 / LSPosed 管理器「模块设置」/ adb am start -n com.eta.afb/.SettingsActivity
 */
public class SettingsActivity extends Activity {

    private static final int BG = 0xFF0B0E12;
    private static final int CARD = 0xFF161B22;
    private static final int STROKE = 0x1FFFFFFF;
    private static final int DIVIDER = 0x14FFFFFF;
    private static final int TITLE = 0xFFE6EDF3;
    private static final int SUB = 0xFF8B949E;
    private static final int DIM = 0xFF6E7681;
    private static final int ACCENT = 0xFF2BD576;
    private static final int WARN = 0xFFF2B33D;

    private static final String TARGET_PKG = "com.coloros.phonemanager";

    private SharedPreferences sp;
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, View> rows = new LinkedHashMap<>();

    private TextView statusTitle;
    private TextView statusDesc;
    private TextView overrideBanner;
    private TextView footer;
    private Switch master;

    /** 框架通道（XposedService）是否已连上 */
    private boolean remoteOk;
    /** 写远程配置走单线程，避免同步 Binder 压主线程 / 乱序覆盖 */
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** 视图回填期间忽略开关回调 */
    private boolean binding;
    /** 框架 binder 通常比 Activity 晚到，做几次短重试 */
    private int retries = 6;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sp = getSharedPreferences(AfbPrefs.GROUP, MODE_PRIVATE);
        decorateWindow();
        setContentView(buildRoot());
        applyPrefs();
        refreshUi();
        syncFromRemote();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (master != null) {
            applyPrefs();
            refreshUi();
        }
        if (!remoteOk) {
            retries = 6;
            syncFromRemote();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 存储

    /**
     * 官方写通道：XposedService#getRemotePreferences(group) 可读可写
     * （同步 Binder，放后台单线程执行）；框架没连上时降级写设备快照。
     */
    private void publish() {
        final Map<String, Boolean> snap = new LinkedHashMap<>();
        for (String k : AfbPrefs.ALL_KEYS) {
            snap.put(k, sp.getBoolean(k, AfbPrefs.defaultOf(k)));
        }
        writeSnapshot(snap);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = pushRemote(snap);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        remoteOk = ok;
                        refreshUi();
                    }
                });
            }
        });
    }

    /** 启动时以远程配置为准回填（框架推送的是权威值） */
    private void syncFromRemote() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                SharedPreferences remote = AfbPrefs.remote();
                if (remote == null) {
                    retryLater();
                    return;
                }
                final boolean empty;
                boolean changed = false;
                try {
                    empty = remote.getAll().isEmpty();
                    for (String k : AfbPrefs.ALL_KEYS) {
                        if (empty) break;
                        boolean v = remote.getBoolean(k, AfbPrefs.defaultOf(k));
                        if (v != sp.getBoolean(k, AfbPrefs.defaultOf(k))) {
                            sp.edit().putBoolean(k, v).commit();
                            changed = true;
                        }
                    }
                } catch (Throwable t) {
                    android.util.Log.w("AntiFraudBlock", "remote read failed: " + t);
                    retryLater();
                    return;
                }
                final boolean seed = empty;
                final boolean refilled = changed;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        remoteOk = true;
                        if (refilled) applyPrefs();
                        refreshUi();
                        if (seed) publish();   // 首次：把默认值写进远程配置
                    }
                });
            }
        });
    }

    private void retryLater() {
        if (retries-- <= 0) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    remoteOk = false;
                    refreshUi();
                }
            });
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                syncFromRemote();
            }
        }, 800);
    }

    private boolean pushRemote(Map<String, Boolean> snap) {
        SharedPreferences remote = AfbPrefs.remote();
        if (remote == null) return false;
        try {
            SharedPreferences.Editor e = remote.edit();
            for (Map.Entry<String, Boolean> en : snap.entrySet()) {
                e.putBoolean(en.getKey(), en.getValue());
            }
            return e.commit();
        } catch (Throwable t) {
            android.util.Log.w("AntiFraudBlock", "remote prefs write failed: " + t);
            return false;
        }
    }

    /** 降级快照：/storage/emulated/0/Android/media/com.eta.afb/afb.conf */
    private void writeSnapshot(Map<String, Boolean> snap) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> en : snap.entrySet()) {
            sb.append(en.getKey()).append('=').append(en.getValue() ? '1' : '0').append('\n');
        }
        byte[] body;
        try {
            body = sb.toString().getBytes("UTF-8");
        } catch (Throwable t) {
            return;
        }
        writeFile(new File(getFilesDir(), "afb.conf"), body);
        try {
            File[] dirs = getExternalMediaDirs();
            if (dirs != null) {
                for (File d : dirs) {
                    if (d == null) continue;
                    if (!d.isDirectory() && !d.mkdirs()) continue;
                    writeFile(new File(d, "afb.conf"), body);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("AntiFraudBlock", "media snapshot failed: " + t);
        }
    }

    private void writeFile(File f, byte[] body) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(body);
            out.flush();
        } catch (Throwable t) {
            android.util.Log.w("AntiFraudBlock", "snapshot write failed: " + f + " : " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }


    // ------------------------------------------------------------------ 窗口

    private void decorateWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(BG));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(28);
    }

    // ------------------------------------------------------------------ 布局

    private View buildRoot() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, statusBarHeight() + dp(18), pad, dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addHeader(root);
        addStatusCard(root);

        addSectionLabel(root, "上报拦截");
        LinearLayout blockCard = addCard(root);
        addRow(blockCard, AfbPrefs.K_FRAUD, "反诈与安全守护上报",
                "anti_fraud · ai_detect · sg_* 埋点，以及 DCS 云端上报", true);
        addDivider(blockCard);
        addRow(blockCard, AfbPrefs.K_APPLIST, "本地应用列表",
                "云查杀、广告识别的包名集合；risk_app · ad_identify 事件", true);
        addDivider(blockCard);
        addRow(blockCard, AfbPrefs.K_NETWORK, "网络检测数据",
                "network_detect_finish_event：网速、诊断结果", true);

        addSectionLabel(root, "云端推理");
        LinearLayout cloudCard = addCard(root);
        addRow(cloudCard, AfbPrefs.K_STRICT, "云端音频 / 人脸推理",
                "CloudDetectClient.detectSync 上传；开启后云端 AI 检测不可用", true);

        addSectionLabel(root, "说明");
        addNotesCard(root);

        addButtons(root);

        footer = new TextView(this);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        footer.setTextColor(DIM);
        footer.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(18);
        root.addView(footer, flp);
        return scroll;
    }

    private void addHeader(LinearLayout root) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(ACCENT);
        barBg.setCornerRadius(dp(2));
        bar.setBackground(barBg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(4), dp(30));
        blp.rightMargin = dp(12);
        head.addView(bar, blp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("反诈上报阻断");
        title.setTextColor(TITLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(title);

        TextView sub = new TextView(this);
        sub.setText("v" + versionName() + " · 作用域 " + TARGET_PKG);
        sub.setTextColor(SUB);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        titles.addView(sub, slp);
        head.addView(titles);
        root.addView(head);
    }

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = addCard(root);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
        lp.topMargin = dp(20);
        card.setLayoutParams(lp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(14), dp(15));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        statusTitle = new TextView(this);
        statusTitle.setTextColor(TITLE);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        statusTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(statusTitle);

        statusDesc = new TextView(this);
        statusDesc.setTextColor(SUB);
        statusDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        col.addView(statusDesc, dlp);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        master = newSwitch();
        master.setChecked(sp.getBoolean(AfbPrefs.K_ENABLED, true));
        row.addView(master);
        card.addView(row);

        overrideBanner = new TextView(this);
        overrideBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        overrideBanner.setTextColor(WARN);
        overrideBanner.setPadding(dp(16), 0, dp(16), dp(13));
        overrideBanner.setVisibility(View.GONE);
        card.addView(overrideBanner);

        switches.put(AfbPrefs.K_ENABLED, master);
        master.setOnCheckedChangeListener(listenerFor(AfbPrefs.K_ENABLED));
    }

    private void addSectionLabel(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(SUB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        lp.bottomMargin = dp(9);
        lp.leftMargin = dp(4);
        root.addView(tv, lp);
    }

    private LinearLayout addCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), STROKE);
        card.setBackground(bg);
        card.setClipToOutline(true);
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void addDivider(LinearLayout card) {
        View v = new View(this);
        v.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
        lp.leftMargin = dp(16);
        card.addView(v, lp);
    }

    private void addRow(final LinearLayout card, final String key, String title,
                        String desc, final boolean def) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setClickable(true);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TITLE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        col.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(SUB);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        d.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        dlp.rightMargin = dp(10);
        col.addView(d, dlp);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = newSwitch();
        sw.setChecked(sp.getBoolean(key, def));
        row.addView(sw);
        card.addView(row);

        switches.put(key, sw);
        rows.put(key, row);

        sw.setOnCheckedChangeListener(listenerFor(key));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Switch s = switches.get(key);
                if (s != null && s.isEnabled()) s.toggle();
            }
        });
    }

    private Switch newSwitch() {
        Switch sw = new Switch(this);
        sw.setShowText(false);
        ColorStateList thumb = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, 0xFF5C6673});
        ColorStateList track = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0x552BD576, 0xFF30363D});
        sw.setThumbTintList(thumb);
        sw.setTrackTintList(track);
        sw.setPadding(0, 0, dp(2), 0);
        return sw;
    }

    private void addNotesCard(LinearLayout root) {
        LinearLayout card = addCard(root);
        TextView tv = new TextView(this);
        tv.setText("· 关闭某一项即放行对应上报，1 秒内生效，无需重启手机管家\n"
                + "· 短信 / 通话反诈的本地检测、提醒、记录不在拦截范围\n"
                + "· adb 逃生阀始终有效：/data/local/tmp/afb.disable 等");
        tv.setTextColor(SUB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setLineSpacing(dp(6), 1f);
        tv.setPadding(dp(16), dp(15), dp(16), dp(16));
        card.addView(tv);
    }

    private void addButtons(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        root.addView(row, lp);

        TextView reset = pill("恢复默认（全拦截）", ACCENT, 0x332BD576);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        rlp.rightMargin = dp(10);
        row.addView(reset, rlp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetDefaults();
                Toast.makeText(SettingsActivity.this, "已恢复默认拦截", Toast.LENGTH_SHORT).show();
            }
        });

        TextView open = pill("打开手机管家", TITLE, STROKE);
        row.addView(open, new LinearLayout.LayoutParams(0, dp(44), 1f));
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage(TARGET_PKG);
                if (i == null) {
                    Toast.makeText(SettingsActivity.this, "未找到手机管家", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, "启动失败：" + t, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private TextView pill(String text, int fg, int stroke) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), stroke);
        tv.setBackground(bg);
        tv.setClickable(true);
        return tv;
    }

    // ------------------------------------------------------------------ 状态

    private void resetDefaults() {
        for (String key : AfbPrefs.ALL_KEYS) {
            sp.edit().putBoolean(key, AfbPrefs.defaultOf(key)).apply();
        }
        publish();
        applyPrefs();
        refreshUi();
    }

    /**
     * 视图态以 SharedPreferences 为唯一真相：Activity 重建时 Switch 会尝试恢复旧勾选，
     * 这里统一覆盖回来，避免把界面残留状态又写回配置。
     */
    private void applyPrefs() {
        binding = true;
        for (Map.Entry<String, Switch> e : switches.entrySet()) {
            String key = e.getKey();
            Switch s = e.getValue();
            s.setOnCheckedChangeListener(null);
            s.setChecked(sp.getBoolean(key, AfbPrefs.defaultOf(key)));
            s.setOnCheckedChangeListener(listenerFor(key));
        }
        binding = false;
    }

    private CompoundButton.OnCheckedChangeListener listenerFor(final String key) {
        return new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (binding) return;
                if (checked == sp.getBoolean(key, AfbPrefs.defaultOf(key))) {
                    refreshUi();
                    return;
                }
                sp.edit().putBoolean(key, checked).apply();
                publish();
                refreshUi();
            }
        };
    }

    private void refreshUi() {
        if (master == null) return;
        boolean on = master.isChecked();
        statusTitle.setText(on ? "拦截执行中" : "全部放行");
        statusTitle.setTextColor(on ? ACCENT : SUB);
        statusDesc.setText(on
                ? "本地检测、提醒、记录保持原样"
                : "所有 hook 直接透传，等同停用模块");

        for (Map.Entry<String, Switch> e : switches.entrySet()) {
            String key = e.getKey();
            if (AfbPrefs.K_ENABLED.equals(key)) continue;
            Switch s = e.getValue();
            s.setEnabled(on);
            s.setAlpha(on ? 1f : 0.42f);
            View row = rows.get(key);
            if (row != null) row.setAlpha(on ? 1f : 0.55f);
        }

        String[] valves = AfbPrefs.activeOverrides();
        if (valves.length == 0) {
            overrideBanner.setVisibility(View.GONE);
        } else {
            StringBuilder sb = new StringBuilder("检测到 adb 逃生阀：");
            for (int i = 0; i < valves.length; i++) {
                if (i > 0) sb.append("、");
                sb.append(valves[i]);
            }
            overrideBanner.setText(sb.toString());
            overrideBanner.setVisibility(View.VISIBLE);
        }

        StringBuilder f = new StringBuilder();
        f.append(remoteOk
                ? "配置：XposedService 已连接 → RemotePreferences(group = afb)"
                : "配置：框架未连接，降级为设备快照 afb.conf");
        f.append("\n手机管家侧只读读取并注册变更监听，改完即时生效");
        f.append("\nadb 逃生阀：/data/local/tmp/afb.disable（始终有效）");
        f.append("\n模块：").append(getPackageName()).append("  ·  ").append(TARGET_PKG);
        footer.setText(f.toString());
        footer.setTextColor(remoteOk ? DIM : WARN);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
