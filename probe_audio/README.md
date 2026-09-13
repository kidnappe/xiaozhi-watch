# P0 · 音频能力探针（XZProbe）

> ✅ **已真机跑完（2026-09-12），结论看 `P0_RESULTS.md`**（那份是权威；本文件是"怎么跑、怎么读"）。
> 原始证据 `pulled/probe.log` + 12 个 wav。

小智手表端移植的**第一步、也是唯一的前置条件**。装上这一个 APK，一次跑完就能回答方案里
"现在回答不了"的三个问题 + 两个附带风险。

| 探针回答的问题 | 对应风险 | 实测结果 |
|---|---|---|
| ① ColorOS Watch 放不放行第三方 App 录音 | R1（致命） | ✅ 放行（`MIC` 源 peak 355~468） |
| ② 表内喇叭能不能真出声，还是被路由到蓝牙 | R3（高） | ✅ 出声且响亮，但**只有媒体通路** |
| ③ 纯 Java Opus（Concentus）在 A53 上够不够快 | R2（高） | ✅ 编码 9.27ms/帧（预算 60ms） |
| ④ TLS / wss 能不能通、header 有没有在握手时带出去 | R4（高） | ✅ TLSv1.2 + HTTP 101 |
| ⑤ Device-Id 自造+持久化是否可行 | R9（低） | ✅ 跨 4 次启动一致 |

> 探针**用假 token 连真服务器**，目的不是打通业务，而是看服务器给什么反应。这一步不做，P1 会在
> 同一个坑里烧掉两天。

---

## 0. 一键复跑（adb，不用点表）

```bash
ADB="D:/software/android-sdk/platform-tools/adb.exe"
"$ADB" install -r probe_audio/out/app.apk
"$ADB" shell pm grant com.xiaozhi.probe android.permission.RECORD_AUDIO
"$ADB" shell am start -n com.xiaozhi.probe/.MainActivity --ez autorun true   # 跑全套
"$ADB" shell am start -n com.xiaozhi.probe/.MainActivity --es step record    # 只跑录音矩阵
"$ADB" shell am start -n com.xiaozhi.probe/.MainActivity --es step loopback  # 只跑回环（测喇叭）
"$ADB" logcat -s XZProbe
```
全套约 61 秒（其中 Opus 压测 28 秒），会响 5 次、每次 3 秒（媒体音量 = 最大值的一半）。

---

## 1. 构建

```powershell
powershell -ExecutionPolicy Bypass -File E:\code\xiaozhi-watch\probe_audio\build.ps1
```

产物：`probe_audio\out\app.apk`（约 300 KB）。
依赖三个 jar（在 `..\libs\`，来源与校验值见下方依赖表）：
`concentus-1.0.2.jar`、`Java-WebSocket-1.5.7.jar`、`slf4j-api-2.0.6.jar`。

> **`slf4j-api` 是必须的**：Java-WebSocket 1.5.x 内部有 `LoggerFactory.getLogger(...)`，
> 缺了它运行时直接 `NoClassDefFoundError`（哪怕你只用一个客户端）。这是 P1 会踩的坑，探针先把它验掉。

**本工程与 `E:\code\watch\app\build.ps1` 的差异（3+1 处）**
1. `javac` 的 `-classpath` 加上三个 jar；
2. 自己的 class 打成 `app.jar`，和**第三方 jar 一起**喂给 d8（`--min-api 27`）；
3. dex 合并脚本改为合并 `classes*.dex`（多 dex 时也在 Android 8.1 原生支持，无需 multidex 库）；
4. 额外一步：拆包剔除第三方 jar 的 `META-INF/versions/`（multi-release），避免 d8 碰 `module-info.class`。

### 构建脚本里三个已经踩过的坑（改脚本前先看这里）
- **.ps1 必须带 UTF-8 BOM**。脚本里有中文注释/字符串时，PowerShell 5.1 会按 ANSI(GBK) 解码 UTF-8 字节，
  直接**解析期失败**——症状是"脚本一行都没执行、连目录都没建、也没有任何输出"。（`E:\code\watch\probe\build.ps1`
  是纯 ASCII 所以没事。）
- **不要用 `$ErrorActionPreference='Stop'`**。PS 5.1 下 native 命令往 stderr 写任何东西（javac 的报错、
  d8 的 warning）都会被当成 `NativeCommandError` 终止脚本，**真正的编译错误反而被吞掉**。
  现在改成 `Continue` + 每个 native 调用后手动查 `$LASTEXITCODE`。
- `Remove-Item -Recurse` 在带安全包装的环境里可能被劫持到"回收站"并失败；清理 `out/` 已加 .NET 兜底。
- 另外两个已知的"上不了车"的坑：`E:\code\watch\probe` 的清单里挂着 `@mipmap/ic_launcher` 但工程没有
  `res/` 目录，aapt2 会 `resource mipmap/ic_launcher not found` 直接失败（本工程已去掉该属性）。

---

## 2. 安装与运行

```bash
ADB="D:/software/android-sdk/platform-tools/adb.exe"
"$ADB" install -r "E:/code/xiaozhi-watch/probe_audio/out/app.apk"
"$ADB" shell am start -n com.xiaozhi.probe/.MainActivity
"$ADB" logcat -s XZProbe            # 实时看（也可在表上看屏幕日志）
```

**表上操作**：首次进会弹录音权限，**必须点允许**；然后点【全跑】。
全跑期间会出声两次（媒体通路、通话通路各 3 秒）：**请留意听，是"表内喇叭出声"还是"没声/从手机/蓝牙出"**。
跑完（含 60 秒 Opus 压测，整体约 2~3 分钟）把日志和 wav 拉出来：

```bash
"$ADB" pull /sdcard/Android/data/com.xiaozhi.probe/files/ E:/code/xiaozhi-watch/probe_audio/pulled
```

会得到 `probe.log` + 若干个 `rec_<rate>_src<source>_<n>s.wav`（**用电脑听这些 wav，能听到人声就是 R1 通过**）。

### 按钮对照
| 按钮 | 作用 |
|---|---|
| 全跑 | 系统能力 → 录音横扫 → 播媒体 → 播通话 → 回环(媒体/通话/AEC) → 网络/TLS → wss 握手 → Opus 压测 |
| 系统 | 只跑能力查询（设备列表 / 最小缓冲 / AEC·NS·AGC / MediaCodec 里的 audio/opus / 内存 / 电量 / CPU） |
| 回环 / 回环通话 / 回环AEC | **测喇叭的客观手段**：一边放 1200Hz 一边用 MIC 录，用 goertzel 比该频点高多少 dB。不靠耳朵 |
| 录音横扫 | 4 个 AudioSource（MIC / VOICE_RECOGNITION / VOICE_COMMUNICATION / DEFAULT）各录 2 秒并写 wav |
| 播媒体 / 播通话 | 3 秒播音，媒体通路 vs `MODE_IN_COMMUNICATION + setSpeakerphoneOn(true)` 通话通路 |
| 网络 | HTTPS GET OTA 端点（验 TLS 证书链）+ wss 握手（验 header 与服务器反应） |
| 录音48k | 用 48k 再横扫一遍（有些表只给 48k 通路，16k 是软件重采样） |
| Opus压测(慢) | 60 秒音频的编码+解码计时 + complexity 0/3/5/10 扫描 + 24k 下行解码（约 28 秒） |

> 🔊 **音量**：回环测试会把 `STREAM_MUSIC` 设成**最大值的一半**（第一版设成最大，在手腕上响得人一跳，已改）。

---

## 3. 判读标准（跑完照着读）

**R1 录音**
- 权限被拒（日志 `RECORD_AUDIO = -1`）→ **R1 判死**，方案要退化成"文字输入 + 语音播报"。
  但还要确认是不是 ColorOS 的第三方策略（小布助手能用只说明硬件通路在）。
- 权限给了、`peak=0` / `【静音】` → 麦克风被挡或被系统静音，换 source 再试。
- `【有声音】` 且 wav 用电脑能听到人声 → **通过**，记下哪个 source 最优（大概率是 VOICE_COMMUNICATION）。

**R3 播放**
- 耳朵听到 + `playbackHead` 推进到接近"预期时长" → **通过**。
- 只有"播通话"那一次出声 → P1/P2 必须走 `MODE_IN_COMMUNICATION + setSpeakerphoneOn(true)`，
  这也顺带决定 AEC 的会话策略。
- 两次都没声、但 playbackHead 在推进 → 输出被路由走了（蓝牙/手机），查【系统】里输出设备列表。

**R2 Opus**（看 `>>> 结论:` 那一行）
- `avg ≤ 5ms` → 轻松，方案 A 直接用。
- `≤ 15ms` → 够用（60ms 帧有 4 倍余量），但**编码必须在独立线程**。
- `> 30ms` → 切 JNI libopus（方案 B）或自建服务器走 `format:pcm`（方案 C）。音频层已抽象成接口，切换不动上层。
- 抄下来的参数：16k/mono/60ms/960 样本/complexity=0/VBR/DTX。

**R4 网络**
- `GET ota → HTTP 200/404/405` 且打印出证书 subject/issuer → **TLS 证书链 OK**（Android 8.1 系统 CA 够用）。
- `onOpen: HTTP 101` → 握手通；`connectBlocking 返回 true` → TCP+TLS+WS 三层都通。
- 随后的 `onClose code=4xxx` → 是**鉴权/绑定**问题（假 token 的预期结果），不是通道问题，P1 拿到真 token 即可。
- 顺带确认：日志里"即将在握手请求里带的 header"四条与实际发出的应当一致（这是 ESP32 那次的原始事故点）。

**R9** 杀掉进程重进，两段日志里的 `Device-Id` 应完全相同。

---

## 4. 探针里刻意没做的事（留给 P1）
- 不实现 OTA 激活流程（要真 6 位激活码 + 控制台操作）；
- 不做 VAD / 打断 / 状态机；不发音频流；
- UI 就是能点的按钮列表，不是小方屏精修设计（方屏 UI 适配属于 P2；注：旧文档曾误称"圆屏"，2026-09-13 已推翻）。

跑完把 `probe.log` 给我，我按上面的判据给出 P1 的具体参数（用哪个 source、哪个通路、要不要降级），
然后开始写 `com.xiaozhi.watch` 主工程。
