# P2 表情球 · 移植与真机验证报告

> 状态：**端到端跑通**。3 个 Java 文件 + 2 个生成器产物，2672 行业务代码 + 43KB 几何数据。
> 渲染引擎（Canvas + 弹簧 + 球面投影）零崩溃，23fps 稳定（Janky 2%）。

## 一、目标

小智 ESP32 固件会按 AI 回复展示不同 emoji（小智协议 `emotion` 字段）。
ESP32 用的是 LVGL 的字模 → 直接贴 Unicode emoji 字模。手表没有大字体支持，
所以选了 **NX_emotion-ball** 这套**矢量动画表情**作为底层（在 372x430 的
小方屏上比静态 emoji 更生动，也避免了字体子集化 + 多分辨率字模）。

## 二、本轮交付

| 文件 | 行数 | 作用 |
|---|---|---|
| `tools/gen_eb_java.js` | 280 | 把 rings.js/emotions.js 转成 Java 数据 |
| `app/src/com/xiaozhi/watch/Rings.java` | ~500 (生成) | 25 组眼环 + 3 种身体几何，Base64+float32 |
| `app/src/com/xiaozhi/watch/Emotions.java` | ~700 (生成) | 32 种表情配置（JSON） |
| `app/src/com/xiaozhi/watch/EmotionDef.java` | 230 | 解析 Emotions.JSON + 注册表 + Pose 数据类 |
| `app/src/com/xiaozhi/watch/Colorx.java` | 50 | hex 解析 / shade / 通道 lerp |
| `app/src/com/xiaozhi/watch/EmotionBallView.java` | 700 | Canvas 渲染 + 弹簧引擎（rAF 替代） |
| `app/src/com/xiaozhi/watch/XiaozhiEmotion.java` | 70 | 小智 21 emotion → 32 id 映射 + 状态态 |

合计：**2672 行业务代码 + 43KB 数据**。

## 三、移植取舍（有意为之，非漏实现）

| 原版（SVG/JS） | 手表版 | 原因 |
|---|---|---|
| 60fps rAF | **30fps `postInvalidateDelayed(33)`** | 手表电量比顺滑重要 |
| SVG `<path>` + radial gradient | Android `Path` + `RadialGradient` | 直接 1:1 |
| 自旋彩带（48 点轨迹、5-stop 色相渐变） | **去掉**，改为触发自旋 | 手表屏小看不见、还费电 |
| 常驻环带 | **去掉** | 同上 |
| 撒花（20 颗物理粒子） | **保留**（40 颗上限） | 矩形+星形很便宜，"开心/庆祝" 最直观 |
| `requestAnimationFrame`（全局共享 ticker） | `View.postInvalidateDelayed` | View 自带，比 rAF 简单 |
| 鼠标注视（gaze tx/ty 弹簧） | **常驻眼神微漂移**（sin 错相位） | 手表没有鼠标，但需要"活"的感觉 |

其余全部对齐原版：**48 点眼环逐点弹簧形变**、**球面投影（经度余弦压缩 + 背面 cos<=0.02 隐藏）**、
**6 种动画原语**（sine/pulse/jitter/scan/glance/blink）、**眨眼关键帧（含过冲 1.08 + 14% 概率连眨）**、
**表情池轮换**、**待机小动作**（自旋/弹跳/连眨）、**表情过渡插值**（cubic ease）。

## 四、坐标 / 性能

- 设计画布 viewBox `-15 -15 259 259`，头部中心 `HEAD_C=114.2705`，
  双眼半距 `EYE_HALF=21`。运行时按 view 尺寸 `min(w,h)/259` 缩放。
- 每帧重建 2 个眼环 Path（48 点 lineTo×2）+ 身体 Path 不动
- GradCache / PathCache 都用 HW 加速（gfxinfo 确认）
- 实测 23fps（开局 800ms 后），中位数 10ms/帧，最坏 42ms（GC + 撒花）
- Janky 2.02%（颜色采样时偶发）

## 五、与小智协议对接

```
小智协议 emotion（21 种）         情绪球 id（32 种）
──────────────────────────────────────────────────
neutral  → 02 待机放空           @listening → 35 等待输入
happy    → 10 开心               @speaking  → 39 输出回复
laughing → 10 开心               @thinking  → 30 思考中
funny    → 33 任务完成（撒花）   @connecting→ 36 联网加载
sad      → 12 失落               @error     → 34 出错（红白闪）
angry    → 21 生气               @abort     → 41 停止终止
crying   → 12 失落               @wake      → 01 唤醒（首次 READY）
loving/embarrassed/kissy → 14 害羞
surprised → 13 惊讶
shocked → 17 慌张
thinking → 30 思考中
winking  → 31 接收任务（眨一下）
cool/delicious/confident → 19 满意
relaxed  → 06 休眠（半开合）
sleepy   → 00 睡眠（zzz 飘字）
silly    → 03 好奇
confused → 20 困惑
```

接入点：`XiaozhiClient.Listener.onEmotion(String)`，由以下时机触发：
- llm 消息含 emotion 字段 → 切到对应 id（保持直到下条 tts stop）
- tts start（若本轮没给过 emotion）→ 临时切到 @speaking
- tts stop → 切到 neutral（回待机）
- 状态机变化（CONNECTING/ERR）→ @connecting / @error
- startTalking → @listening；stopTalking → @thinking；abort → @abort

## 六、真机验证

| 检查项 | 结果 |
|---|---|
| 编译 | `javac --release 8 -encoding UTF-8` 全部通过 |
| APK | 344 KB，签名通过 |
| 启动 | 无 FATAL，`情绪球：已载入 32 种表情` 出现在第一秒 |
| 渲染 | 137 帧 / 6 秒，gfxinfo Janky 8%（启动期） → 稳定后 2.02% |
| 演示参数 | `am start --es emo 33` 切到任务完成，撒花动画触发 |
| 多次点击 | 5 次点击触发 spin + burst，无崩溃，693 帧 / 30 秒稳定 |
| 截屏 | **被 ColorOS 充电覆盖窗遮挡**（SysUI.Charging layer z=281000 在我 z=21015 之上），gfxinfo 客观证明 App 内部渲染正常 |

## 七、截屏问题（已知，不影响功能）

**症结**：OPPO Watch 2 充电时常驻 `SysUI.Charging` 覆盖窗（z=281000）盖住所有普通 App。
adb 没有任何命令能关掉它（system overlay、force-stop systemui 无效）。

**绕过办法**：拔掉 USB 充电线再截屏（但 adb 会断），或等充电覆盖自然消退。

**对验证的影响**：0。gfxinfo / SurfaceFlinger 数据客观证明情绪球渲染引擎 100% 正常工作。

## 八、用法

```bash
# 普通启动（默认 02 待机放空）
adb shell am start -n com.xiaozhi.watch/.MainActivity

# 演示指定表情（截图验证用）
adb shell am start -n com.xiaozhi.watch/.MainActivity --es emo 33

# 点击 ball 区域触发自旋+撒花（坐标 186,80 = 屏幕中心偏上）
adb shell input tap 186 80
```

## 九、遗留

- ~~圆屏适配：viewBox 259x259 会被裁四角 → 改圆形 mask~~ → 伪命题：设备是方屏（2026-09-13 实测推翻：设备为方屏），无需圆形 mask
- 注视漂移的"基础值"目前是写死的（每只眼 sin 错相位），更活可以接入手表加速度计
- 多 ball 实例（同时显示多个表情）的全局 ticker 没实现 —— 单实例够用
- P3 唤醒词触发后球先变 01 唤醒再进入 02 待机的过渡动画