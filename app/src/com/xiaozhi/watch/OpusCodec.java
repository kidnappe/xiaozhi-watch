package com.xiaozhi.watch;

/**
 * 音频编解码抽象层（PLAN §4.1 的纪律：把编码器藏在接口后面）。
 * 当前实现 A = Concentus（纯 Java Opus）。
 * 若将来要切 B（JNI libopus）或 C（自建服务器走 PCM），只换实现类，不动上层。
 */
public interface OpusCodec {

    /** 编码一帧。返回编码后的字节数，失败返回 -1 */
    int encode(short[] pcm, int samples, byte[] out);

    /** 解码一包。返回解出的样本数，失败返回 -1 */
    int decode(byte[] data, int len, short[] out);

    void close();
}
