package com.xiaozhi.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启：拉起 EntryKeepAlive 前台服务，从而常驻进程 + 注册上键长按接收器。
 * BOOT_COMPLETED 属于 Android 8 隐式广播豁免名单，清单接收器有效。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Lg.i("BootReceiver: 开机自启，拉起 EntryKeepAlive");
            try {
                context.startService(new Intent(context, EntryKeepAlive.class));
            } catch (Exception e) {
                Lg.i("BootReceiver: startService 失败 " + e);
            }
        }
    }
}
