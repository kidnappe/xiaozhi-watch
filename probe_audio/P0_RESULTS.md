# P0 探针实测结论（OPPO Watch 2 / OWW202）

> 真机实测：2026-09-12 23:55 ~ 00:05，adb 侧载 `probe_audio/out/app.apk`（com.xiaozhi.probe）自动跑全套。
> 原始证据：`probe_audio/pulled/probe.log` + 12 个 wav + `mr_test.3gp`。
> 结论都在这里，早期方案文档里与本文件冲突的，**以本文件为准**。

---

## 一、一页结论

| # | 问题 | 结论 | 判据（原始证据） |
|---|---|---|---|
| **R1** | ColorOS 放不放行第三方 App 录音 | ✅ **放行，能录到真声音**（但有坑，见下） | 矩阵 6/6 次 `startRecording()` 返回 `getRecordingState=3`；MIC 源 peak 355~468（有效声），HAL 侧 `in_snd_device(65: handset-mic)`、`start_input_stream` 正常 |
| **R2** | 纯 Java Opus 够不够快 | ✅ **够用，A 方案定案，不需要 JNI** | 编码 avg **9.27ms**/帧（预算 60ms）p95 10.46ms max 15.88ms；解码 avg 1.39ms；单核占用 ~15%，RTF **6.5×** |
| **R3** | 表内喇叭能不能出声 | ✅ **能，而且响亮** —— 但**只有媒体通路** | 回环：媒体通路 1200Hz 比邻近频点高 **+81.2 dB**（peak 2940）；通话通路 **−29.3 dB**（无音调，peak 32768 削顶噪声） |
| **R4** | wss 能不能通 / header 有没有带上 | ✅ **全通** | `TLSv1.2 ECDHE_RSA_AES128_GCM`，证书 `CN=api.tenclass.net`（GeoTrust G2/DigiCert，有效至 2026-11-10），`HTTP 101 Switching Protocols`，`connectBlocking=true` |
| **R9** | Device-Id 自造+持久化 | ✅ | `02:53:28:8D:2A:72` 跨 4 次启动完全一致 |
| 附 | 系统 Opus 编解码器 | 只有解码器，**没有编码器** | `OMX.google.opus.decoder type=audio/opus`，编码器数=0 → 印证自带上行编码器的必要性 |

**一句话**：三个"能不能"全部是"能"。方案不用降级到 JNI libopus，也不用改成 PCM。**但有两个参数必须按实测改**（录音源、播放通路），照原方案写会翻车。

---

## 二、必须改的两个参数（重要）

### ① 录音源：`MIC`，不是 `VOICE_COMMUNICATION`

同一条麦克风，四个 source 的实测电平（每档 1~2 秒）：

| source | 16k peak | 判定 |
|---|---|---|
| **`MIC`(1)** | **468 / 355(带AEC) / 442(48k)** | ✅ 唯一稳定拿到真声音的 |
| `DEFAULT`(0) | 75 | 🟡 勉强 |
| `VOICE_RECOGNITION`(6) | 91 / 9 | ❌ 基本是聋的 |
| `VOICE_COMMUNICATION`(7) | 10 / 29 / 157(带AEC) | ❌ 不加 AEC 几乎是聋的 |

`VOICE_COMMUNICATION` 在这块表上被激进处理（增益门控/降噪），**不加 AEC 时 peak 只有 10**（数字静音级）。
PLAN §4.4 原写"优先 VOICE_COMMUNICATION"——**作废，改 `MIC`**。

### ② 播放通路：`USAGE_MEDIA`（STREAM_MUSIC），**不要** `MODE_IN_COMMUNICATION`

回环测试（放 1200Hz 同时用 MIC 录，看录音里该频点比邻近频点高多少）：

| 播放通路 | 1200Hz 相对 1500Hz | 结论 |
|---|---|---|
| `USAGE_MEDIA` + `MODE_NORMAL` | **+81.2 dB** | ✅ 喇叭大声出声 |
| `USAGE_MEDIA` + `MODE_IN_COMMUNICATION` + `setSpeakerphoneOn(true)` | −29.3 dB | ❌ 放不出来（还削顶出噪声） |

PLAN §7 R3 的兜底建议"再不行试 `MODE_IN_COMMUNICATION` + `setSpeakerphoneOn`"——**实测这条路是死的**，
就直接用媒体通路。输出设备列表也支持这个结论：只有 `内置喇叭(2)`（48000Hz）和 `电话(18)`（8k/16k），没有蓝牙设备连着。

### ③ AEC：可用，但**只削掉约 16 dB**，不能当 barge-in 的充分条件

| 回环 | 音调强度 |
|---|---|
| AEC/NS 关 | +81.2 dB |
| AEC/NS 开 | +64.8 dB |

`AcousticEchoCanceler.isAvailable()=true`、`NoiseSuppressor.isAvailable()=true`、`AutomaticGainControl.isAvailable()=false`。
AEC 确实在工作（衰减 ~16dB），但**回声仍然比邻近频点高 64.8dB** → P2 的"TTS 期间闭麦"伪 AEC 策略仍然必须保留，
不能指望真 AEC 独自解决"自我打断"。

---

## 三、Opus 压测明细（60 秒音频 / 1000 帧）

```
编码: avg=9.27ms p50=8.98ms p95=10.46ms max=15.88ms   (预算 60ms/帧)
解码: avg=1.39ms p50=1.31ms p95=1.69ms  max=3.51ms
平均包大小=145.6 B/帧 → 约 19.4 kbps；编解码合计占单核 ≈ 17.8%；RTF(编码)=6.5×
失败帧 = 0 / 0
```

complexity 扫描（各 200 帧）：

| complexity | avg | p95 | 包大小 | 单核占用 |
|---|---|---|---|---|
| **0** | **8.88ms** | 9.66ms | 150.0 B | 14.8% |
| 3 | 10.06ms | 10.99ms | 135.7 B | 16.8% |
| 5 | 16.42ms | 17.35ms | 132.3 B | 27.4% |
| 10 | 28.47ms | 29.55ms | 136.4 B | 47.5% |

→ **定案 complexity=0**（沿用 ESP32 donor 的值，实测就是最优性价比；c=3 包小一点点但没必要）。
下行 24k 解码：avg 3.97ms、单核 6.6%。

> 注意 max=15.88ms 是单帧尖峰（第一次跑到 49.27ms）。60ms 帧预算够，但**编码必须在独立线程 + 2~3 帧抖动缓冲**，
> 别和 UI/网络串在一起。

---

## 四、其它实测事实（P1 会用到的）

- **设备**：`MODEL=OWW202 / SDK=27 / RELEASE=8.1.0 / armeabi-v7a`，4 核 ARMv7 `CPU part 0xd03`（A53）@2016MHz，NEON/vfpv4 齐全
- **音频参数**：`PROPERTY_OUTPUT_SAMPLE_RATE=48000`、`FRAMES_PER_BUFFER=192`；输入设备支持 8k/11.025k/12k/16k/22.05k/24k/32k/44.1k/48k（**16k 是硬件支持的**）
- **最小缓冲**：in 16k=1280 字节、in 48k=3840；out 16k=2576、out 24k=3856、out 48k=7688
- **内存**：app heap max 96MB（当前用 2.6MB）；系统 878MB，可用 **377MB**，lowMemory=false → 内存确实不是瓶颈
- **电量**：100%，**31.8°C**（充电中）；音量 MUSIC=8/16、VOICE_CALL=4/5，`setMode` 调用正常
- **网络**：`activeNetwork = COMPANION_PROXY` —— **手表是走手机蓝牙代理上网的，不是 Wi-Fi 直连**。
  这会影响 P1/P2 的延迟与稳定性评估（19.4kbps 上行带宽没问题，但延迟和断链要实测）。
- **OTA 端点**：`GET https://api.tenclass.net/xiaozhi/ota/` → `HTTP 200 {"status":"ok","message":"Server is working normally"}`
- **假 token 行为**：`hello` 发出去后服务器以 `code=1000`（正常关闭）断开 → 通道没问题，是鉴权未通过，符合预期。
- **MediaRecorder 也能录**（`mr_test.3gp` 6363 B）→ 万一 AudioRecord 某天不通，还有第二条采集路径。

---

## 五、唯一还没读懂的异常（P1 必须先兜住）

**第一次全跑时**：录音横扫 4/4 全部失败，异常是
`java.lang.IllegalStateException: permission denied`，而此时运行时权限与 `appops` 都是 `allow`。

之后三次运行（含专门做的"撤销权限→立即重新授权→马上跑"复现实验）**全部正常**，
`startRecording()` 6/6、4/4 成功。所以它不是硬性禁止。

最可能的原因：**App 当时没有真正拿到前台焦点**——第一次那会儿 `mCurrentFocus=SysUI.Charging`（充电界面盖在上面），
而后来 `mFocusedApp` 就是我们的 Activity。倾向结论：**ColorOS 把麦克风门控和"应用是否真的在前台可见"绑定**。

> **对 P1 的要求（不管原因是什么，这么写都没错）**：
> `startRecording()` 必须包**重试逻辑**（≥3 次、间隔 500ms），失败就提示"请把表盘切到小智界面再说话"。
> 另外录音发起时必须确保 Activity 已 `onResume` 且窗口可见。

---

## 六、P1 定案参数（照抄即可）

```
录音 : AudioSource.MIC, 16000Hz, mono, PCM16, 缓冲 ≥ 6400B（read 块 1600 样本=100ms）
       + AcousticEchoCanceler / NoiseSuppressor（可选，弱化回声 ~16dB）
       + startRecording 重试 ≥3 次 / 间隔 500ms
播放 : AudioAttributes USAGE_MEDIA + CONTENT_TYPE_SPEECH, 24000Hz, mono, PCM16
       MODE_NORMAL + setSpeakerphoneOn(false)   ← 千万别用 IN_COMMUNICATION
编码 : Concentus OpusEncoder(16000,1,VOIP), complexity=0, bitrate=24000, VBR, DTX, signal=VOICE
       帧 = 960 样本 / 60ms；独立线程 + 2~3 帧抖动缓冲
解码 : OpusDecoder(24000,1)（服务端下行是 24k）
传输 : Java-WebSocket wss://api.tenclass.net/xiaozhi/v1/  + addHeader 后 connect()
       header: Authorization / Protocol-Version: 1 / Device-Id(持久化) / Client-Id(持久化)
```

---

## 七、顺手修掉的一个体验问题

第一版回环测试为了测回声把 `STREAM_MUSIC` 拉到最大(16)，**在手腕上响得人一跳**。
已改成**最大值的一半(8/16)**，并在日志里先打招呼。回环检测靠信噪比不靠绝对音量，半音量照样测出 81dB。
