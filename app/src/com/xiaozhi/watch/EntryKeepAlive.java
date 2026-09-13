package com.xiaozhi.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

/**
 * 哑前台服务：唯一目的是把进程抬出"缓存"态。
 * ColorOS 对后台/缓存应用的清单广播执行 "Background execution not allowed" 管控
 * （实测 deviceidle 白名单无效），不抬进程优先级的话，上键长按广播
 * LONG_PRESS_NAVI 到不了 NaviKeyReceiver，接管入口就是死的。
 * 通知为 MIN 级别、静音、无震动，服务本身不做任何工作。
 */
public class EntryKeepAlive extends Service {
    private static final String CHANNEL_ID = "xiaozhi_keepalive";
    private static final int NOTI_ID = 1;
    private NaviKeyReceiver receiver;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台待命",
                NotificationManager.IMPORTANCE_MIN);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification noti = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("小智")
                .setContentText("语音入口待命")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(NOTI_ID, noti);
        // 关键：LONG_PRESS_NAVI 是隐式广播，Android 8 起清单 <receiver> 不收（"Background
        // execution not allowed"），只有运行时注册的接收器豁免。故在此动态注册，
        // 借用本前台服务常驻的进程接收上键长按。
        receiver = new NaviKeyReceiver();
        registerReceiver(receiver, new IntentFilter("heytap.intent.action.LONG_PRESS_NAVI"));
        Lg.i("EntryKeepAlive: 前台服务已启动 + 上键长按接收器运行时注册");
    }

    @Override
    public void onDestroy() {
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
        super.onDestroy();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
