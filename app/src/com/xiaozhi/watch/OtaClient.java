package com.xiaozhi.watch;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * OTA 激活 + 拿 ws url/token。
 *
 * 流程（PLAN §3.5）：
 *   1) POST https://api.tenclass.net/xiaozhi/ota/  body = 固件信息（不会真的刷固件）
 *   2) 未绑定 → 响应 {"activation":{"code":"539000","message":...,"challenge":...}}
 *      → 把 6 位码显示在表盘上 → 用户在 xiaozhi.me 控制台「添加设备」输入
 *   3) 已绑定 → 响应 {"websocket":{"url":..., "token":...}}
 *   4) ⚠️ 激活完成必须**重新建立 WS 连接**才生效
 *
 * 这里用"反复轮询 OTA 端点"的方式等激活（不依赖 activate 端点的 HMAC 细节），
 * 每次都在日志里打原始响应，方便对照。
 */
public final class OtaClient {

    /** 默认官方；自建服务器改用 config.json 里的 ota_url（换服务器只改参数，不动这份代码） */
    public static final String OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";

    public static final class Result {
        public boolean ok;              // HTTP 通了
        public int httpCode;
        public String raw;              // 原始响应（截断）
        public boolean activated;       // 服务端给了 websocket 配置
        public String wsUrl;
        public String token;
        public String code;             // 激活码
        public String message;
        public String challenge;
        public String error;
        public String serverTime;       // 服务端时间（用于判断本地时钟偏差）
        public boolean officialBlocked;  // 服务端已登记本设备，却只给占位 token → 官方 WS 走不通
    }

    private OtaClient() {
    }

    public static Result fetch(String otaUrl, String deviceId, String clientId, String uuid, String appVersion) {
        Result r = new Result();
        HttpURLConnection c = null;
        try {
            JSONObject application = new JSONObject();
            application.put("name", "xiaozhi-watch");
            application.put("version", appVersion);
            application.put("id", uuid);

            JSONObject board = new JSONObject();
            board.put("type", "oppo-watch-2");
            board.put("name", "OPPO Watch 2");
            board.put("mac", deviceId);

            JSONObject chip = new JSONObject();
            chip.put("model", "android-armv7");
            chip.put("cores", Runtime.getRuntime().availableProcessors());

            JSONObject body = new JSONObject();
            body.put("version", 2);
            body.put("uuid", uuid);
            body.put("application", application);
            body.put("board", board);
            body.put("chip", chip);

            String payload = body.toString();
            Lg.i("OTA POST " + otaUrl);
            Lg.i("OTA 请求体: " + payload);

            c = (HttpURLConnection) new URL(otaUrl).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept-Language", "zh-CN");
            // ★ 官方协议原文：Activation-Version = "设备芯片 efuse 区是否存储了有效的序列号，有则 2，无则 1"。
            //   我们是 Android App，**没有 efuse、没有序列号** → 必须声明 1（声称 2 会被当成已授权设备，
            //   控制台随后就要求输入序列号 → "请检查是否烧录"，2026-09-13 踩过）。
            //   同时不能带 Serial-Number 头。
            c.setRequestProperty("Activation-Version", "1");
            c.setRequestProperty("User-Agent", "xiaozhi-watch/0.1 (Android 8.1; OWW202)");
            c.setRequestProperty("Device-Id", deviceId);
            c.setRequestProperty("Client-Id", clientId);

            OutputStream os = c.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.flush();
            os.close();

            r.httpCode = c.getResponseCode();
            InputStream is = r.httpCode >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(is);
            r.raw = text.length() > 1200 ? text.substring(0, 1200) + "…" : text;
            Lg.i("OTA HTTP " + r.httpCode + " 响应: " + r.raw);
            r.ok = r.httpCode == 200;

            if (r.ok && text.trim().startsWith("{")) {
                JSONObject j = new JSONObject(text);
                if (j.has("activation")) {
                    JSONObject a = j.getJSONObject("activation");
                    r.code = a.optString("code", null);
                    r.message = a.optString("message", null);
                    r.challenge = a.optString("challenge", null);
                }
                if (j.has("websocket")) {
                    JSONObject w = j.getJSONObject("websocket");
                    r.wsUrl = w.optString("url", null);
                    r.token = w.optString("token", null);
                }
                // ★ 真机实测（2026-09-13）：**未绑定**时服务端会同时返回 activation 和 websocket，
                //   但那个 websocket.token 是占位符 "test-token" —— 拿它去连会被 101 后立刻 CLOSE。
                //   所以判据是"没有 activation.code 才算绑定成功"，不能只看 websocket.url 存在。
                boolean unbound = r.code != null && r.code.length() > 0;
                boolean placeholder = "test-token".equals(r.token);
                r.activated = !unbound && !placeholder
                        && r.wsUrl != null && r.wsUrl.length() > 0
                        && r.token != null && r.token.length() > 0;
                // 2026-09-13 实测的第二种状态：设备**已经登记**（服务端不再返回 activation），
                // 但 token 仍是占位符 "test-token"，拿它连 WS 会在握手后 2ms 被关。
                // 这说明官方对自建客户端只给登记、不给可用凭据 → 必须走自建服务器。
                r.officialBlocked = !unbound && placeholder;
                if (r.officialBlocked) {
                    Lg.w("服务端已登记本设备，但只返回占位 token（test-token）——官方 WS 不接受自建客户端");
                } else if (unbound && placeholder) {
                    Lg.i("未绑定：服务端给的是占位 token，忽略这套 websocket 配置");
                }
                r.serverTime = j.optString("timestamp", null);
            }
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Lg.e("OTA 失败", t);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    // ==========================================================
    // /ota/activate —— v1（无序列号）激活长轮询
    // 官方固件就是这么等的：设备反复 POST challenge，用户在控制台输验证码；
    // 返回码语义：200=已激活 / 202=等待绑定 / 404=该序列号没有 License / 其它=失败。
    // 我们打这个端点主要是**取证**：服务端到底把我们的设备当成什么状态。
    // ==========================================================
    public static final class ActivateResult {
        public int httpCode;
        public String message;
        public boolean activated;
        public boolean pending;
        public String error;
    }

    public static ActivateResult activate(String otaUrl, String deviceId, String clientId, String challenge) {
        ActivateResult r = new ActivateResult();
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            if (challenge != null) body.put("challenge", challenge);

            c = (HttpURLConnection) new URL(otaUrl + "activate").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Activation-Version", "1");     // 无序列号
            c.setRequestProperty("Device-Id", deviceId);
            c.setRequestProperty("Client-Id", clientId);
            c.setRequestProperty("User-Agent", "xiaozhi-watch/0.1 (Android 8.1; OWW202)");

            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            r.httpCode = c.getResponseCode();
            InputStream is = r.httpCode >= 400 ? c.getErrorStream() : c.getInputStream();
            r.message = readAll(is);
            r.activated = r.httpCode == 200;
            r.pending = r.httpCode == 202;
            Lg.i("OTA activate → HTTP " + r.httpCode + " " + r.message
                    + (r.activated ? "  ✅ 激活成功" : r.pending ? "  ⏳ 等你在控制台输入激活码" : "  ✗ 失败"));
        } catch (Throwable t) {
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Lg.w("OTA activate 异常：" + r.error);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }
}
