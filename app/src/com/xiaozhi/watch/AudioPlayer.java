package com.xiaozhi.watch;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * 下行播放。参数按 P0 真机实测定案（probe_audio/P0_RESULTS.md §六）：
 *
 *   24000Hz / mono / PCM16（服务端在 hello 里把下行采样率改成 24000）
 *   USAGE_MEDIA + CONTENT_TYPE_SPEECH  ← 实测媒体通路回环 +81.2dB（响亮）
 *
 *   ✗ 不要用 USAGE_VOICE_COMMUNICATION / MODE_IN_COMMUNICATION + setSpeakerphoneOn(true)：
 *     P0 特意测了这条"兜底路径"，结果 -29.3dB 放不出声还削顶出噪声，是条死路。
 *   ✗ 本类不碰系统音量：P0 那次把 STREAM_MUSIC 拉到最大是探针为了测回声，主程序不干这事。
 */
public final class AudioPlayer {

    public static final int RATE = 24000;

    private AudioTrack track;
    private volatile boolean running;

    public boolean isRunning() {
        return running;
    }

    public boolean start() {
        if (running) return true;
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Lg.w("AudioTrack.getMinBufferSize 返回 " + min + "（24k 不被支持？）→ 兜底");
            min = RATE / 2;
        }
        int bufBytes = Math.max(min * 2, RATE * 2 / 2);   // 至少 ~500ms 缓冲，抗抖动

        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            track = new AudioTrack(attrs, fmt, bufBytes, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        } catch (Throwable t) {
            Lg.e("AudioTrack 构造失败", t);
            return false;
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Lg.e("AudioTrack 未 INITIALIZED（state=" + track.getState() + "）");
            stop();
            return false;
        }
        track.play();
        running = true;
        Lg.i("播放已启动：" + RATE + "Hz/mono，USAGE_MEDIA，minBuf=" + min + " 用 " + bufBytes);
        return true;
    }

    /** 阻塞写完整帧（写不完会循环），失败只告警不抛 */
    public void write(short[] pcm, int samples) {
        AudioTrack t = track;
        if (t == null || !running) return;
        int off = 0;
        int guard = 0;
        while (off < samples && guard++ < 100) {
            int n;
            try {
                n = t.write(pcm, off, samples - off);
            } catch (Throwable e) {
                Lg.w("AudioTrack.write 抛异常: " + e);
                return;
            }
            if (n <= 0) {
                Lg.w("AudioTrack.write 返回 " + n + "，放弃本帧剩余 " + (samples - off) + " 样本");
                return;
            }
            off += n;
        }
    }

    /** 打断用：丢弃当前缓冲，立即静音 */
    public void flush() {
        AudioTrack t = track;
        if (t == null) return;
        try {
            t.pause();
            t.flush();
            if (running) t.play();
        } catch (Throwable ignored) { }
    }

    public void stop() {
        running = false;
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                if (t.getState() == AudioTrack.STATE_INITIALIZED) t.stop();
            } catch (Throwable ignored) { }
            try { t.release(); } catch (Throwable ignored) { }
        }
    }
}
