package com.xiaozhi.watch;

import android.util.Log;

/** 日志：logcat + 屏幕两路输出（P1 阶段 UI 还糙，屏幕日志是主要调试手段） */
public final class Lg {

    public static final String TAG = "XZW";

    public interface Sink {
        void line(String s);
    }

    private static final StringBuilder BUF = new StringBuilder();
    private static volatile Sink sink;

    private Lg() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void i(String s) {
        Log.i(TAG, s);
        push("· " + s);
    }

    public static void w(String s) {
        Log.w(TAG, s);
        push("! " + s);
    }

    public static void e(String s) {
        Log.e(TAG, s);
        push("✗ " + s);
    }

    public static void e(String s, Throwable t) {
        Log.e(TAG, s, t);
        push("✗ " + s + " → " + (t == null ? "?" : t.getClass().getSimpleName() + ": " + t.getMessage()));
    }

    private static void push(String s) {
        synchronized (BUF) {
            BUF.append(s).append('\n');
            if (BUF.length() > 40000) BUF.delete(0, BUF.length() - 30000);
        }
        Sink k = sink;
        if (k != null) {
            try {
                k.line(s);
            } catch (Throwable ignored) {
            }
        }
    }

    public static String text() {
        synchronized (BUF) {
            return BUF.toString();
        }
    }

    public static void clear() {
        synchronized (BUF) {
            BUF.setLength(0);
        }
    }
}
