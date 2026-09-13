package com.xiaozhi.watch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * 情绪球 —— 把 NX_emotion-ball（SVG + JS）移植到 Android Canvas。
 *
 * <p>移植时按手表做了三处取舍，都是有意为之，不是漏实现：</p>
 * <ol>
 *   <li><b>去掉自旋彩带（ribbons 拖尾）</b>：48 点轨迹 + 逐帧重建渐变，在 372x430 屏上
 *       既看不清又费电。ribbons 配置改为触发一次自旋（yaw 弹簧），保留动态但零成本。</li>
 *   <li><b>去掉常驻环带（orbit）</b>：同上。</li>
 *   <li><b>保留撒花（confetti）</b>：矩形/星形粒子很便宜，且是"开心/庆祝"最直观的反馈。</li>
 * </ol>
 *
 * <p>其余全部对齐原版：48 点眼环逐点弹簧形变、球面投影与背面隐藏、
 * 6 种动画原语、眨眼关键帧（含过冲与连眨）、表情池轮换、待机小动作、表情过渡插值。</p>
 *
 * <p>用法：{@code view.setEmotion("02"); view.setEmotionName("happy");}</p>
 */
public class EmotionBallView extends View {

    private static final float TAU = (float) (Math.PI * 2);
    private static final String FALLBACK_ID = "02";
    /** 目标帧率：手表上 30fps 足够顺滑且省电（原版是 60fps 的 rAF） */
    private static final long FRAME_MS = 33;

    private static final float[] BOUNCE_H = { 48, 28, 14, 6 };
    private static final float[] BOUNCE_D = { 0.5f, 0.382f, 0.27f, 0.177f };
    private static final float BOUNCE_TOTAL = 1.329f;

    private static final int CONFETTI_MAX = 48;
    private static final int[] CONFETTI_COLORS = {
            0xFFF9705C, 0xFF5B95F0, 0xFF3FBE86, 0xFFF5B13F, 0xFF9A72EE, 0xFF35C3BD
    };

    // ---------------- 形状 ----------------
    private final int shapeIdx;
    private final float[] face;      // { x, y, sx, sy, eye }
    private final float[] headRing;
    private final Path headPath = new Path();
    private float silMinY, silMaxY;
    private float[][] silRows;
    private static final float SIL_STEP = 2f;

    // ---------------- 绘制 ----------------
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eyePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zzzPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path eyePath = new Path();
    private final float[] eyeBuf = new float[Rings.RING_POINTS * 2];
    private float headBx, headBy, headBw, headBh;
    private int gradColor = 0;
    private RadialGradient grad;

    // ---------------- 引擎状态 ----------------
    private final float seed;
    private EmotionDef def;
    private long emoStart, transStart, transDur, lastTick;
    private long poolNext, blinkNext, anticNext;
    private long bounceAt = -1;
    private boolean running, attached;

    private final EmotionDef.Pose cur = new EmotionDef.Pose();
    private final EmotionDef.Pose prev = new EmotionDef.Pose();
    private final EmotionDef.Pose tmpA = new EmotionDef.Pose();
    private final EmotionDef.Pose tmpB = new EmotionDef.Pose();
    private final EmotionDef.Pose out = new EmotionDef.Pose();
    private boolean havePrev;

    // 眼环形变
    private final float[] srcL = new float[Rings.RING_POINTS * 2];
    private final float[] srcR = new float[Rings.RING_POINTS * 2];
    private final float[] dstL = new float[Rings.RING_POINTS * 2];
    private final float[] dstR = new float[Rings.RING_POINTS * 2];
    private final float[] curL = new float[Rings.RING_POINTS * 2];
    private final float[] curR = new float[Rings.RING_POINTS * 2];
    private final Spring ringSpring = new Spring(1);
    private float ringSpeed = 7;
    private int exprIdx;
    private int poolPos;

    // 眨眼 / 开合 / 自旋
    private final Spring open = new Spring(1);
    private final Spring spin = new Spring(0);
    private boolean spinning;
    private final long[] blinkAt = new long[16];
    private final float[] blinkV = new float[16];
    private int blinkN;

    // 注视漂移（手表没有鼠标，只保留常驻微漂移，gaze 目标留 0）
    private float gazeX, gazeY;

    // ---- 戳戳互动（POKE_INTERACTION R1/R2）----
    /** 临时视线偏置（戳哪看哪/回正），渲染环 ~1.5s 衰减归零，与 gaze 微漂移叠加不冲突 */
    private float pokeGazeX, pokeGazeY;
    /** rua 头模式（长按）：按住期间眯眼蹭头 */
    private volatile boolean patted;
    /** 眯眼跟随量 0~1，平滑出入 */
    private float patAmt;
    private long patKickAt;
    /** 倾角注视（加速度计映射，2026-09-13）：目标±1，compose 里平滑跟随后叠加到 lookX/lookY */
    private volatile float tiltTX, tiltTY;
    private float tiltCX, tiltCY;

    // 撒花
    private static final class Piece {
        float x, y, vx, vy, life, max, r, rot, vr, stretch;
        boolean star, round;
        int color;
    }
    private final Piece[] pieces = new Piece[CONFETTI_MAX];
    private int pieceN;

    private float dt = 1f / 60f;

    // ================================================================

    public EmotionBallView(Context c) {
        this(c, 0);
    }

    public EmotionBallView(Context c, int shape) {
        super(c);
        shapeIdx = shape;
        headRing = Rings.shape(shape);
        face = Rings.FACE_DATA[shape];
        seed = (float) (Math.random() * 100);
        buildHeadPath();
        buildSilhouette();
        fillPaint.setStyle(Paint.Style.FILL);
        eyePaint.setStyle(Paint.Style.FILL);
        zzzPaint.setColor(0xFFA8A296);
        zzzPaint.setFakeBoldText(true);
        zzzPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < pieces.length; i++) pieces[i] = new Piece();
        setEmotion(FALLBACK_ID);
    }

    // ---------------- 对外 ----------------

    public void setEmotion(String id) {
        EmotionDef d = EmotionDef.get(id);
        if (d == null) {
            Lg.w("情绪球：未知表情 " + id + "，回退 " + FALLBACK_ID);
            d = EmotionDef.get(FALLBACK_ID);
            if (d == null) return;
        }
        Lg.i("情绪球.setEmotion: " + id + " → " + d.name + "（pool=" + java.util.Arrays.toString(d.pool) + "）");
        // 客户端主动换脸：戳互动临时态（视线偏置/rua 眯眼）全部让路，不留残留
        pokeGazeX = pokeGazeY = 0;
        patted = false;
        long now = SystemClock.uptimeMillis();
        if (def != null) {
            prev.copyFrom(cur);
            havePrev = true;
        }
        def = d;
        emoStart = now;
        transStart = now;
        transDur = havePrev ? d.transition : 0;
        poolPos = 0;
        setExpr(d.pool[0], d.poolSpeed >= 10 ? 10f : 8f);
        poolNext = now + rand(d.poolMs[0], d.poolMs[1]);
        if (havePrev && d.blinkMs != null) blinkNow(now);
        blinkNext = d.blinkMs != null ? now + rand(d.blinkMs[0], d.blinkMs[1]) : Long.MAX_VALUE;
        anticNext = now + rand(2500, 5000);

        if (d.base.body.ribbons > 0) doSpin(d.base.body.ribbons >= 1 ? 2 : 1);
        if (d.base.body.confetti > 0) burst(20);
        if (!running) renderStatic(now);
    }

    /** 手动触发一次自旋（点击交互用） */
    public void spinOnce() {
        doSpin(1);
    }

    /**
     * 被戳（POKE_INTERACTION L1/L2）：level 1~3（轻晃/歪头躲/晕到弹），dirX∈[-1,1] 戳点左右偏移。
     * 给 spin 弹簧冲量（t 保持 0）而不是 doSpin 的整圈目标语义：晃回去自然回正，
     * 不会停在非零角度（doSpin 的 t=TAU 能停在整圈是因为 2π 视觉等价 0）。
     */
    public void poke(int level, float dirX) {
        if (!spinning) {
            float d = dirX >= 0 ? 1 : -1;
            spin.x = 0; spin.t = 0;
            spin.v = d * (1.6f + 1.5f * level);
            spinning = true;
        }
        if (level >= 3 && bounceAt < 0) bounceAt = android.os.SystemClock.uptimeMillis();   // 高连击：弹一下
    }

    /** 临时视线偏置（哪戳看哪/回正确认），(dx,dy)∈[-1,1]，约 1.5s 衰减 */
    public void lookAt(float dx, float dy) {
        pokeGazeX = clamp(dx, -1f, 1f) * 5f;
        pokeGazeY = clamp(dy, -1f, 1f) * 3f;
    }

    /** rua 头模式：按住期间眼睛眯到 0.35×，每 260ms 交替小幅蹭头；任何换脸自动解除 */
    public void setPatted(boolean on) {
        patted = on;
    }

    /** 倾角注视：手腕横滚/俯仰映射成视线偏置目标（±1），球缓慢把目光跟过去（~0.4s 时间常数） */
    public void setTiltBias(float tx, float ty) {
        tiltTX = clamp(tx, -1f, 1f);
        tiltTY = clamp(ty, -1f, 1f);
    }

    /** 撒花（默认星形比例 0.18） */
    public void burst(int count) {
        burst(count, 0.18f);
    }

    /** 撒花：starBias 拉高星形粒子比例（连击越高越"晕"） */
    public void burst(int count, float starBias) {
        for (int i = 0; i < count && pieceN < CONFETTI_MAX; i++) {
            Piece p = pieces[pieceN++];
            float ang = (float) (i / (double) count * TAU + rand(-0.35f, 0.35f));
            float spd = rand(170, 360);
            p.star = Math.random() < starBias;
            p.round = !p.star && Math.random() < 0.3;
            p.x = Rings.HEAD_C + (float) Math.cos(ang) * rand(96, 116);
            p.y = Rings.HEAD_C + (float) Math.sin(ang) * rand(96, 116);
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd - rand(20, 75);
            p.life = 0;
            p.max = rand(0.45f, 0.85f);
            p.r = p.star ? rand(4, 7) : rand(3.5f, 8);
            p.rot = rand(0, 360);
            p.vr = rand(-260, 260);
            p.stretch = (!p.star && !p.round) ? 1.9f : 1f;
            p.color = p.star ? 0xFFF4C34E : CONFETTI_COLORS[(int) (Math.random() * CONFETTI_COLORS.length)];
        }
    }

    public void start() {
        running = true;
        lastTick = 0;
        invalidate();
    }

    public void stop() {
        running = false;
    }

    public String emotionId() {
        return def == null ? null : def.id;
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (running) invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        dt = lastTick == 0 ? 1f / 60f : clamp((now - lastTick) / 1000f, 0.001f, 0.05f);
        lastTick = now;
        compose(now);
        drawPose(canvas, cur);
        stepConfetti(dt);
        if (running && attached) postInvalidateDelayed(FRAME_MS);
    }

    /** 静态渲染一帧（未启动时也保证有画面） */
    private void renderStatic(long now) {
        ringSpring.x = 1; ringSpring.v = 0;
        open.x = def == null ? 1 : def.openness; open.v = 0;
        long save = lastTick;
        compose(now);
        lastTick = save;
        invalidate();
    }

    // ---------------- 姿态合成（对齐 engine.js _compose） ----------------

    private void compose(long now) {
        if (def == null) return;
        long t = now - emoStart;

        tmpA.copyFrom(def.base);
        EmotionDef.Pose pose = tmpA;

        // 内置呼吸（相位用绝对时间，切换表情不跳变）
        float br = pose.body.breathe;
        if (br != 0) {
            float ph = TAU * now / 3600f;
            pose.body.scale += br * (float) Math.sin(ph);
            pose.body.y += br * 55f * (float) Math.sin(ph + 0.6f);
        }

        for (int i = 0; i < def.anims.length; i++) applyAnim(pose, def.anims[i], t);

        // 表情池轮换
        if (running && now >= poolNext) {
            if (def.pool.length > 1) {
                poolPos = (poolPos + 1 + (int) rand(0, def.pool.length - 1)) % def.pool.length;
                setExpr(def.pool[poolPos], def.poolSpeed);
            }
            poolNext = now + rand(def.poolMs[0], def.poolMs[1]);
        }

        // 眨眼调度
        if (running && def.blinkMs != null && now >= blinkNext) {
            blinkNow(now);
            blinkNext = now + rand(def.blinkMs[0], def.blinkMs[1]);
        }
        Float openKey = null;
        while (blinkN > 0 && now >= blinkAt[0]) {
            openKey = blinkV[0];
            System.arraycopy(blinkAt, 1, blinkAt, 0, --blinkN);
            System.arraycopy(blinkV, 1, blinkV, 0, blinkN);
        }
        open.t = openKey != null ? openKey : (blinkN > 0 ? open.t : def.openness);

        // 待机小动作
        if (running && def.antics && now >= anticNext) {
            if (!spinning && bounceAt < 0) {
                float pick = (float) Math.random();
                if (pick < 0.45f) doSpin(1);
                else if (pick < 0.8f) bounceAt = now;
                else blinkNow(now);
            }
            anticNext = now + rand(9000, 18000);
        }

        // 弹簧整步（子步 1/120 保数值稳定）
        int steps = Math.max(1, (int) Math.ceil(dt / (1f / 120f)));
        float sub = dt / steps;
        for (int s = 0; s < steps; s++) {
            springStep(ringSpring, ringSpeed, 1f, sub);
            springStep(open, 26f, 1f, sub);
            if (spinning) {
                springStep(spin, 6.2f, 1f, sub);
                if (Math.abs(spin.t - spin.x) < 0.01f && Math.abs(spin.v) < 0.05f) spinning = false;
            }
        }
        // rua 头（长按）：眯眼量平滑跟随；按住期间每 260ms 左右各蹭一下头
        float patTarget = patted ? 1f : 0f;
        patAmt += (patTarget - patAmt) * Math.min(1f, dt * 9f);
        if (patted && !spinning && now - patKickAt >= 260) {
            patKickAt = now;
            spin.x = 0; spin.t = 0;
            spin.v = ((patKickAt / 260) % 2 == 0 ? 1 : -1) * 1.1f;
            spinning = true;
        }
        // 戳互动视线偏置：~1.5s 指数衰减归零
        if (pokeGazeX != 0 || pokeGazeY != 0) {
            float k = Math.max(0f, 1f - dt * 1.8f);
            pokeGazeX *= k; pokeGazeY *= k;
            if (Math.abs(pokeGazeX) < 0.02f) pokeGazeX = 0;
            if (Math.abs(pokeGazeY) < 0.02f) pokeGazeY = 0;
        }
        pose.body.yaw = spinning ? spin.x : 0f;

        // 弹跳位移
        if (bounceAt >= 0) {
            float be = (now - bounceAt) / 1000f;
            if (be >= BOUNCE_TOTAL) {
                bounceAt = -1;
            } else {
                float acc = 0;
                int bi = 0;
                while (bi < BOUNCE_D.length && be >= acc + BOUNCE_D[bi]) { acc += BOUNCE_D[bi]; bi++; }
                int si = Math.min(bi, BOUNCE_D.length - 1);
                float bn = (be - acc) / BOUNCE_D[si];
                pose.body.y += -4f * BOUNCE_H[si] * bn * (1f - bn);
            }
        }

        // 当前眼环
        if (ringSpring.x < 0.999f || ringSpring.v > 0.001f || ringSpring.v < -0.001f) {
            float rs = clamp(ringSpring.x, 0f, 1.35f);
            lerpInto(srcL, dstL, curL, rs);
            lerpInto(srcR, dstR, curR, rs);
        } else {
            System.arraycopy(dstL, 0, curL, 0, curL.length);
            System.arraycopy(dstR, 0, curR, 0, curR.length);
        }
        pose.ringL = curL;
        pose.ringR = curR;

        // 常驻眼神微漂移 + 倾角注视（抬腕/转腕时它的目光跟着手腕姿态走）
        if (def.gaze) {
            gazeX = gazeY = 0;
            float w = now / 1000f;
            tiltCX += (tiltTX - tiltCX) * Math.min(1f, dt * 2.5f);
            tiltCY += (tiltTY - tiltCY) * Math.min(1f, dt * 2.5f);
            pose.left.lookX += 1.4f * Math.sin(0.42f * w) + 0.5f * Math.sin(1.0f * w) + gazeX + tiltCX * 6f;
            pose.right.lookX += 1.4f * Math.sin(0.42f * w + 1) + 0.5f * Math.sin(1.0f * w + 2) + gazeX + tiltCX * 6f;
            pose.left.lookY += 0.9f * Math.sin(0.58f * w) + gazeY + tiltCY * 4f;
            pose.right.lookY += 0.9f * Math.sin(0.58f * w + 1) + gazeY + tiltCY * 4f;
        }

        // 戳互动视线偏置：不受 gaze 开关限制（"定格脸"被戳也该看一眼戳它的位置）
        if (pokeGazeX != 0 || pokeGazeY != 0) {
            pose.left.lookX += pokeGazeX;
            pose.right.lookX += pokeGazeX;
            pose.left.lookY += pokeGazeY;
            pose.right.lookY += pokeGazeY;
        }

        // 开合度
        float openS = clamp(open.x, 0.02f, 1.5f) * (1f - 0.65f * patAmt);   // rua 头时眯到 ~0.35×
        pose.left.open = clamp(pose.left.open, 0f, 1.3f) * openS;
        pose.right.open = clamp(pose.right.open, 0f, 1.3f) * openS;
        pose.left.scaleX = Math.max(pose.left.scaleX, 0.05f);
        pose.left.scaleY = Math.max(pose.left.scaleY, 0.05f);
        pose.right.scaleX = Math.max(pose.right.scaleX, 0.05f);
        pose.right.scaleY = Math.max(pose.right.scaleY, 0.05f);

        // 表情切换过渡插值
        long tt = now - transStart;
        if (transDur > 0 && tt < transDur && havePrev) {
            float k = easeInOutCubic(tt / (float) transDur);
            lerpPose(prev, pose, k, out);
            cur.copyFrom(out);
        } else {
            cur.copyFrom(pose);
        }
    }

    private void setExpr(int idx, float speed) {
        if (idx == exprIdx && ringSpring.x >= 0.999f) return;
        float s = clamp(ringSpring.x, 0f, 1f);
        lerpInto(srcL, dstL, srcL, s);
        lerpInto(srcR, dstR, srcR, s);
        System.arraycopy(Rings.ring(idx, 0), 0, dstL, 0, dstL.length);
        System.arraycopy(Rings.ring(idx, 1), 0, dstR, 0, dstR.length);
        ringSpring.x = 0; ringSpring.v = 0; ringSpring.t = 1;
        ringSpeed = speed;
        exprIdx = idx;
    }

    private void blinkNow(long t) {
        push(t, 0.05f); push(t + 70, 0.05f); push(t + 150, 1.08f); push(t + 300, 1f);
        if (Math.random() < 0.14) { push(t + 370, 0.05f); push(t + 480, 1f); }
    }

    private void push(long at, float v) {
        if (blinkN >= blinkAt.length) return;
        int i = blinkN++;
        while (i > 0 && blinkAt[i - 1] > at) { blinkAt[i] = blinkAt[i - 1]; blinkV[i] = blinkV[i - 1]; i--; }
        blinkAt[i] = at;
        blinkV[i] = v;
    }

    private void doSpin(int turns) {
        if (spinning) return;
        float d = Math.random() < 0.5 ? -1 : 1;
        spin.x = 0; spin.v = 0;
        spin.t = Math.max(1, turns) * TAU * d;
        spinning = true;
    }

    private void applyAnim(EmotionDef.Pose pose, EmotionDef.Anim a, long t) {
        float v;
        if ("sine".equals(a.type)) {
            v = a.amp * (float) Math.sin(TAU * t / a.period + a.phase);
        } else if ("pulse".equals(a.type)) {
            v = a.amp * 0.5f * (1f - (float) Math.cos(TAU * t / a.period + a.phase));
        } else if ("jitter".equals(a.type)) {
            float s = t / 1000f * a.speed;
            v = (float) ((Math.sin(s * 3.1 + seed) + Math.sin(s * 5.7 + seed * 2.3)
                    + Math.sin(s * 9.3 + seed * 4.1)) / 3 * a.amp);
            if (a.decay > 0) v *= clamp(1f - t / a.decay, 0f, 1f);
        } else if ("scan".equals(a.type)) {
            float p = ((t + a.phaseMs) % a.period) / a.period;
            float tri = p < 0.5f ? p * 4f - 1f : 3f - p * 4f;
            v = a.amp * tri;
        } else if ("glance".equals(a.type)) {
            float ph = TAU * (((t + a.phaseMs) % a.period) / a.period) + a.phase;
            v = a.amp * (float) Math.tanh(2.8 * Math.sin(ph));
        } else if ("blink".equals(a.type)) {
            float p = (t + a.phaseMs + seed * 97) % a.interval;
            v = p >= a.dur ? 0 : -a.depth * (float) Math.sin(Math.PI * (p / a.dur));
        } else {
            return;
        }
        addProp(pose, a.target, a.prop, v, false);
    }

    private void addProp(EmotionDef.Pose pose, String target, String prop, float v, boolean bodyOnly) {
        boolean eyes = "eyes".equals(target);
        boolean body = "body".equals(target);
        boolean left = eyes || "left".equals(target);
        boolean right = eyes || "right".equals(target);
        if (body) addBody(pose.body, prop, v);
        if (left) addEye(pose.left, prop, v);
        if (right) addEye(pose.right, prop, v);
    }

    private void addBody(EmotionDef.Body b, String prop, float v) {
        if ("scale".equals(prop)) b.scale += v;
        else if ("x".equals(prop)) b.x += v;
        else if ("y".equals(prop)) b.y += v;
        else if ("rotate".equals(prop)) b.rotate += v;
    }

    private void addEye(EmotionDef.Eye e, String prop, float v) {
        if ("scale".equals(prop)) { e.scaleX += v; e.scaleY += v; }
        else if ("scaleX".equals(prop)) e.scaleX += v;
        else if ("scaleY".equals(prop)) e.scaleY += v;
        else if ("x".equals(prop)) e.x += v;
        else if ("y".equals(prop)) e.y += v;
        else if ("rotate".equals(prop)) e.rotate += v;
        else if ("open".equals(prop)) e.open += v;
        else if ("lookX".equals(prop)) e.lookX += v;
        else if ("lookY".equals(prop)) e.lookY += v;
    }

    // ---------------- 绘制 ----------------

    private void drawPose(Canvas canvas, EmotionDef.Pose pose) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float size = Math.min(w, h);
        float scale = size / Rings.VIEW;

        canvas.save();
        canvas.translate((w - size) / 2f, (h - size) / 2f);
        canvas.scale(scale, scale);
        canvas.translate(-Rings.VIEW_OFF, -Rings.VIEW_OFF);

        EmotionDef.Body b = pose.body;
        canvas.save();
        canvas.translate(Rings.HEAD_C + b.x, Rings.HEAD_C + b.y);
        canvas.rotate(b.rotate);
        canvas.scale(b.scale, b.scale);
        canvas.translate(-Rings.HEAD_C, -Rings.HEAD_C);

        // 身体
        if (b.sketch > 0.5f) {
            fillPaint.setShader(null);
            fillPaint.setStyle(Paint.Style.STROKE);
            fillPaint.setStrokeWidth(2f);
            fillPaint.setColor(Colorx.shade(b.color, -0.6f));
            fillPaint.setAlpha(217);
        } else {
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setAlpha(255);
            if (gradColor != b.color || grad == null) {
                gradColor = b.color;
                float cx = headBx + 0.38f * headBw;
                float cy = headBy + 0.32f * headBh;
                float r = 0.75f * (float) Math.sqrt((headBw * headBw + headBh * headBh) / 2f);
                // 3 段色标：高光 22% → 本色 → 暗部 -12%（对齐 ball.js 的 radialGradient）
                grad = new RadialGradient(cx, cy, r,
                        new int[]{ Colorx.shade(b.color, 0.22f), b.color, Colorx.shade(b.color, -0.12f) },
                        new float[]{ 0f, 0.62f, 1f }, Shader.TileMode.CLAMP);
            }
            fillPaint.setShader(grad);
            fillPaint.setColor(b.color);
        }
        canvas.drawPath(headPath, fillPaint);
        fillPaint.setShader(null);
        fillPaint.setAlpha(255);

        // 双眼
        drawEye(canvas, pose, pose.left, pose.ringL, b.yaw, b.sketch);
        drawEye(canvas, pose, pose.right, pose.ringR, b.yaw, b.sketch);
        canvas.restore();

        // zzz（在身体变换之外）
        if (b.zzz > 0) {
            long now = SystemClock.uptimeMillis();
            for (int z = 0; z < 3; z++) {
                float zp = (now * 0.00033f + z / 3f) % 1f;
                float zo = (zp < 0.18f ? zp / 0.18f : 1f - (zp - 0.18f) / 0.82f) * 0.8f * b.zzz;
                if (zo <= 0.01f) continue;
                zzzPaint.setAlpha((int) (clamp(zo, 0f, 1f) * 255));
                zzzPaint.setTextSize(12 + zp * 11);
                canvas.save();
                canvas.translate(180 + zp * 34 + 4 * (float) Math.sin(zp * 9), 48 - zp * 42);
                canvas.rotate(-10 + zp * 14);
                canvas.drawText("z", 0, 0, zzzPaint);
                canvas.restore();
            }
            zzzPaint.setAlpha(255);
        }

        // 撒花
        for (int i = 0; i < pieceN; i++) {
            Piece p = pieces[i];
            float u = p.life / p.max;
            float fd = u < 0.1f ? u / 0.1f : (float) Math.pow(1 - (u - 0.1f) / 0.9f, 1.7);
            float sz = Math.max(p.r * (1 - 0.4f * u), 0.5f);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(p.color);
            fillPaint.setAlpha((int) (clamp(fd, 0f, 1f) * 255));
            canvas.save();
            canvas.translate(p.x, p.y);
            canvas.rotate(p.rot);
            canvas.scale(sz, sz * p.stretch);
            if (p.star) canvas.drawPath(starPath(), fillPaint);
            else if (p.round) canvas.drawCircle(0, 0, 1f, fillPaint);
            else canvas.drawRect(-0.5f, -0.5f, 0.5f, 0.5f, fillPaint);
            canvas.restore();
        }
        fillPaint.setAlpha(255);

        canvas.restore();
    }

    private Path starCache;

    private Path starPath() {
        if (starCache != null) return starCache;
        Path p = new Path();
        for (int i = 0; i < 10; i++) {
            float a = (float) (-Math.PI / 2 + i * Math.PI / 5);
            float r = i % 2 == 0 ? 1f : 0.42f;
            float x = (float) Math.cos(a) * r, y = (float) Math.sin(a) * r;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        starCache = p;
        return p;
    }

    private void drawEye(Canvas canvas, EmotionDef.Pose pose, EmotionDef.Eye ey, float[] ring,
                         float yaw, float sketch) {
        if (ring == null) return;
        // 质心
        float bx = 0, by = 0;
        for (int i = 0; i < ring.length; i += 2) { bx += ring[i]; by += ring[i + 1]; }
        int n = ring.length / 2;
        bx /= n; by /= n;

        float op = clamp(ey.open, 0.02f, 2.4f);
        float sy = clamp(ey.scaleY * op * face[4], 0.02f, 2.4f);
        float sxBase = ey.scaleX * face[4];

        float halfH = Rings.EYE_HALF * sy + 2;
        float ey0 = Rings.HEAD_C + face[1] + (by - Rings.HEAD_C) * face[3] + ey.y + ey.lookY;
        ey0 = clamp(ey0, silMinY + halfH, silMaxY - halfH);

        float[] sil = silAt(ey0);
        float cx0 = (sil[0] + sil[1]) / 2f;
        float hw = Math.max((sil[1] - sil[0]) / 2f, 12f);

        float ox = face[0] + (bx - Rings.HEAD_C) * face[2] + ey.x + ey.lookX;
        float theta = clamp(ox / hw, -1.15f, 1.15f);
        float total = theta + yaw;
        float cn = (float) Math.cos(total);
        if (cn <= 0.02f) return;                       // 转到背面，隐藏

        float ex = cx0 + hw * (float) Math.sin(total) * 0.985f;
        float dyN = (ey0 - Rings.HEAD_C) / 130f;
        float fy = (float) Math.sqrt(1 - dyN * dyN * 0.22f);

        canvas.save();
        canvas.translate(ex, ey0);
        if (ey.rotate != 0) canvas.rotate(ey.rotate);
        canvas.scale(sxBase * cn, sy * fy);
        canvas.translate(-bx, -by);

        eyePath.rewind();
        eyePath.moveTo(ring[0], ring[1]);
        for (int i = 2; i < ring.length; i += 2) eyePath.lineTo(ring[i], ring[i + 1]);
        eyePath.close();

        if (sketch > 0.5f) {
            eyePaint.setStyle(Paint.Style.STROKE);
            eyePaint.setStrokeWidth(1.6f / Math.max(sxBase * cn, 0.05f));
            eyePaint.setColor(ey.color);
        } else {
            eyePaint.setStyle(Paint.Style.FILL);
            eyePaint.setColor(ey.color);
        }
        canvas.drawPath(eyePath, eyePaint);
        canvas.restore();
    }

    private void stepConfetti(float d) {
        for (int i = pieceN - 1; i >= 0; i--) {
            Piece p = pieces[i];
            p.life += d;
            if (p.life >= p.max) {
                pieces[i] = pieces[--pieceN];
                pieces[pieceN] = p;
                continue;
            }
            p.x += p.vx * d;
            p.y += p.vy * d;
            float drag = (float) Math.pow(0.94, 60 * d);
            p.vx *= drag;
            p.vy = p.vy * drag + 40 * d;
            p.rot += p.vr * d;
        }
    }

    // ---------------- 几何预计算 ----------------

    private void buildHeadPath() {
        float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
        for (int i = 0; i < headRing.length; i += 2) {
            float x = headRing[i], y = headRing[i + 1];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        headBx = minX; headBy = minY; headBw = maxX - minX; headBh = maxY - minY;
        headPath.rewind();
        headPath.moveTo(headRing[0], headRing[1]);
        for (int i = 2; i < headRing.length; i += 2) headPath.lineTo(headRing[i], headRing[i + 1]);
        headPath.close();
    }

    /** 每 2px 一行的 [minX,maxX]，供眼睛贴合任意身体轮廓（对齐 ball.js buildSil） */
    private void buildSilhouette() {
        silMinY = 1e9f; silMaxY = -1e9f;
        for (int i = 1; i < headRing.length; i += 2) {
            if (headRing[i] < silMinY) silMinY = headRing[i];
            if (headRing[i] > silMaxY) silMaxY = headRing[i];
        }
        int rows = (int) Math.ceil((silMaxY - silMinY) / SIL_STEP) + 1;
        silRows = new float[rows][];
        int n = headRing.length / 2;
        for (int r = 0; r < rows; r++) {
            float y = silMinY + r * SIL_STEP;
            float lo = 1e9f, hi = -1e9f;
            for (int e = 0; e < n; e++) {
                int f = (e + 1) % n;
                float y0 = headRing[e * 2 + 1], y1 = headRing[f * 2 + 1];
                if ((y0 <= y && y1 >= y) || (y1 <= y && y0 >= y)) {
                    float t = y1 == y0 ? 0 : (y - y0) / (y1 - y0);
                    float x = headRing[e * 2] + (headRing[f * 2] - headRing[e * 2]) * t;
                    if (x < lo) lo = x;
                    if (x > hi) hi = x;
                }
            }
            if (lo > hi) { lo = Rings.HEAD_C - 4; hi = Rings.HEAD_C + 4; }
            silRows[r] = new float[]{ lo, hi };
        }
    }

    private float[] silAt(float y) {
        int r = Math.round((clamp(y, silMinY, silMaxY) - silMinY) / SIL_STEP);
        if (r < 0) r = 0;
        if (r >= silRows.length) r = silRows.length - 1;
        return silRows[r];
    }

    // ---------------- 小工具 ----------------

    private static final class Spring {
        float x, v, t;
        Spring(float v0) { x = v0; t = v0; }
    }

    private static void springStep(Spring s, float w, float z, float d) {
        s.v += (-2 * z * w * s.v - w * w * (s.x - s.t)) * d;
        s.x += s.v * d;
        if (Float.isNaN(s.x) || Float.isInfinite(s.x) || Float.isNaN(s.v) || Float.isInfinite(s.v)) {
            s.x = s.t; s.v = 0;
        }
    }

    private static void lerpInto(float[] a, float[] b, float[] out, float t) {
        for (int i = 0; i < a.length; i++) out[i] = a[i] + (b[i] - a[i]) * t;
    }

    private static void lerpPose(EmotionDef.Pose a, EmotionDef.Pose b, float t, EmotionDef.Pose o) {
        lerpBody(a.body, b.body, t, o.body);
        lerpEye(a.left, b.left, t, o.left);
        lerpEye(a.right, b.right, t, o.right);
        o.ringL = b.ringL;
        o.ringR = b.ringR;
    }

    private static void lerpBody(EmotionDef.Body a, EmotionDef.Body b, float t, EmotionDef.Body o) {
        o.x = a.x + (b.x - a.x) * t;
        o.y = a.y + (b.y - a.y) * t;
        o.scale = a.scale + (b.scale - a.scale) * t;
        o.rotate = a.rotate + (b.rotate - a.rotate) * t;
        o.breathe = a.breathe + (b.breathe - a.breathe) * t;
        o.ribbons = a.ribbons + (b.ribbons - a.ribbons) * t;
        o.confetti = a.confetti + (b.confetti - a.confetti) * t;
        o.sketch = a.sketch + (b.sketch - a.sketch) * t;
        o.zzz = a.zzz + (b.zzz - a.zzz) * t;
        o.orbit = a.orbit + (b.orbit - a.orbit) * t;
        o.yaw = a.yaw + (b.yaw - a.yaw) * t;
        o.color = Colorx.lerp(a.color, b.color, t);
    }

    private static void lerpEye(EmotionDef.Eye a, EmotionDef.Eye b, float t, EmotionDef.Eye o) {
        o.x = a.x + (b.x - a.x) * t;
        o.y = a.y + (b.y - a.y) * t;
        o.scaleX = a.scaleX + (b.scaleX - a.scaleX) * t;
        o.scaleY = a.scaleY + (b.scaleY - a.scaleY) * t;
        o.rotate = a.rotate + (b.rotate - a.rotate) * t;
        o.open = a.open + (b.open - a.open) * t;
        o.lookX = a.lookX + (b.lookX - a.lookX) * t;
        o.lookY = a.lookY + (b.lookY - a.lookY) * t;
        o.color = Colorx.lerp(a.color, b.color, t);
    }

    private static float clamp(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    private static float easeInOutCubic(float t) {
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    private static float rand(float a, float b) {
        return a + (float) (Math.random() * (b - a));
    }

    private static long rand(long a, long b) {
        return a + (long) (Math.random() * (b - a));
    }
}
