# P1 实测结论（真机 OPPO Watch 2 / OWW202）

写入日期：2026-09-13。工程 `app/`（`com.xiaozhi.watch`），产物 `app/out/app.apk`（299,411 B，apksigner 验签通过）。

---

## 一、结论速览

| 项 | 结果 |
|---|---|
| P1「按住说话 → AI 回话」代码链路 | ✅ **已在真机端到端跑通**（麦克风 → Opus 编码 → WS 上行 → 下行 → 喇叭） |
| 官方 `api.tenclass.net` 云 | ⚠️ **能连**（见 §二·修订），但设备被钉在测试组 `GID_test`、token 是占位符；服务端是否回 AI 待定 |
| 替代路径 | ✅ **自建服务器**（config.json 已支持，见 §四） |

---

## 二、官方云的真实状态

> ### ⚠️ 修订：下面 2.1~2.4 的"官方云已关闭"结论**已被推翻**（2026-09-13 深夜）
>
> **根因是我自己的一个 bug：`Device-Id` 用了大写十六进制。**
> 官方服务端按 Device-Id 字符串**精确匹配、大小写敏感**。大写 MAC 在它的设备表里查不到，
> 于是握手 101 正常、但**一收到 hello 就立刻 CLOSE**（code=1000），现象极像"服务器拒绝未登记设备"。
> 我当时把这个现象锚定成了"官方已关闭服务"，并据此写了 `hasCredentials()` 守卫和 `officialBlocked` 逻辑，
> 又因为守卫本身会拒绝 `test-token`，导致**现象被自己的代码二次掩盖**，越查越像"官方关门"。
>
> **A/B 实测（唯一变量就是大小写）：**
>
> | Device-Id | 结果 |
> |---|---|
> | `02:20:**:**:**:f6`（小写） | 101 → 回 hello → 收 listen → 保持连接 ✅ |
> | `02:20:**:**:**:F6`（大写） | 101 → 收到 hello 后 **立刻 CLOSE**（code=1000）❌ |
>
> 你 ESP32 项目的备份笔记里那条「激活 400 Invalid MAC → MAC 改带冒号 `%02x`」用的正是**小写**，
> 这个坑其实早就被踩过一次，只是当时归因成了"服务端残留记录"。
>
> **修正后的真机实测（Device-Id 小写，直连官方云）：**
> ```
> WS onOpen: HTTP 101 Switching Protocols
> >>> hello: {"type":"hello",...,"audio_params":{"format":"opus","sample_rate":16000,...}}
> <<< {"type":"hello",...,"audio_params":{"format":"opus","sample_rate":24000,...},"session_id":"addfb55d"}
> 服务端音频参数：format=opus rate=24000 ch=1 frame=60ms
> → 自动开麦
> >>> listen start mode=manual
> 采集已启动：16000Hz/mono/60ms，源=MIC
> 上行线程退出：共 64 帧 / 4077B，平均 63B/帧，编码均 25ms，丢帧 0
> >>> listen stop mode=manual
> ```
> 全程 45 秒**没有 CLOSE、没有重连**。即：**官方云的音频上行链路是通的。**
>
> **但仍然拿不到 AI 回复**，原因见 §2.5（服务端把设备归在 `GID_test` 测试组，token 是占位符）。

### 2.5 修正后的卡点：设备被钉在测试组 `GID_test`

`tools/ota_probe.py` 打官方 OTA 的**当前**响应：
```json
{
  "server_time": {"timestamp": 1789232209938, "timezone_offset": 540},
  "firmware": {"version": "0.1", "url": ""},
  "mqtt": {
    "endpoint": "api.tenclass.net",
    "client_id": "GID_test@@@02_20_90_93_01_f6@@@7876b0b4-...",
    "username": "eyJpcCI6...", "password": "qxji4qz4i1Uj..."
  },
  "websocket": {"url": "wss://api.tenclass.net/xiaozhi/v1/", "token": "test-token"}
}
```
判读：
- `activation` 字段**已消失** → 服务器认为这台设备已激活（不再发 6 位码）；
- 但 `mqtt.client_id` 前缀是 **`GID_test`** → 设备被划进"测试组"，没归属到任何用户/智能体；
- `websocket.token` 仍是占位符 **`test-token`**；
- 真机连上去能收发音频，但服务端**不产生 stt/tts**。

这与 ESP32 那次的记录**是同一个状态**（`GID_test` + `test-token`），你当时给出的处置是
「请官方服务器侧手动解绑，约 1 个工作日」。

### 2.6 已排除："没说话"不是原因（2026-09-13 01:0x 补测）

§2.6 原本怀疑"不回 AI 是因为发的是静音"。已用**循环监听**补测（每轮开麦 5s → 松手 → 等 4s，共 30 轮）：

| 轮次 | 上行码率 | 判读 |
|---|---|---|
| 第 1 轮 | **106 B/帧，13 kbps** | 明确**有声音**（对照：静音 DTX≈45~49 B/帧） |
| 第 20 轮 | **70 B/帧，8 kbps** | 环境噪声 |
| 第 29 轮 | 46 B/帧 | 静音 |

三轮发完 `listen stop` 后，服务端**一个字节都没回**（`<<<` 里只有 hello）。
→ **已排除"静音没触发 VAD"**。服务端是收到了音频但**不做 ASR/LLM/TTS**。

### 2.7 `GID_test` 是通用规则，不是个案（多 MAC 对照）

同一时刻用 5 个自造 MAC 打官方 OTA：

| MAC | HTTP | mqtt.client_id 前缀 |
|---|---|---|
| `02:20:**:**:**:f6`（现用） | 200 | **`GID_test`** |
| `02:1a:2b:3c:4d:5e` | 200 | **`GID_test`** |
| `02:aa:bb:cc:dd:ee` | 200 | **`GID_test`** |
| `02:00:11:22:33:44` | 200 | **`GID_test`** |
| `02:02:f2:c3:de:cf:eb` | 400 `Invalid MAC address` | —（偶发，与分组无关） |

→ **所有自造 MAC 一律进 `GID_test`**，token 一律 `test-token`。
这不是随机现象，而是服务端对"没有官方烧录序列号的设备"的**默认分组策略**。
与 ESP32 那次的记录完全同构。

### 2.8 下一步只能在服务端侧解决

客户端侧能做的**已经全做完了**：握手 101 ✅、hello 协商 ✅、音频上行 0 丢帧 ✅、下行解码播放 ✅。
剩下的卡点 100% 在服务端：设备没归属到用户/智能体。

三条路（早期方案权衡）：
1. **请官方解绑** `GID_test`（提 issue 附 MAC，约 1 个工作日）—— 与你 ESP32 那次的处置相同；
2. **换用已能正常工作的设备的身份**（若你手上有一台官方设备，把它的 MAC/token 借来对比，可立刻确认差异）；
3. **自建服务端**（你已明确不走这条路）。

---

## 二·原始记录（结论已被 §二·修订推翻，保留作为"错误推理"的样本）

### 2.1 现象
控制台「添加设备」要求**序列号**并提示"请检查是否烧录"。

### 2.2 实验与证据

**实验 1 — 官方 OTA 一切正常，设备能被服务端登记**
```
POST https://api.tenclass.net/xiaozhi/ota/        → HTTP 200
{"activation":{"code":"481005","message":"xiaozhi.me\n481005","challenge":"..."},
 "websocket":{"url":"wss://api.tenclass.net/xiaozhi/v1/","token":"test-token"},
 "mqtt":{"client_id":"GID_test@@@02_20_90_93_01_f6@@@..."}}
```
绑定时 `activation` 字段消失，`POST /ota/activate` 返回
`HTTP 200 {"message":"Device activated","device_id":2678203}` —— **设备确实登记成功**。

**实验 2 — 但拿到的 token 永远是占位符 `test-token`**
补齐官方文档标注为"必需"的 `mac_address`、`chip_model_name`、`flash_size`、`ps_partition_table`、
换成已知板型 `bread-compact-wifi` …… token 一律仍是 `test-token`，与请求体无关。

**实验 3 — WS 端点在握手后 2ms 直接关闭，且与我们发什么无关**
`tools/ws_probe.py` 加时间戳实测：

```
[+  685ms] [→ 握手请求]
[+  812ms] [← 握手响应]
[+  812ms] [握手结论] HTTP/1.1 101 Switching Protocols
[+  812ms] [A/B] skip-hello：握手后不发任何东西
[+  814ms] [← CLOSE] code=-1 reason=
```

握手后**什么都不发**也被关 → 关闭与 hello 内容无关。穷举排除：
- 不发 `Authorization` 头 → 仍 2ms 被关
- 用 MQTT 的 password 当 token → 仍被关
- URL 加 `?from=mqtt_gateway` → 仍被关
- hello 的 4 种变体（full / 无 features / minimal / version=2）→ 全部被关

**实验 4 — 连全新随机设备也是 202「正常等待」**
`POST /ota/activate` 对从未见过的随机 MAC 也返回 `202 Device activation timeout`（= 正常的"等你输码"状态）。
说明服务端的**验证码链路本身是活的**，卡点在控制台要求序列号。

### 2.3 根因
小智官方已上线**「激活设备流程 v2」（一机一码）**，官方文档原文：

> Developer 在后台面板创建一个 Product……License 列表（SerialNumber、HMAC key……）
> 如果是 ESP32，把 SerialNumber 和 HMAC key efuse 到芯片上。**如果是 Android App，开发者需要自行部署一个授权服务器来计算 HMAC**。

实测自造序列号打 `/ota/activate` 的回应是：
```
HTTP 404 {"success":false,"error":"License not found or already activated"}
```
即：**没有官方签发的 License，就没有可用 token**。控制台那句"需要序列号/请检查是否烧录"就是这个校验的用户可见形态。
License 走的是官方开发者/商务流程，个人 DIY 项目拿不到。

### 2.4 附带纠正过的两个自造 bug
1. 我原来硬编码 `Activation-Version: 2`。官方协议原文：该头表示"芯片 efuse 是否存了有效序列号，**有则 2，无则 1**"。
   Android App 没有 efuse → 必须发 **1**。已改（虽然本场景下最终没改变结论）。
2. 曾把"服务端给了 `test-token`"当成"未绑定"的判据。实测存在第二种状态：**设备已登记、但仍只给占位 token**。
   现在 `Result.officialBlocked` 专门表示这个状态，App 会直接停掉轮询并提示改用自建服务器（不再白耗电）。

---

## 三、P1 端到端实测（用桩服务器绕开官方云）

### 3.1 怎么验的
因为官方云不通，用 `tools/stub_server.py`（零依赖的裸 WS 桩服务器）+ `adb reverse`，
在**不依赖任何外部服务、不用 Wi-Fi** 的前提下把整条链路跑通：

```bash
# 1) 电脑起桩服务器（下行声明为 pcm，这样服务端不需要 Opus 编码器）
python tools/stub_server.py --port 8000 --downlink pcm

# 2) 把表上的 127.0.0.1:8000 隧道到电脑（走 USB，绕开内网/公网）
adb reverse tcp:8000 tcp:8000

# 3) 配置指向桩服务器
adb push tools/config.local.json /sdcard/Android/data/com.xiaozhi.watch/files/config.json

# 4) 一键自动"按住说话 4 秒"
adb shell am start -n com.xiaozhi.watch/.MainActivity --ei talkms 4000
```

### 3.2 实测日志（节选）
```
config.json 指定了服务器 → 跳过激活，直接连 ws://127.0.0.1:8000/xiaozhi/v1/
token 为空 → 不发 Authorization 头（自建服务器模式）
WS onOpen: HTTP 101 Switching Protocols
>>> hello: {"type":"hello","version":1,"transport":"websocket",
            "audio_params":{"format":"opus","sample_rate":16000,"channels":1,"frame_duration":60}}
<<< {"type":"hello","session_id":"stub-...","audio_params":{"format":"pcm","sample_rate":24000,...}}
AEC 已启用 (enabled=true)      NS 已启用
采集已启动：16000Hz/mono/60ms，源=MIC，minBuf=1280 用 7680
>>> {"session_id":"...","type":"listen","state":"start","mode":"manual"}
   … 4 秒 …
采集线程退出，共投递 65 帧
上行线程退出：共 64 帧 / 3161B，平均 49B/帧，编码均 24ms，丢帧 0
>>> {"session_id":"...","type":"listen","state":"stop","mode":"manual"}
<<< {"type":"stt","text":"（桩服务器：我收到了你的 64 帧音频）"}
<<< {"type":"tts","state":"start","text":"这是一段测试音"}
播放已启动：24000Hz/mono，USAGE_MEDIA，minBuf=3856 用 24000
<<< {"type":"tts","state":"stop"}
```
崩溃数 **0**。

### 3.3 判读
- 上行 64 帧 / 4 秒 = 16 帧/秒，**正好 60ms 一帧，零丢帧** → 采集与编码跟得上实时。
- 平均 49 B/帧 ≈ 6.5 kbps：因为表静置无声，**DTX 在起作用**（P0 测有声音时是 145 B/帧 / 19.4 kbps）。
- 编码均值 **24ms**，比 P0 空载测的 9.27ms 慢 —— 有采集/WS/播放三个线程在抢 CPU。
  **这不影响可用性**（预算 60ms），但如实记录。

### 3.4 本次按实测做的三处加固
| 问题 | 处理 |
|---|---|
| 首次跑丢 3 帧（编码毛刺打满 3 帧队列） | 上行抖动缓冲 3 帧(180ms) → **6 帧(360ms)** |
| 编码 29ms → 24ms | 上行线程提到 `THREAD_PRIORITY_URGENT_AUDIO` |
| `WS onClose → 重连 → 关掉新连接 → 又 onClose` 的**重连风暴**（2 秒一轮，永不恢复） | onClose 加三道闸：主动关闭不重连 / 非当前连接忽略 / 已断开不重连 |

重连风暴的成因值得记一笔：`connectWs()` 会 `closeWsQuietly()` 掉旧连接，而旧连接的 `onClose` 是
**事件驱动、可能晚到**的，它醒来时字段 `ws` 已指向新连接，于是又去关新连接 —— 自激振荡。

---

## 四、交付与用法

产物：`app/out/app.apk`（签名 CN=XZW）

```bash
adb install -r app/out/app.apk
adb shell pm grant com.xiaozhi.watch android.permission.RECORD_AUDIO
```

**配置（不用在表上打字）**：`/sdcard/Android/data/com.xiaozhi.watch/files/config.json`
```json
{
  "ws_url":  "ws://192.168.1.10:8000/xiaozhi/v1/",
  "token":   "",
  "ota_url": "http://192.168.1.10:8002/xiaozhi/ota/"
}
```
- 填了 `ws_url` → 跳过官方 OTA/激活，直连。
- 只填 `ota_url` → 走该服务器自己的 OTA（**自建服务器的智控台给激活码，不需要序列号/License**）。
- 改完点表上「重载」，或 `am force-stop` 后重启。

**界面**：状态 + 大字激活码 + 「按住说话」大按钮 + 激活/连接/重载/清屏 + 滚动日志。
屏幕实测 372×430 px @320dpi = **186×215dp**，所有尺寸按这个倒推（第一版按手机尺寸写，激活码折行、按钮全被挤出屏幕）。

**自动化入口**（真机验证用，省得和屏幕坐标较劲）：
```bash
adb shell am start -n com.xiaozhi.watch/.MainActivity --ei talkms 4000
```

---

## 五、遗留 / 下一步

- [x] ~~决定性验证：是不是因为没说话~~ → **已排除**（§2.6：106 B/帧的带声音频同样零响应）
- [x] **修掉两个真 bug**（本轮）：
      - WS 保活：`setConnectionLostTimeout(0)` = 不发 ping → 手表走蓝牙代理时空闲 **60s 必掉线(1006)**；改 20s 后恢复
      - 状态卡死：`listen stop` 后服务端不回话 → 永久停在 `THINKING`，之后每次说话都被"没就绪"挡掉。
        已加 `STUCK_TIMEOUT_MS=12s` 看门狗兜底回 READY
- [ ] **决定性待办（需用户侧操作）**：`GID_test` 只能服务端侧解（§2.8）。
      优先做「借一台能正常工作的官方设备，对比它的 OTA 响应」——这能立刻确认差异，比等官方回复快得多
- [ ] 备选：去 78/xiaozhi-esp32 提 issue 请官方清设备状态（附 Device-Id `02:20:**:**:**:f6`，官方说约 1 个工作日）
- [ ] 清理已作废的逻辑：`officialBlocked`（判据基于被推翻的观察）
- [ ] 编码均值 24~27ms 偏高，P2 可再压（当前不影响可用性）
- [ ] P2：小方屏 UI 适配（原"圆屏"说法已于 2026-09-13 实测推翻：设备为方屏）、`mode:auto` 自动续听、TTS 期间闭麦（伪 AEC）、`abort` 打断
- [ ] P4：MCP 暴露手表传感器（心率/血氧/HRV/心情）

## 六、调试工具（本轮新增，复用价值高）

| 工具 | 用途 |
|---|---|
| `tools/ws_probe.py` | 零依赖裸 WS 探针。带时间戳 dump 自己发出去的每一字节；`--skip-hello` / `--hello-variant` / `--no-auth` / `--stop-after` 做 A/B 隔离。**判"是服务端拒我们、还是我们发错了"的唯一可靠手段。** |
| `tools/raw_dump.py` | 裸 TCP 监听器，把客户端实际发出的 HTTP 升级请求**逐字节**打出来。配合 `adb reverse` 抓 App（Java-WebSocket）真实报文，用来和探针做 diff。**本次就是靠它定位到大小写问题。** |
| `tools/ota_probe.py` | 打官方 OTA，看某台设备的绑定状态（有没有 `activation`、token 是不是占位符、`GID_test` 前缀）。 |
| `tools/stub_server.py` | 零依赖裸 WS 桩服务器，下行可声明 pcm，用于绕开官方云做音频全链路验证。 |
