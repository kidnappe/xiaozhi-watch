package com.xiaozhi.probe;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * 探针日志：三路同时输出（logcat / 屏幕 / 文件）。
 * 文件落在 getExternalFilesDir() 下，不需要任何存储权限，adb pull 就能取。
 */
public final class Probe {

    public static final String TAG = "XZProbe";

    public interface Listener {
        void onLine(String line);
    }

    private static final Object LOCK = new Object();
    private static final StringBuilder BUF = new StringBuilder();
    private static File file;
    private static Writer writer;
    private static Listener listener;
    private static long t0 = SystemClock.elapsedRealtime();

    private Probe() {
    }

    public static void init(File dir) {
        synchronized (LOCK) {
            try {
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs();
                    file = new File(dir, "probe.log");
                    writer = new OutputStreamWriter(new FileOutputStream(file, false), "UTF-8");
                }
            } catch (Throwable t) {
                Log.e(TAG, "log file init failed", t);
            }
            t0 = SystemClock.elapsedRealtime();
        }
    }

    public static String filePath() {
        synchronized (LOCK) {
            return file == null ? "(无)" : file.getAbsolutePath();
        }
    }

    public static void setListener(Listener l) {
        synchronized (LOCK) {
            listener = l;
        }
    }

    public static void log(String s) {
        String line = "[+" + (SystemClock.elapsedRealtime() - t0) + "ms] " + s;
        Log.i(TAG, line);
        Listener l;
        synchronized (LOCK) {
            BUF.append(line).append('\n');
            if (BUF.length() > 200000) BUF.delete(0, BUF.length() - 150000);
            l = listener;
            if (writer != null) {
                try {
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                } catch (Throwable ignored) {
                }
            }
        }
        if (l != null) l.onLine(line);
    }

    public static void logf(String fmt, Object... args) {
        log(String.format(fmt, args));
    }

    /** 章节标题，方便在长日志里定位 */
    public static void sep(String title) {
        log(" ");
        log("================ " + title + " ================");
    }

    public static String text() {
        synchronized (LOCK) {
            return BUF.toString();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            BUF.setLength(0);
        }
    }

    /** 时间戳格式化：2026-09-12 23:40:01 */
    public static String stamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
