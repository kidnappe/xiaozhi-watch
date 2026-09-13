package com.xiaozhi.watch;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * 外部配置：从 App 外部目录读 config.json —— **用 adb push 就能改配置，不用在手表小屏上打字**。
 *
 * 路径：/sdcard/Android/data/com.xiaozhi.watch/files/config.json
 * 格式：
 * {
 *   "ws_url":  "ws://192.168.1.10:8000/xiaozhi/v1/",   // 自建服务器；填了就跳过官方 OTA
 *   "token":   "",                                      // 自建服务器通常不需要
 *   "ota_url": "http://192.168.1.10:8002/xiaozhi/ota/"  // 自建服务器的 OTA（能自动发激活码）
 * }
 *
 * 为什么需要这个：官方 api.tenclass.net 的 WS 端点在升级后**握手后 2ms 就把连接关掉**
 * （2026-09-13 实测：与 Authorization 有无/取值、hello 内容、URL 参数全都无关），
 * 官方已经转向 License/序列号的一机一码体系。自建服务器是唯一可控的路。
 */
public final class Cfg {

    public static final String DEFAULT_OTA = "https://api.tenclass.net/xiaozhi/ota/";
    public static final String DEFAULT_WS = "wss://api.tenclass.net/xiaozhi/v1/";

    public String wsUrl;
    public String token;
    public String otaUrl = DEFAULT_OTA;
    public boolean fromFile;

    private Cfg() {
    }

    public static Cfg load(Context ctx) {
        Cfg c = new Cfg();
        File f = new File(ctx.getExternalFilesDir(null), "config.json");
        if (!f.exists()) {
            Lg.i("没有 config.json，用默认官方服务器（" + DEFAULT_OTA + "）");
            return c;
        }
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            String text = new String(buf, 0, Math.max(0, n), "UTF-8");
            JSONObject j = new JSONObject(text);
            c.wsUrl = emptyToNull(j.optString("ws_url", null));
            String t = j.optString("token", null);
            c.token = t == null ? "" : t;          // 自建服务器常常不需要 token
            String ota = emptyToNull(j.optString("ota_url", null));
            if (ota != null) c.otaUrl = ota;
            c.fromFile = true;
            Lg.i("已读 config.json：ws_url=" + c.wsUrl + " ota_url=" + c.otaUrl
                    + " token=" + (c.token.length() == 0 ? "(空)" : "****" + tail(c.token)));
        } catch (Throwable e) {
            Lg.w("读 config.json 失败（用默认值）：" + e);
        }
        return c;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().length() == 0) ? null : s.trim();
    }

    private static String tail(String s) {
        return s.length() <= 6 ? s : s.substring(s.length() - 6);
    }

    /** 给 adb 用的：写一份示例配置进去（只在文件不存在时） */
    public static String sampleJson() {
        return "{\n"
                + "  \"ws_url\": \"\",\n"
                + "  \"token\": \"\",\n"
                + "  \"ota_url\": \"\"\n"
                + "}\n";
    }

    static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return bos.toByteArray();
    }
}
