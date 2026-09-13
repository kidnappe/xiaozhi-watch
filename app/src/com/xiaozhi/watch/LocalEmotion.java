package com.xiaozhi.watch;

import java.util.HashMap;

/**
 * 本地情绪分析 + 共情回应翻译。
 *
 * <p>情绪球是<b>对话的另一方</b>，不是复读机。用户说"我生气了"，球不该跟着生气，
 * 而要像正常人一样<b>心疼、安抚、担忧</b>；用户开心，球也一起开心 —— 这叫"共情回应"。</p>
 *
 * <p>两步：</p>
 * <ol>
 *   <li>{@link #userEmotion(String)}：从 stt 文字里识别<b>用户</b>的情绪（happy/sad/angry/…）；</li>
 *   <li>{@link #empathize(String)}：把用户情绪翻译成<b>球该做的回应表情</b>（共情映射）。</li>
 * </ol>
 *
 * <p>调用方直接调 {@link #of(String)}（识别 + 回应一步到位）：
 * 传入用户说的话，返回球应表现的情绪名（可直接喂给 {@link XiaozhiEmotion#of}）。</p>
 *
 * <p>为什么用关键词规则而非端侧模型：手表是 32 位 armv7 + API 27，跑不动语义模型；
 * 规则零依赖、零延迟、省电，短对话够用。以后要换真模型只改这一处。</p>
 */
public final class LocalEmotion {

    /** 空结果：没分析出情绪，调用方保持当前表情。 */
    public static final String NONE = "";

    // ---- 第一步：用户情绪识别（关键词 → 用户情绪名）----
    // 靠前的规则优先命中。负面情绪单独先扫（强度高，应盖过正面语气词）。
    private static final String[][] RULES = {
            // 负面（强度排序）
            { "angry",  "生气", "愤怒", "气死", "烦死", "滚", "讨厌", "烦人", "闭嘴", "神经病", "去死", "恼火" },
            { "crying", "哭", "呜呜", "想哭", "泪", "委屈", "伤心欲绝" },
            { "sad",    "失望", "遗憾", "伤心", "低落", "沮丧", "郁闷", "不开心", "难受", "难过", "不好", "心酸" },

            // 正面
            { "happy",  "开心", "高兴", "太好了", "棒", "赞", "厉害", "真好", "太棒", "耶", "哈哈", "嘻嘻", "美滋滋" },
            { "laughing","笑死", "好笑", "逗", "哈哈哈哈", "哈哈哈", "嘿嘿" },
            { "funny",  "搞笑", "好玩", "有趣" },

            // 惊讶 / 困惑
            { "surprised", "哇", "天哪", "真的假的", "竟然", "居然", "吓", "惊讶", "没想到", "卧槽", "不会吧" },
            { "confused", "为什么", "怎么回事", "不懂", "不明白", "奇怪", "啥意思", "什么意思", "困惑", "懵" },
            { "thinking", "让我想想", "思考", "想想", "让我考虑" },

            // 其他
            { "loving",  "爱你", "喜欢", "么么", "亲亲", "想你了" },
            { "sleepy",  "困", "累", "睡觉", "晚安", "睡", "疲惫", "没精神" },
            { "cool",    "不错", "可以", "满意", "没问题", "靠谱" },

            // 新增：害怕、无聊、兴奋、害羞
            { "fearful", "害怕", "怕", "好怕", "吓人", "恐怖", "担心", "焦虑", "不安" },
            { "bored",   "无聊", "没意思", "没劲", "好无聊", "无聊死了" },
            { "excited", "兴奋", "激动", "期待", "好期待", "等不及" },
            { "shy",     "害羞", "不好意思", "尴尬", "脸红", "羞" },
    };

    private static final HashMap<String, String> RULE_MAP = new HashMap<String, String>();

    static {
        for (String[] r : RULES) {
            String emotion = r[0];
            for (int i = 1; i < r.length; i++) RULE_MAP.put(r[i], emotion);
        }
    }

    // ---- 第二步：共情回应映射（用户情绪 → 球的回应情绪）----
    // 球是"对话另一方"，看到用户情绪后给出正常人的共情反应，而不是复读。
    private static final HashMap<String, String> EMPATHY = new HashMap<String, String>();

    static {
        // 负面 → 心疼 / 安抚 / 担忧 / 同仇敌忾（不是跟着生气或哭）。
        // 2026-09-13：细分 4 张共情脸（app/new_emotions.json 50–53），不全部挤在 12 失落一张脸上。
        EMPATHY.put("angry", "indignant");    // 你生气 → 球挺你但克制（暗砖红怒目，51）
        EMPATHY.put("crying", "soothe");      // 你哭 → 球歪头前倾轻拍安抚（暖玫瑰灰，50）
        EMPATHY.put("sad", "speechless");     // 你难过 → 球难说到说不出话（灰脸半阖颤睫，53）

        // 正面 → 一起开心（共情上扬）
        EMPATHY.put("happy", "happy");    // 你开心 → 球也开心（撒花）
        EMPATHY.put("laughing", "laughing"); // 你大笑 → 球一起笑
        EMPATHY.put("funny", "laughing"); // 你觉得好笑 → 球一起乐

        // 惊讶 / 困惑 → 同步
        EMPATHY.put("surprised", "surprised"); // 你惊讶 → 球同惊讶（巨眼）
        EMPATHY.put("confused", "thinking");   // 你困惑 → 球陪你一起想
        EMPATHY.put("thinking", "thinking");   // 你想 → 球也思考

        // 其他 → 温柔回应
        EMPATHY.put("loving", "loving");   // 你示爱 → 球害羞温柔
        EMPATHY.put("sleepy", "relaxed");  // 你困 → 球陪你安静
        EMPATHY.put("cool", "cool");       // 你满意 → 球也满意

        // 新增共情回应
        EMPATHY.put("fearful", "concerned");  // 你害怕/担心 → 球屏息悬心、上眸看你（冷灰大眼，52）
        EMPATHY.put("bored", "happy");     // 你无聊 → 球逗你开心（撒花）
        EMPATHY.put("excited", "happy");   // 你兴奋/期待 → 球一起兴奋
        EMPATHY.put("shy", "loving");      // 你害羞/尴尬 → 球温柔回应
    }

    private LocalEmotion() {
    }

    /**
     * 一步到位：识别用户文字情绪，翻译成球的共情回应。
     *
     * @return 球应表现的情绪名（happy/sad/laughing/surprised/thinking/…），
     *         没识别到用户情绪时返回 {@link #NONE}。
     */
    public static String of(String text) {
        String user = userEmotion(text);
        return empathize(user);
    }

    /**
     * 第一步：识别用户情绪。返回用户情绪名（happy/sad/angry/…），没命中返回 {@link #NONE}。
     */
    public static String userEmotion(String text) {
        if (text == null || text.trim().length() == 0) return NONE;
        String t = text.trim();
        if (t.length() > 200) return NONE;   // 过长是误识别，避免噪声

        String hit = matchNeg(t);            // 负面优先
        if (hit != null) return hit;

        for (String[] r : RULES) {
            String emotion = r[0];
            for (int i = 1; i < r.length; i++) {
                if (t.contains(r[i])) return emotion;
            }
        }
        return NONE;
    }

    /**
     * 第二步：共情回应翻译。把用户情绪映射成球该做的回应情绪。
     * 未知情绪原样返回（不强行改变）。
     */
    public static String empathize(String userEmotion) {
        if (userEmotion == null || userEmotion.length() == 0) return NONE;
        String e = EMPATHY.get(userEmotion);
        return e != null ? e : userEmotion;
    }

    /** 负面词优先单扫一遍：angry > crying > sad 的强度排序 */
    private static String matchNeg(String t) {
        String[] neg = { "angry", "crying", "sad" };
        for (String e : neg) {
            for (String[] r : RULES) {
                if (!r[0].equals(e)) continue;
                for (int i = 1; i < r.length; i++) {
                    if (t.contains(r[i])) return e;
                }
            }
        }
        return null;
    }

    /** 调试用：规则总数 */
    public static int ruleCount() {
        return RULE_MAP.size();
    }
}
