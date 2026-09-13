package com.xiaozhi.probe;

import android.os.SystemClock;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * 纯 Java Opus（Concentus）性能压测 —— 回答 R2：A53 跑纯 Java 编码够不够快。
 *
 * 参数完全抄 ESP32 donor 的实测值：16k / mono / 60ms / complexity=0 / VBR / signal=VOICE。
 * 上行 16k×60ms = 960 样本；下行是 24k×60ms = 1440 样本（服务端会把采样率改成 24000）。
 */
public final class OpusBench {

    private static final int UP_RATE = 16000;
    private static final int UP_FRAME = 960;    // 60ms
    private static final int DOWN_RATE = 24000;
    private static final int DOWN_FRAME = 1440; // 60ms

    private OpusBench() {
    }

    /** 主压测：60 秒音频的上行编码 + 解码 */
    public static void run() {
        Probe.sep("Opus 压测（Concentus 纯 Java）");
        Runtime rt = Runtime.getRuntime();
        long memBefore = rt.totalMemory() - rt.freeMemory();

        // ---------- 上行：16k 编码 + 16k 解码 ----------
        Probe.log("-- 上行路径 16k/mono/60ms/960样本 complexity=0 VBR --");
        try {
            OpusEncoder enc = new OpusEncoder(UP_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            enc.setComplexity(0);
            enc.setBitrate(24000);
            enc.setUseVBR(true);
            enc.setUseDTX(false);          // 压测必须每帧真编，不能因 DTX 跳过
            enc.setUseInbandFEC(false);
            enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
            Probe.log("encoder 建立 OK, bitrate=" + enc.getBitrate() + " complexity=" + enc.getComplexity()
                    + " lookahead=" + enc.getLookahead() + " vbr=" + enc.getUseVBR());

            OpusDecoder dec = new OpusDecoder(UP_RATE, 1);
            Probe.log("decoder 建立 OK, Fs=" + dec.getSampleRate());

            short[] pcm = voiceLike(UP_FRAME);
            byte[] out = new byte[1500];
            short[] decPcm = new short[UP_FRAME];

            // 预热（JIT / 表初始化）
            for (int i = 0; i < 30; i++) {
                int n = enc.encode(pcm, 0, UP_FRAME, out, 0, out.length);
                dec.decode(out, 0, n, decPcm, 0, UP_FRAME, false);
            }
            Probe.log("预热完成");

            int totalFrames = 1000;        // 1000 × 60ms = 60 秒音频
            long[] encNs = new long[totalFrames];
            long[] decNs = new long[totalFrames];
            int[] sizes = new int[totalFrames];
            long bytes = 0;
            int encFail = 0, decFail = 0;

            long wall0 = SystemClock.elapsedRealtime();
            for (int i = 0; i < totalFrames; i++) {
                long a = System.nanoTime();
                int n = enc.encode(pcm, 0, UP_FRAME, out, 0, out.length);
                long b = System.nanoTime();
                if (n <= 0) {
                    encFail++;
                } else {
                    sizes[i] = n;
                    bytes += n;
                    int got = dec.decode(out, 0, n, decPcm, 0, UP_FRAME, false);
                    if (got <= 0) decFail++;
                }
                long c = System.nanoTime();
                encNs[i] = b - a;
                decNs[i] = c - b;
                if ((i + 1) % 200 == 0) Probe.log("   进度 " + (i + 1) + "/" + totalFrames);
            }
            long wall = SystemClock.elapsedRealtime() - wall0;

            Stats es = new Stats(encNs);
            Stats ds = new Stats(decNs);
            double avgSize = 0;
            int cnt = 0;
            for (int s : sizes) if (s > 0) { avgSize += s; cnt++; }
            avgSize = cnt > 0 ? avgSize / cnt : 0;

            Probe.log(String.format(java.util.Locale.US,
                    "编码: avg=%.2fms p50=%.2fms p95=%.2fms max=%.2fms  (预算 60ms/帧)", es.avgMs(), es.p50Ms(), es.p95Ms(), es.maxMs()));
            Probe.log(String.format(java.util.Locale.US,
                    "解码: avg=%.2fms p50=%.2fms p95=%.2fms max=%.2fms", ds.avgMs(), ds.p50Ms(), ds.p95Ms(), ds.maxMs()));
            Probe.log(String.format(java.util.Locale.US,
                    "平均包大小=%.1f B/帧 → 约 %.1f kbps；总墙钟=%dms（音频时长 60000ms）", avgSize, avgSize * 8 * 1000 / 60 / 1000.0, wall));
            Probe.log("编码失败帧=" + encFail + " 解码失败帧=" + decFail + "（应为 0）");
            Probe.log("编解码合计占单核 ≈ " + String.format(java.util.Locale.US, "%.1f%%", (es.avgMs() + ds.avgMs()) / 60 * 100));
            Probe.log("实时余量 RTF(编码) = " + String.format(java.util.Locale.US, "%.1f×", 60.0 / Math.max(0.01, es.avgMs())));
            Probe.log(">>> 结论: " + verdict(es.avgMs()));
        } catch (OpusException e) {
            Probe.log("✗ Opus 异常: " + e.getMessage());
        } catch (Throwable t) {
            Probe.log("✗ 压测崩了: " + t);
        }

        // ---------- complexity 扫描 ----------
        Probe.sep("complexity 扫描（各 200 帧 @16k/60ms）");
        for (int c : new int[]{0, 3, 5, 10}) {
            try {
                OpusEncoder enc = new OpusEncoder(UP_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
                enc.setComplexity(c);
                enc.setBitrate(24000);
                enc.setUseVBR(true);
                enc.setUseDTX(false);
                enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
                short[] pcm = voiceLike(UP_FRAME);
                byte[] out = new byte[1500];
                for (int i = 0; i < 20; i++) enc.encode(pcm, 0, UP_FRAME, out, 0, out.length);
                long[] ns = new long[200];
                int bytes = 0;
                for (int i = 0; i < 200; i++) {
                    long a = System.nanoTime();
                    int n = enc.encode(pcm, 0, UP_FRAME, out, 0, out.length);
                    ns[i] = System.nanoTime() - a;
                    if (n > 0) bytes += n;
                }
                Stats s = new Stats(ns);
                Probe.log(String.format(java.util.Locale.US,
                        "complexity=%-2d avg=%.2fms p95=%.2fms 包=%.1fB 单核占用≈%.1f%%",
                        c, s.avgMs(), s.p95Ms(), bytes / 200.0, s.avgMs() / 60 * 100));
            } catch (Throwable t) {
                Probe.log("complexity=" + c + " 失败: " + t);
            }
        }

        // ---------- 下行：24k 解码（服务端就是 24k 推给我们） ----------
        Probe.sep("下行路径 24k/mono/60ms/1440样本");
        try {
            OpusEncoder enc = new OpusEncoder(DOWN_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            enc.setComplexity(0);
            enc.setBitrate(24000);
            enc.setUseVBR(true);
            short[] pcm = voiceLike(DOWN_FRAME);
            byte[] out = new byte[2000];
            int n = enc.encode(pcm, 0, DOWN_FRAME, out, 0, out.length);
            OpusDecoder dec = new OpusDecoder(DOWN_RATE, 1);
            short[] decPcm = new short[DOWN_FRAME];
            for (int i = 0; i < 20; i++) dec.decode(out, 0, n, decPcm, 0, DOWN_FRAME, false);
            long[] ns = new long[300];
            for (int i = 0; i < 300; i++) {
                long a = System.nanoTime();
                dec.decode(out, 0, n, decPcm, 0, DOWN_FRAME, false);
                ns[i] = System.nanoTime() - a;
            }
            Stats s = new Stats(ns);
            Probe.log(String.format(java.util.Locale.US,
                    "24k 解码: avg=%.2fms p95=%.2fms max=%.2fms 单核占用≈%.1f%%",
                    s.avgMs(), s.p95Ms(), s.maxMs(), s.avgMs() / 60 * 100));
        } catch (Throwable t) {
            Probe.log("下行路径失败: " + t);
        }

        long memAfter = rt.totalMemory() - rt.freeMemory();
        Probe.logf("压测内存增量 ≈ %.1f MB (java heap 侧)", (memAfter - memBefore) / 1048576.0);
        Probe.sep("Opus 压测结束");
    }

    private static String verdict(double avgEncMs) {
        if (avgEncMs <= 5) return "轻松：编码占用 <10% 单核，方案 A 直接可用（无需 JNI libopus）";
        if (avgEncMs <= 15) return "够用：编码占单核 " + String.format(java.util.Locale.US, "%.0f%%", avgEncMs / 60 * 100)
                + "，60ms 帧有 4 倍余量，方案 A 可用（注意别在主线程跑）";
        if (avgEncMs <= 30) return "吃紧：已占单核过半，建议降帧率/加大帧长，或准备切 JNI libopus";
        return "危险：单帧编码耗时超过帧长的 50%，必须切 JNI libopus 或自建服务器走 PCM";
    }

    /** 合成"像人声"的信号：120Hz 基频 + 谐波 + 轻噪声 + 音节包络（可复现，不用随机种子） */
    private static short[] voiceLike(int frame) {
        short[] d = new short[frame];
        int seed = 12345;
        for (int i = 0; i < frame; i++) {
            double t = i / 16000.0;
            double base = 120 + 40 * Math.sin(2 * Math.PI * 0.7 * t);
            double v = 0.6 * Math.sin(2 * Math.PI * base * t)
                    + 0.25 * Math.sin(2 * Math.PI * base * 2 * t)
                    + 0.12 * Math.sin(2 * Math.PI * base * 3 * t);
            // 音节包络：3Hz 断续，模拟说话
            double env = 0.35 + 0.65 * Math.max(0, Math.sin(2 * Math.PI * 3.0 * t));
            seed = seed * 1103515245 + 12345;
            double noise = ((seed >> 16) & 0x7FFF) / 32768.0 - 0.5;
            double s = (v * env + noise * 0.03) * 9000;
            if (s > 32000) s = 32000;
            if (s < -32000) s = -32000;
            d[i] = (short) s;
        }
        return d;
    }

    private static final class Stats {
        final long[] sorted;
        final double avgNs;

        Stats(long[] ns) {
            long sum = 0;
            for (long v : ns) sum += v;
            avgNs = (double) sum / ns.length;
            sorted = ns.clone();
            java.util.Arrays.sort(sorted);
        }

        double avgMs() { return avgNs / 1e6; }
        double p50Ms() { return sorted[sorted.length / 2] / 1e6; }
        double p95Ms() { return sorted[(int) (sorted.length * 0.95)] / 1e6; }
        double maxMs() { return sorted[sorted.length - 1] / 1e6; }
    }
}
