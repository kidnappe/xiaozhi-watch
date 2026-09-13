package com.xiaozhi.probe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * 网络探针：提前验证 R4（TLS / wss 能不能通）与"headers 到底有没有在握手时发出去"。
 * 这里用的是**假 token**，目的不是打通业务，而是看服务器给什么反应（101 / 401 / 直接 CLOSE）。
 */
public final class WsProbe {

    public static final String WS_URL = "wss://api.tenclass.net/xiaozhi/v1/";
    public static final String OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";

    private WsProbe() {
    }

    // ---------------------------------------------------------------
    // 1. 网络环境 + TLS 握手（GET OTA 端点，无副作用）
    // ---------------------------------------------------------------
    public static void netAndTls(Context ctx) {
        Probe.sep("网络环境与 TLS");
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            Probe.log("activeNetwork: " + (ni == null ? "null（没网？）" : ni.getTypeName() + "/" + ni.getSubtypeName()
                    + " state=" + ni.getState() + " connected=" + ni.isConnected()));
        } catch (Throwable t) {
            Probe.log("ConnectivityManager 失败: " + t);
        }
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                Probe.log("wifi: ssid=" + wi.getSSID() + " rssi=" + wi.getRssi() + "dBm linkSpeed=" + wi.getLinkSpeed()
                        + "Mbps ip=" + intToIp(wi.getIpAddress()));
                Probe.log("     (SSID 若显示 <unknown ssid> 是因为没给定位权限，不影响联网)");
            }
        } catch (Throwable t) {
            Probe.log("WifiManager 失败: " + t);
        }

        HttpsURLConnection c = null;
        try {
            URL u = new URL(OTA_URL);
            c = (HttpsURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "xzprobe/0.1");
            int code = c.getResponseCode();
            Probe.log("GET " + OTA_URL + " → HTTP " + code + " " + c.getResponseMessage());
            Probe.log("TLS protocol=" + c.getCipherSuite() + " 已安全建立（能到这一步说明系统 CA 信任链 OK）");
            try {
                Certificate[] certs = c.getServerCertificates();
                if (certs.length > 0 && certs[0] instanceof X509Certificate) {
                    X509Certificate x = (X509Certificate) certs[0];
                    Probe.log("leaf cert subject=" + x.getSubjectDN());
                    Probe.log("leaf cert issuer =" + x.getIssuerDN());
                    Probe.log("leaf cert 有效期至 " + x.getNotAfter());
                }
            } catch (Throwable t) {
                Probe.log("读证书失败: " + t);
            }
            String body = readSome(c, 400);
            Probe.log("响应前 400 字: " + body);
        } catch (Throwable t) {
            Probe.log("✗ TLS/HTTPS 失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readSome(HttpsURLConnection c, int n) {
        try {
            java.io.InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) return "(空)";
            byte[] b = new byte[n];
            int r = is.read(b);
            is.close();
            return r <= 0 ? "(空)" : new String(b, 0, r, "UTF-8").replace("\n", " ");
        } catch (Throwable t) {
            return "(读不到: " + t + ")";
        }
    }

    private static String intToIp(int i) {
        return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF) + "." + ((i >> 24) & 0xFF);
    }

    // ---------------------------------------------------------------
    // 2. wss 握手：验证自定义 header 在握手时就带上去了（ESP32 那次踩过的坑）
    // ---------------------------------------------------------------
    public static void wsHandshake(final String url, final String deviceId, final String clientId) {
        Probe.sep("wss 握手（假 token，只看服务器反应）");
        final Object done = new Object();
        final boolean[] closedLog = new boolean[]{false};
        try {
            final URI uri = new URI(url);
            final Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer " + "probe-fake-token");
            headers.put("Protocol-Version", "1");
            headers.put("Device-Id", deviceId);
            headers.put("Client-Id", clientId);

            Probe.log("即将在**握手请求**里带的 header：");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                Probe.log("   " + e.getKey() + ": " + e.getValue());
            }

            WebSocketClient client = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake h) {
                    Probe.log(">>> onOpen: HTTP " + h.getHttpStatus() + " " + h.getHttpStatusMessage()
                            + "   ← 101 = 握手通过");
                    StringBuilder sb = new StringBuilder();
                    Iterator<String> it = h.iterateHttpFields();
                    while (it.hasNext()) {
                        String k = it.next();
                        sb.append(k).append("=").append(h.getFieldValue(k)).append("; ");
                    }
                    Probe.log(">>> 响应头: " + sb);
                    String hello = "{\"type\":\"hello\",\"version\":1,\"transport\":\"websocket\","
                            + "\"audio_params\":{\"format\":\"opus\",\"sample_rate\":16000,\"channels\":1,\"frame_duration\":60}}";
                    Probe.log(">>> 发送 hello: " + hello);
                    try {
                        send(hello);
                    } catch (Throwable t) {
                        Probe.log("✗ send 失败: " + t);
                    }
                }

                @Override
                public void onMessage(String m) {
                    Probe.log("<<< 收到消息: " + (m.length() > 500 ? m.substring(0, 500) + "…" : m));
                }

                @Override
                public void onMessage(java.nio.ByteBuffer b) {
                    Probe.log("<<< 收到二进制帧 " + b.remaining() + " 字节");
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Probe.log("<<< onClose code=" + code + " reason=" + reason + " remote=" + remote
                            + "   ← code=1000/1006=被关；4xxx=服务器主动拒绝");
                    synchronized (closedLog) {
                        closedLog[0] = true;
                        synchronized (done) {
                            done.notifyAll();
                        }
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Probe.log("<<< onError: " + ex.getClass().getSimpleName() + " " + ex.getMessage());
                }
            };
            // 1.5.7 没有 connect(ClientUpgradeRequest)：自定义 header 用 addHeader，且必须在 connect() 之前
            for (Map.Entry<String, String> e : headers.entrySet()) {
                client.addHeader(e.getKey(), e.getValue());
            }
            client.setConnectionLostTimeout(0);
            Probe.log("connectBlocking(15s) …");
            boolean ok;
            try {
                ok = client.connectBlocking(15, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                ok = false;
            }
            Probe.log("connectBlocking 返回 " + ok + " (true=TCP+TLS+WS 握手都完成) readyState=" + client.getReadyState());
            if (client.hasSSLSupport()) {
                Probe.log("SSLSession: protocol=" + (client.getSSLSession() != null ? client.getSSLSession().getProtocol() : "?"));
            }
            // 等服务器回话
            synchronized (done) {
                done.wait(8000);
            }
            if (!closedLog[0]) {
                Probe.log("8 秒内没有被关闭 → 服务器接受了这条连接（说明鉴权头格式对，只是假 token 它没校验/或校验通过）");
            }
            try {
                client.closeBlocking();
            } catch (Throwable ignored) {
            }
            Probe.log("wss 测试结束");
        } catch (Throwable t) {
            Probe.log("✗ wss 测试异常: " + t);
        }
    }
}
