package com.xiaozhi.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * 上键（导航键）长按入口的接管接收器。
 *
 * ColorOS framework 的 LongPressNaviKeyCtrl 在导航键（KEYCODE_F1/131）长按 800ms 时：
 *   1) 发一条隐式、无权限保护的广播 heytap.intent.action.LONG_PRESS_NAVI（本接收器监听它）；
 *   2) 显式启动 com.heytap.wearable.breeno/.MainActivity —— 小布被 pm disable 后
 *      该步抛 ActivityNotFoundException 被其内部 catch，静默失败。
 * 因此：本接收器 + 禁用小布 = 上键长按只拉起小智。
 *
 * 与小布 StartBroadcast 官方行为对齐：息屏时忽略（长按上键本来就该在亮屏时用；
 * 充电覆盖窗由 MainActivity 的沉浸式全屏机制自行压过，无需特判）。
 */
public class NaviKeyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !"heytap.intent.action.LONG_PRESS_NAVI".equals(intent.getAction())) {
            return;
        }
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) {
            Lg.i("NaviKey: 息屏中忽略（与小布官方行为一致）");
            return;
        }
        Lg.i("NaviKey: 上键长按 → 拉起小智（自动开麦听 8s）");
        Intent main = new Intent(context, MainActivity.class);
        main.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        // 语音助手语义：首轮就绪后自动"按住说话"8s，模拟小布"长按即说"体验
        main.putExtra("talkms", 8000);
        context.startActivity(main);
    }
}
