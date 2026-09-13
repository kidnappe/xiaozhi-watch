package com.xiaozhi.watch;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * 实现 A：Concentus 纯 Java Opus。参数按 P0 真机实测定案（probe_audio/P0_RESULTS.md §三）：
 *   上行 16k / mono / 60ms(960 样本) / complexity=0 / VBR / DTX / 24kbps / signal=VOICE
 *   下行 24k（服务端会把采样率改成 24000）/ mono / 60ms(1440 样本)
 * 实测：编码 avg 9.27ms/帧（预算 60ms）、解码 1.39ms、19.4kbps、单核 ~15% → 不需要 JNI。
 *
 * ⚠️ 下行解码缓冲区必须开大：服务端可能发 20/40/60ms 的包，frame_size 传缓冲区容量即可，
 *    decode() 会返回实际解出的样本数。
 */
public final class ConcentusCodec implements OpusCodec {

    private final OpusEncoder enc;
    private final OpusDecoder dec;
    private final byte[] scratch;

    public ConcentusCodec(int encRate, int encChannels, int decRate, int decChannels, int maxPacket)
            throws OpusException {
        enc = new OpusEncoder(encRate, encChannels, OpusApplication.OPUS_APPLICATION_VOIP);
        enc.setComplexity(0);
        enc.setBitrate(24000);
        enc.setUseVBR(true);
        enc.setUseDTX(true);
        enc.setUseInbandFEC(false);
        enc.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        dec = new OpusDecoder(decRate, decChannels);
        scratch = new byte[Math.max(1275, maxPacket)];
    }

    @Override
    public int encode(short[] pcm, int samples, byte[] out) {
        try {
            return enc.encode(pcm, 0, samples, out, 0, out.length);
        } catch (Throwable t) {
            Lg.e("Opus 编码失败", t);
            return -1;
        }
    }

    @Override
    public int decode(byte[] data, int len, short[] out) {
        try {
            return dec.decode(data, 0, len, out, 0, out.length, false);
        } catch (Throwable t) {
            Lg.e("Opus 解码失败", t);
            return -1;
        }
    }

    @Override
    public void close() {
        // Concentus 是纯 Java，没有 native 资源要释放
    }
}
