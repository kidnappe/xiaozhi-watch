package com.xiaozhi.probe;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Random;
import java.util.UUID;

/**
 * P0 音频能力探针（小智手表端移植第一步）
 * 一次回答三个"能不能"：
 *   ① ColorOS Watch 放不放行第三方 App 录音（R1）
 *   ② 表内喇叭能不能真出声，还是被路由到蓝牙（R3）
 *   ③ 纯 Java Opus 在 A53 上够不够快（R2）
 * 附带：④ TLS/wss 能不能通 + header 有没有在握手时带上（R4）
 *      ⑤ Device-Id 自造并持久化是否可行（R9）
 */
public class MainActivity extends Activity {

    private TextView status;
    private TextView logView;
    private ScrollView scroller;
    private volatile boolean busy = false;

    private String deviceId;
    private String clientId;
    /** adb 一键跑全套：am start -n com.xiaozhi.probe/.MainActivity --ez autorun true
     *  只跑录音矩阵：  … --es step record */
    private boolean autorun = false;
    private String stepName = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Probe.init(getExternalFilesDir(null));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        autorun = getIntent() != null && getIntent().getBooleanExtra("autorun", false);
        stepName = getIntent() != null ? getIntent().getStringExtra("step") : null;

        buildUi();

        Probe.setListener(new Probe.Listener() {
            @Override
            public void onLine(final String line) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        logView.append(line + "\n");
                        scroller.post(new Runnable() {
                            @Override
                            public void run() {
                                scroller.fullScroll(View.FOCUS_DOWN);
                            }
                        });
                    }
                });
            }
        });

        Probe.log("==================== XZProbe 启动 " + Probe.stamp() + " ====================");
        Probe.log("包名=" + getPackageName() + " 日志文件=" + Probe.filePath());
        Probe.log("用法：插上表后按顺序点【全跑】，然后用耳朵确认两次播音是否听见；跑完把日志 pull 出来");

        ensureIdentity();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Probe.log("申请 RECORD_AUDIO 权限 …（表上会弹窗，必须点允许）");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else {
            Probe.log("RECORD_AUDIO 已授权");
            maybeAutostart();
        }
    }

    /** autorun / step 的自动起跑（权限就绪后延迟 2 秒，等界面画完） */
    private void maybeAutostart() {
        if (stepName != null) {
            final String s = stepName;
            Probe.log("step=" + s + " → 2 秒后自动跑该步骤");
            status.postDelayed(new Runnable() {
                public void run() {
                    if ("record".equals(s)) {
                        step("录音矩阵", new Runnable() {
                            public void run() {
                                AudioProbe.recordMatrix(MainActivity.this);
                            }
                        });
                    } else if ("loopback".equals(s)) {
                        step("回环(媒体)", new Runnable() {
                            public void run() {
                                AudioProbe.loopback(MainActivity.this, 0, false);
                            }
                        });
                    }
                }
            }, 2000);
            return;
        }
        if (autorun) {
            Probe.log("autorun=true → 2 秒后自动跑全套");
            status.postDelayed(new Runnable() {
                public void run() {
                    runAll();
                }
            }, 2000);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        int r = grantResults.length > 0 ? grantResults[0] : -99;
        Probe.log("权限结果 RECORD_AUDIO = " + r + " (0=GRANTED, -1=DENIED)"
                + (r == 0 ? "" : "  ← 被拒！R1 直接判死，后面的录音测试无意义（仍需确认是否为 ColorOS 特有策略）"));
        if (r == 0) {
            maybeAutostart();
        }
    }

    private void ensureIdentity() {
        SharedPreferences sp = getSharedPreferences("xzprobe", MODE_PRIVATE);
        deviceId = sp.getString("device_id", null);
        clientId = sp.getString("client_id", null);
        if (deviceId == null) {
            Random r = new Random();
            byte[] mac = new byte[6];
            r.nextBytes(mac);
            mac[0] = (byte) 0x02; // 本地管理位，避免和真实厂商前缀撞车
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", mac[i]));
            }
            deviceId = sb.toString();
            sp.edit().putString("device_id", deviceId).apply();
            Probe.log("首次运行：自造 Device-Id = " + deviceId + "（Android 6+ 读不到真实 MAC，必须自造+持久化）");
        } else {
            Probe.log("复用已持久化的 Device-Id = " + deviceId);
        }
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            sp.edit().putString("client_id", clientId).apply();
            Probe.log("首次运行：生成 Client-Id = " + clientId);
        } else {
            Probe.log("复用已持久化的 Client-Id = " + clientId);
        }
    }

    // ==================================================================
    // UI
    // ==================================================================
    private void buildUi() {
        int pad = dp(6);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0E1116"));
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#7FD1FF"));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setText("待机 — 点【全跑】");
        root.addView(status);

        root.addView(row(
                btn("全跑", new Runnable() {
                    public void run() {
                        runAll();
                    }
                }),
                btn("系统", new Runnable() {
                    public void run() {
                        step("系统能力", new Runnable() {
                            public void run() {
                                AudioProbe.systemCaps(MainActivity.this);
                            }
                        });
                    }
                }),
                btn("回环", new Runnable() {
                    public void run() {
                        step("回环测试", new Runnable() {
                            public void run() {
                                AudioProbe.loopback(MainActivity.this, 0, false);
                            }
                        });
                    }
                })));

        root.addView(row(
                btn("录音横扫", new Runnable() {
                    public void run() {
                        step("录音横扫", new Runnable() {
                            public void run() {
                                AudioProbe.recordSourceSweep(MainActivity.this, 16000, 2);
                            }
                        });
                    }
                }),
                btn("播媒体", new Runnable() {
                    public void run() {
                        step("播放(媒体通路)", new Runnable() {
                            public void run() {
                                AudioProbe.play(MainActivity.this, 24000, 3, 0, "媒体通路24000");
                            }
                        });
                    }
                }),
                btn("播通话", new Runnable() {
                    public void run() {
                        step("播放(通话通路)", new Runnable() {
                            public void run() {
                                AudioProbe.play(MainActivity.this, 24000, 3, 1, "通话通路24000");
                            }
                        });
                    }
                })));

        root.addView(row(
                btn("网络", new Runnable() {
                    public void run() {
                        step("网络与TLS", new Runnable() {
                            public void run() {
                                WsProbe.netAndTls(MainActivity.this);
                                WsProbe.wsHandshake(WsProbe.WS_URL, deviceId, clientId);
                            }
                        });
                    }
                }),
                btn("录音48k", new Runnable() {
                    public void run() {
                        step("48k 录音", new Runnable() {
                            public void run() {
                                AudioProbe.recordSourceSweep(MainActivity.this, 48000, 2);
                            }
                        });
                    }
                }),
                btn("清屏", new Runnable() {
                    public void run() {
                        logView.setText("");
                    }
                })));

        root.addView(row(
                btn("Opus压测(慢)", new Runnable() {
                    public void run() {
                        step("Opus 压测", new Runnable() {
                            public void run() {
                                OpusBench.run();
                            }
                        });
                    }
                }),
                btn("回环通话", new Runnable() {
                    public void run() {
                        step("回环(通话通路)", new Runnable() {
                            public void run() {
                                AudioProbe.loopback(MainActivity.this, 1, false);
                            }
                        });
                    }
                }),
                btn("回环AEC", new Runnable() {
                    public void run() {
                        step("回环(AEC开)", new Runnable() {
                            public void run() {
                                AudioProbe.loopback(MainActivity.this, 0, true);
                            }
                        });
                    }
                })));

        scroller = new ScrollView(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = dp(4);
        scroller.setLayoutParams(sp);
        scroller.setBackgroundColor(Color.parseColor("#070A0D"));

        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#D7DDE5"));
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroller.addView(logView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scroller);
        setContentView(root);
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(4);
        r.setLayoutParams(p);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(3);
            v.setLayoutParams(lp);
            r.addView(v);
        }
        return r;
    }

    private Button btn(String text, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(40));
        b.setBackgroundColor(Color.parseColor("#1B2530"));
        b.setTextColor(Color.parseColor("#EAF2FA"));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ==================================================================
    // 跑测试
    // ==================================================================
    private void step(final String name, Runnable body) {
        if (busy) {
            Probe.log("'" + name + "' 被忽略：上一个测试还在跑");
            return;
        }
        busy = true;
        setStatus("跑：" + name + " …");
        runBody(name, body);
    }

    private void runBody(final String name, final Runnable body) {
        new Thread(new Runnable() {
            public void run() {
                long t0 = android.os.SystemClock.elapsedRealtime();
                try {
                    body.run();
                } catch (Throwable t) {
                    Probe.log("[!] " + name + " 抛异常: " + t);
                }
                long dt = android.os.SystemClock.elapsedRealtime() - t0;
                Probe.log("[✓] " + name + " 结束，耗时 " + (dt / 1000.0) + "s");
                busy = false;
                runOnUiThread(new Runnable() {
                    public void run() {
                        setStatus("完成：" + name + "（可继续点别的）");
                    }
                });
            }
        }, "probe-" + name).start();
    }

    /** 全跑：一次点完，顺序经过所有检查点 */
    private void runAll() {
        if (busy) {
            Probe.log("全跑被忽略：正在忙");
            return;
        }
        busy = true;
        setStatus("全跑中 …（会在表上出声两次，请留意）");
        new Thread(new Runnable() {
            public void run() {
                String[] stage = new String[]{"系统能力", "录音横扫 16k", "播放(媒体通路)", "播放(通话通路)",
                        "回环(媒体)", "回环(通话)", "回环(AEC开)", "网络与TLS", "wss 握手", "Opus 压测"};
                try {
                    for (int i = 0; i < stage.length; i++) {
                        final String s = stage[i];
                        final int idx = i + 1;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                setStatus("全跑 " + idx + "/" + stage.length + "：" + s);
                            }
                        });
                        switch (i) {
                            case 0:
                                AudioProbe.systemCaps(MainActivity.this);
                                break;
                            case 1:
                                AudioProbe.recordSourceSweep(MainActivity.this, 16000, 2);
                                break;
                            case 2:
                                AudioProbe.play(MainActivity.this, 24000, 3, 0, "媒体通路24000(请听)");
                                break;
                            case 3:
                                AudioProbe.play(MainActivity.this, 24000, 3, 1, "通话通路24000(请听)");
                                break;
                            case 4:
                                AudioProbe.loopback(MainActivity.this, 0, false);
                                break;
                            case 5:
                                AudioProbe.loopback(MainActivity.this, 1, false);
                                break;
                            case 6:
                                AudioProbe.loopback(MainActivity.this, 0, true);
                                break;
                            case 7:
                                WsProbe.netAndTls(MainActivity.this);
                                break;
                            case 8:
                                WsProbe.wsHandshake(WsProbe.WS_URL, deviceId, clientId);
                                break;
                            case 9:
                                OpusBench.run();
                                break;
                            default:
                                break;
                        }
                    }
                    Probe.log("==================== 全跑结束 " + Probe.stamp() + " ====================");
                    Probe.log("日志文件: " + Probe.filePath());
                    Probe.log("把日志和 wav 拉出来：");
                    Probe.log("  adb pull /sdcard/Android/data/com.xiaozhi.probe/files/ ./pulled");
                } catch (Throwable t) {
                    Probe.log("[!] 全跑异常中断: " + t);
                }
                busy = false;
                runOnUiThread(new Runnable() {
                    public void run() {
                        setStatus("全部完成 — 请 pull 日志");
                    }
                });
            }
        }, "probe-all").start();
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            public void run() {
                status.setText(s);
            }
        });
    }

    @Override
    protected void onDestroy() {
        Probe.setListener(null);
        super.onDestroy();
    }
}
