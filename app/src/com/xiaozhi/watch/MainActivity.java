package com.xiaozhi.watch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * P1 界面：功能优先、UI 很糙（P2 才做小方屏美化；372×430 是方屏，旧文档"圆屏"说法已推翻）。
 * 布局就三块：状态 + 大字激活码 / 按住说话大按钮 / 日志。
 */
public class MainActivity extends Activity implements XiaozhiClient.Listener, SensorEventListener {

    private static final int REQ_MIC = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private TextView detailView;
    private TextView codeView;
    private TextView talkBtn;
    private TextView logView;
    private ScrollView logScroll;
    private EmotionBallView ball;

    private DeviceIdentity identity;
    private Cfg cfg;
    private XiaozhiClient client;

    private volatile boolean destroyed;
    private volatile boolean activating;

    // ---- 待机氛围轮换（2026-09-13）：空闲不再死盯一张"02 待机放空"，按"多久没人碰"分档安静轮换；
    //      对话中（连接/听/想/说）自动暂停，回待机才恢复；离开表盘（onPause）不转，省电省唤醒。
    private final java.util.Random ambRnd = new java.util.Random();
    private volatile long lastInteractTs = System.currentTimeMillis();
    private boolean ambienceDisabled;          // --es emo 演示模式：人工控制表情，氛围让路
    private static final String[] AMB_ALERT  = {"02", "04", "03", "30", "56", "57"};   // 近期活跃：放空/发呆/好奇/思考/四处张望/偷瞑装没事
    private static final String[] AMB_DROWSY = {"06", "04", "02"};         // 3~10 分钟：犯困（休眠半开合）
    private static final String[] AMB_ASLEEP = {"00", "06"};               // >10 分钟：睡觉 zzz
    private static final String[] AMB_READY  = {"02", "19", "30", "03", "57"};  // 已连待命短窗口：满意/思考/偷瞑（在线感）

    // ---- 戳戳互动（POKE_INTERACTION R1/R2；用户拍板 2026-09-13：戳球=打断AI、音效默认开）----
    /** 互动层用脸查表：50+ 新表情增减时只改这张表，决策逻辑不动（方案 §4.4） */
    private static final String FACE_COMBO2 = "03";       // 双击：歪头好奇，看向戳点
    private static final String FACE_COMBO3 = "19";       // 三连击：被逗乐的满意
    private static final String FACE_COMBO5 = "58";       // 五连及以上：被戳晕（与打断懵同脸，一套两用的"懵"）
    private static final String FACE_PAT_END = "05";      // rua 完松手：精神一振
    private static final String FACE_PAT_DEEP = "06";     // rua 超 3s：好，安静陪你
    private static final String FACE_READY = "31";        // 已连待命被戳："嗯？接任务"
    private static final String FACE_THINKING = "05";     // 等回复时："快了快了"脉冲
    private final PokeSfx sfx = new PokeSfx();
    private int comboN;
    private long lastTapTs;
    private volatile boolean pokeBusy;        // 噱头进行中：氛围计时让路一拍
    private volatile boolean pointerDown;
    private long downTs;
    private float downX, downY;

    /** 长按 ≥500ms 仍未松手：进入"rua 头"模式（眯眼蹭头）；录音中不打扰麦 */
    private final Runnable patStart = new Runnable() {
        @Override
        public void run() {
            if (!pointerDown || destroyed || ball == null || client == null) return;
            if (client.state() == XiaozhiClient.St.LISTENING) return;
            ball.setPatted(true);
        }
    };

    /** 58 懵定格 → 41 停止终止 的两级时序（引擎无 sequence.settle，手动补；同氛围 07→02 手法） */
    private final Runnable abortSettle = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && ball != null && "58".equals(ball.emotionId())) ball.setEmotion("41");
        }
    };

    private float dirOf(float x) {
        float w = ball.getWidth() > 0 ? ball.getWidth() : 1;
        return Math.max(-1f, Math.min(1f, (x - w / 2f) / (w / 2f)));
    }

    private float levOf(float y) {
        float h = ball.getHeight() > 0 ? ball.getHeight() : 1;
        return Math.max(-1f, Math.min(1f, (y - h / 2f) / (h / 2f)));
    }

    /** 深夜档（23~7 点）：小幅度、几颗星、不配音，"还没睡呀"的安静回应 */
    private boolean isQuietHour() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 23 || h < 7;
    }

    /** 亮一张临时脸，dwellMs 后归 02（仅当还停在待机、且没人换过脸） */
    private void showMicroFace(final String id, final long dwellMs) {
        pokeBusy = true;
        ui.removeCallbacks(ambTick);              // 氛围全程让路
        ball.setEmotion(id);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                pokeBusy = false;
                XiaozhiClient.St st = client == null ? XiaozhiClient.St.OFF : client.state();
                if (!destroyed && ball != null && id.equals(ball.emotionId())
                        && (st == XiaozhiClient.St.OFF || st == XiaozhiClient.St.READY)) {
                    ball.setEmotion("02");
                }
                if (!destroyed && client != null) manageAmbience(st);   // 噱头收完重新起表
            }
        }, dwellMs);
    }

    /** 单点判定：手势×场合矩阵（POKE_INTERACTION §2/§3） */
    private void onTapBall(float x, float y, long heldMs) {
        XiaozhiClient.St st = client.state();
        float dir = dirOf(x);
        if (st == XiaozhiClient.St.SPEAKING) {
            // 戳球=打断 AI（用户拍板）：停话（协议 abort + 清音频），脸由 @abort 的 58→41 链驱动
            ball.poke(1, dir);
            sfx.play(PokeSfx.ABORT);
            vibe(45, 255);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    client.abort();
                }
            }, "xz-poke-abort").start();
            return;
        }
        if (st == XiaozhiClient.St.LISTENING) {
            ball.poke(1, dir);          // 录音中：只轻晃；粒子/声音/换脸都不做，绝不干扰采集（震动无声，保留）
            vibe(8, 120);
            return;
        }
        if (st == XiaozhiClient.St.THINKING) {
            ball.poke(1, dir);
            sfx.play(1);
            vibe(12, 160);
            showMicroFace(FACE_THINKING, 1500);
            return;
        }
        if (st == XiaozhiClient.St.CONNECTING || st == XiaozhiClient.St.ERR) {
            ball.poke(1, dir);          // 脸由状态机驱动，噱头只给物理反馈
            vibe(10, 120);
            return;
        }
        // OFF / READY：连击递进
        long now = System.currentTimeMillis();
        comboN = (now - lastTapTs < 600) ? comboN + 1 : 1;   // 600ms：人手双击足够紧凑，又给 adb 实测留出余量
        lastTapTs = now;
        int level = comboN >= 5 ? 3 : (comboN >= 3 ? 2 : 1);
        boolean night = isQuietHour();
        ball.poke(level, dir);
        ball.burst(night ? 3 + level * 2 : 4 + level * 4, night ? 0.5f : 0.12f + 0.14f * level);
        if (!night || comboN >= 3) sfx.play(level);
        vibe(level == 3 ? 35 : (level == 2 ? 22 : 15), 140 + 40 * level);
        if (level >= 2) ball.lookAt(dir, levOf(y) * 0.6f);
        String face = st == XiaozhiClient.St.READY ? FACE_READY : null;
        if (st == XiaozhiClient.St.OFF && comboN >= 5) face = FACE_COMBO5;
        else if (st == XiaozhiClient.St.OFF && comboN >= 3 && !night) face = FACE_COMBO3;
        else if (st == XiaozhiClient.St.OFF && comboN == 2 && !night) face = FACE_COMBO2;
        if (face != null) showMicroFace(face, comboN >= 5 ? 1800 : 1600);
        Lg.i("戳球：combo=" + comboN + " level=" + level + " st=" + st + (night ? " 深夜档" : ""));
        if (face == null) manageAmbience(st);   // 单戳无换脸时也要重置氛围表，防两次戳之间冒脸
    }

    /** rua 松手（≥500ms）：轻 rua 精神一振；长 rua ≥3s 进安静陪伴档 */
    private void onBallPattedEnd(long heldMs) {
        ball.setPatted(false);
        XiaozhiClient.St st = client.state();
        if (st == XiaozhiClient.St.LISTENING || st == XiaozhiClient.St.SPEAKING
                || st == XiaozhiClient.St.CONNECTING) return;   // 已进入主动场合，脸归客户端
        if (heldMs >= 3000) {
            showMicroFace(FACE_PAT_DEEP, 4200);
            vibe(55, 130);
        } else {
            showMicroFace(FACE_PAT_END, 1600);
            vibe(25, 180);
        }
        Lg.i("rua 头：held=" + heldMs + "ms st=" + st);
    }

    /** 按当前状态重排/暂停氛围轮换：只有 OFF（待机）/ READY（已连待命）两态允许转。 */
    private void manageAmbience(XiaozhiClient.St st) {
        ui.removeCallbacks(ambTick);
        if (ambienceDisabled || ball == null || destroyed) return;
        if (st == XiaozhiClient.St.OFF) {
            ui.postDelayed(ambTick, 9000 + ambRnd.nextInt(6000));      // 回待机先静置一会儿再换脸
        } else if (st == XiaozhiClient.St.READY) {
            ui.postDelayed(ambTick, 6000 + ambRnd.nextInt(3000));      // READY 只有 ~20s（随后 goIdle），够换一拍
        }
        // 其余状态（连接/听/想/说/错）：不排，等下一次 OFF/READY 回调再恢复
    }

    private final Runnable ambTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || ambienceDisabled || ball == null || client == null) return;
            if (pokeBusy) { ui.postDelayed(ambTick, 5000); return; }   // 戳噱头进行中：氛围让路
            XiaozhiClient.St st = client.state();
            if (st != XiaozhiClient.St.OFF && st != XiaozhiClient.St.READY) {
                return;                       // 对话中：本轮停转，onState 回待机时会重新起表
            }
            long idleAge = System.currentTimeMillis() - lastInteractTs;
            String cur = ball.emotionId();
            if (st == XiaozhiClient.St.OFF && idleAge < 180_000
                    && ("00".equals(cur) || "06".equals(cur))) {
                // 瞌睡中被叫回来：先播"07 抖动唤醒"，2.3s 后归位 02（引擎不支持自动 settle，手动补）
                Lg.i("待机氛围：刚从盹里回来 → 07 抖动唤醒");
                ball.setEmotion("07");
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || ball == null || client == null) return;
                        if (client.state() == XiaozhiClient.St.OFF && "07".equals(ball.emotionId())) {
                            ball.setEmotion("02");
                        }
                    }
                }, 2300);
                ui.postDelayed(ambTick, 9000 + ambRnd.nextInt(8000));
                return;
            }
            String[] pool;
            long dwell;
            if (st == XiaozhiClient.St.READY) {
                pool = AMB_READY;  dwell = 7000 + ambRnd.nextInt(5000);
            } else if (idleAge < 180_000) {
                pool = AMB_ALERT;  dwell = 9000 + ambRnd.nextInt(8000);
            } else if (idleAge < 600_000) {
                pool = AMB_DROWSY; dwell = 13000 + ambRnd.nextInt(9000);
            } else {
                pool = AMB_ASLEEP; dwell = 25000 + ambRnd.nextInt(15000);
            }
            String pick;
            int tries = 0;
            do {
                pick = pool[ambRnd.nextInt(pool.length)];
            } while (pick.equals(cur) && ++tries < 4);
            Lg.i("待机氛围：" + cur + " → " + pick + "（已静 " + (idleAge / 1000) + "s）");
            ball.setEmotion(pick);
            ui.postDelayed(ambTick, dwell);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        // 沉浸式：ColorOS 在充电时常驻 SysUI.Charging 覆盖表盘和普通 App，
        // 强制全屏（隐藏 status bar + navigation）让它退出 surface 合成层
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        buildUi();

        // 哑前台服务：抬进程优先级，保证上键长按广播（NaviKeyReceiver）能送达
        startService(new Intent(this, EntryKeepAlive.class));

        Lg.setSink(new Lg.Sink() {
            @Override
            public void line(final String s) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        appendLog(s);
                    }
                });
            }
        });

        cfg = Cfg.load(this);
        identity = new DeviceIdentity(this);
        client = new XiaozhiClient(identity, this);
        ball.start();

        // 倾角注视（加速度计）+ 戳互动触觉（震动马达）：都先探测可用性，缺了只差日志不报错
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Lg.i("加速度计：" + (accel != null ? "可用，注视联动倾角" : "不可用（注视保持自走漂移）"));
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        Lg.i("震动马达：" + (vib != null && vib.hasVibrator() ? "可用" : "不可用（戳互动纯视觉+音效）"));

        // 启动演示：默认切到"待机放空"。
        // --es emo <id>     切到指定表情（如 21=生气红 / 33=撒花）
        // --es emo loop     8 个高对比表情循环切换，每 3 秒一个，肉眼直接对比差异
        final String emo = getIntent() == null ? null : getIntent().getStringExtra("emo");
        if (emo != null && emo.length() > 0) {
            ambienceDisabled = true;      // 表情演示由 intent 接管，氛围轮换让路
        }
        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (emo == null || emo.length() == 0) return;
                if ("loop".equals(emo)) {
                    // 戏剧化高对比组：待机→开心撒花→惊讶巨眼→失落→心疼安抚→无语凝嚝→悬心担忧→困惑→生气红→同仇敌忾→满意→睡眠
                    final String[] showcase = { "02", "33", "13", "12", "50", "53", "52", "20", "21", "51", "19", "00" };
                    final String[] labels = { "待机", "撒花", "惊讶", "失落", "心疼安抚", "无语凝嚝", "悬心担忧", "困惑", "生气红", "同仇敌忾", "满意", "睡眠" };
                    new Thread(new Runnable() {
                        @Override public void run() {
                            for (int i = 0; i < showcase.length * 2 && !destroyed; i++) {
                                int k = i % showcase.length;
                                final String id = showcase[k];
                                final String lab = labels[k];
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        ball.setEmotion(id);
                                        detailView.setText("演示：" + lab + " (" + id + ")");
                                    }
                                });
                                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
                            }
                        }
                    }, "xz-emo-demo").start();
                    Lg.i("演示表情 → 循环 8 种");
                } else {
                    ball.setEmotion(emo);
                    Lg.i("演示表情 → " + emo);
                }
            }
        }, 800);

        Lg.i("=== 小智手表端 P1 启动 ===");
        Lg.i("Device-Id=" + identity.deviceId + " Client-Id=" + identity.clientId);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Lg.w("申请录音权限（表上会弹窗，点允许）");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            Lg.i("录音权限已授予");
            afterPermission();
        }

        // 自动化入口（真机验证用，省得和屏幕坐标较劲）：
        //   adb shell am start -n com.xiaozhi.watch/.MainActivity --ei talkms 3000
        //   ... --ei talkms 5000 --ei loop 3     # 连续听 3 轮，每轮 5 秒（人工说话时用它兜底）
        // manual 模式必须 listen stop 服务端才出 ASR → 单窗口一过就没机会了，
        // 所以人工配合说话时用 loop 反复开关麦，随时开口都能被某一轮覆盖到。
        final boolean viaSpeechAction = getIntent() != null
                && "com.heytap.wearable.breeno.intent.action.SPEECH".equals(getIntent().getAction());
        final int talkMs = (getIntent() != null && getIntent().getIntExtra("talkms", 0) > 0)
                ? getIntent().getIntExtra("talkms", 0)
                : (viaSpeechAction ? 8000 : 0);
        final int loopN = getIntent() == null ? 1 : getIntent().getIntExtra("loop", viaSpeechAction ? 2 : 1);
        if (talkMs > 0) {
            Lg.i("自动化：自动按住说话 " + talkMs + "ms，共 " + loopN + " 轮");
            // 不再显式预热：onResume 的亮屏预连已先一步发起；真没就绪 startTalking 自带 pending 兜底
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int n = 1; n <= loopN && !destroyed; n++) {
                        sleep(500);
                        Lg.i("=== 第 " + n + "/" + loopN + " 轮 → 开麦（对着表说话）===");
                        // 不再因"未就绪"跳过：startTalking 自带 pending，未连上会排队到 hello 后自动开麦
                        client.startTalking();
                        sleep(talkMs);
                        Lg.i("=== 第 " + n + " 轮 → 松手，等服务端回 stt/tts ===");
                        client.stopTalking();
                        sleep(4000);
                    }
                    Lg.i("自动化测试结束（空闲 20s 后自动断开回待机；可继续手动按住说话）");
                }
            }, "xz-autotalk").start();
        }

        // 戳互动自检：adb shell am start ... --ei poke 6 → 表端以 200ms 节奏模拟 6 次连点，
        //   验证 combo 递进/连击脸/音效链路（绕开 adb input 的单发往返延迟，那是测量假象不是产品问题）。
        final int pokeN = getIntent() == null ? 0 : getIntent().getIntExtra("poke", 0);
        if (pokeN > 0) {
            Lg.i("自检：2.5s 后模拟连戳 " + pokeN + " 次（200ms 间隔）");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    sleep(2500);
                    for (int i = 0; i < pokeN && !destroyed; i++) {
                        final int idx = i;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (destroyed || client == null) return;
                                lastInteractTs = System.currentTimeMillis();
                                onTapBall(74 + (idx % 3) * 24, 104, 60);   // 小偏移模拟戳在球左/中/右侧
                            }
                        });
                        sleep(200);
                    }
                }
            }, "xz-poketest").start();
        }
    }

    private void afterPermission() {
        // config.json 指定"非官方 + 非占位 token" → 直连（自建逃生口；本项目官方路线不用）。
        boolean selfHost = cfg.wsUrl != null
                && !Cfg.DEFAULT_WS.equals(cfg.wsUrl)
                && !"test-token".equals(cfg.token);
        if (selfHost) {
            Lg.i("config.json 指定了非官方服务器 → 常驻直连 " + cfg.wsUrl);
            identity.saveCredentials(cfg.wsUrl, cfg.token == null ? "" : cfg.token);
            client.setOnDemand(false);      // 自建（stub 服务器等）：保留保活+自动重连，方便长时间测试
            client.connectWs();
            return;
        }
        if (identity.hasCredentials()) {   // 已有真 token（绑定成功过）
            Lg.i("已有真 token → 按需联网（空闲不挂连接）");
            client.setOnDemand(true);
            client.goIdle("待机 · 按住说话自动联网");
            return;
        }
        // 官方云 → **按需连接**（2026-09-13）：待机时不挂 WS。
        //   按住说话/上键长按入口当场才连（未就绪时 pending 排队，hello 到点自动开麦），
        //   READY 静置 20s 断开回待机。官方对 test-token 的 ~65s 空闲回收再也轮不到我们：
        //   表情球不再周期闪"联网加载→唤醒"，也没有空闲重连耗电。
        //   想试绑自己智能体仍手动点「激活」（轮询与连接互不干扰）；调试想常驻点「连接」。
        Lg.i("官方云 · 按需联网：待机不挂连接，按住说话自动联网（点「连接」可切常驻调试）");
        identity.saveCredentials(Cfg.DEFAULT_WS, "test-token");   // 凭据先落盘，按下即取用（不等 OTA）
        client.setOnDemand(true);
        client.goIdle("待机 · 按住说话自动联网");
    }

    // ==========================================================
    // 激活：POST OTA → 显示 6 位码 → 每 3 秒轮询直到绑定成功
    // ==========================================================
    private void startActivation() {
        if (activating) return;
        activating = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Lg.i("激活轮询开始：表上会显示 6 位码，去 xiaozhi.me 控制台 → 添加设备 输入；最多等 5 分钟");
                // 注意：不清 identity.token —— test-token 是当前可用的连接凭据（§2.1/afterPermission），
                // 清掉会打断已在跑的直连。绑定成功时下面 activated 分支会自行覆盖成真 token。
                // 前 2 分钟 3 秒一次，之后放慢到 10 秒一次，总共约 32 分钟（给人留足去控制台绑定的时间）
                int total = 240;
                int blockedStreak = 0;          // 连续"有 websocket 但无 activation"的帧数（用于区分真阻塞 vs 代理抖动丢包）
                for (int i = 1; i <= total && !destroyed; i++) {
                    OtaClient.Result r = OtaClient.fetch(cfg.otaUrl, identity.deviceId,
                            identity.clientId, identity.uuid, "0.1");
                    if (r.activated) {
                        identity.saveCredentials(r.wsUrl, r.token);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                codeView.setVisibility(View.GONE);
                            }
                        });
                        Lg.i("✅ 激活成功，拿到 ws url：" + r.wsUrl);
                        activating = false;
                        client.connectWs();
                        return;
                    }
                    if (r.code != null && r.code.length() > 0) {
                        blockedStreak = 0;       // 拿到码 → 之前那些"没码"多半是抖动，清零
                        if (!r.code.equals(identity.activationCode)) {
                            identity.saveActivationCode(r.code);
                            showCode(r.code, r.message);
                        } else if (i % 5 == 0) {
                            Lg.i("等待表主在控制台输入 " + r.code + " …（第 " + i + " 次轮询）");
                        }
                    } else if (r.officialBlocked) {
                        // 这一帧"只给 test-token、没 activation"。手表走 COMPANION_PROXY（蓝牙经手机代理），
                        // 单帧丢包/断流会让服务端响应缺字段——所以只有"连续多帧稳定如此"才可能是真被登记成测试设备。
                        blockedStreak++;
                        final int streak = blockedStreak;
                        final String keep = identity.activationCode;   // 上一次的码，抖动期间别从屏上抹掉
                        if (streak < 6) {
                            Lg.w("本帧官方只给占位 token（连续 " + streak + "/6）→ 多半是代理抖动，继续轮询；表上保留上次激活码 " + keep);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    statusView.setText("等待发码");
                                    detailView.setText("网络抖动丢帧，继续轮询中…（已有码：" + keep + "，可直接去 xiaozhi.me 输）");
                                    if (keep != null && keep.length() > 0) {
                                        codeView.setVisibility(View.VISIBLE);
                                        codeView.setText(keep);
                                    }
                                }
                            });
                        } else {
                            Lg.w("★ 连续 " + streak + " 帧都无激活码 → 这设备可能被登记成测试设备了。到 xiaozhi.me 删该设备（尾号 …" + tailId() + "），再点表上「重新配对」换新身份。");
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    statusView.setText("未发激活码");
                                    detailView.setText("连续" + streak + "帧无码：xiaozhi.me 删旧设备→点「重新配对」（尾号 …" + tailId() + "）");
                                    if (keep != null && keep.length() > 0) {
                                        codeView.setVisibility(View.VISIBLE);
                                        codeView.setText(keep);
                                    } else {
                                        codeView.setVisibility(View.GONE);
                                    }
                                }
                            });
                        }
                    } else if (!r.ok) {
                        Lg.w("OTA 没通：http=" + r.httpCode + " err=" + r.error + " raw=" + r.raw);
                        final String msg = r.error != null ? r.error : ("HTTP " + r.httpCode);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                statusView.setText("OTA 失败");
                                detailView.setText(msg);
                            }
                        });
                    }
                    // 长轮询 /ota/activate 取证：200=已激活 / 202=等服务端 / 404=没有 License。
                    // 官方的 202 就代表"设备在等你输激活码"，是我们判断链路是否正常的关键指标。
                    if (r.challenge != null) {
                        OtaClient.ActivateResult ar = OtaClient.activate(cfg.otaUrl, identity.deviceId,
                                identity.clientId, r.challenge);
                        if (ar.httpCode == 404) {
                            Lg.w("服务端说这个设备没有 License —— 这就是控制台提示「需要序列号/请检查是否烧录」的根源");
                        }
                    }
                    sleep(i <= 40 ? 3000 : 10000);
                }
                activating = false;
                Lg.w("激活轮询结束（未拿到凭据）");
            }
        }, "xz-activate").start();
    }

    /** 设备尾号（Device-Id 末 5 字符），给用户去控制台认设备用 */
    private String tailId() {
        String d = identity.deviceId;
        return d == null ? "?" : d.substring(Math.max(0, d.length() - 5));
    }

    private void showCode(final String code, final String message) {
        Lg.i("★ 激活码 = " + code + "  " + (message == null ? "" : message));
        ui.post(new Runnable() {
            @Override
            public void run() {
                codeView.setVisibility(View.VISIBLE);
                codeView.setText(code);
                statusView.setText("待绑定");
                detailView.setText("在 xiaozhi.me 控制台 → 添加设备 → 输入上面的 6 位码");
            }
        });
    }

    // ==========================================================
    // 客户端回调
    // ==========================================================
    @Override
    public void onState(final XiaozhiClient.St st, final String detail) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusView.setText(st.cn);
                detailView.setText(detail == null ? "" : detail);
                switch (st) {
                    case LISTENING:
                        statusView.setTextColor(0xFFFF5252);
                        talkBtn.setText("松开发送");
                        break;
                    case SPEAKING:
                        statusView.setTextColor(0xFF4CAF50);
                        talkBtn.setText("按住打断");
                        break;
                    case THINKING:
                    case CONNECTING:
                        statusView.setTextColor(0xFFFFC107);
                        talkBtn.setText("按住说话");
                        break;
                    case ERR:
                        statusView.setTextColor(0xFFE53935);
                        talkBtn.setText("按住说话");
                        break;
                    default:
                        statusView.setTextColor(0xFF64B5F6);
                        talkBtn.setText("按住说话");
                        break;
                }
                manageAmbience(st);
            }
        });
    }

    @Override
    public void onEvent(final String kind, final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (text == null || text.length() == 0) return;
                detailView.setText(kind + "：" + text);
            }
        });
    }

    /**
     * 表情回调：小智的 emotion 字段 / 客户端状态态 都走这里。
     * 回调来自网络线程，必须切回 UI 线程再动 View。
     */
    @Override
    public void onEmotion(final String emotion) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || ball == null) return;
                if ("@abort".equals(emotion)) {
                    // 被打断懵一下（用户拍板）：58 定格发懵 1.4s → 41 停止终止。两级时序手动补
                    ball.setEmotion("58");
                    ui.removeCallbacks(abortSettle);
                    ui.postDelayed(abortSettle, 1400);
                    Lg.i("onEmotion: '@abort' → 58 懵（1.4s 后落 41）");
                    return;
                }
                String id = XiaozhiEmotion.of(emotion);
                Lg.i("onEmotion: '" + emotion + "' → id=" + id + "（当前=" + ball.emotionId() + "）");
                if (id.equals(ball.emotionId())) return;   // 同一个表情不重复触发过渡
                ball.setEmotion(id);
            }
        });
    }

    // ==========================================================
    // UI
    // ==========================================================
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /**
     * ⚠️ 屏幕只有 372×430 px / density 320 → **186×215dp**，不是手机尺寸。
     * 第一版按手机比例写，结果激活码折成两行、按钮全被挤出屏幕（真机截图见 app/shots/）。
     * 这里所有尺寸都是"按 215dp 高度倒推"出来的，改任何一项都要重新算总高。
     */
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(18), dp(10), dp(18), dp(6));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        ball = new EmotionBallView(this);
        LinearLayout.LayoutParams blp = lp(dp(80), dp(80));
        blp.topMargin = dp(2);
        root.addView(ball, blp);
        // 戳戳互动：自带触摸判定（tap/长按/连击/方向），不用 OnClickListener（它给不了时长与坐标序列）
        ball.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pointerDown = true;
                        downTs = System.currentTimeMillis();
                        downX = e.getX();
                        downY = e.getY();
                        ui.removeCallbacks(patStart);
                        ui.postDelayed(patStart, 500);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        pointerDown = false;
                        ui.removeCallbacks(patStart);
                        lastInteractTs = System.currentTimeMillis();    // 摸表 = 交互，氛围分档重置
                        long held = System.currentTimeMillis() - downTs;
                        if (destroyed || client == null
                                || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            if (ball != null) ball.setPatted(false);
                            return true;
                        }
                        if (held >= 500) onBallPattedEnd(held);
                        else onTapBall(downX, downY, held);
                        return true;
                    }
                    default:
                        return true;
                }
            }
        });

        statusView = new TextView(this);
        statusView.setText("启动中");
        statusView.setTextColor(0xFF64B5F6);
        statusView.setTextSize(16);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusView.setSingleLine(true);
        root.addView(statusView, lp(-1, -2));

        detailView = new TextView(this);
        detailView.setTextColor(0xFFCCCCCC);
        detailView.setTextSize(9);
        detailView.setGravity(Gravity.CENTER);
        detailView.setMaxLines(1);
        detailView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = lp(-1, -2);
        dlp.topMargin = dp(1);
        root.addView(detailView, dlp);

        codeView = new TextView(this);
        codeView.setTextColor(0xFF4CAF50);
        codeView.setTextSize(24);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setLetterSpacing(0.04f);
        codeView.setSingleLine(true);
        codeView.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = lp(-1, -2);
        clp.topMargin = dp(2);
        root.addView(codeView, clp);

        talkBtn = new TextView(this);
        talkBtn.setText("按住说话");
        talkBtn.setTextColor(Color.WHITE);
        talkBtn.setTextSize(15);
        talkBtn.setTypeface(Typeface.DEFAULT_BOLD);
        talkBtn.setGravity(Gravity.CENTER);
        talkBtn.setBackground(roundBg(0xFF1E88E5));
        LinearLayout.LayoutParams tlp = lp(-1, dp(40));
        tlp.topMargin = dp(6);
        root.addView(talkBtn, tlp);
        talkBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setBackground(roundBg(0xFFEF5350));
                        lastInteractTs = System.currentTimeMillis();   // 摸表 = 交互，氛围分档计时重置
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                client.startTalking();
                            }
                        }, "xz-talk-on").start();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setBackground(roundBg(0xFF1E88E5));
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                client.stopTalking();
                            }
                        }, "xz-talk-off").start();
                        return true;
                    default:
                        return false;
                }
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = lp(-1, -2);
        rlp.topMargin = dp(6);
        root.addView(row, rlp);
        row.addView(smallBtn("激活", new Runnable() {
            @Override
            public void run() {
                startActivation();
            }
        }));
        row.addView(smallBtn("连接", new Runnable() {
            @Override
            public void run() {
                // 调试入口：切到"常驻连接"模式（保活+自动重连，与旧版一致），方便长时间看日志。
                // 点「重载」或重启界面会回到官方云默认的按需模式。
                if (!identity.hasCredentials()) {
                    identity.saveCredentials(Cfg.DEFAULT_WS, "test-token");
                }
                client.setOnDemand(false);
                client.connectWs();
                Lg.i("点「连接」→ 切常驻模式（空闲不掉线，适合调试；「重载」可回按需）");
            }
        }));
        row.addView(smallBtn("重新配对", new Runnable() {
            @Override
            public void run() {
                Lg.i("重新配对：清空身份（旧 Device-Id/Client-Id/凭据全没）→ 重启换新身份重走激活。"
                        + "请先在 xiaozhi.me 删除尾号 …" + tailId() + " 的旧设备");
                client.disconnect();
                identity.wipeIdentity();
                finish();
                startActivity(new Intent(MainActivity.this, MainActivity.class));
            }
        }));
        row.addView(smallBtn("重载", new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        cfg = Cfg.load(MainActivity.this);
                        // 统一交给 afterPermission 判定（自建=常驻；官方=按需联网，待机不挂连接）
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                afterPermission();
                            }
                        });
                    }
                }, "xz-reload").start();
            }
        }));
        row.addView(smallBtn("清屏", new Runnable() {
            @Override
            public void run() {
                logView.setText("");
            }
        }));

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(0xFF8A8A8A);
        logView.setTextSize(8);
        logView.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(logView, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = lp(-1, 0);
        slp.weight = 1;
        slp.topMargin = dp(4);
        root.addView(logScroll, slp);

        setContentView(root);
    }

    private GradientDrawable roundBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(22));
        d.setColor(color);
        return d;
    }

    private TextView smallBtn(String text, final Runnable action) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(9);
        b.setTextColor(0xFFDDDDDD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(roundBg(0xFF2C2C2C));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(26), 1f);
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
        b.setLayoutParams(p);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private void appendLog(String s) {
        CharSequence cur = logView.getText();
        if (cur != null && cur.length() > 30000) {
            logView.setText(cur.subSequence(15000, cur.length()));
            logView.append("\n");
        }
        logView.append(s);
        logView.append("\n");
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ==========================================================
    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ambTick);      // 不在表盘上就不换脸，省电也避免回来时表情错乱
        if (sensorManager != null) sensorManager.unregisterListener(this);
        // 息屏即收线：按需模式下 READY 挂着没意义（人不看表），立刻断开省电；亮屏 onResume 再预连
        if (client != null && client.isOnDemand() && client.state() == XiaozhiClient.St.READY) {
            Lg.i("息屏 → 立即断开（亮屏自动预连）");
            client.goIdle("待机 · 亮屏自动预连");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastInteractTs = System.currentTimeMillis();    // 回到表盘算一次交互（瞌睡脸下次会被 07 唤醒）
        if (client != null) manageAmbience(client.state());
        // 抬手预连接：亮屏回到表盘且按需模式离线 → 立刻联网，用户按住说话时大概率已 READY
        if (client != null && client.isOnDemand() && client.state() == XiaozhiClient.St.OFF
                && identity != null && identity.wsUrl != null && identity.token != null) {
            Lg.i("亮屏预连接（按需模式）");
            client.connectWs();
        }
        if (sensorManager != null && accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
        }
    }

    // ---- 倾角注视：手腕姿态→球的目光（低通去抖，5Hz 目标就够 30fps 渲染环平滑）----
    private SensorManager sensorManager;
    private Sensor accel;
    private float gX, gY;
    private long lastTiltLog;

    @Override
    public void onSensorChanged(SensorEvent e) {
        gX += (e.values[0] - gX) * 0.15f;
        gY += (e.values[1] - gY) * 0.15f;
        float tx = clampF(gX / 9.8f, -1f, 1f) * 0.9f;
        float ty = clampF(gY / 9.8f, -1f, 1f) * 0.7f;
        if (ball != null) ball.setTiltBias(tx, ty);
        long now = System.currentTimeMillis();
        if (now - lastTiltLog > 5000) {
            lastTiltLog = now;
            Lg.i("倾角注视: gx=" + (int) (gX * 10) / 10f + " gy=" + (int) (gY * 10) / 10f
                    + " → bias(" + (int) (tx * 100) / 100f + "," + (int) (ty * 100) / 100f + ")");
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---- 触觉：戳互动轻震（马达缺失/调用失败静默降级）----
    private Vibrator vib;

    private void vibe(int ms, int amp) {
        try {
            if (vib == null || !vib.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(ms, amp));
            } else {
                vib.vibrate(ms);
            }
        } catch (Throwable t) {
            Lg.w("震动失败（忽略）：" + t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            Lg.i("录音权限结果 = " + (granted ? "已授予" : "被拒绝（没麦就等于没用）"));
            if (granted) {
                afterPermission();
            } else {
                statusView.setText("无麦克风权限");
                detailView.setText("去 ColorOS 设置里给「小智」开麦克风权限");
            }
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (ball != null) ball.stop();
        Lg.setSink(null);
        if (client != null) client.disconnect();
        sfx.release();
        super.onDestroy();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
