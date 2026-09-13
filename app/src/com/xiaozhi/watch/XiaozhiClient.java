package com.xiaozhi.watch;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 小智协议客户端（P1：按住说话 → AI 回话）。
 *
 * 协议要点（PLAN §3）：
 *   · WS 直连是**裸 Opus**：不加 16 字节头（那个头只在 MQTT+UDP 网关模式用）
 *   · 上下行采样率不同：上行 16k(960 样本/60ms)，下行 24k(1440 样本/60ms)
 *   · 自定义 header 必须**在 connect() 之前** addHeader（1.5.7 没有 connect(ClientUpgradeRequest)）
 *   · P1 用 mode=manual：按住 → listen start，松手 → listen stop
 *
 * 线程模型：WS 回调线程（Java-WebSocket 自己的）负责收 + 解码 + 播放；
 *          独立上行线程负责 编码 + 发送，中间隔一个 2~3 帧的有界队列（满了丢最旧，绝不阻塞采集）。
 *
 * 连接模式（2026-09-13 按需连接改造）：
 *   · 常驻（setOnDemand(false)，自建/调试）：保活 + onClose 指数退避重连，与旧版一致。
 *   · 按需（官方云默认）：空闲不持连接。按住说话（或入口预热）才 connectWs；连接未就绪时
 *     按下会被 pendingListenStart 排队，hello 一到自动开麦，用户无感。进入 READY 静置 20s
 *     主动断开回待机 —— 官方对 test-token 的 ~65s 空闲回收永远轮不到我们，
 *     表情球不再周期性闪"联网加载→唤醒"，也没有空闲重连耗电。
 */
public final class XiaozhiClient {

    // ---- 音频参数（P0 定案）----
    public static final int UPLINK_RATE = 16000;
    public static final int FRAME_SAMPLES = 960;      // 60ms @16k
    public static final int DOWNLINK_RATE = 24000;

    // 抖动缓冲：3 帧(180ms) 时真机实测丢过 3 帧（编码均值 29ms、有毛刺）→ 放宽到 6 帧(360ms)。
    // 代价只是多 360ms 延迟，对"按住说话"完全无感；丢帧会直接切掉语音，代价大得多。
    private static final int QUEUE_MAX = 6;
    private static final int RECONNECT_MAX = 5;
    // 发完 listen stop 后等服务端回话的上限。官方云实测可能一个字都不回 → 必须有兜底，否则卡死。
    private static final int STUCK_TIMEOUT_MS = 12000;
    // 情绪表情在"回答结束"后的停留时长：让用户看清当前情绪，再回待机。
    private static final int EMOTION_HOLD_MS = 2600;
    // ---- 按需连接----
    // 空闲断开阈值：够连续几轮对话不用重复建连，又远低于服务端 ~65s 的空闲回收线。
    private static final int IDLE_DISCONNECT_MS = 20000;
    // 按下后等"连接就绪"的上限：超时取消本轮待命，回到待机（不让用户按着不放干等）。
    private static final int PENDING_TALK_TIMEOUT_MS = 12000;
    private volatile long watchdogSeq = 0;
    private volatile long emotionHoldSeq = 0;
    private volatile long idleSeq = 0;
    private volatile long pendingSeq = 0;
    /** 按需连接模式：官方云路线使用；自建/调试常驻为 false（保留旧版保活+自动重连）。 */
    private volatile boolean onDemand;
    /** 用户已按住但连接还没就绪：hello 回包到达时自动补上"开麦"，兑现这次按下。 */
    private volatile boolean pendingListenStart;

    public enum St {
        // OFF 在按需模式下就是"待机"（空闲不挂连接，说话才联网）；常驻模式手动断开也显示待机，
        // 具体缘由看 detail 文案
        OFF("待机"), CONNECTING("连接中"), READY("就绪"), LISTENING("聆听中"),
        THINKING("思考中"), SPEAKING("回答中"), ERR("出错");

        public final String cn;

        St(String cn) {
            this.cn = cn;
        }
    }

    public interface Listener {
        /** 状态机变化（屏幕主文案由它驱动） */
        void onState(St st, String detail);

        /** 过程事件：kind = "识别" / "朗读" / "情绪" / "提示" */
        void onEvent(String kind, String text);

        /**
         * 表情切换（驱动情绪球）。
         *
         * @param emotion 小智的 emotion 字段（neutral/happy/...），
         *                或客户端状态态（以 @ 开头：@listening / @speaking / @error ...）。
         *                空串/null 表示"没有明确表情，保持当前"。
         */
        void onEmotion(String emotion);
    }

    private final DeviceIdentity id;
    private final Listener li;

    private final ConcentusCodec codec;               // 可能为 null（构造失败）
    private final AudioCapture capture;
    private final AudioPlayer player;

    private final short[] decBuf = new short[DOWNLINK_RATE / 1000 * 120];  // 120ms 余量

    private volatile St state = St.OFF;
    private volatile String sessionId;
    private volatile String downlinkFormat = "opus";  // 服务端 hello 里协商（若给 pcm 就直接写 PCM）
    private volatile int downlinkRate = DOWNLINK_RATE;
    private volatile boolean talking;
    /** 本轮对话服务端有没有给过 emotion（决定 tts 开播时是否用 @speaking 占位） */
    private volatile boolean gotLlmEmotion;
    /** 本轮对话本地 stt 情绪是否已锁定（用户说了明确情绪词，llm 默认 emotion 不覆盖） */
    private volatile boolean localEmotionLocked;
    private volatile boolean wantConnected;

    private volatile Ws ws;   // 多线程读写（连接线程/WS 回调/上行线程/空闲计时线程）→ volatile
    private volatile int reconnectCount;

    // ---- 上行队列 ----
    private final ArrayDeque<short[]> upQueue = new ArrayDeque<short[]>();
    private final Object upLock = new Object();
    private volatile boolean upOn;
    private Thread upThread;
    private int dropped;

    public XiaozhiClient(DeviceIdentity id, Listener li) {
        this.id = id;
        this.li = li;

        ConcentusCodec c = null;
        try {
            c = new ConcentusCodec(UPLINK_RATE, 1, DOWNLINK_RATE, 1, 1500);
        } catch (Throwable t) {
            Lg.e("Opus 编解码器初始化失败（不能说话也听不到）", t);
        }
        codec = c;

        capture = new AudioCapture(new AudioCapture.Sink() {
            @Override
            public void onFrame(short[] pcm, int samples) {
                if (!upOn) return;
                synchronized (upLock) {
                    if (upQueue.size() >= QUEUE_MAX) {
                        upQueue.pollFirst();
                        dropped++;
                    }
                    upQueue.addLast(pcm);
                    upLock.notifyAll();
                }
            }

            @Override
            public void onStopped(String reason) {
                Lg.w("采集异常停止：" + reason);
                li.onEvent("提示", "麦克风被中断：" + reason);
            }
        });

        player = new AudioPlayer();
    }

    public St state() {
        return state;
    }

    public String sessionId() {
        return sessionId;
    }

    // ==========================================================
    // 连接
    // ==========================================================
    /**
     * ⚠️ 方法名故意不叫 connect()：Ws 继承 WebSocketClient 也有个 connect()，
     * 在 Ws 内部的匿名类里写裸 connect() 会解析到**父类的那个**（外层类成员被继承成员遮蔽），
     * 结果运行期抛 "WebSocketClient objects are not reuseable" —— 2026-09-13 真机踩过。
     */
    public void connectWs() {
        connectWs(true);
    }

    /**
     * 建立连接。userInitiated=true 表示"用户动作要求的连接"（按住说话/连接按钮/入口预热），
     * 重连计数归零重新起算；onClose 的自动重连必须传 false，保留指数退避计数。
     */
    public void connectWs(boolean userInitiated) {
        if (codec == null) {
            setState(St.ERR, "Opus 编解码器不可用");
            return;
        }
        if (id.wsUrl == null || id.wsUrl.length() == 0 || id.token == null) {
            setState(St.ERR, "没有 ws url/token，先激活设备");
            return;
        }
        // 注意：这里**允许 test-token** 连接（官方云用 test-token 可跑通）。
        // 掉线风暴改由 onClose 的退避 + 上限治理，不靠拒连。
        wantConnected = true;
        if (userInitiated) reconnectCount = 0;
        closeWsQuietly();
        // 上一轮会话的残留状态必须清：sessionId 挂着会让新连接的 listen 带旧 session_id，
        // 且 handshakeReady 会误判"已就绪"
        sessionId = null;
        downlinkFormat = "opus";
        downlinkRate = DOWNLINK_RATE;

        try {
            URI uri = new URI(id.wsUrl);
            ws = new Ws(uri);
            // ⚠️ 1.5.7 没有 connect(ClientUpgradeRequest)：header 只能先 addHeader 再 connect()
            //    顺序反了 → 101 握手成功但鉴权头没上去 → 服务器 hello 后立刻 CLOSE（ESP32 那次的原坑）
            // 自建服务器常不开鉴权：token 为空就**不发** Authorization 头（发个空的更容易被拒）
            if (id.token != null && id.token.length() > 0) {
                ws.addHeader("Authorization", "Bearer " + id.token);
            } else {
                Lg.i("token 为空 → 不发 Authorization 头（自建服务器模式）");
            }
            ws.addHeader("Protocol-Version", "1");
            ws.addHeader("Device-Id", id.deviceId);
            ws.addHeader("Client-Id", id.clientId);
            // ⚠️ 这个值必须 > 0：设 0 = 完全不发 ping 保活。
            //    手表走 COMPANION_PROXY（蓝牙代理到手机），实测空闲 60 秒中间层就掐断 TCP
            //    → onClose(1006) 正好卡在 60s，看着像"服务端踢人"，其实是我们自己不保活。
            //    设 20 = 每 20 秒发一次 ping，服务端回 pong 就续命。
            ws.setConnectionLostTimeout(20);
        } catch (Throwable t) {
            Lg.e("构造 WebSocket 失败", t);
            setState(St.ERR, "url 非法：" + id.wsUrl);
            return;
        }

        Lg.i("连接 " + id.wsUrl);
        Lg.i("握手 header: Authorization=Bearer ****" + tail(id.token) + " Protocol-Version=1 Device-Id="
                + id.deviceId + " Client-Id=" + id.clientId);
        setState(St.CONNECTING, id.wsUrl);

        final Ws w = ws;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = w.connectBlocking(15, TimeUnit.SECONDS);
                    Lg.i("connectBlocking = " + ok + " readyState=" + w.getReadyState());
                    if (!ok) {
                        Lg.w("连接未完成（超时或失败）");
                        if (onDemand) goIdle("没连上 · 按住说话自动联网");
                        else setState(St.ERR, "连接超时");
                    }
                } catch (Throwable t) {
                    Lg.e("connectBlocking 异常", t);
                    if (onDemand) goIdle("没连上 · 按住说话自动联网");
                    else setState(St.ERR, String.valueOf(t.getMessage()));
                }
            }
        }, "xz-connect").start();
    }

    private void closeWsQuietly() {
        Ws w = ws;
        ws = null;
        if (w != null) {
            w.deliberate = true;          // 让它的 onClose 不再触发重连
            try { w.closeBlocking(); } catch (Throwable ignored) { }
        }
    }

    public void disconnect() {
        wantConnected = false;
        stopTalking();
        closeWsQuietly();
        player.stop();
        setState(St.OFF, "已断开");
    }

    public void setOnDemand(boolean v) {
        if (onDemand == v) return;
        onDemand = v;
        Lg.i("连接模式 → " + (v ? "按需（空闲断开，说话才联网）" : "常驻（保活 + 自动重连）"));
    }

    public boolean isOnDemand() {
        return onDemand;
    }

    /**
     * 按需模式的"待机"：主动关连接、回待机展示（表情球归位 neutral）。
     * 空闲计时到点、按下待命超时、连接失败兜底都收口到这里；
     * 也供 afterPermission 初始待机 / 「重载」切模式时调用。可重入、幂等。
     */
    public void goIdle(String detail) {
        wantConnected = false;
        pendingListenStart = false;
        pendingSeq++;                 // 让在跑的"待命超时"任务作废
        idleSeq++;                    // 让在跑的"空闲断开"任务作废
        stopUplink();                 // 麦克风没开过也安全（start/stop 均幂等）
        talking = false;
        closeWsQuietly();
        player.stop();
        setState(St.OFF, detail);
        li.onEmotion("neutral");
    }

    /** 起/重起"空闲断开"倒计时：每次进入 READY（一轮活动结束）都调，从最近一拍重新计时。 */
    private void armIdleTimer() {
        if (!onDemand) return;
        final long my = ++idleSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(IDLE_DISCONNECT_MS);
                if (my != idleSeq) return;                        // 期间有新活动，本发作废
                if (!onDemand || talking || pendingListenStart) return;
                if (state != St.READY) return;                    // 正在听/思考/回答，不掐线
                Lg.i("空闲 " + (IDLE_DISCONNECT_MS / 1000) + "s → 主动断开回待机（按需连接）");
                goIdle("待机 · 按住说话自动联网");
            }
        }, "xz-idle").start();
    }

    /** 撤销空闲断开倒计时（用户有动作，本轮活动还没结束）。 */
    private void cancelIdleTimer() {
        idleSeq++;
    }

    /**
     * "按住等连接就绪"的超时看门狗：到点还没等到 hello 就取消本轮开麦待命。
     * 与 hello 分支的 pending 消费存在理论竞态（seq 快照后毫秒级窗口），
     * 真撞上最多浪费一轮按键，服务端间歇场景下影响可忽略。
     */
    private void startPendingTalkWatchdog() {
        final long my = ++pendingSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(PENDING_TALK_TIMEOUT_MS);
                if (my != pendingSeq || !pendingListenStart) return;   // 已消费/已取消
                Lg.w("按住 " + (PENDING_TALK_TIMEOUT_MS / 1000) + "s 连接仍未就绪 → 取消本轮");
                pendingListenStart = false;
                talking = false;
                li.onEvent("提示", "网络没就绪，本轮取消 —— 松手再按住即可");
                if (onDemand) goIdle("没连上 · 按住说话自动联网");
            }
        }, "xz-pending-wd").start();
    }

    // ==========================================================
    // 说话
    // ==========================================================
    /** 连接已就绪（open + 收到服务端 hello 回包）→ 可以直接发 listen。
     *  用 helloOk 而非 sessionId：不依赖服务端 hello 里必带 session_id（stub 服务器可能不带）。 */
    private boolean handshakeReady() {
        Ws w = ws;
        return w != null && w.isOpen() && w.helloOk;
    }

    /** 按下：开始说话（若正在播放 AI 回答，先打断） */
    public void startTalking() {
        cancelIdleTimer();            // 用户有动作，先停"空闲断开"表
        if (talking) return;

        if (state == St.SPEAKING) {
            abort();
            sleep(120);
        }

        talking = true;
        gotLlmEmotion = false;
        localEmotionLocked = false;

        if (!handshakeReady()) {
            // 按需连接核心：按下瞬间没就绪 → 发起/等待连接，并把"开麦"排队（pendingListenStart）。
            // hello 到达时自动补 listen start + 开麦，用户只是按住等 1~2 秒，不用松手重来。
            pendingListenStart = true;
            startPendingTalkWatchdog();
            Ws w = ws;
            if (w == null || w.isClosed()) {
                connectWs();          // 没有进行中的尝试才新起一条（user-initiated，退避计数归零）
            }
            if (state == St.ERR) {
                // 没凭据/编解码器不可用：永远就绪不了，立刻取消本轮（别让用户白按 12 秒）
                pendingListenStart = false;
                pendingSeq++;
                talking = false;
                li.onEvent("提示", "没凭据或编码器不可用，看上方报错");
                return;
            }
            setState(St.CONNECTING, "联网中，请保持按住…");
            return;
        }
        beginListening();
    }

    /** 握手完成后真正开麦：正常路径与 hello 兑现 pending 共用 */
    private void beginListening() {
        sendListen("start", "manual");
        startUplink();
        setState(St.LISTENING, "正在听…（松手发送）");
        li.onEmotion("@listening");
    }

    /** 松手：停止采集 + 通知服务端做识别 */
    public void stopTalking() {
        if (!talking) {
            // 仍要保证采集停掉（异常路径兜底）
            stopUplink();
            return;
        }
        if (pendingListenStart) {
            // 连接还没就绪就松手了：本轮从没开过麦，不能发没发过的 listen stop，直接取消。
            pendingListenStart = false;
            pendingSeq++;                              // 撤销待命看门狗
            talking = false;
            Lg.i("松手过早：连接未就绪，本轮取消");
            li.onEvent("提示", "网络还没就绪，本轮取消 —— 再按住即可");
            if (handshakeReady()) {
                setState(St.READY, "就绪，按住下方按钮说话");
                armIdleTimer();
            } else if (state == St.CONNECTING) {
                // 连接在途：让它跑完（hello 到达后会正常进 READY 并自起空闲表），
                // 杀掉反而下次又从 TLS 握手重来。若最终连不上，onClose 的按需收口会回待机。
                Lg.i("松手时连接在途 → 保留这次建连，就绪后转入空闲计时");
                li.onEmotion("neutral");
            } else if (onDemand) {
                goIdle("没连上 · 按住说话自动联网");
            } else {
                li.onEmotion("neutral");
            }
            return;
        }
        talking = false;
        stopUplink();
        sendListen("stop", "manual");
        setState(St.THINKING, "识别中…");
        li.onEmotion("@thinking");
        startStuckWatchdog();
    }

    /**
     * 状态看门狗：stopTalking 后如果服务端长时间没回任何东西（stt/tts），
     * 状态会永远停在 THINKING —— 实测官方云就是这样，导致后续每次说话都被"没就绪"挡掉。
     * 到点无条件回 READY，宁可误判也不能卡死。
     */
    private void startStuckWatchdog() {
        final long mySeq = ++watchdogSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(STUCK_TIMEOUT_MS);
                if (mySeq != watchdogSeq) return;           // 已经有更新的一轮了
                if (state == St.THINKING || state == St.SPEAKING) {
                    Lg.w("等服务端响应超时 " + STUCK_TIMEOUT_MS + "ms（状态=" + state + "）→ 强制回 READY");
                    setState(St.READY, "服务端没回话，可再说一次");
                    li.onEmotion("@stuck");   // 思考卡壳冒烟（55）：本轮黄了的表情化表达，后续由待机氛围接管
                    armIdleTimer();                    // 按需：这轮黄了，静置够久就断开回待机
                }
            }
        }, "xz-watchdog").start();
    }

    /**
     * tts start 后，给 llm 情绪一个到达窗口（800ms）；超时还没 emotion 才用 @speaking 占位。
     * llm 情绪到达会 ++emotionHoldSeq（见 llm 分支），使本任务失效。
     */
    private void scheduleSpeakingFallback() {
        final long mySeq = emotionHoldSeq;   // 捕获当前 seq，不主动 +1
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(800);
                // 情绪已到达（seq 变化 或 服务端给了 emotion 或 本地已锁定）→ 别发 @speaking 占位
                if (mySeq != emotionHoldSeq) return;
                if (gotLlmEmotion || localEmotionLocked) return;
                li.onEmotion("@speaking");
            }
        }, "xz-emo-speaking").start();
    }

    /**
     * 回答结束后，延迟 EMOTION_HOLD_MS 再回待机表情。
     * 用 seq 做"取消"：新的 llm 情绪到达会 ++seq，让旧的延迟任务失效。
     */
    private void scheduleBackToIdle() {
        final long mySeq = ++emotionHoldSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(EMOTION_HOLD_MS);
                if (mySeq != emotionHoldSeq) return;   // 期间来了新情绪，别覆盖
                li.onEmotion("neutral");
            }
        }, "xz-emo-hold").start();
    }

    /** 打断 AI 说话 */
    public void abort() {
        if (state != St.SPEAKING) return;
        try {
            JSONObject j = new JSONObject();
            if (sessionId != null) j.put("session_id", sessionId);
            j.put("type", "abort");
            j.put("reason", "wake_word_detected");
            send(j.toString());
            Lg.i("已发送 abort（打断）");
        } catch (Throwable t) {
            Lg.w("abort 失败: " + t);
        }
        player.flush();
        setState(St.READY, "已打断");
        li.onEmotion("@abort");
        armIdleTimer();
    }

    public boolean isTalking() {
        return talking;
    }

    // ==========================================================
    // 上行管线
    // ==========================================================
    private void startUplink() {
        synchronized (upLock) {
            upQueue.clear();
            dropped = 0;
            upOn = true;
        }
        upThread = new Thread(new Runnable() {
            @Override
            public void run() {
                uplinkLoop();
            }
        }, "xz-uplink");
        // 实测编码均值 29ms（P0 空载测得 9ms）—— 有采集/WS/播放线程在抢 CPU，把上行线程提到音频优先级
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); }
        catch (Throwable ignored) { }
        upThread.start();

        if (!capture.start()) {
            Lg.e("麦克风启动失败（3 次重试都失败）");
            synchronized (upLock) {
                upOn = false;
                upLock.notifyAll();
            }
            li.onEvent("提示", "麦克风启不来：把表盘切到小智界面再试");
            setState(St.ERR, "麦克风不可用");
        }
    }

    private void stopUplink() {
        synchronized (upLock) {
            upOn = false;
            upQueue.clear();
            upLock.notifyAll();
        }
        capture.stop();
        Thread t = upThread;
        upThread = null;
        if (t != null) {
            try { t.join(1000); } catch (InterruptedException ignored) { }
        }
    }

    private void uplinkLoop() {
        byte[] out = new byte[600];
        long frames = 0;
        long bytes = 0;
        long encMs = 0;
        long t0 = System.currentTimeMillis();
        while (true) {
            short[] frame;
            synchronized (upLock) {
                while (upOn && upQueue.isEmpty()) {
                    try {
                        upLock.wait(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (!upOn) {
                    Lg.i("上行线程退出：共 " + frames + " 帧 / " + bytes + "B"
                            + (frames > 0 ? ("，平均 " + (bytes / frames) + "B/帧，编码均 "
                            + (encMs / frames) + "ms，丢帧 " + dropped) : ""));
                    return;
                }
                frame = upQueue.pollFirst();
            }
            if (frame == null) continue;

            long a = System.nanoTime();
            int n = codec.encode(frame, frame.length, out);
            long ms = (System.nanoTime() - a) / 1000000L;
            encMs += ms;
            if (n <= 0) {
                Lg.w("编码返回 " + n + "，跳过该帧");
                continue;
            }
            frames++;
            bytes += n;

            Ws w = ws;
            if (w != null && w.isOpen()) {
                try {
                    w.send(Arrays.copyOf(out, n));
                } catch (Throwable t) {
                    Lg.w("上行发送失败：" + t);
                }
            }
            if (frames % 50 == 0) {
                long dt = Math.max(1, System.currentTimeMillis() - t0);
                Lg.i("上行 " + frames + " 帧/" + dt + "ms（" + (bytes / frames) + "B/帧，"
                        + (bytes * 8 / dt) + "kbps，编码均 " + (encMs / frames) + "ms，丢 " + dropped + "）");
            }
        }
    }

    // ==========================================================
    // 发送
    // ==========================================================
    private void send(String s) {
        Ws w = ws;
        if (w == null || !w.isOpen()) {
            Lg.w("WS 未开，丢弃待发消息：" + clip(s));
            return;
        }
        if (!s.contains("\"type\":\"hello\"") && !s.contains("\"type\": \"hello\"")) {
            Lg.i(">>> " + clip(s));
        }
        try {
            w.send(s);
        } catch (Throwable t) {
            Lg.w("send 失败：" + t);
        }
    }

    private void sendListen(String st, String mode) {
        try {
            JSONObject j = new JSONObject();
            if (sessionId != null) j.put("session_id", sessionId);
            j.put("type", "listen");
            j.put("state", st);
            if (mode != null) j.put("mode", mode);
            send(j.toString());
        } catch (Throwable t) {
            Lg.w("sendListen 失败: " + t);
        }
    }

    // ==========================================================
    // WS 回调
    // ==========================================================
    private final class Ws extends WebSocketClient {

        /** 我们主动关的（换连接/退出），onClose 里不该再触发重连 */
        volatile boolean deliberate;
        /** 本次连接是否已收到服务端 hello 回包（=会话真正健康）；收到才把 reconnectCount 清零 */
        volatile boolean helloOk;

        Ws(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake h) {
            Lg.i("WS onOpen: HTTP " + h.getHttpStatus() + " " + h.getHttpStatusMessage());
            helloOk = false;
            sendHello();
        }

        @Override
        public void onMessage(String m) {
            handleText(m);
        }

        @Override
        public void onMessage(ByteBuffer b) {
            handleAudio(b);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            boolean isCurrent = (this == ws);
            Lg.w("WS onClose code=" + code + " reason=" + reason + " remote=" + remote
                    + " deliberate=" + deliberate + " 是当前连接=" + isCurrent);
            stopUplink();
            player.stop();
            // ⚠️ 2026-09-13 真机踩到重连风暴：connectWs() 会 closeWsQuietly() 掉旧连接，
            //   而旧连接的 onClose 是**事件驱动、可能晚到**的 —— 它醒来时 ws 已经指向新连接，
            //   于是又调 connectWs() 把新连接也关了 …… 每 2 秒一轮，永远好不了。
            //   三道闸：主动关闭不重连 / 非当前连接不重连 / 已断开不重连。
            if (deliberate) {
                Lg.i("主动关闭，不重连");
                return;
            }
            if (!isCurrent) {
                Lg.i("这是旧连接的关闭事件，忽略（防重连风暴）");
                return;
            }
            if (onDemand && !pendingListenStart) {
                // 按需模式：会话中途掉线（含官方 ~65s 空闲回收）→ **不自动重连**。
                // 用户下一次按住会重新联网（也省电：服务端沉默时不追着连）。
                // 唯一例外是 pendingListenStart：用户还按着等说话 → 走下面的退避重连。
                wantConnected = false;
                ws = null;
                boolean wasTalking = talking;
                talking = false;
                pendingListenStart = false;
                pendingSeq++;
                idleSeq++;
                if (wasTalking) li.onEvent("提示", "掉线了，松手再按住一次");
                setState(St.OFF, "已断开 · 按住说话自动联网");
                li.onEmotion("neutral");
                Lg.i("按需模式关闭 → 回待机（不追重连）");
                return;
            }
            // 官方云对 test-token 会**间歇性**握手后立刻 1000 关。这里指数退避：
            //   2→4→8→16→32→60s（封顶 60s），既不再每 2 秒狂连（风暴消除），又能自动等到下一个
            //   "可连接窗口"恢复（不硬放弃；用户主动 disconnect() 会置 wantConnected=false 停止）。
            if (wantConnected) {
                reconnectCount++;
                final int n = reconnectCount;
                final long delay = Math.min(2000L * (1L << Math.min(n - 1, 5)), 60000L);
                setState(St.CONNECTING, "掉线，" + (delay / 1000) + "s 后第 " + n + " 次重连…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        sleep(delay);
                        // 必须写成 XiaozhiClient.this.xxx：裸 connectWs() 这里不会歧义，
                        // 但同类名冲突是真实踩过的坑（见 connectWs 的注释）。
                        // 传 false：自动重连不清零退避计数（否则又是 2s 风暴的原 bug）
                        if (wantConnected) XiaozhiClient.this.connectWs(false);
                    }
                }, "xz-reconnect").start();
            } else {
                setState(St.OFF, "连接已关闭 code=" + code);
            }
        }

        @Override
        public void onError(Exception ex) {
            Lg.e("WS onError", ex);
            setState(St.ERR, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void sendHello() {
        try {
            JSONObject j = new JSONObject();
            j.put("type", "hello");
            j.put("version", 1);
            j.put("transport", "websocket");
            JSONObject ap = new JSONObject();
            ap.put("format", "opus");
            ap.put("sample_rate", UPLINK_RATE);
            ap.put("channels", 1);
            ap.put("frame_duration", 60);
            j.put("audio_params", ap);
            JSONObject f = new JSONObject();
            f.put("mcp", false);          // P4 才开
            j.put("features", f);
            Lg.i(">>> hello: " + j);
            send(j.toString());
        } catch (Throwable t) {
            Lg.e("发 hello 失败", t);
        }
    }

    private void handleText(String m) {
        Lg.i("<<< " + clip(m));
        try {
            JSONObject j = new JSONObject(m);
            String type = j.optString("type", "");
            if ("hello".equals(type)) {
                sessionId = j.optString("session_id", null);
                JSONObject ap = j.optJSONObject("audio_params");
                if (ap != null) {
                    downlinkFormat = ap.optString("format", "opus");
                    int sr = ap.optInt("sample_rate", 0);
                    if (sr > 0) downlinkRate = sr;
                    Lg.i("服务端音频参数：format=" + downlinkFormat + " rate=" + downlinkRate
                            + " ch=" + ap.optInt("channels", 1) + " frame=" + ap.optInt("frame_duration", 60) + "ms");
                    if ("opus".equals(downlinkFormat) && downlinkRate != DOWNLINK_RATE) {
                        Lg.w("下行采样率 " + downlinkRate + " ≠ 预期的 " + DOWNLINK_RATE + "：播放会变调（要改解码器）");
                    }
                }
                Lg.i("会话 session_id=" + sessionId);
                // 收到服务端 hello 回包 = 会话真正建立 → 此刻才把重连计数清零（而不是 onOpen 即清）。
                // 关键：治理"握手成功但服务端立刻 1000 关"的风暴——那种连接活不到发 hello 回包，
                // 计数不会被重置，累加到 RECONNECT_MAX 后自动退避停止，不再每 2 秒狂连。
                Ws cur = ws;
                if (cur != null && !cur.helloOk) {
                    cur.helloOk = true;
                    reconnectCount = 0;
                }
                if (pendingListenStart && talking) {
                    // 这次连接是用户按着按钮触发的：就绪瞬间兑现排队的"开麦"，无感衔接
                    pendingListenStart = false;
                    pendingSeq++;                      // 停掉待命看门狗
                    Lg.i("连接就绪 → 自动开麦（兑现按住待命）");
                    beginListening();
                } else {
                    setState(St.READY, "就绪，按住下方按钮说话");
                }
            } else if ("stt".equals(type)) {
                String text = j.optString("text", "");
                li.onEvent("识别", text);
                setState(St.THINKING, "你说：" + text);
                if (text.trim().length() == 0) {
                    // 转写为空（噪声/误触）：球“没听清凑近”，不影响后续 tts/llm 到达后覆盖
                    li.onEmotion("@unclear");
                }
                // 共情回应：先识别用户情绪，再翻译成球的回应（不是复读用户情绪）。
                // 命中即"锁定"，后续服务端 llm 的 emotion 不再覆盖 ——
                // 因为服务端给的是 AI 拟人反应（如 embarrassed），不是对用户情绪的共情回应。
                String user = LocalEmotion.userEmotion(text);
                String local = LocalEmotion.empathize(user);
                if (local.length() > 0) {
                    localEmotionLocked = true;
                    emotionHoldSeq++;
                    Lg.i("共情回应：用户'" + user + "' → 球'" + local + "'（锁定，llm 不覆盖）");
                    li.onEmotion(local);
                }
            } else if ("tts".equals(type)) {
                String st = j.optString("state", "");
                String text = j.optString("text", "");
                if ("start".equals(st)) {
                    if (!player.isRunning() && !player.start()) {
                        Lg.e("播放器启动失败，听不到回答");
                    }
                    setState(St.SPEAKING, "正在回答…");
                    // 服务端没给 emotion 时才用"输出回复"占位；给了就保持。
                    // 但 llm 情绪在 tts start 之后约 500ms 才到 —— 若此刻立刻占位，
                    // 会先闪一下 @speaking 再被 emotion 覆盖（突兀跳变）。
                    // 所以延迟占位：给 llm 一个到达窗口，超时再上 @speaking。
                    if (!gotLlmEmotion) scheduleSpeakingFallback();
                } else if ("sentence_start".equals(st)) {
                    li.onEvent("朗读", text);
                } else if ("stop".equals(st)) {
                    if (state != St.SPEAKING) {
                        // 迟到的 tts stop：被打断的上一轮回答现在才停流，而用户已开新一轮麦（LISTENING/THINKING）。
                        // 不能把状态冲回 READY/待机，否则会闪断当前轮的表情与看门狗（2026-09-13 实测到一次）。
                        Lg.i("tts stop 到达时状态=" + state + "（不在回答中）→ 忽略这次复位");
                    } else {
                        setState(St.READY, "回答结束");
                        // 情绪表情停留：回答结束后让情绪多显示一会儿，别立刻弹回待机。
                        // 否则 llm 的 emotion 往往在 tts start 之后才到、又被 stop 秒冲掉，人眼根本抓不住。
                        scheduleBackToIdle();
                    }
                }
            } else if ("llm".equals(type)) {
                String emo = j.optString("emotion", "");
                li.onEvent("情绪", emo);
                if (emo != null && emo.length() > 0) {
                    gotLlmEmotion = true;
                    emotionHoldSeq++;             // 新情绪到达，取消已排队的"回待机"
                    // 本地已锁定（用户明确说了情绪词）→ 服务端 emotion 不覆盖
                    if (localEmotionLocked) {
                        Lg.i("llm emotion='" + emo + "' 被本地情绪锁定忽略");
                    } else {
                        li.onEmotion(emo);
                    }
                }
            } else if ("iot".equals(type) || "mcp".equals(type)) {
                Lg.i("收到 " + type + "（P4 再接）");
            } else if ("system".equals(type)) {
                Lg.w("服务端 system 指令：" + clip(m));
            } else if ("alert".equals(type)) {
                Lg.w("服务端 alert：" + clip(m));
            } else {
                Lg.i("未处理的类型：" + type);
            }
        } catch (Throwable t) {
            Lg.w("解析文本消息失败：" + t);
        }
        // 任何服务端消息都算"最近一拍活动"：若此时已回到 READY（如 tts stop 之后 llm 才到），
        // 空闲倒计时从这一拍重新起算（hello/tts stop 等路径走到这里一并覆盖）
        if (state == St.READY && !talking && !pendingListenStart) armIdleTimer();
    }

    private void handleAudio(ByteBuffer b) {
        if (codec == null) return;
        byte[] data = new byte[b.remaining()];
        b.get(data);
        if (data.length == 0) return;

        if ("pcm".equals(downlinkFormat)) {
            // 服务端直接给 PCM（自建服务器可配）→ 跳过解码
            if (!player.isRunning() && !player.start()) return;
            int samples = data.length / 2;
            short[] pcm = new short[samples];
            for (int i = 0; i < samples; i++) {
                pcm[i] = (short) ((data[i * 2] & 0xFF) | (data[i * 2 + 1] << 8));
            }
            player.write(pcm, samples);
            return;
        }

        int n = codec.decode(data, data.length, decBuf);
        if (n <= 0) {
            Lg.w("解码失败（len=" + data.length + "）");
            return;
        }
        if (!player.isRunning() && !player.start()) return;
        player.write(decBuf, n);
        audioCount++;
        if (audioCount <= 3 || audioCount % 100 == 0) {
            Lg.i("下行 #" + audioCount + " 包 " + data.length + "B → " + n + " 样本（"
                    + (n * 1000 / Math.max(1, downlinkRate)) + "ms）");
        }
    }

    private int audioCount;

    // ==========================================================
    private void setState(St st, String detail) {
        St old = state;
        state = st;
        li.onState(st, detail);
        // 表情：只在这里处理"与对话内容无关"的状态态，对话内的由 llm/tts 各自发
        if (st == St.ERR) li.onEmotion("@error");
        else if (st == St.CONNECTING && old != St.READY) li.onEmotion("@connecting");
        else if (st == St.READY && old == St.CONNECTING) li.onEmotion("@wake");
    }

    private static String clip(String s) {
        if (s == null) return "null";
        return s.length() > 400 ? s.substring(0, 400) + "…(" + s.length() + ")" : s;
    }

    private static String tail(String s) {
        if (s == null) return "null";
        return s.length() <= 6 ? s : s.substring(s.length() - 6);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
