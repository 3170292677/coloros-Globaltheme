package com.thexiaoxia43.coloros.theme;

import android.os.IBinder;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;

import java.lang.reflect.Method;

public class SystemUIHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.android.systemui";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("SystemUIHook: Loaded in " + lpparam.packageName);

        // Hook 状态栏相关方法
        hookStatusBar(lpparam);

        // Hook 通知栏相关方法
        hookNotificationPanel(lpparam);

        // Hook 快捷设置相关方法
        hookQuickSettings(lpparam);

        // Hook 其他系统UI组件
        hookOtherComponents(lpparam);

        // 连续圆角工具：解析 OplusPath 并截下系统真实权重（磁贴 / 胶囊 / 底图共用）
        try {
            SmoothCorners.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing SmoothCorners - " + t);
        }

        // 控制中心磁贴底图替换（暗色模式，支持每磁贴独立配置）
        try {
            TileBackgroundHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing TileBackgroundHook - " + t);
        }

        // 控制中心右上角（✎ 左侧）注入自定义按钮
        try {
            QuickEntranceButtonHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing QuickEntranceButtonHook - " + t);
        }

        // 磁贴长按选项替换：按形状拦截长按，不再打开系统设置/应用
        try {
            TileLongPressInterceptor.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing TileLongPressInterceptor - " + t);
        }

        // 磁贴圆角：ColorOS 的圆角由 ViewOutlineProvider 决定，重写 outline 才能改
        try {
            TileCornerHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing TileCornerHook - " + t);
        }

        // 控制中心背景：拦截空白处长按，弹背景面板（可见度）
        try {
            QsBackgroundHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing QsBackgroundHook - " + t);
        }

        // 通知卡片：长按拦截 + 底图替换
        try {
            NotificationHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing NotificationHook - " + t);
        }

        // 亮度 / 音量胶囊：长按弹样式面板 + 底图替换
        try {
            SliderHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing SliderHook - " + t);
        }

        // 图标底色：2×1 宽卡片图标下的小方块 + 三阶段跟着图标滑动的白块
        try {
            IconColorHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing IconColorHook - " + t);
        }

        // 图标着色 / 文字颜色：图标笔画本身的颜色 + 标签文字（含方形设备卡片）
        try {
            TileColorHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing TileColorHook - " + t);
        }

        // 通知栏「清除全部」圆形按钮：底图替换 + 长按弹样式面板
        try {
            ClearAllButtonHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing ClearAllButtonHook - " + t);
        }

        // 通知卡片文字颜色：把自定义色贴到通知的标题与正文上
        try {
            NotificationTextHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing NotificationTextHook - " + t);
        }

        // 通知卡片侧滑图标：让那层「模糊 + 灰底」跟着宿主一起淡（否则半透明底图下会留残影）
        try {
            NotificationMenuHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing NotificationMenuHook - " + t);
        }

        // 通知卡片操作按钮（回复 / 复制验证码 / 标记已读）：灰底压深
        try {
            NotificationActionHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing NotificationActionHook - " + t);
        }

        // 通知界面头部（时钟 / 日期 / 铃铛）：长按弹样式面板，调字体与图标颜色
        try {
            NotifHeaderHook.install(lpparam);
        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error installing NotifHeaderHook - " + t);
        }
    }

    private void hookStatusBar(LoadPackageParam lpparam) {
        try {
            // 查找状态栏类
            Class<?> statusBarClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.StatusBar", lpparam.classLoader);

            // Hook 状态栏更新方法
            XposedHelpers.findAndHookMethod(statusBarClass, "updateClock", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("SystemUIHook: Clock update intercepted");
                    // 在这里可以修改时间显示格式
                }
            });

            // Hook 信号强度显示
            XposedHelpers.findAndHookMethod(statusBarClass, "setSignal", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("SystemUIHook: Signal update intercepted");
                    // 在这里可以修改信号显示
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error hooking StatusBar - " + t);
        }
    }

    private void hookNotificationPanel(LoadPackageParam lpparam) {
        try {
            // 查找通知栏类
            Class<?> notificationPanelClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.NotificationPanelViewController", lpparam.classLoader);

            // Hook 通知展开/收起方法
            XposedHelpers.findAndHookMethod(notificationPanelClass, "animateExpand", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("SystemUIHook: Notification panel expand intercepted");
                    // 在这里可以修改通知栏展开动画
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error hooking NotificationPanel - " + t);
        }
    }

    private void hookQuickSettings(LoadPackageParam lpparam) {
        try {
            // 查找快捷设置类
            Class<?> quickSettingsClass = XposedHelpers.findClass("com.android.systemui.qs.QSFragment", lpparam.classLoader);

            // Hook 快捷设置创建方法
            XposedHelpers.findAndHookMethod(quickSettingsClass, "onViewCreated", android.view.View.class, android.os.Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("SystemUIHook: QuickSettings view created");
                    // 在这里可以修改快捷设置布局
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error hooking QuickSettings - " + t);
        }
    }

    private void hookOtherComponents(LoadPackageParam lpparam) {
        try {
            // Hook 电池图标
            Class<?> batteryControllerClass = XposedHelpers.findClass("com.android.systemui.statusbar.policy.BatteryControllerImpl", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(batteryControllerClass, "setBatteryLevel", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("SystemUIHook: Battery level update intercepted");
                    // 在这里可以修改电池图标显示
                }
            });

            // Hook 蓝牙图标
            Class<?> bluetoothControllerClass = XposedHelpers.findClass("com.android.systemui.statusbar.policy.BluetoothController", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(bluetoothControllerClass, "setBluetoothState", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("SystemUIHook: Bluetooth state update intercepted");
                    // 在这里可以修改蓝牙图标显示
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("SystemUIHook: Error hooking other components - " + t);
        }
    }
}
