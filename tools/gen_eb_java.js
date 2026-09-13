#!/usr/bin/env node
/**
 * 从 NX_emotion-ball（情绪球原版仓库）生成手表端 Java 几何数据。
 *
 * 为什么用 Base64 而不是直接写 float 字面量：
 *   25 组 x 2 眼 x 48 点 x 2 坐标 = 4800 个 float。全写进静态初始化会撑爆
 *   <clinit> 的 64KB 方法字节码上限（javac: "code too large"）。
 *   改成"每组一个 Base64 字符串 + 运行时解码"后，clinit 只剩 25 次 ldc，安全得多。
 *
 * 用法：
 *   node tools/gen_eb_java.js
 * 输出：
 *   app/src/com/xiaozhi/watch/Rings.java
 *   app/src/com/xiaozhi/watch/Emotions.java
 *
 * Emotions 的数据来源有两级（生成管线是唯一入口，Emotions.java 禁止手改）：
 *   1) 原版基底  references/NX_emotion-ball/js/emotions.js（32 种，勿动）
 *   2) 自定义追加 app/new_emotions.json（可选，50+ 分段，见文件内 desc 字段）
 * 自定义条目与基底同 schema，生成时同样过 KEEP 白名单（desc 等人读字段会被剔除），
 * 并校验 id 必须落在 50+ 段、不与任何现有 id 冲突。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'references', 'NX_emotion-ball', 'js');
const OUT = path.join(ROOT, 'app', 'src', 'com', 'xiaozhi', 'watch', 'Rings.java');
const OUT2 = path.join(ROOT, 'app', 'src', 'com', 'xiaozhi', 'watch', 'Emotions.java');

// ---- 加载原版 rings.js（它是 window.EB_RINGS = {...} 形式）----
global.window = {};
// eslint-disable-next-line no-eval
eval(fs.readFileSync(path.join(SRC, 'rings.js'), 'utf8'));
const R = global.window.EB_RINGS;

if (!R || !R.EXPRESSIONS) {
  console.error('解析失败：没拿到 window.EB_RINGS，检查 references/NX_emotion-ball 是否完整');
  process.exit(1);
}

// ---- 编码：float32 小端 -> base64 ----
function encFloats(arr) {
  const flat = [];
  (function walk(a) {
    if (typeof a[0] === 'number') { flat.push(a[0], a[1]); return; }
    for (const x of a) walk(x);
  })(arr);
  const buf = Buffer.alloc(flat.length * 4);
  flat.forEach((v, i) => buf.writeFloatLE(v, i * 4));
  return buf.toString('base64');
}

// 长字符串按每行 100 字符切块，拼成 Java 字符串常量
function javaStr(b64, indent) {
  const CH = 100;
  const parts = [];
  for (let i = 0; i < b64.length; i += CH) parts.push(b64.slice(i, i + CH));
  return parts.map((p, i) => (i === 0 ? '' : indent) + '"' + p + '"').join('\n' + indent + '+ ');
}

const L = [];
L.push('package com.xiaozhi.watch;');
L.push('');
L.push('import java.nio.ByteBuffer;');
L.push('import java.nio.ByteOrder;');
L.push('import java.nio.FloatBuffer;');
L.push('import android.util.Base64;');
L.push('');
L.push('/**');
L.push(' * 情绪球几何数据 —— 由 tools/gen_eb_java.js 从 NX_emotion-ball/js/rings.js 自动生成。');
L.push(' * <b>请勿手改</b>，改了下次生成会丢。');
L.push(' *');
L.push(' * <p>坐标系：viewBox = ' + (-15) + ' ' + (-15) + ' 259 259（259x259 的正方形设计画布），');
L.push(' * 头部中心 HEAD_C。绘制时整体缩放到 View 尺寸即可，不用关心这里的具体数值。</p>');
L.push(' *');
L.push(' * <p>数据以 float32 小端 + Base64 存放，运行时解码。原因见 gen_eb_java.js 顶部注释');
L.push(' * （直接写字面量会撑爆 &lt;clinit&gt; 的 64KB 字节码上限）。</p>');
L.push(' */');
L.push('public final class Rings {');
L.push('');
L.push('    /** 设计画布边长（viewBox 宽高） */');
L.push('    public static final float VIEW = 259f;');
L.push('    /** viewBox 原点偏移（左上角的 x/y） */');
L.push('    public static final float VIEW_OFF = -15f;');
L.push('    /** 头部中心在设计画布里的坐标 */');
L.push('    public static final float HEAD_C = ' + R.HEAD_C + 'f;');
L.push('    /** 双眼间距的一半 */');
L.push('    public static final float EYE_HALF = ' + R.EYE_HALF + 'f;');
L.push('    /** 庆祝撒花的金色 */');
L.push('    public static final String STAR_GOLD = "' + R.STAR_GOLD + '";');
L.push('');
L.push('    /** 25 组表情眼环，每组 = [左眼环, 右眼环]，各 48 点。索引含义见 Emotions 的 pool 字段。 */');
L.push('    public static final int EXPRESSION_COUNT = ' + R.EXPRESSIONS.length + ';');
L.push('    /** 每只眼的轮廓点数 */');
L.push('    public static final int RING_POINTS = 48;');
L.push('');

// 25 组眼环，每组一个 Base64 常量
L.push('    // ---------------- 眼环数据（每组 2 眼 x 48 点 x (x,y)）----------------');
for (let i = 0; i < R.EXPRESSIONS.length; i++) {
  L.push('    private static final String RING_' + String(i).padStart(2, '0') + ' =');
  L.push('            ' + javaStr(encFloats(R.EXPRESSIONS[i]), '            ') + ';');
  L.push('');
}

L.push('    private static final String[] RING_DATA = {');
for (let i = 0; i < R.EXPRESSIONS.length; i++) {
  L.push('            RING_' + String(i).padStart(2, '0') + (i < R.EXPRESSIONS.length - 1 ? ',' : ''));
}
L.push('    };');
L.push('');

// 三种身体形状
L.push('    // ---------------- 身体轮廓 ----------------');
L.push('    public static final String[] SHAPE_KEYS = { "blob", "wedge", "gem" };');
L.push('');
for (const key of ['blob', 'wedge', 'gem']) {
  const s = R.SHAPES[key];
  const up = key.toUpperCase();
  L.push('    /** 身体形状 ' + key + ' 的轮廓（96 点） */');
  L.push('    private static final String SHAPE_' + up + ' =');
  L.push('            ' + javaStr(encFloats(s.ring), '            ') + ';');
  L.push('    /** ' + key + ' 的五官拟合参数：{ x, y, sx, sy, eye } */');
  L.push('    public static final float[] FACE_' + up + ' = { ' +
    s.face.x + 'f, ' + s.face.y + 'f, ' + s.face.sx + 'f, ' + s.face.sy + 'f, ' + s.face.eye + 'f };');
  L.push('    /** ' + key + ' 的倾斜缩放系数 */');
  L.push('    public static final float TILT_' + up + ' = ' + s.tiltScale + 'f;');
  L.push('');
}
L.push('    private static final String[] SHAPE_DATA = { SHAPE_BLOB, SHAPE_WEDGE, SHAPE_GEM };');
L.push('    public static final float[][] FACE_DATA = { FACE_BLOB, FACE_WEDGE, FACE_GEM };');
L.push('    public static final float[] TILT_DATA = { TILT_BLOB, TILT_WEDGE, TILT_GEM };');
L.push('');

// 解码 + 访问器
L.push('    /** 解码结果缓存：25 组眼环 / 3 种身体。每帧都要取，不能每次都解一遍 Base64 */');
L.push('    private static final float[][] RING_CACHE = new float[EXPRESSION_COUNT][];');
L.push('    private static final float[][][] RING_SPLIT = new float[EXPRESSION_COUNT][2][];');
L.push('    private static final float[][] SHAPE_CACHE = new float[SHAPE_DATA.length][];');
L.push('');
L.push('    /** 解码：float32 小端 Base64 -> float[] */');
L.push('    private static float[] decode(String b64) {');
L.push('        byte[] raw = Base64.decode(b64, Base64.DEFAULT);');
L.push('        FloatBuffer fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();');
L.push('        float[] out = new float[fb.remaining()];');
L.push('        fb.get(out);');
L.push('        return out;');
L.push('    }');
L.push('');
L.push('    /**');
L.push('     * 取第 index 组眼环的轮廓点。');
L.push('     *');
L.push('     * <p>首次取用才解码，之后走缓存 —— 每帧都要取，不能每次都 Base64 解一遍。</p>');
L.push('     *');
L.push('     * @param index 眼环组索引 0..24');
L.push('     * @param eye   0 = 左眼，1 = 右眼');
L.push('     * @return 长度 RING_POINTS*2 的数组，按 [x0,y0,x1,y1,...] 排布');
L.push('     */');
L.push('    public static float[] ring(int index, int eye) {');
L.push('        float[] pair = RING_CACHE[index];');
L.push('        if (pair == null) {');
L.push('            pair = decode(RING_DATA[index]);');
L.push('            RING_CACHE[index] = pair;');
L.push('        }');
L.push('        int n = RING_POINTS * 2;');
L.push('        float[] out = RING_SPLIT[index][eye];');
L.push('        if (out == null) {');
L.push('            out = new float[n];');
L.push('            System.arraycopy(pair, eye * n, out, 0, n);   // 2 眼连续存放');
L.push('            RING_SPLIT[index][eye] = out;');
L.push('        }');
L.push('        return out;');
L.push('    }');
L.push('');
L.push('    /** 取身体轮廓（96 点，[x0,y0,...]），同样带缓存 */');
L.push('    public static float[] shape(int which) {');
L.push('        float[] v = SHAPE_CACHE[which];');
L.push('        if (v == null) { v = decode(SHAPE_DATA[which]); SHAPE_CACHE[which] = v; }');
L.push('        return v;');
L.push('    }');
L.push('');
L.push('    private Rings() { }');
L.push('}');
L.push('');

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, L.join('\n'), 'utf8');

const kb = (Buffer.byteLength(L.join('\n'), 'utf8') / 1024).toFixed(1);
console.log('已生成 ' + path.relative(ROOT, OUT) + '（' + kb + ' KB，'
  + R.EXPRESSIONS.length + ' 组眼环 / ' + Object.keys(R.SHAPES).length + ' 种身体）');

// ============================================================
// 2) Emotions.java —— 32 种表情配置
//    嵌套结构翻译成 Java 字面量既啰嗦又易错，而 Android 自带 org.json，
//    所以直接内嵌 JSON、运行时解析。去掉 desc / en（手表 186x215dp 显示不下，
//    且引擎不消费），只保留引擎真正要用的字段。
// ============================================================
// eslint-disable-next-line no-eval
eval(fs.readFileSync(path.join(SRC, 'emotions.js'), 'utf8'));
const SEED = global.window.EMOTION_SEED;
if (!SEED) { console.error('解析失败：没拿到 window.EMOTION_SEED'); process.exit(1); }

const KEEP = ['id', 'name', 'group', 'transition', 'gaze', 'pool', 'poolMs', 'poolSpeed',
  'blinkMs', 'openness', 'antics', 'body', 'eyes', 'anims', 'sequence'];
const slim = SEED.map(function (e) {
  const o = {};
  for (const k of KEEP) if (e[k] !== undefined) o[k] = e[k];
  return o;
});

// ---- 追加自定义表情（app/new_emotions.json，可选）：50+ 分段，管线唯一入口 ----
const CUSTOM = path.join(ROOT, 'app', 'new_emotions.json');
const customIds = [];
if (fs.existsSync(CUSTOM)) {
  let extra;
  try {
    extra = JSON.parse(fs.readFileSync(CUSTOM, 'utf8'));
  } catch (t) {
    console.error('app/new_emotions.json 解析失败：' + t.message);
    process.exit(1);
  }
  if (!Array.isArray(extra)) { console.error('app/new_emotions.json 必须是 JSON 数组'); process.exit(1); }
  const seen = {};
  for (const e of slim) seen[e.id] = true;
  for (const e of extra) {
    if (typeof e.id !== 'string' || !/^[5-9]\d$/.test(e.id)) {
      console.error('自定义表情 id 必须是 50+ 段的两位数字字符串，收到：' + JSON.stringify(e.id)); process.exit(1);
    }
    if (seen[e.id]) { console.error('自定义表情 id 冲突：' + e.id); process.exit(1); }
    if (!e.name) { console.error('自定义表情 ' + e.id + ' 缺 name'); process.exit(1); }
    seen[e.id] = true;
    const o = {};
    for (const k of KEEP) if (e[k] !== undefined) o[k] = e[k];
    slim.push(o);
    customIds.push(e.id);
  }
  console.log('已合并自定义表情 ' + customIds.length + ' 种：[' + customIds.join(',') + ']');
}

const json = JSON.stringify(slim);
// 切成若干 Java 字符串字面量拼接（单条超 64KB 会撑爆方法字节码上限）。
// 坑：不能按固定字符数硬切 —— 若切点在 `\"` 中间，前一段末尾会留下孤立的反斜杠，
// 它把字面量的右引号转义掉 → javac 报 "未结束的字符串字面量"。所以按"转义单元"切。
function javaJsonStr(s, indent) {
  let esc = '';
  for (const ch of s) {
    const c = ch.codePointAt(0);
    if (ch === '\\') esc += '\\\\';
    else if (ch === '"') esc += '\\"';
    else if (ch === '\n') esc += '\\n';
    else if (ch === '\r') esc += '\\r';
    else if (c < 0x20 || c > 0x7e) esc += '\\u' + ('0000' + c.toString(16)).slice(-4);
    else esc += ch;
  }
  const CH = 100;
  const parts = [];
  for (let i = 0; i < esc.length;) {
    let n = 0, j = i;
    while (n < CH && j < esc.length) {
      if (esc[j] === '\\') j += (esc[j + 1] === 'u' ? 6 : 2); else j += 1;
      n++;
    }
    parts.push(esc.slice(i, j));
    i = j;
  }
  return parts.map(function (p, i) {
    return (i === 0 ? '' : indent) + '"' + p + '"';
  }).join('\n' + indent + '+ ');
}

const M = [];
M.push('package com.xiaozhi.watch;');
M.push('');
M.push('/**');
M.push(' * 情绪球 ' + slim.length + ' 种表情配置 —— 由 tools/gen_eb_java.js 从 NX_emotion-ball/js/emotions.js');
M.push(' * （基底 ' + SEED.length + ' 种）+ app/new_emotions.json（自定义 ' + customIds.length + ' 种）自动生成。');
M.push(' * <b>请勿手改</b>，要改表情请改源文件后重新生成。');
M.push(' *');
M.push(' * <p>嵌套结构翻译成 Java 字面量既啰嗦又易错，而 Android 自带 org.json，');
M.push(' * 所以这里直接内嵌 JSON、运行时解析（只在首次取用时解析一次）。</p>');
M.push(' *');
M.push(' * <p>已剔除 desc / en 文案：手表屏幕只有 186x215dp，显示不下，引擎也不消费。</p>');
M.push(' *');
M.push(' * <p>ID 分段：00-09 生命周期 · 10-29 情绪反应 · 30-49 代理工作状态 · 50+ 自定义。</p>');
M.push(' */');
M.push('public final class Emotions {');
M.push('');
M.push('    /** ' + slim.length + ' 种表情的完整配置（JSON 数组，含 50+ 自定义段） */');
M.push('    public static final String JSON =');
M.push('            ' + javaJsonStr(json, '            ') + ';');
M.push('');
M.push('    private Emotions() { }');
M.push('}');
M.push('');

fs.writeFileSync(OUT2, M.join('\n'), 'utf8');
const kb2 = (Buffer.byteLength(M.join('\n'), 'utf8') / 1024).toFixed(1);
console.log('已生成 ' + path.relative(ROOT, OUT2) + '（' + kb2 + ' KB，' + slim.length + ' 种表情）');

// ---- 自检：从生成的 Java 源码反向还原 JSON，确认转义没把数据弄坏 ----
// （转义是这类"生成代码"最容易出错的地方，必须每次生成都校验，而不是等运行时崩）
(function selfCheck() {
  const src = fs.readFileSync(OUT2, 'utf8');
  // 逐字符扫 Java 字符串字面量（正则方案会被切片边界搞乱，不可靠）
  const region = src.slice(src.indexOf('String JSON ='), src.lastIndexOf('};'));
  const lits = [];
  for (let i = 0; i < region.length; i++) {
    if (region[i] !== '"') continue;
    let buf = '', j = i + 1;
    while (j < region.length && region[j] !== '"') {
      if (region[j] === '\\') {
        const e = region[j + 1];
        if (e === 'u') { buf += String.fromCharCode(parseInt(region.substr(j + 2, 4), 16)); j += 6; }
        else { buf += ({ n: '\n', r: '\r', t: '\t', b: '\b', f: '\f' })[e] || e; j += 2; }
      } else { buf += region[j]; j++; }
    }
    lits.push(buf);
    i = j;
  }
  if (!lits.length) { console.error('自检失败：没解析出任何字符串字面量'); process.exit(1); }
  const arr = JSON.parse(lits.join(''));
  if (arr.length !== slim.length) {
    console.error('自检失败：条数不符 ' + arr.length + ' != ' + slim.length);
    process.exit(1);
  }
  // 抽样比对几何相关字段，确保数字没被转义破坏
  const a0 = arr.find(function (e) { return e.id === '00'; });
  if (!a0 || a0.pool.join(',') !== '13,22,4' || a0.openness !== 0.08) {
    console.error('自检失败：00 号表情数据异常', a0);
    process.exit(1);
  }
  // 自定义段：逐个确认存在、且关键姿态字段没被转义破坏（抽 body 首个数值键比对）
  for (const cid of customIds) {
    const got = arr.find(function (e) { return e.id === cid; });
    const want = slim.find(function (e) { return e.id === cid; });
    if (!got) { console.error('自检失败：自定义表情 ' + cid + ' 丢失'); process.exit(1); }
    if (JSON.stringify(got) !== JSON.stringify(want)) {
      console.error('自检失败：自定义表情 ' + cid + ' 往返不一致', got, want); process.exit(1);
    }
  }
  console.log('自检通过：' + arr.length + ' 种表情往返一致（含自定义 ' + customIds.join(',') + '），'
    + '抽样 00 号 pool=[' + a0.pool + '] openness=' + a0.openness + ' color=' + a0.body.color);
})();
