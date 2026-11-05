package com.eta.afb;

import android.app.Application;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 进程入口。
 *
 * 按 libxposed service 102 的约定：框架（LSPosed / Vector）会查询
 * authority = &lt;applicationId&gt;.XposedService 的 ContentProvider，
 * 通过 call("SendBinder") 把 IXposedService 推送进来；
 * App 侧只负责在 Application.onCreate() 注册一次 listener。
 *
 * 回调运行在 Binder 线程 —— 对外只暴露一个 volatile 引用，UI 自行切线程。
 */
public class AfbApp extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "AntiFraudBlock";

    private static volatile XposedService sService;

    /** 框架尚未推送 binder 时返回 null，调用方必须降级 */
    public static XposedService service() {
        return sService;
    }

    public static boolean bound() {
        return sService != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // javadoc：只应调用一次；重复调用会覆盖旧 listener
            XposedServiceHelper.registerListener(this);
        } catch (Throwable t) {
            Log.w(TAG, "XposedServiceHelper.registerListener failed: " + t);
        }
    }

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        Log.i(TAG, "xposed service bound: " + describe(service));
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (sService == service) sService = null;
        Log.i(TAG, "xposed service died");
    }

    private static String describe(XposedService s) {
        try {
            long props = s.getFrameworkProperties();
            return s.getFrameworkName() + " " + s.getFrameworkVersion()
                    + " api=" + s.getApiVersion()
                    + " caps=" + Long.toHexString(props)
                    + (s.getApiVersion() >= XposedService.API_102 ? "" : " (service api<102)");
        } catch (Throwable t) {
            return "?";
        }
    }
}
