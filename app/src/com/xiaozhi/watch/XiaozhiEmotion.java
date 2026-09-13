package com.xiaozhi.watch;

import java.util.HashMap;

/**
 * 小智 emotion → 情绪球表情 ID 的映射。
 *
 * <p>小智侧 21 种（来自 78/xiaozhi-esp32 的 emoji_collection，emotion 字段取值），
 * 情绪球侧 32 种。这里是两者之间唯一的翻译层。</p>
 *
 * <p>另外补了 4 个<b>状态态</b>（小智协议里不是 emotion，而是会话状态）：
 * listening / speaking / connecting / error —— 让球在没有 emotion 字段时也有反应。</p>
 */
public final class XiaozhiEmotion {

    private static final String IDLE = "02";        // 待机放空

    private static final HashMap<String, String> MAP = new HashMap<String, String>();

    static {
        // ---- 小智 21 种 emotion → 选「高对比」的几个戏剧化表情 ----
        // 小屏 + 单色为主，眼环 pool 微调肉眼难辨。
        // 优先用「体色 / 撒花 / 巨眼 / 歪头 / 身体旋转」这些高对比特征的表情。
        MAP.put("happy", "33");          // 任务完成 → 撒花
        MAP.put("laughing", "33");       // 😆 同上
        MAP.put("funny", "33");          // 😂 同上
        MAP.put("sad", "12");            // 失落：身体前倾 4° + 眼缩小 0.88
        MAP.put("crying", "12");         // 😭 同上
        MAP.put("angry", "21");          // 生气：身体变红 #E4574A
        MAP.put("loving", "14");         // 害羞
        MAP.put("embarrassed", "14");    // 😳 害羞
        MAP.put("kissy", "14");          // 😘 害羞
        MAP.put("surprised", "13");      // 惊讶：身体放大 1.03 + 巨眼 1.14
        MAP.put("shocked", "13");        // 😱 同惊讶（更夸张的版本原版没有，用 13）
        MAP.put("thinking", "30");       // 思考中：身体轻旋 -3° + 眼环轮换
        MAP.put("winking", "31");        // 接收任务：轻轻眨一下
        MAP.put("cool", "19");           // 满意
        MAP.put("relaxed", "06");        // 休眠（半开合、几乎静止）
        MAP.put("delicious", "19");      // 满意
        MAP.put("confident", "19");      // 满意
        MAP.put("sleepy", "00");         // 睡眠 + zzz
        MAP.put("silly", "03");          // 好奇
        MAP.put("confused", "20");       // 困惑：左眼 1.16 / 右眼 0.8 异形
        MAP.put("neutral", "02");        // 待机放空

        // ---- 会话状态（不是 emotion 字段，由客户端状态机驱动）----
        MAP.put("@listening", "35");     // 等待输入
        MAP.put("@speaking", "39");      // 输出回复
        MAP.put("@connecting", "36");    // 联网加载
        MAP.put("@error", "34");         // 出错
        MAP.put("@abort", "41");         // 停止终止
        MAP.put("@wake", "01");          // 唤醒
        // @thinking 此前是"发了但无 key→回落 02 待机"的孤儿态（子会话观察），补上：松手后陪你思考
        MAP.put("@thinking", "30");      // 思考中

        // ---- 共情细分与对话新态（50+ 自定义段，源 app/new_emotions.json）----
        // 名字来自 LocalEmotion.empathize() 的新输出，不与小智 21 种重名。
        MAP.put("soothe", "50");         // 心疼安抚（你哭时）
        MAP.put("indignant", "51");      // 同仇敌忾·克制（你生气时，区别于球自己生气 21）
        MAP.put("concerned", "52");      // 悬心担忧（你害怕/担心时）
        MAP.put("speechless", "53");     // 无语凝嚝（你难过到讲不动）
        MAP.put("@unclear", "54");       // 凑近细听（stt 转写为空，没听清）
        MAP.put("@stuck", "55");         // 卡壳冒烟（12s 看门狗：服务端没回话）
    }

    /** 取情绪球 ID；不认识的名字一律回落到待机，不抛异常。 */
    public static String of(String emotion) {
        if (emotion == null) return IDLE;
        String v = MAP.get(emotion.trim());
        return v != null ? v : IDLE;
    }

    private XiaozhiEmotion() { }
}
