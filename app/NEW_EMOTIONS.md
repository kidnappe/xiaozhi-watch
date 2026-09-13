# 情绪球新增表情（id 50–57）· 设计与接入文档

> 2026-09-13 · Proma 子任务产出。机器可读定义在 `app/new_emotions.json`（即生成管线源文件），
> 本文档解释"为什么是这 8 张、为什么是这些参数、怎么接进去的、还剩什么没验"。

---

## 0. 引擎能力红线（读 `EmotionBallView.java` / `EmotionDef.java` 逐行求证，不是猜）

新增表情只允许使用引擎**真实消费**的能力。三个关键事实：

1. **`sequence` 在手表端是死代码**。`EmotionDef.parse()` 会解析 `frames`/`settle`，但
   `compose()`/`drawPose()` 没有任何一处读取 `def.frames`（grep 零命中）。现有表情里的
   sequence 关键帧（01/07/13/21/31/33/34/38）在真机上实际只等效于 base 姿态 + transition 渐变。
   **所以新表情一律不写 sequence**；"渐变入场"由较长的 `transition` 本身承担
   （过渡期 prev→base 数值插值，颜色自然变化，如 21 的变红其实是 base 色 + 300ms lerp）。
2. **`openness` 会把 `open` 属性动画的幅度一起乘小**。`compose()` 顺序是：anim 先加到
   `pose.open`，之后才 `× openS`（openS 由 openness 驱动）。想"半阖眼 + 周期性颤睫"
   （53 无语凝噎）不能靠 `openness:0.35` + open 动画，必须把下垂写进 `eyes.both.open` 基值、
   `openness` 留 1。这是本次设计里唯一容易静默做错的坑。
3. **一次性入场事件**：`setEmotion` 入口检查 `body.ribbons>0` → 触发一次自旋（yaw 弹簧，
   ≥1 转两圈）、`body.confetti>0` → `burst(20)` 撒花。常驻 orbit 环带与 48 点彩带拖尾已在移植时
   剔除（省电考虑，见 EmotionBallView 顶注）。`body.sketch>0.5` 线稿模式引擎支持但现有 32 张没用过，
   本次不启用（理由见 §5）。

每帧安全的动效原语：`sine` / `pulse` / `jitter(可 decay)` / `scan` / `glance(tanh 撇视)` /
`blink(定节奏眨睫)`；弹簧全部数值步积分，无每帧对象/渐变重建。渲染上限 30fps
（`FRAME_MS=33`）。

小屏高对比结论沿用 XiaozhiEmotion 头部注释的教训：**pool 眼环微调肉眼难辨，每张脸必须有
体色 / 尺寸 / 大角度歪头 / 眯眼 / 节奏动画这类主锚点**。以下 8 张全部满足。

---

## 1. 八张总表

| id | 名字 | 触发接入（key → 来源） | 一句话视觉 | 高对比主锚点 |
|----|------|------|-----------|--------------|
| 50 | 心疼安抚 | `LocalEmotion` crying → **soothe** | 歪头低头前倾、左眼眯起心疼、1.6s 节奏"轻拍"起伏 | 暖玫瑰灰脸 + 持续安抚节奏 |
| 51 | 同仇敌忾 | angry → **indignant** | **暗砖红**脸、怒目斜眼下压、挺胸深呼吸微颤 | 体色（沉稳暗红）+ 镜像怒目 |
| 52 | 悬心担忧 | fearful → **concerned** | 屏息定格、圆睁大眼上瞟、左眼更高"挑眉"、高频浅眨眼 | 冷灰脸 + 屏息静止 + 快眨眼 |
| 53 | 无语凝噎 | sad → **speechless** | 灰脸下沉、双眼半阖成缝、每 2.8s 哽咽式颤睫 | 极限半阖 + 灰化 + 颤睫节奏 |
| 54 | 凑近细听 | stt 空转写 → **@unclear** | 身体放大 1.06 上提、歪头、右眼眯缝打量、左右轻晃 | 整体放大（凑近感）+ 单眼眯 |
| 55 | 卡壳冒烟 | 12s 看门狗超时 → **@stuck** | 对眼+一大一小眼、眼环 0.7~1.1s 高频翻跳、烟灰脸抖动 | 对眼异形 + 高频 morph + 抖 |
| 56 | 四处张望 | 待机氛围池 AMB_ALERT | 头 ±5° 慢摆 + 目光 ±12 单位来回扫、偶有弹跳 | 大摆头 + 全幅扫视 |
| 57 | 偷瞄装没事 | AMB_ALERT + AMB_READY | 快速斜瞟一眼→回正瞬间"没事人"眨眼、头小幅跟转 | 大幅斜瞟 + 同步眨 |

与旧共情映射的关系：angry/crying/sad/fearful 此前全部 → 12 失落，现分流到 51/50/53/52。
**12 本身不动**，仍服务于服务端 llm 镜像（服务器说 sad → 球 12，语义未变）；
`XiaozhiEmotion` 原有 21 种 emotion 映射一条未改，只新增上表 6 个 key。

---

## 2. 逐张设计理由

### 50 心疼安抚（soothe）—— 对"你哭"
- **隐喻**：人手拍对方背安抚的节奏 → `body.y` sine amp 2.8 / period 1600ms 的持续起伏
  （1.6s ≈ 一次"拍-拍"，比呼吸 3.6s 快一档，读作"主动安抚"而非呼吸）；`eyes.y` sine
  同周期 phase 0.5 → 眼睛滞后半拍跟随，避免整体刚性平移的机械感。
- **心疼脸**：左眼 `open:0.62`（眯起心疼）+ 右眼 `scaleY:1.06`（另一眼瞪大观察你的状态）
  —— 不对称眼在小屏上是最容易被读出的"表情变化"之一（先例：20 困惑）。
- `rotate:-7` 歪头 + `y:5` 下沉 = 低头靠近；色 #F3E7E2（暖玫瑰灰，比 14 害羞 #F4D3D0 淡一档，
  避免读成"脸红"）。pool [10,1] 聆听环："我在听你"。`openness:0.88` 柔化目光。
- **与 12 的区分**：12 = 亮色 + 静止 + 双缩眼下垂（丧）；50 = 暖色 + 持续节奏起伏 + 歪头前倾（照顾）。

### 51 同仇敌忾（indignant）—— 对"你生气"
- 语义是"我站你这边、火压着"，必须与 21（球自己生气）拉开三个维度：
  **色**（#E4574A 亮红 → #B95A4E 暗砖红，同色相降明度降饱和）；
  **方向**（21 身体 y:+1 往下压 → 51 y:-2 + scale:1.02 往上挺=为对方撑腰）；
  **节奏**（21 jitter 1.1/7 高频抖 → 51 breathe 0.018 深呼吸 + jitter 0.7/4 低频忍）。
- 怒意载体：pool [7,16] 怒目环 + 双眼镜像 `rotate ±10°`（左+10 右-10 = 内角下压外角上扬，
  即"皱眉"——引擎没眉毛，眼旋转是唯一手段，34 出错已验证 rotate 渲染生效）
  + `openness 0.78`/`scaleY 0.92` 眯缝。
- 300ms transition 快速"挺出来"，4~7.5s 慢眨眼 = 稳定不慌乱。

### 52 悬心担忧（concerned）—— 对"你害怕/担心"
- **屏息**：`breathe:0.003`（现有表情最低也 0.004~0.005，这里配合 `y:-3` 悬高 = 一口气提着的定格）。
- 担忧注视：pool [3,21] 圆睁环 + `lookY:-2` 上瞟（看你的脸）+ 左眼 `scaleY:1.12` / 右 0.98
  （单侧挑眉）+ `blinkMs [1400,2800]` —— 快而浅的眨眼频率是"不安"最本能的生理信号。
- 冷灰 #E9E6DF = 脸色发白；`jitter amp 1.0 speed 5` 低频细颤（比 17 慌张的 6/11 慢一半——
  慌是为自己，担忧是为对方，动作要"收着"）。
- 与 13 惊讶（暖脸弹大、无歪头无变色）、17 慌张（眼乱晃身体同晃）锚点全部错开。

### 53 无语凝噎（speechless）—— 对"你难过"
- 烈度阶梯设计：12（轻度低落，还能看着你）→ 53（重到说不出话）。
- 半阖用 `eyes.both.open:0.35` 基值而非 openness（§0 坑 2），pool [13,4] 细线眼环叠乘后
  成"重帘缝眼"；`blink` 型 anim（interval 2800/dur 650/depth 0.3）= 每 2.8s 一次喉头哽咽时
  眼皮猛挤一下又回到半阖——固定周期的微抽比全闭眼更能传达"忍着的难过"。
- `gaze:false` + `blinkMs:null` 关掉全部自发漂移与正常眨眼 = 凝滞感；唯一动的是
  `rotate` sine amp 1.8/period 5200 的极慢晃（被难过摇到了）。900ms 长 transition：灰下去、
  塌下去都是渐变。
- 风险提示（验证清单里单列）：此张静止成分多，小方屏 30fps 下可能读作"死机脸"；若真机观感发闷，
  候选回调是 rotate amp 1.8→2.6 或 blink interval 2800→2200。

### 54 凑近细听（unclear）—— 对"没听清"
- 小方屏上"凑近"唯一可信的手段是 `scale:1.06` + `y:-5`（向面部方向放大上提）；配
  `rotate:-6` 歪头、`x` sine amp 2.5/2400ms 左右小幅晃（找角度）、右眼 `open:0.45` 眯缝打量
  左眼 1.05 —— 经典"侧头眯一只眼细听"。pool [10,1] 聆听环延续"我在听"。
- 与 16 专注（双眼内聚 x±4、身体不动）区分：16 是"认真听"，54 是"没听清"。

### 55 卡壳冒烟（stuck）—— 对"12 秒服务端没回话"
- 引擎无烟雾粒子；"冒烟"转译为三件套：烟灰脸 #E4E1DB + 身体抖动（`x` jitter 1.6/10 持续，
  无 decay，对照 17 用 6/11 抖手、这里抖全身）+ `rotate` sine 2.6/1300ms 抽风摆头。
- **死机眼神**：对眼（左 `lookX:+7` 右 `-7`，向鼻内聚）+ 左眼 1.22 / 右眼 0.72 大小眼，
  `openness:1.08` 瞪到最大。
- "翻找感"：pool [3,21] + `poolMs [650,1100]` + `poolSpeed:10` —— 眼环形状高频翻跳，
  40 检索已验证该机制在 1000~1800ms 周期下的弹簧追随性，650ms 更激进一档（gfxinfo 见 §4，
  janky 1.03% 无异常）。
- `blinkMs:null`：瞪着不眨眼才是卡壳；180ms 硬切入场。

### 56 四处张望（restless）—— 待机微叙事 ①
- "等得无聊左右看"：`rotate` sine amp 5/period 5200（±5° 慢摇头，18 无奈用 10° 歪头但无摆动
  节奏）+ `lookX` **scan** amp 12/period 2800 —— 40 检索用 scan 0.7s 急促来回，这里 2.8s
  悠闲来回，节奏差异即性格差异。pool 用 40 同族扫读环 [15,9,20,12,18] 但慢一档。
- `antics:true` 叠加随机弹跳/自旋，池子轮换时真机表现为"一会儿bounce一会儿看东看西"。
- 触发后 9~17s 氛围 dwell 内完整播 2~3 个 scan 周期，叙事成立。

### 57 偷瞄装没事（peek）—— 待机微叙事 ②
- glance 原语（`tanh(2.8·sin)`）的波形天然就是"快速撇过去 → 顿一下 → 快速回正"，
  是引擎里唯一接近"瞟"的曲线（02/10 已验证）：`lookX` glance amp 8 + 基值 4 →
  视线在 +12/-4 单位间撇动（≈17px 位移，小屏可辨）。
- "装没事"眨：`blink` 型 anim（interval 4600/dur 420/depth 1.0/phaseMs 1300）——
  诚实说明：anim 的相位含 `seed*97` 随机偏移，**做不到**逐帧对齐"回正瞬间眨"，
  实际观感是"同 4.6s 周期的心虚瞟 + 抽冷子一次快速眨"，微叙事靠节奏巧合而非剧本。
- pool [24,14,5]：24 羞怯环（原版"羞怯"专用）+ 斜眼环族，`lookY:2` 低视线不与你正视；
  头 `rotate` sine amp 3 同周期小幅跟转。
- 与 03 好奇（lookY 上瞟 ±2.4、身体歪 4° 定住）区分：03 是"对世界好奇"，57 是"对你做贼心虚"。

---

## 3. 风格一致性检查

- 色板延续 #EEEBE4 系暖白灰基底：50 #F3E7E2 / 52 #E9E6DF / 53 #E7E5E1 / 55 #E4E1DB 都是
  基底的明度微调；唯二饱和色是 51 #B95A4E（暗砖红，与 21 #E4574A、34 #E25B5B 拉开明度色相距离）
  和既有 14 粉。点缀色克制 ✓
- 圆润小生物性格载体：全部无表情包式符号（无汗滴/无感叹号），情绪全靠姿态、眼神方向、
  节奏、体色表达 ✓
- 数值量程全部落在现有 32 张已验证区间内（lookX≤12=40、rotate≤10=18、眼 scale≤1.25≈13×1.45
  瞬时值以下、breathe 0.003~0.018=00/15 区间、transition 180~900=既有区间）。未发明新数值档位。

## 4. 改动清单与已验证项

**改动文件**（6 个，全部最小增量）：
| 文件 | 改动 |
|---|---|
| `app/new_emotions.json` | **新增**。8 张定义 + `desc` 人读字段（生成时过 KEEP 白名单剔除） |
| `tools/gen_eb_java.js` | 扩展：SEED 精简后合并 `app/new_emotions.json`；校验 id 必须 50+ 段/不冲突/有 name；自检扩展为逐自定义 id 往返比对；注释动态计数 |
| `app/src/com/xiaozhi/watch/Emotions.java` | 仅由管线再生成：32→40 种，现有 32 条逐字节不变（自检往返验证） |
| `app/src/com/xiaozhi/watch/XiaozhiEmotion.java` | 仅新增 6 个 key（soothe/indignant/concerned/speechless/@unclear/@stuck），原映射零改动 |
| `app/src/com/xiaozhi/watch/LocalEmotion.java` | EMPATHY 4 条目标 rewire（crying→soothe、angry→indignant、sad→speechless、fearful→concerned），注释更新 |
| `app/src/com/xiaozhi/watch/XiaozhiClient.java` | 2 行级触发：stt 空文本→`onEmotion("@unclear")`；12s 看门狗超时→`onEmotion("@stuck")` |
| `app/src/com/xiaozhi/watch/MainActivity.java` | AMB_ALERT 池加 56/57、AMB_READY 加 57；`--es emo loop` 扩为 12 张（含 4 张共情新脸对照 12/21） |

**已验证（真机 cbbbb74b，2026-09-13 17:55）**：
- [x] 构建通过：`build.ps1` 7 步全绿，app.apk 352,659B（原 ~344KB，+8 表情 JSON 合理增量）
- [x] 载入：`情绪球：已载入 40 种表情`
- [x] 8 张逐一 `--es emo 50..57` 演示：`setEmotion` 日志全部命中正确 id+名字+pool，无
      `未知表情` 告警、无异常/崩溃（logcat 全扫）
- [x] 管线唯一入口：手改 Emotions.java 被禁止的约束保持（改的都是源文件）
- [x] 性能无回退：55 janky 1.03% / 56 1.55% ≈ 既有 40（1.54%）同带；对照 33 撒花 3.12% 反而更高
- [x] Rings.java 数据路径零改动（生成器只动了 Emotions 段）

**视觉验收（主观观感）未做**：充电时 SysUI 覆盖窗挡截屏（真机已知限制：充电时被 SysUI 覆盖窗遮挡），
本轮验证以日志/帧率客观数据为准。**下面 §6 是给用户的目检清单。**

## 5. 明确不做 / 留给后续

- **"被打断懵一下"未做**：该事件已被 `@abort → 41 停止终止` 占用，一个事件挂两张脸需要
  两级时序（懵 0.5s → 41），引擎无 `sequence.settle` 做不到自动回落（现在 MainActivity
  "07→02" 是手动 postDelayed 补的，给 abort 再加一套时序收益低）。41 已承担该语义。
- **`sketch` 线稿模式未动用**：引擎支持（身体/眼改描边），适合未来"魂掉了"类脸；但描边眼下
  小屏上眼部神态读不出，"懵"会糊。若父任务想启用，建议先给 sketch 眼保留实心填充再验。
- 对父任务的两条观察/建议（未动代码）：
  1. `stopTalking` 发 `li.onEmotion("@thinking")`，但 `XiaozhiEmotion` 无此 key →
     实际回落 02 待机。若想"松手后球陪你思考"，加一行 `MAP.put("@thinking","30")` 即可
     （语义增量，不违反现有映射；本次未擅自加）。
  2. 若未来恢复入场戏剧性（settle/frames），改动点在 `compose()` 增加 ~25 行最简消费
     （`t≥frames[last].at` 时按 settle=base/hold/next 三态回落），届时 53/57 可以做更精确的剧本。

## 6. 遗留验证清单（按优先级）

1. **视觉目检**（需拔 USB 或佩戴状态看表，或拆充电底座观察）：
   ```bash
   adb shell am force-stop com.xiaozhi.watch
   adb shell am start -n com.xiaozhi.watch/.MainActivity --es emo 50   # 50~57 逐张
   adb shell am start -n com.xiaozhi.watch/.MainActivity --es emo loop # 12 张对照循环
   ```
   逐张确认：51 的暗红与怒目在小屏是否读得出"克制"（不是"更凶"）；53 是否发闷（发闷→§2 回调方案）；
   57 的瞟-眨节奏是否成立；55 对眼是否"滑稽大于故障"（若是，right scale 0.72→0.8 收一点）。
2. **共情分流端到端**（对着表说，`logcat -s XZW` 看 `共情回应：用户'x' → 球'y'`）：
   "呜呜呜好委屈"→soothe(50)；"气死我了"→indignant(51)；"我有点害怕"→concerned(52)；
   "我心里好难受"→speechless(53)。预期 4 张脸不再撞车。
3. **@unclear/@stuck 触发**：按住不说话只喂噪声→松手，若服务端回空 stt 应见 54；
   断网/拔路由器后说一句话→12s 看门狗应见 55 再回待机。
4. **待机氛围观感**：静置 30s~3min，确认 AMB 轮换 5~6 拍内出现 56/57 且与弹跳 antics 不打架。
5. 若目检需回调参数：只改 `app/new_emotions.json` → `node tools/gen_eb_java.js` →
   `app/build.ps1` → 重装。Emotions.java 保持生成产物身份。
