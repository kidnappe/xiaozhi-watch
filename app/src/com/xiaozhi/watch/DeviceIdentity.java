package com.xiaozhi.watch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * 设备身份：Device-Id（自造随机 MAC，首字节 0x02）+ Client-Id（UUID）+ 服务端下发的 ws url / token。
 * 必须持久化 —— Android 6+ 读不到真实 MAC，每次启动换 ID 会让后台绑定反复失效（PLAN R9，P0 已实测持久化正常）。
 */
public final class DeviceIdentity {

    private static final String SP = "xiaozhi";

    public final String deviceId;
    public final String clientId;
    public final String uuid;

    public String wsUrl;
    public String token;
    public String activationCode;

    private final SharedPreferences sp;

    public DeviceIdentity(Context ctx) {
        sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);

        String mac = sp.getString("device_id", null);
        if (mac == null) {
            Random r = new Random();
            byte[] b = new byte[6];
            r.nextBytes(b);
            b[0] = (byte) 0x02;                 // 本地管理位，避开真实厂商前缀
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(':');
                // ⚠️ 必须小写 %02x！官方服务端按 Device-Id 字符串**精确匹配、大小写敏感**：
                //    大写 F6 在它设备表里查不到 → 握手 101 正常，但一收到 hello 就立刻 CLOSE
                //    （code=1000，现象极像"服务器拒绝未登记设备"）。2026-09-13 A/B 实测锁定，
                //    唯一变量就是大小写；ESP32 那次的原坑也记着「MAC 改带冒号 %02x」。
                sb.append(String.format(Locale.US, "%02x", b[i]));
            }
            mac = sb.toString();
            sp.edit().putString("device_id", mac).apply();
            Lg.i("首次运行：自造 Device-Id = " + mac);
        } else {
            String lower = mac.toLowerCase(Locale.US);
            if (!lower.equals(mac)) {
                mac = lower;                       // 存量大写 ID 规范成小写，保住已登记的设备身份
                sp.edit().putString("device_id", mac).apply();
                Lg.w("存量 Device-Id 含大写字母 → 已规范为小写：" + mac);
            }
        }
        deviceId = mac;

        String cid = sp.getString("client_id", null);
        if (cid == null) {
            cid = UUID.randomUUID().toString();
            sp.edit().putString("client_id", cid).apply();
            Lg.i("首次运行：生成 Client-Id = " + cid);
        }
        clientId = cid;

        String u = sp.getString("uuid", null);
        if (u == null) {
            u = UUID.randomUUID().toString();
            sp.edit().putString("uuid", u).apply();
        }
        uuid = u;

        wsUrl = sp.getString("ws_url", null);
        token = sp.getString("token", null);
        activationCode = sp.getString("activation_code", null);
    }

    public boolean hasCredentials() {
        // token == null 才算"没有凭据"；空串合法（自建服务器不开鉴权，connectWs 会跳过 Authorization 头）。
        //
        // ⚠️ 2026-09-13 深夜修正：原来这里额外排斥 "test-token"，依据是"拿它连会在握手后 2ms 被 CLOSE"。
        // 复测推翻了那个观察 —— 同一个 Device-Id / 同一个 test-token，官方云能正常 101 握手、回 hello、
        // 收下 listen start/stop（`tools/ws_probe.py --skip-hello` / 默认都验过）。
        // 当时那次 2ms 关闭是瞬时/环境现象，被错误地固化成了代码里的守卫。现在不再拦：
        // 服务端是否接受，交给 WS 的 onClose 去判。
        return wsUrl != null && wsUrl.length() > 0
                && token != null && token.length() > 0
                && !"test-token".equals(token);
    }

    public void saveCredentials(String url, String tok) {
        wsUrl = url;
        token = tok;
        sp.edit().putString("ws_url", url).putString("token", tok).apply();
    }

    public void saveActivationCode(String code) {
        activationCode = code;
        sp.edit().putString("activation_code", code).apply();
    }

    public void clear() {
        wsUrl = null;
        token = null;
        sp.edit().remove("ws_url").remove("token").apply();
    }

    /**
     * 「重新配对」用：连身份一起清掉（device_id/client_id/uuid/凭据/激活码全没）。
     * 下次 new DeviceIdentity() 会造一个全新的小写自造 MAC + 新 Client-Id/UUID →
     * 官方 OTA 会把它当一台**全新未注册设备**，从而重新下发 activation.code。
     * 配合在 xiaozhi.me 控制台删除旧设备使用。清完需重启 Activity 生效。
     */
    public void wipeIdentity() {
        sp.edit().clear().apply();
    }
}
