package com.xiaozhi.watch;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

/**
 * 上行采集。参数按 P0 真机实测定案（probe_audio/P0_RESULTS.md §六）：
 *
 *   AudioSource.MIC  ← 不是 VOICE_COMMUNICATION！同一支麦实测 MIC peak 355~468 = 真声音，
 *                      而 VOICE_COMMUNICATION 不加 AEC 时 peak 只有 10（数字静音级，近乎聋）。
 *   16000Hz / mono / PCM16 / 60ms 一帧（960 样本）
 *   AEC + NS 都开（P0：均可用）—— 但 AEC 只削约 16dB，所以 P2 的"TTS 期间闭麦"伪 AEC 仍要保留。
 *
 * startRecording 带 3 次重试：P0 实测刚 pm grant 完会有瞬时 IllegalStateException:
 * permission denied（运行时权限与 appops 都是 allow），疑似 ColorOS 把麦门控绑在"应用真的在前台"上。
 */
public final class AudioCapture {

    public static final int RATE = 16000;
    public static final int FRAME = 960;              // 60ms

    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 500;

    public interface Sink {
        /** 一帧 PCM（长度恒为 FRAME）。实现方拿到数组所有权，可自行持有 */
        void onFrame(short[] pcm, int samples);

        /** 采集异常停止（正常 stop 不回调） */
        void onStopped(String reason);
    }

    private final Sink sink;

    private volatile boolean running;
    private volatile boolean muted;
    private Thread thread;
    private AudioRecord record;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;

    public AudioCapture(Sink sink) {
        this.sink = sink;
    }

    public boolean isRunning() {
        return running;
    }

    /** TTS 播放期间闭麦用（P2 伪 AEC）。静音时仍在录，只是不投递 */
    public void setMuted(boolean m) {
        muted = m;
    }

    /** 带重试启动。返回 false = 彻底失败（上层应提示用户"把表盘切到小智界面再说话"） */
    public boolean start() {
        if (running) return true;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (tryStart()) return true;
            Lg.w("录音启动失败 " + attempt + "/" + MAX_ATTEMPTS
                    + "，" + RETRY_DELAY_MS + "ms 后重试（P0 实测：刚授权时会瞬时 permission denied）");
            sleep(RETRY_DELAY_MS);
        }
        return false;
    }

    private boolean tryStart() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Lg.w("getMinBufferSize 返回 " + min + "（16k 不被支持？）→ 兜底用 " + RATE);
            min = RATE;
        }
        int bufBytes = Math.max(min * 2, FRAME * 2 * 4);   // 至少留 4 帧余量

        AudioRecord r;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes);
        } catch (Throwable t) {
            Lg.e("AudioRecord 构造失败", t);
            return false;
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            Lg.e("AudioRecord 未 INITIALIZED（state=" + r.getState() + "）→ 释放后重试");
            try { r.release(); } catch (Throwable ignored) { }
            return false;
        }
        record = r;

        // AEC / NS：都可用，但别指望 AEC 独自解决自我打断（只削 ~16dB）
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(r.getAudioSessionId());
                if (aec != null) {
                    aec.setEnabled(true);
                    Lg.i("AEC 已启用 (enabled=" + aec.getEnabled() + ")");
                }
            } else {
                Lg.w("AEC 不可用 → 依赖闭麦策略");
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(r.getAudioSessionId());
                if (ns != null) {
                    ns.setEnabled(true);
                    Lg.i("NS 已启用");
                }
            }
        } catch (Throwable t) {
            Lg.w("AEC/NS 设置异常（继续）：" + t);
        }

        try {
            r.startRecording();
        } catch (Throwable t) {
            Lg.e("startRecording 抛异常", t);
            release();
            return false;
        }
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            Lg.e("startRecording 后 state=" + r.getRecordingState() + "（≠3 = 没在录）");
            release();
            return false;
        }

        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "xz-capture");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        Lg.i("采集已启动：" + RATE + "Hz/mono/" + (FRAME * 1000 / RATE) + "ms，源=MIC，minBuf=" + min + " 用 " + bufBytes);
        return true;
    }

    private void readLoop() {
        final AudioRecord r = record;
        int consecutiveErrors = 0;
        long frames = 0;
        while (running) {
            short[] frame = new short[FRAME];
            int n;
            try {
                n = r.read(frame, 0, FRAME);
            } catch (Throwable t) {
                if (running) sink.onStopped("read 抛异常: " + t);
                break;
            }
            if (n <= 0) {
                if (!running) break;
                if (++consecutiveErrors >= 20) {
                    running = false;
                    sink.onStopped("read 连续 " + consecutiveErrors + " 次返回 " + n + "（麦克风被抢占？）");
                    break;
                }
                sleep(20);
                continue;
            }
            consecutiveErrors = 0;
            if (n < FRAME) {
                // 正常阻塞读应当正好 FRAME；不足则补零，保证帧长恒定（上层按 60ms 编码）
                for (int i = n; i < FRAME; i++) frame[i] = 0;
            }
            if (muted) continue;
            frames++;
            sink.onFrame(frame, FRAME);
        }
        Lg.i("采集线程退出，共投递 " + frames + " 帧");
    }

    public void stop() {
        running = false;
        AudioRecord r = record;
        if (r != null) {
            try {
                if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) r.stop();
            } catch (Throwable ignored) { }
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(800); } catch (InterruptedException ignored) { }
        }
        release();
    }

    private void release() {
        try {
            if (aec != null) { aec.setEnabled(false); aec.release(); }
        } catch (Throwable ignored) { }
        aec = null;
        try {
            if (ns != null) { ns.setEnabled(false); ns.release(); }
        } catch (Throwable ignored) { }
        ns = null;
        try {
            if (record != null) record.release();
        } catch (Throwable ignored) { }
        record = null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
