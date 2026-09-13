package com.xiaozhi.watch;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 情绪球表情定义 —— 解析 {@link Emotions#JSON}（源自 NX_emotion-ball/js/emotions.js）得到。
 *
 * <p>手表端只保留引擎真正消费的字段。颜色在解析时就转成 ARGB int，
 * 避免每帧插值时反复解析字符串。</p>
 *
 * <p>ID 分段：00-09 生命周期 · 10-29 情绪反应 · 30-49 代理工作状态。</p>
 */
public final class EmotionDef {

    // ---------------- 姿态 ----------------

    /** 单只眼睛的姿态。字段与 emotions.js 的 eyes.* 一一对应。 */
    public static final class Eye {
        public float x, y, scaleX = 1, scaleY = 1, rotate, open = 1, lookX, lookY;
        public int color = 0xFF1A1A1A;

        public void copyFrom(Eye o) {
            x = o.x; y = o.y; scaleX = o.scaleX; scaleY = o.scaleY;
            rotate = o.rotate; open = o.open; lookX = o.lookX; lookY = o.lookY;
            color = o.color;
        }
    }

    /** 身体姿态。 */
    public static final class Body {
        public float x, y, scale = 1, rotate, breathe;
        public float ribbons, confetti, sketch, zzz, orbit;
        /** 由自旋弹簧写入，不属于配置 */
        public float yaw;
        public int color = 0xFFF3F0EA;

        public void copyFrom(Body o) {
            x = o.x; y = o.y; scale = o.scale; rotate = o.rotate; breathe = o.breathe;
            ribbons = o.ribbons; confetti = o.confetti; sketch = o.sketch;
            zzz = o.zzz; orbit = o.orbit; yaw = o.yaw; color = o.color;
        }
    }

    /** 一帧完整姿态。ring 单独放，因为它是 48 点数组、不走数值插值。 */
    public static final class Pose {
        public final Body body = new Body();
        public final Eye left = new Eye();
        public final Eye right = new Eye();
        public float[] ringL, ringR;

        public void copyFrom(Pose o) {
            body.copyFrom(o.body);
            left.copyFrom(o.left);
            right.copyFrom(o.right);
            ringL = o.ringL;
            ringR = o.ringR;
        }
    }

    /** 动画原语。type ∈ sine / pulse / jitter / scan / glance / blink。 */
    public static final class Anim {
        public String target, prop, type;
        public float amp, period = 2000, phase, phaseMs, speed = 8, decay;
        public float interval = 3800, dur = 200, depth = 1;
    }

    /** sequence 关键帧。 */
    public static final class Frame {
        public long at;
        public final Pose pose = new Pose();
    }

    // ---------------- 表情定义 ----------------

    public String id, name, group;
    public boolean gaze = true;
    public long transition = 500;
    public int[] pool = { 0, 8 };
    public long[] poolMs = { 9000, 16000 };
    public float poolSpeed = 6;
    public long[] blinkMs = { 6000, 14000 };
    public float openness = 1;
    public boolean antics;
    public final Pose base = new Pose();
    public Anim[] anims = new Anim[0];
    public Frame[] frames;
    public String settle;

    // ---------------- 注册表 ----------------

    private static EmotionDef[] ALL;
    private static java.util.HashMap<String, EmotionDef> BY_ID;

    /** 解析并注册全部表情（首次调用时做一次，之后走缓存）。 */
    public static synchronized void init() {
        if (ALL != null) return;
        try {
            JSONArray arr = new JSONArray(Emotions.JSON);
            EmotionDef[] out = new EmotionDef[arr.length()];
            java.util.HashMap<String, EmotionDef> map = new java.util.HashMap<String, EmotionDef>();
            for (int i = 0; i < arr.length(); i++) {
                out[i] = parse(arr.getJSONObject(i));
                map.put(out[i].id, out[i]);
            }
            ALL = out;
            BY_ID = map;
            Lg.i("情绪球：已载入 " + out.length + " 种表情");
        } catch (Throwable t) {
            Lg.e("情绪球表情解析失败", t);
            ALL = new EmotionDef[0];
            BY_ID = new java.util.HashMap<String, EmotionDef>();
        }
    }

    public static EmotionDef get(String id) {
        init();
        return id == null ? null : BY_ID.get(id);
    }

    public static EmotionDef[] all() {
        init();
        return ALL;
    }

    // ---------------- 解析 ----------------

    private static EmotionDef parse(JSONObject o) throws org.json.JSONException {
        EmotionDef d = new EmotionDef();
        d.id = o.optString("id");
        d.name = o.optString("name");
        d.group = o.optString("group", "custom");
        d.gaze = o.optBoolean("gaze", true);
        d.transition = o.optLong("transition", 500);
        d.openness = (float) o.optDouble("openness", 1);
        d.antics = o.optBoolean("antics", false);
        d.poolSpeed = (float) o.optDouble("poolSpeed", 6);

        d.pool = toIntArray(o.optJSONArray("pool"));
        if (d.pool.length == 0) d.pool = new int[]{ 0 };
        d.poolMs = toLongArray(o.optJSONArray("poolMs"), 9000, 16000);
        JSONArray bm = o.optJSONArray("blinkMs");
        if (bm == null) d.blinkMs = null;                 // 显式 null = 不眨眼
        else if (bm.length() == 0) d.blinkMs = null;
        else d.blinkMs = toLongArray(bm, 6000, 14000);

        fillPose(d.base, o);

        JSONArray an = o.optJSONArray("anims");
        if (an != null && an.length() > 0) {
            d.anims = new Anim[an.length()];
            for (int i = 0; i < an.length(); i++) d.anims[i] = parseAnim(an.getJSONObject(i));
        }

        JSONObject sq = o.optJSONObject("sequence");
        if (sq != null) {
            JSONArray fs = sq.optJSONArray("frames");
            if (fs != null && fs.length() > 0) {
                d.frames = new Frame[fs.length()];
                for (int i = 0; i < fs.length(); i++) {
                    JSONObject f = fs.getJSONObject(i);
                    Frame fr = new Frame();
                    fr.at = f.optLong("at", 0);
                    fr.pose.copyFrom(d.base);
                    fillPose(fr.pose, f);
                    d.frames[i] = fr;
                }
                // 保证按时间升序
                java.util.Arrays.sort(d.frames, new java.util.Comparator<Frame>() {
                    @Override
                    public int compare(Frame a, Frame b) {
                        return a.at < b.at ? -1 : (a.at > b.at ? 1 : 0);
                    }
                });
            }
            d.settle = sq.optString("settle", "base");
        }
        return d;
    }

    /** 把 { body:{...}, eyes:{ both|left|right:{...} } } 合并进 pose。 */
    private static void fillPose(Pose p, JSONObject src) {
        JSONObject b = src.optJSONObject("body");
        if (b != null) {
            Body bd = p.body;
            bd.x = (float) b.optDouble("x", bd.x);
            bd.y = (float) b.optDouble("y", bd.y);
            bd.scale = (float) b.optDouble("scale", bd.scale);
            bd.rotate = (float) b.optDouble("rotate", bd.rotate);
            bd.breathe = (float) b.optDouble("breathe", bd.breathe);
            bd.ribbons = (float) b.optDouble("ribbons", bd.ribbons);
            bd.confetti = (float) b.optDouble("confetti", bd.confetti);
            bd.sketch = (float) b.optDouble("sketch", bd.sketch);
            bd.zzz = (float) b.optDouble("zzz", bd.zzz);
            bd.orbit = (float) b.optDouble("orbit", bd.orbit);
            if (b.has("color")) bd.color = Colorx.parse(b.optString("color"), bd.color);
        }
        JSONObject e = src.optJSONObject("eyes");
        if (e == null) return;
        JSONObject both = e.optJSONObject("both");
        if (both != null) { fillEye(p.left, both); fillEye(p.right, both); }
        JSONObject l = e.optJSONObject("left");
        if (l != null) fillEye(p.left, l);
        JSONObject r = e.optJSONObject("right");
        if (r != null) fillEye(p.right, r);
    }

    private static void fillEye(Eye ey, JSONObject o) {
        ey.x = (float) o.optDouble("x", ey.x);
        ey.y = (float) o.optDouble("y", ey.y);
        ey.scaleX = (float) o.optDouble("scaleX", ey.scaleX);
        ey.scaleY = (float) o.optDouble("scaleY", ey.scaleY);
        ey.rotate = (float) o.optDouble("rotate", ey.rotate);
        ey.open = (float) o.optDouble("open", ey.open);
        ey.lookX = (float) o.optDouble("lookX", ey.lookX);
        ey.lookY = (float) o.optDouble("lookY", ey.lookY);
        if (o.has("color")) ey.color = Colorx.parse(o.optString("color"), ey.color);
    }

    private static Anim parseAnim(JSONObject o) {
        Anim a = new Anim();
        a.target = o.optString("target", "body");
        a.prop = o.optString("prop", "y");
        a.type = o.optString("type", "sine");
        a.amp = (float) o.optDouble("amp", 0);
        a.period = (float) o.optDouble("period", 2000);
        a.phase = (float) o.optDouble("phase", 0);
        a.phaseMs = (float) o.optDouble("phaseMs", 0);
        a.speed = (float) o.optDouble("speed", 8);
        a.decay = (float) o.optDouble("decay", 0);
        a.interval = (float) o.optDouble("interval", 3800);
        a.dur = (float) o.optDouble("dur", 200);
        a.depth = (float) o.optDouble("depth", 1);
        return a;
    }

    private static int[] toIntArray(JSONArray a) {
        if (a == null) return new int[0];
        int[] out = new int[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optInt(i, 0);
        return out;
    }

    private static long[] toLongArray(JSONArray a, long d0, long d1) {
        if (a == null || a.length() == 0) return new long[]{ d0, d1 };
        if (a.length() == 1) return new long[]{ a.optLong(0, d0), a.optLong(0, d0) };
        return new long[]{ a.optLong(0, d0), a.optLong(1, d1) };
    }

    private EmotionDef() { }
}
