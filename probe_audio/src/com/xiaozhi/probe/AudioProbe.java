package com.xiaozhi.probe;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** P0 音频能力探针：能不能录音 / 能不能出声 / 系统给了什么能力 */
public final class AudioProbe {

    /** 上一次成功录到的 PCM（用于"回放录音"闭环测试） */
    public static volatile short[] lastPcm;
    public static volatile int lastRate;

    /** 每个 rate 的最小缓冲，减少重复调用 */
    private static final int BUF_MS = 200;

    private AudioProbe() {
    }

    // ==================================================================
    // 1. 系统能力（不发音、不录音，纯查询）
    // ==================================================================
    public static void systemCaps(Context ctx) {
        Probe.sep("系统能力");
        Probe.log("SDK_INT=" + Build.VERSION.SDK_INT + " RELEASE=" + Build.VERSION.RELEASE);
        Probe.log("MODEL=" + Build.MODEL + " | DEVICE=" + Build.DEVICE + " | BRAND=" + Build.BRAND);
        Probe.log("CPU_ABI=" + Build.CPU_ABI + " | ABIS=" + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        Probe.log("cpu cores=" + Runtime.getRuntime().availableProcessors());

        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            Probe.log("AudioManager = null  ← 严重异常");
            return;
        }
        Probe.log("PROPERTY_OUTPUT_SAMPLE_RATE = " + am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
        Probe.log("PROPERTY_OUTPUT_FRAMES_PER_BUFFER = " + am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
        Probe.log("nativeOutputSampleRate(STREAM_MUSIC) = " + AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC));
        Probe.log("mode=" + am.getMode() + " (0=NORMAL,1=RINGTONE,2=IN_CALL,3=IN_COMM,4=IN_COMM2)");
        Probe.log("isMusicActive=" + am.isMusicActive() + " isSpeakerphoneOn=" + am.isSpeakerphoneOn()
                + " isBluetoothA2dpOn=" + am.isBluetoothA2dpOn() + " isWiredHeadsetOn=" + am.isWiredHeadsetOn()
                + " isBluetoothScoOn=" + am.isBluetoothScoOn());
        Probe.log("vol MUSIC=" + am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + "  VOICE_CALL=" + am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) + "/" + am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL));
        try {
            Probe.log("MODE_IN_COMMUNICATION 可用性：setMode 测试（会短暂切换，随后恢复）");
            int old = am.getMode();
            am.setMode(AudioManager.MODE_NORMAL);
            am.setMode(old);
            Probe.log("  setMode 正常返回，old=" + old);
        } catch (Throwable t) {
            Probe.log("  setMode 抛异常: " + t);
        }

        // 音频设备列表：判断有没有内置喇叭、是否有蓝牙占用
        listDevices(am);

        // 设备支持的采样率（AudioDeviceInfo.getSampleRates()，API 23+）
        Probe.log("-- AudioRecord.getMinBufferSize(mono, PCM16) --");
        for (int rate : new int[]{8000, 16000, 22050, 44100, 48000}) {
            int m = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            Probe.log("   in  " + rate + "Hz -> " + m + (m < 0 ? "  (不支持)" : ""));
        }
        Probe.log("-- AudioTrack.getMinBufferSize(mono, PCM16) --");
        for (int rate : new int[]{16000, 24000, 44100, 48000}) {
            int m = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            Probe.log("   out " + rate + "Hz -> " + m + (m < 0 ? "  (不支持)" : ""));
        }

        Probe.log("AEC(AcousticEchoCanceler).isAvailable = " + AcousticEchoCanceler.isAvailable());
        Probe.log("NS(NoiseSuppressor).isAvailable     = " + NoiseSuppressor.isAvailable());
        Probe.log("AGC(AutomaticGainControl).isAvailable = " + AutomaticGainControl.isAvailable());

        mediaCodecs();

        // 内存
        Runtime rt = Runtime.getRuntime();
        Probe.logf("heap max=%.1fMB total=%.1fMB free=%.1fMB",
                rt.maxMemory() / 1048576.0, rt.totalMemory() / 1048576.0, rt.freeMemory() / 1048576.0);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager amgr = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (amgr != null) {
            amgr.getMemoryInfo(mi);
            Probe.logf("system mem: avail=%.0fMB total=%.0fMB threshold=%.0fMB lowMemory=%s",
                    mi.availMem / 1048576.0, mi.totalMem / 1048576.0, mi.threshold / 1048576.0, mi.lowMemory);
        }

        // 电量 / 温度
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int volt = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            Probe.log("battery level=" + lvl + "/" + sc + " temp=" + (temp / 10.0) + "C voltage=" + volt + "mV");
        }

        // CPU 频率（读得到就读）
        readCpuInfo();
    }

    private static void listDevices(AudioManager am) {
        try {
            AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            Probe.log("-- 输出设备 " + outs.length + " 个 --");
            for (AudioDeviceInfo d : outs) {
                Probe.log("  OUT " + devTypeName(d.getType()) + "(" + d.getType() + ") id=" + d.getId()
                        + " name=" + d.getProductName() + " rates=" + java.util.Arrays.toString(d.getSampleRates()));
            }
            AudioDeviceInfo[] ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            Probe.log("-- 输入设备 " + ins.length + " 个 --");
            for (AudioDeviceInfo d : ins) {
                Probe.log("  IN  " + devTypeName(d.getType()) + "(" + d.getType() + ") id=" + d.getId()
                        + " name=" + d.getProductName() + " rates=" + java.util.Arrays.toString(d.getSampleRates()));
            }
        } catch (Throwable t) {
            Probe.log("getDevices 失败: " + t);
        }
    }

    private static String devTypeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "内置喇叭";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "内置麦";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "听筒";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "蓝牙A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "蓝牙SCO";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "有线耳机";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有线耳麦";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "电话";
            case AudioDeviceInfo.TYPE_FM_TUNER: return "FM";
            default: return "其他";
        }
    }

    /** 系统自带 Opus 编解码器？→ 决定 R2 的降级路径 */
    private static void mediaCodecs() {
        Probe.sep("MediaCodec 里的 audio/opus");
        int enc = 0, dec = 0;
        List<String> audioTypes = new ArrayList<String>();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                for (String t : ci.getSupportedTypes()) {
                    String lt = t.toLowerCase();
                    if (lt.startsWith("audio/") && !audioTypes.contains(lt)) audioTypes.add(lt);
                    if (lt.contains("opus")) {
                        if (ci.isEncoder()) enc++; else dec++;
                        Probe.log((ci.isEncoder() ? "  ENCODER " : "  DECODER ") + ci.getName() + " type=" + t);
                    }
                }
            }
        } catch (Throwable t) {
            Probe.log("MediaCodecList 失败: " + t);
        }
        Probe.log("audio/opus 编码器数=" + enc + " 解码器数=" + dec
                + "   → " + (enc > 0 ? "系统有编码器，方案 D 也可用" : "系统无 Opus 编码器，必须自带（如 Concentus）"));
        Probe.log("系统所有 audio/* 类型: " + audioTypes);
    }

    private static void readCpuInfo() {
        try {
            RandomAccessFile raf = new RandomAccessFile("/proc/cpuinfo", "r");
            String line;
            int shown = 0;
            StringBuilder hw = new StringBuilder();
            while ((line = raf.readLine()) != null && shown < 12) {
                if (line.startsWith("Hardware") || line.startsWith("model name")
                        || line.startsWith("Processor") || line.startsWith("CPU part")
                        || line.startsWith("Features")) {
                    hw.append(line).append('\n');
                    shown++;
                }
            }
            raf.close();
            Probe.log("/proc/cpuinfo:\n" + hw.toString().trim());
        } catch (Throwable t) {
            Probe.log("读 /proc/cpuinfo 失败: " + t);
        }
        try {
            File f = new File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
            if (f.canRead()) {
                RandomAccessFile r = new RandomAccessFile(f, "r");
                String s = r.readLine();
                r.close();
                Probe.log("cpu0 cur_freq = " + (Long.parseLong(s.trim()) / 1000) + " MHz");
            } else {
                Probe.log("cpu0 cpufreq 不可读（正常，很多表会锁频）");
            }
        } catch (Throwable t) {
            Probe.log("读 cpufreq 失败: " + t);
        }
    }

    // ==================================================================
    // 2. 录音（R1：第三方 App 到底能不能录到声音）
    // ==================================================================
    public static final class RecResult {
        public String path;
        public int rate;
        public int source;
        public int samples;
        public int peak;
        public double rms;
        public double nonzeroPct;
        public int clipped;
        public long ms;
        public boolean silent;
    }

    /** 按最优顺序试一下几个 source 各录 1 秒，报告哪个能出声音 */
    public static List<RecResult> recordSourceSweep(Context ctx, int rate, int seconds) {
        Probe.sep("录音 source 横扫（每档 " + seconds + "s @ " + rate + "Hz）");
        // 2026-09-12 实测后调整顺序：这块表上 MIC(1) 信号最强（peak≈450），
        // VOICE_COMMUNICATION(7) 不加 AEC 时几乎是聋的（peak≈10），VOICE_RECOGNITION(6) 也差（peak≈91）。
        // 所以把 MIC 放第一个——第一个能出声的 source 就是 P1 该用的。
        int[] sources = new int[]{
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.DEFAULT,
        };
        String[] names = new String[]{"MIC", "VOICE_RECOGNITION", "VOICE_COMMUNICATION", "DEFAULT"};
        List<RecResult> out = new ArrayList<RecResult>();
        for (int i = 0; i < sources.length; i++) {
            Probe.log("-- source=" + names[i] + "(" + sources[i] + ") --");
            RecResult r = record(ctx, rate, seconds, sources[i]);
            if (r != null) {
                out.add(r);
                Probe.log("   结果: " + summarize(r));
            }
        }
        return out;
    }

    /**
     * 录一段并写成 wav。
     * @return null 表示这个 (rate, source) 组合起不来
     */
    public static RecResult record(Context ctx, int rate, int seconds, int source) {
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        Probe.log("   getMinBufferSize=" + min);
        if (min <= 0) {
            Probe.log("   ✗ 该采样率不支持");
            return null;
        }
        int bufBytes = Math.max(min * 2, rate * 2 * BUF_MS / 1000);
        AudioRecord ar = null;
        try {
            ar = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes);
        } catch (Throwable t) {
            Probe.log("   ✗ 构造 AudioRecord 抛异常: " + t);
            return null;
        }
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            Probe.log("   ✗ getState=" + ar.getState() + "（未初始化）→ release");
            ar.release();
            return null;
        }
        Probe.log("   ✓ 初始化成功 state=INITIALIZED 实际采样率=" + ar.getSampleRate()
                + " 通道=" + ar.getChannelCount() + " isAEC可用=" + AcousticEchoCanceler.isAvailable());

        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(ar.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
                Probe.log("   AEC session=" + ar.getAudioSessionId() + " enabled=" + (aec != null && aec.getEnabled()));
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(ar.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
                Probe.log("   NS enabled=" + (ns != null && ns.getEnabled()));
            }
        } catch (Throwable t) {
            Probe.log("   AEC/NS 附加失败（忽略）: " + t);
        }

        int total = rate * seconds;
        short[] data = new short[total];
        int got = 0;
        long t0 = SystemClock.elapsedRealtime();
        try {
            ar.startRecording();
            if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Probe.log("   ✗ startRecording 后 state=" + ar.getRecordingState() + "（被系统拒绝？）");
                return null;
            }
            while (got < total) {
                int n = ar.read(data, got, Math.min(1600, total - got));
                if (n <= 0) {
                    Probe.log("   ✗ read 返回 " + n + "（读取失败，可能权限被拒）");
                    break;
                }
                got += n;
            }
        } catch (Throwable t) {
            Probe.log("   ✗ 录音过程异常: " + t);
        } finally {
            try {
                ar.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (aec != null) aec.release();
                if (ns != null) ns.release();
            } catch (Throwable ignored) {
            }
            ar.release();
        }
        long ms = SystemClock.elapsedRealtime() - t0;

        RecResult r = new RecResult();
        r.rate = rate;
        r.source = source;
        r.samples = got;
        r.ms = ms;
        int peak = 0, clipped = 0, nonzero = 0;
        double sq = 0;
        for (int i = 0; i < got; i++) {
            int v = data[i];
            int a = v < 0 ? -v : v;
            if (a > peak) peak = a;
            if (a >= 32000) clipped++;
            if (a > 100) nonzero++;
            sq += (double) v * v;
        }
        r.peak = peak;
        r.rms = got > 0 ? Math.sqrt(sq / got) : 0;
        r.nonzeroPct = got > 0 ? nonzero * 100.0 / got : 0;
        r.clipped = clipped;
        r.silent = peak <= 100;
        lastPcm = java.util.Arrays.copyOf(data, got);
        lastRate = rate;

        File dir = ctx.getExternalFilesDir(null);
        File wav = new File(dir, "rec_" + rate + "_src" + source + "_" + seconds + "s.wav");
        try {
            writeWav(wav, data, got, rate, 1);
            r.path = wav.getAbsolutePath();
        } catch (IOException e) {
            Probe.log("   ✗ 写 wav 失败: " + e);
        }
        if (r.silent) {
            Probe.log("   ⚠ peak=" + peak + " → 全静音！要么麦克风被挡/被系统静音，要么这个 source 拿不到声音");
        }
        return r;
    }

    public static String summarize(RecResult r) {
        return String.format(java.util.Locale.US,
                "%dHz src=%d 取到%d样本 用时%dms peak=%d rms=%.1f 有效声%%=%.1f 削顶=%d %s",
                r.rate, r.source, r.samples, r.ms, r.peak, r.rms, r.nonzeroPct, r.clipped,
                r.silent ? "【静音】" : "【有声音】");
    }

    /** 标准 44 字节 WAV 头 + PCM16 数据 */
    public static void writeWav(File f, short[] pcm, int len, int rate, int channels) throws IOException {
        FileOutputStream os = new FileOutputStream(f);
        int dataLen = len * 2 * channels;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putIntLE(h, 4, 36 + dataLen);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putIntLE(h, 16, 16);
        putShortLE(h, 20, 1);                       // PCM
        putShortLE(h, 22, channels);
        putIntLE(h, 24, rate);
        putIntLE(h, 28, rate * channels * 2);       // byte rate
        putShortLE(h, 32, channels * 2);            // block align
        putShortLE(h, 34, 16);                      // bits
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        putIntLE(h, 40, dataLen);
        os.write(h);
        byte[] buf = new byte[4096];
        int i = 0, bi = 0;
        while (i < len) {
            short v = pcm[i++];
            buf[bi++] = (byte) (v & 0xFF);
            buf[bi++] = (byte) ((v >> 8) & 0xFF);
            if (bi == buf.length) {
                os.write(buf);
                bi = 0;
            }
        }
        if (bi > 0) os.write(buf, 0, bi);
        os.close();
    }

    private static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8); b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }

    private static void putShortLE(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
    }

    // ==================================================================
    // 3. 播放（R3：表内喇叭到底出不出声）
    // ==================================================================
    /**
     * @param mode 0 = USAGE_MEDIA（媒体通路，走 STREAM_MUSIC）
     *             1 = USAGE_VOICE_COMMUNICATION + MODE_IN_COMMUNICATION + setSpeakerphoneOn(true)（通话通路）
     *             2 = 老旧 STREAM_VOICE_CALL 构造方式
     */
    public static void play(Context ctx, int rate, int seconds, int mode, String tag) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        Probe.sep("播放测试 " + tag + " rate=" + rate + " mode=" + mode);
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        Probe.log("getMinBufferSize=" + min);
        if (min <= 0) {
            Probe.log("✗ 该采样率系统不支持");
            return;
        }
        short[] pcm;
        int pcmRate = rate;
        if (lastPcm != null && lastPcm.length > rate / 2) {
            pcm = lastPcm;
            pcmRate = lastRate;
            Probe.log("用「刚录到的真人声」回放（" + lastPcm.length + " 样本 @" + lastRate + "Hz），时长 " + (lastPcm.length / lastRate) + "s");
        } else {
            pcm = tone(rate, seconds, 440);
            Probe.log("没有录音可用，改播 440Hz 正弦 " + seconds + "s");
        }

        int oldMode = AudioManager.MODE_NORMAL;
        boolean oldSpk = false;
        AudioTrack at = null;
        try {
            if (am != null) {
                oldMode = am.getMode();
                oldSpk = am.isSpeakerphoneOn();
                if (mode == 1) {
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    am.setSpeakerphoneOn(true);
                    Probe.log("已设 MODE_IN_COMMUNICATION + setSpeakerphoneOn(true)");
                } else if (mode == 0) {
                    am.setMode(AudioManager.MODE_NORMAL);
                    am.setSpeakerphoneOn(false);
                    Probe.log("已设 MODE_NORMAL + setSpeakerphoneOn(false)（媒体通路）");
                }
            }

            int minBytes = Math.max(min * 2, rate * 2 * BUF_MS / 1000);
            if (Build.VERSION.SDK_INT >= 21) {
                AudioAttributes.Builder attrs = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
                attrs.setUsage(mode == 1 ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA);
                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcmRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                at = new AudioTrack(attrs.build(), fmt, minBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            } else {
                at = new AudioTrack(AudioManager.STREAM_MUSIC, pcmRate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, minBytes, AudioTrack.MODE_STREAM);
            }
            Probe.log("AudioTrack state=" + at.getState() + " (1=INITIALIZED) sampleRate=" + at.getSampleRate()
                    + " bufferSizeInFrames=" + at.getBufferSizeInFrames() + " sessionId=" + at.getAudioSessionId());
            if (at.getState() != AudioTrack.STATE_INITIALIZED) {
                Probe.log("✗ AudioTrack 未初始化成功，放弃");
                return;
            }
            at.setVolume(1.0f);
            at.play();
            long t0 = SystemClock.elapsedRealtime();
            int written = 0;
            int step = 960;
            while (written < pcm.length) {
                int n = Math.min(step, pcm.length - written);
                int w = at.write(pcm, written, n);
                if (w < 0) {
                    Probe.log("✗ write 返回 " + w + "（写入失败）");
                    break;
                }
                written += w;
            }
            // 等播放头把数据吐完
            int lastHead = -1, stall = 0;
            while (SystemClock.elapsedRealtime() - t0 < (long) (pcm.length * 1000L / pcmRate) + 3000) {
                int head = at.getPlaybackHeadPosition();
                if (head == lastHead) {
                    if (++stall > 6) break;
                } else {
                    stall = 0;
                }
                lastHead = head;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            long playedMs = SystemClock.elapsedRealtime() - t0;
            int head = at.getPlaybackHeadPosition();
            Probe.log("write=" + written + "/" + pcm.length + " 样本, 耗时 " + playedMs + "ms"
                    + ", playbackHead=" + head + " 帧 (≈" + (head * 1000L / pcmRate) + "ms 已播出)");
            Probe.log("预期时长 " + (pcm.length * 1000L / pcmRate) + "ms → "
                    + (head > pcm.length / 2 ? "播放头推进正常，硬件通路应该是通的（请用耳朵确认）" : "⚠ 播放头几乎没动，可能被静音/无输出设备"));
            try {
                // 注意：AudioTrack.getLatency() 是 @hide，android.jar 里没有，编译期就用不了
                Probe.log("underrunCount=" + at.getUnderrunCount() + " sessionId=" + at.getAudioSessionId());
            } catch (Throwable ignored) {
            }
            if (mode == 1 && am != null) {
                Probe.log("播音时 isSpeakerphoneOn=" + am.isSpeakerphoneOn() + " mode=" + am.getMode());
            }
        } catch (Throwable t) {
            Probe.log("✗ 播放异常: " + t);
        } finally {
            if (at != null) {
                try {
                    at.stop();
                } catch (Throwable ignored) {
                }
                at.release();
            }
            if (am != null && mode != 0) {
                try {
                    am.setMode(oldMode);
                    am.setSpeakerphoneOn(oldSpk);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================================================================
    // 3.5 回环测试：不靠耳朵，客观判断"喇叭到底有没有出声"
    //     一边用 AudioTrack 放 1200Hz，一边用麦克风录，再看录音里 1200Hz 的能量
    //     比邻近频点（1500Hz）高多少。喇叭真在响 → 高出十几 dB；没响 → 两者一样低。
    // ==================================================================
    public static void loopback(Context ctx, int mode, boolean aecOn) {
        Probe.sep("回环测试 " + (aecOn ? "[AEC/NS 开]" : "[AEC/NS 关]")
                + "  播放通路=" + (mode == 1 ? "通话(IN_COMMUNICATION)" : "媒体(MEDIA)")
                + " 录音源=MIC");
        Probe.log("（会响 3 秒，音量是最大值的一半，不会像上次那样突然最大声）");
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            // 2026-09-12 修正：一开始置最大(16)，表在手腕上震得人一跳。改成 50%，
            // 回环检测靠的是信噪比而不是绝对音量，一半足够；要更大声请手动调表上音量。
            int target = Math.max(1, mx / 2);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            Probe.log("STREAM_MUSIC 音量 = " + target + "/" + mx + "（故意不用最大，避免吓人）");
        }
        final int rate = 16000;
        final short[] tonePcm = tone(rate, 3, 1200);
        final int m = mode;

        Thread player = new Thread(new Runnable() {
            public void run() {
                playRawCore(ctx, rate, tonePcm, m);
            }
        }, "loopback-play");
        player.start();
        try {
            Thread.sleep(250);       // 让喇叭先响起来
        } catch (InterruptedException ignored) {
        }

        // ---- 录 3 秒 ----
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Probe.log("✗ 16k 录音不支持，回环测试跳过");
            return;
        }
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, rate * 2 / 5));
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            Probe.log("✗ AudioRecord 未初始化，回环测试跳过");
            ar.release();
            return;
        }
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            if (aecOn && AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(ar.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
                Probe.log("AEC enabled=" + (aec != null && aec.getEnabled()));
            }
            if (aecOn && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(ar.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
                Probe.log("NS enabled=" + (ns != null && ns.getEnabled()));
            }
        } catch (Throwable t) {
            Probe.log("AEC/NS 设置失败: " + t);
        }

        int total = rate * 3;
        short[] data = new short[total];
        int got = 0;
        try {
            ar.startRecording();
            while (got < total) {
                int n = ar.read(data, got, Math.min(1600, total - got));
                if (n <= 0) break;
                got += n;
            }
        } catch (Throwable t) {
            Probe.log("✗ 回环录音异常: " + t);
        } finally {
            try {
                ar.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (aec != null) aec.release();
                if (ns != null) ns.release();
            } catch (Throwable ignored) {
            }
            ar.release();
        }
        try {
            player.join(5000);
        } catch (InterruptedException ignored) {
        }

        if (got < rate / 2) {
            Probe.log("✗ 只录到 " + got + " 样本，回环测试无效");
            return;
        }
        // 取中段 1.5 秒分析（避开起止的淡入淡出）
        int from = got / 4;
        int len = got / 2;
        double magTone = goertzel(data, from, len, rate, 1200);
        double magRef = goertzel(data, from, len, rate, 1500);
        double magLow = goertzel(data, from, len, rate, 400);
        int peak = 0;
        double sq = 0;
        for (int i = 0; i < got; i++) {
            int a = Math.abs(data[i]);
            if (a > peak) peak = a;
            sq += (double) data[i] * data[i];
        }
        double rms = Math.sqrt(sq / got);
        double dTone = 20 * Math.log10(Math.max(1e-9, magTone));
        double dRef = 20 * Math.log10(Math.max(1e-9, magRef));
        double dLow = 20 * Math.log10(Math.max(1e-9, magLow));
        Probe.log(String.format(java.util.Locale.US,
                "录音 peak=%d rms=%.1f | 1200Hz(音调)=%.1fdB 1500Hz(参照)=%.1fdB 400Hz(参照)=%.1fdB",
                peak, rms, dTone, dRef, dLow));
        Probe.log(String.format(java.util.Locale.US, "1200Hz 相对 1500Hz 高出 %.1f dB", dTone - dRef));
        Probe.log(">>> 回环结论: " + (dTone - dRef >= 10
                ? "喇叭确实在出声（回环录音里能听到 1200Hz 音调）"
                : (dTone - dRef >= 4
                ? "疑似出声但很弱（音量小 / 被路由到蓝牙 / 扬声器功率低），建议耳朵复听"
                : "录不到播放的音调 → 该通路下喇叭很可能没响（或被强制路由到蓝牙）")));
        File dir = ctx.getExternalFilesDir(null);
        try {
            File wav = new File(dir, "loopback_" + (mode == 1 ? "voice" : "media")
                    + (aecOn ? "_aecOn" : "_aecOff") + ".wav");
            writeWav(wav, data, got, rate, 1);
            Probe.log("回环录音已存: " + wav.getName() + "（用电脑听，能听到“嘟嘟”声即喇叭在响）");
        } catch (IOException e) {
            Probe.log("写回环 wav 失败: " + e);
        }
    }

    /** 单纯把一段 PCM 放出来（回环测试专用，不做 lastPcm 判断） */
    private static void playRawCore(Context ctx, int rate, short[] pcm, int mode) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        AudioTrack at = null;
        try {
            if (am != null && mode == 1) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(true);
            }
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                Probe.log("✗ 播放侧不支持 " + rate + "Hz");
                return;
            }
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(mode == 1 ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            at = new AudioTrack(attrs, fmt, Math.max(min * 2, rate * 2 / 5), AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (at.getState() != AudioTrack.STATE_INITIALIZED) {
                Probe.log("✗ 回环播放 AudioTrack 未初始化");
                return;
            }
            at.setVolume(1.0f);
            at.play();
            int written = 0;
            while (written < pcm.length) {
                int w = at.write(pcm, written, Math.min(960, pcm.length - written));
                if (w < 0) break;
                written += w;
            }
            long t0 = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - t0 < 1200) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            Probe.log("回环播放完成: 写入 " + written + " 样本, playbackHead=" + at.getPlaybackHeadPosition()
                    + " underrun=" + at.getUnderrunCount());
        } catch (Throwable t) {
            Probe.log("✗ 回环播放异常: " + t);
        } finally {
            if (at != null) {
                try {
                    at.stop();
                } catch (Throwable ignored) {
                }
                at.release();
            }
            if (am != null && mode != 0) {
                try {
                    am.setMode(AudioManager.MODE_NORMAL);
                    am.setSpeakerphoneOn(false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Goertzel：算某个频点的能量幅值（只要几个频点，比 FFT 省事） */
    private static double goertzel(short[] x, int from, int len, int rate, double freq) {
        double w = 2 * Math.PI * freq / rate;
        double c = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < len; i++) {
            double s0 = x[from + i] + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - c * s1 * s2;
        return Math.sqrt(Math.max(0, power)) * 2 / len;
    }

    // ==================================================================
    // 3.6 录音最小 A/B 隔离矩阵：R1 判死之前必须做的一次实验
    //     权限(运行时 + appops)都 allow 却抛 IllegalStateException: permission denied，
    //     必须区分：① 硬性禁止（每次必失败）② 瞬时占用（重试会好）③ 特定 source/rate 才有问题
    //     ④ 只有 AudioRecord 被拦（MediaRecorder 能录）→ 决定降级方案
    // ==================================================================
    public static void recordMatrix(Context ctx) {
        Probe.sep("录音最小 A/B 隔离矩阵（每次间隔 1.5s）");
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        Probe.log("当前 AudioManager.mode=" + (am == null ? "?" : am.getMode())
                + " AEC可用=" + AcousticEchoCanceler.isAvailable());
        Object[][] combos = {
                {16000, MediaRecorder.AudioSource.VOICE_COMMUNICATION, Boolean.TRUE},
                {16000, MediaRecorder.AudioSource.VOICE_COMMUNICATION, Boolean.FALSE},
                {16000, MediaRecorder.AudioSource.VOICE_RECOGNITION, Boolean.FALSE},
                {16000, MediaRecorder.AudioSource.MIC, Boolean.FALSE},
                {48000, MediaRecorder.AudioSource.MIC, Boolean.FALSE},
                {16000, MediaRecorder.AudioSource.MIC, Boolean.TRUE},
        };
        int ok = 0, silentOk = 0;
        for (int i = 0; i < combos.length; i++) {
            int rate = (Integer) combos[i][0];
            int src = (Integer) combos[i][1];
            boolean aec = (Boolean) combos[i][2];
            Probe.logf("-- 尝试 %d/%d: %dHz source=%d AEC=%s --", i + 1, combos.length, rate, src, aec ? "开" : "关");
            RecResult r = tryRecord(ctx, rate, 1, src, aec, "matrix" + (i + 1));
            if (r != null && r.samples > rate / 2) {
                if (r.silent) {
                    silentOk++;
                    Probe.log("   → 录到了但全是静音（peak=" + r.peak + "）");
                } else {
                    ok++;
                    Probe.log("   → ✓ 录到有效声音 peak=" + r.peak);
                }
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
        }
        Probe.logf(">>> 矩阵结论：6 次里 有声音 %d 次 / 静音 %d 次 / 直接失败 %d 次",
                ok, silentOk, combos.length - ok - silentOk);
        if (ok == 0 && silentOk > 0) {
            Probe.log(">>> 判定：权限给了、AudioRecord 能起，但**拿到的永远是静音数据** → ");
            Probe.log("    等价于麦克风被系统屏蔽（ColorOS 隐私策略），R1 成立，语音上行做不了。");
        } else if (ok == 0) {
            Probe.log(">>> 判定：全部直接失败 → 硬性禁止，R1 成立。");
        } else {
            Probe.log(">>> 判定：能录到有效声音 → R1 通过（注意失败次数，可能是瞬时占用）");
        }

        // MediaRecorder 走的是完全不同的录制路径，用来区分"只有 AudioRecord 被拦"还是"麦克风整体被禁"
        Probe.sep("MediaRecorder 对照（另一条录制路径）");
        try {
            File f = new File(ctx.getExternalFilesDir(null), "mr_test.3gp");
            MediaRecorder mr = new MediaRecorder();
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mr.setOutputFile(f.getAbsolutePath());
            mr.prepare();
            mr.start();
            Probe.log("MediaRecorder.start() 成功，录 2 秒 …");
            Thread.sleep(2000);
            mr.stop();
            mr.release();
            Probe.log("MediaRecorder 成功，文件大小=" + f.length()
                    + (f.length() > 1000 ? " → 录音路径本身是通的" : " → 文件几乎为空，可能也是空录"));
        } catch (Throwable t) {
            Probe.log("MediaRecorder 失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
        Probe.sep("录音矩阵结束");
    }

    /** 单次录音尝试：异常也当成结果记录（不吞） */
    private static RecResult tryRecord(Context ctx, int rate, int seconds, int source, boolean aecOn, String tag) {
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Probe.log("   getMinBufferSize=" + min + " 不支持");
            return null;
        }
        AudioRecord ar = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, rate * 2 / 5));
        Probe.log("   构造 state=" + ar.getState() + " 实际采样率=" + ar.getSampleRate()
                + " sessionId=" + ar.getAudioSessionId());
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            ar.release();
            Probe.log("   ✗ 未初始化");
            return null;
        }
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            if (aecOn && AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(ar.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (aecOn && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(ar.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Throwable t) {
            Probe.log("   AEC/NS 失败: " + t);
        }
        int total = rate * seconds;
        short[] data = new short[total];
        int got = 0;
        long t0 = SystemClock.elapsedRealtime();
        try {
            ar.startRecording();
            Probe.log("   startRecording() 返回，getRecordingState=" + ar.getRecordingState()
                    + " (3=RECORDING)");
            while (got < total) {
                int n = ar.read(data, got, Math.min(1600, total - got));
                if (n <= 0) {
                    Probe.log("   read 返回 " + n);
                    break;
                }
                got += n;
            }
        } catch (Throwable t) {
            Probe.log("   ✗ 异常: " + t.getClass().getName() + " : " + t.getMessage());
        } finally {
            try {
                if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) ar.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (aec != null) aec.release();
                if (ns != null) ns.release();
            } catch (Throwable ignored) {
            }
            ar.release();
        }
        RecResult r = new RecResult();
        r.rate = rate;
        r.source = source;
        r.samples = got;
        r.ms = SystemClock.elapsedRealtime() - t0;
        int peak = 0;
        double sq = 0;
        for (int i = 0; i < got; i++) {
            int a = Math.abs(data[i]);
            if (a > peak) peak = a;
            sq += (double) data[i] * data[i];
        }
        r.peak = peak;
        r.rms = got > 0 ? Math.sqrt(sq / got) : 0;
        r.silent = peak <= 100;
        try {
            writeWav(new File(ctx.getExternalFilesDir(null), "mx_" + tag + "_" + rate + "_s" + source
                    + (aecOn ? "_aec" : "") + ".wav"), data, got, rate, 1);
        } catch (IOException ignored) {
        }
        Probe.log("   结果: " + summarize(r));
        return r;
    }

    public static short[] tone(int rate, int seconds, double freq) {
        int n = rate * seconds;
        short[] d = new short[n];
        double w = 2 * Math.PI * freq / rate;
        int fade = rate / 20; // 50ms 淡入淡出，避免爆音
        for (int i = 0; i < n; i++) {
            double env = 1.0;
            if (i < fade) env = (double) i / fade;
            else if (i > n - fade) env = (double) (n - i) / fade;
            d[i] = (short) (Math.sin(w * i) * 12000 * env);
        }
        return d;
    }
}
