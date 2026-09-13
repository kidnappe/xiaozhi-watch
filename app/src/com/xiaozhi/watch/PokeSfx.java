package com.xiaozhi.watch;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * 戳戳互动合成音效（POKE_INTERACTION §4.3）。
 *
 * 硬约束（交接文档实测）：这块表只有 USAGE_MEDIA 通路放得出声（通知/铃声通路是哑的），
 * 不用 ToneGenerator；且真机实测 **MODE_STATIC 初始化失败**（state=UNINITIALIZED=2），
 * 而 MODE_STREAM 是 AudioPlayer 验证过的活通路 —— 照搬 Stream 构造，每次短音 pause+flush+write+play：
 *   level 1 = 单戳"啵" 340Hz · 60ms      level 2 = 三连击"啵啵" 460Hz
 *   level 3 = 五连击高音双啵 540Hz        ABORT = 打断沉音"啜" 190Hz · 140ms
 * 用户拍板（2026-09-13）：默认开；录音中（LISTENING）绝不放（无 AEC，喇叭会灌回麦克风）——
 * 调用方负责判断状态，这里只保证"失败静默"。
 */
public final class PokeSfx {

    public static final int ABORT = 9;

    private static final int RATE = 16000;
    private static final boolean ENABLED = true;    // 默认开（用户拍板）；关掉改这里重装即可
    private static final double TAU = Math.PI * 2;

    private AudioTrack track;

    /** 放一声（可在 UI 线程调：write 进已初始化的 stream 缓冲不阻塞）。level 见类注释。 */
    public void play(int level) {
        if (!ENABLED) return;
        try {
            short[] buf = tone(level);
            if (buf == null) return;
            if (track == null && !init()) return;
            track.pause();
            track.flush();                      // 丢弃上一声尾巴，连击不串音不积压
            track.write(buf, 0, buf.length);
            track.play();
        } catch (Throwable t) {
            Lg.w("戳音效失败（忽略）：" + t);
        }
    }

    private boolean init() {
        int min;
        try {
            min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
        } catch (Throwable t) {
            Lg.w("戳音效 getMinBufferSize 异常：" + t);
            return false;
        }
        if (min <= 0) min = RATE / 5 * 2;
        int bufBytes = Math.max(min * 2, 6400);         // ≥200ms，余量抗调度抖动
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)   // 与 AudioPlayer 同构：唯一验证过的活配置
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            track = new AudioTrack(attrs, fmt, bufBytes, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        } catch (Throwable t) {
            Lg.w("戳音效初始化失败： " + t);
            return false;
        }
        if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
            Lg.w("戳音效未 INITIALIZED（state=" + (track == null ? "null" : track.getState()) + "）→ 音效静默降级");
            if (track != null) {
                try { track.release(); } catch (Throwable ignored) { }
            }
            track = null;
            return false;
        }
        try { track.setVolume(0.30f); } catch (Throwable ignored) { }
        Lg.i("戳音效就绪（媒体通路 MODE_STREAM，默认开）");
        return true;
    }

    public void release() {
        if (track != null) {
            try { track.release(); } catch (Throwable ignored) { }
            track = null;
        }
    }

    private short[] tone(int level) {
        float freq;
        int ms, blips, gap;
        if (level >= 3)      { freq = 540; ms = 60;  blips = 2; gap = 40; }
        else if (level == 2) { freq = 460; ms = 55;  blips = 2; gap = 45; }
        else if (level == ABORT) { freq = 190; ms = 140; blips = 1; gap = 0; }
        else                 { freq = 340; ms = 60;  blips = 1; gap = 0; }
        int total = (int) (RATE / 1000.0 * (ms * blips + gap * (blips - 1)));
        short[] out = new short[total];
        double gain = level == ABORT ? 0.7 : 0.55;
        for (int b = 0; b < blips; b++) {
            int start = (int) (RATE / 1000.0 * b * (ms + gap));
            int len = (int) (RATE / 1000.0 * ms);
            for (int i = 0; i < len && start + i < total; i++) {
                double tt = i / (double) RATE;
                double env = Math.exp(-tt * 18);                  // 指数衰减包络，"啵"的肉质
                int v = (int) (Math.sin(TAU * freq * tt) * env * gain * 32767);
                out[start + i] = (short) Math.max(-32768, Math.min(32767, v));
            }
        }
        return out;
    }
}
