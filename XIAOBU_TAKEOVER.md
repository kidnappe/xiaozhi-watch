# 夺舍官方小布助手 · 可行性评估（OWW202 实测）

> **快照**：2026-09-13 · 项目现状（P0–P2 已通），对手表 ROM 做了 APK 反编译 + framework 代码级分析 + 无痕真机实测。
> **结论先行**：用户最想要的「表盘左滑页里的小布语音入口」是**写死的显式组件启动，应用层不可重定向**；但系统里另有 **3 条可夺舍的真实入口**（F2 短按/双击、F1 长按广播、表盘小布图标），全部无 root 可实施、完全可逆。唤醒词和电源长按是死路。

---

## 1. 小布在 OWW202 上的架构（反编译实证）

| 组件 | 位置 | 角色 |
|---|---|---|
| `com.heytap.wearable.breeno`（v2.1.0） | `/system/priv-app/HeyBreeno`，targetSdk 27，armv7 | 助手 UI + 对话管线。内嵌思必驰 DUILite SDK（本地唤醒/ASR）+ 讯飞 libmsc5 + heytap 云 |
| `com.oppo.ovoicemanager`（HeyVoiceManager，常驻进程） | `/system/priv-app/HeyVoiceManager` | **唤醒词引擎**：AISpeech `AIWakeupEngine`，模型 `wakeup_for_oppo_watch.bin`，audioSourceType=1999。唤醒词 3 个：`hei bu rui nuo` / `xiao bu xiao bu` / `ni hao xiao bu`。**默认关闭**（`Settings.System wakeup_enable=0`，本机当前为 null） |
| `com.heytap.wearable.launcher` | priv-app | 左滑 QuickCenter 小布卡片 + 表盘小布 complication（`BreenoProviderService`） |
| framework（services.jar） | — | `SingleButtonKeyCtrl`（电源键）、`ShortcutKeyCtrl`（F2）、`LongPressNaviKeyCtrl`（F1）三个按键控制器 |

**关键事实：这块表没有配置任何 VoiceInteractionService**（`voice_interaction_service` 设置为空，小布不是 VIDS）。小布全靠 ROM 硬编码路径拉起。

## 2. 全入口盘点与劫持可行性总表

| 入口 | 触发方式 | 启动方式（实证） | 可劫持？ |
|---|---|---|---|
| **左滑 QuickCenter 小布卡片** | 点卡片头 | launcher `la.java` → 显式 `ComponentName("com.heytap.wearable.breeno","...MainActivity")` + `start_type=5`+`query_key` | ❌ **不可重定向**。disable 小布后点击静默失败（ActivityNotFoundException 被吞） |
| **唤醒词** | ovoicemanager 检测 | 显式广播 `intent.setPackage("com.heytap.wearable.breeno")`，`start_type=2` | ❌ 不可蹭听（setPackage 锁死）。disable 后唤醒空转白耗电 |
| **表盘小布图标**（complication） | 点击图标 | launcher `BreenoProviderService.getTapAction()` 返回隐式 action `com.heytap.wearable.breeno.intent.action.SPEECH` → `ComplicationTapUtils.buildTapIntent` → `setAction` 后 startActivity | ✅ **可接管**：本 app 声明同 action activity；小布在 → 弹选择框（一次）；小布 disable → 直达我们 |
| **F1（keycode 131）长按 800ms** | framework `LongPressNaviKeyCtrl` | ① 显式启动 breeno MainActivity（写死）② 同时发**隐式、无权限**广播 `heytap.intent.action.LONG_PRESS_NAVI`（`sendBroadcastAsUser ALL`） | ⚠️ 显式部分不可；**广播可蹭听** → 小布 disable 后 F1 长按只剩我们 = 完全接管 |
| **F2（keycode 132）短按/双击** | framework `ShortcutKeyCtrl` | 读 `Settings.System single_shortcut_package_name/class_name`（短按）、`double_shortcut_*`（双击），按包名+类名**显式启动** | ✅ **adb 一行改写**（已实测注入按键走到该路径）。当前值 = launcher/RecentsActivity |
| 电源长按 | LegacyGlobalActions | framework-res 表型配置 `config_globalActionsKeyList=["assist"]`，而 assist 项是 OPPO 空壳（日志原话：`Triggerd the assist action, but it had been shielded ... age test`） | ❌ 死路 |
| 电源双击 | `ShortcutKeyCtrl` `double_shortcut_*` | 同 F2 机制（当前 = Recents） | ✅ 可改，但建议留给最近任务 |
| KEYCODE_ASSIST 通道 | PWM `launchAssistAction` → `SearchManagerService.getLegacyAssistComponent` → AMS `startServiceAsUser` | **取系统里第一个**声明 `android.service.voice.VoiceInteractionService` 的服务直接 startService；**不读设置、不要求系统应用**（本 ROM 改造版逻辑） | ✅ 理论通道：声明该 intent-filter 的 Service 即可被选中。但当前无物理入口触发（219 无按键映射、HOME 长按 behavior=0），仅作兜底扩展位 |
| `voice_interaction_service` 设置 | VIMS 标准机制 | 本 ROM 的 assist 流程**不读**它；且 `findAvailInteractor` 只认系统应用 | ⚠️ 此 ROM 上基本无用，别在这上面浪费时间 |

## 2.5 物理按键对照（官方/评测文档 + ROM 代码互证）

OWW202（Watch 2 42mm）**没有旋转表冠**，右侧两枚实体按键（带绿色线条的是下键）：

| 物理 | 键码链路 | framework 控制器 | 官方行为（文档实证） | 对本项目的意义 |
|---|---|---|---|---|
| **上键（导航键）** | gpio 扫描码 59 → KEYCODE_F1(131) | `LongPressNaviKeyCtrl` | 短按=应用列表；**长按 1 秒=小布**（"NaviKey"名字完全对应） | **官方小布入口**。长按同时发无保护广播 `LONG_PRESS_NAVI` → 方案 C 接管的就是它 |
| **下键（功能键/电源键）** | PON 扫描码 60 → KEYCODE_F2(132)；长按走 116(POWER) | `ShortcutKeyCtrl`（短按/双击）+ `SingleButtonKeyCtrl`（电源行为） | 短按=**回到上个任务**（= Recents = 当前 `single_shortcut_*` 值）；"设置 → 功能键"可自定义；连按 4-5 次=SOS | 短按目标就是那个 System 设置 → 方案 A 两行 adb 接管 |
| 上键 gpio 扫描码 143 | KEYCODE_WAKEUP(224) | — | 疑似息屏唤醒专用码 | 无关 |
| 触摸 IC（cyttsp5） | 扫描码 88/142 | — | 触摸手势（如双击亮屏） | 无关 |

> 文档出处见 §7 参考链接。"长按 1 秒打开 Breeno"（评测）与代码里 `LONG_PRESS_TIME=800ms`（framework）互相印证。

## 3. 关键代码证据



- 左滑卡片显式启动：`launcher e/e/c/b/n/f/la.java` → `AbstractViewOnClickListenerC0265aa.a(pkg, cls, view, bundle)`，`new Intent(ACTION_MAIN)+setComponent()`。
- 唤醒词显式广播：`ovoicemanager wakeup/VoiceManager.java#wakeUpSpeechAssist()`，`intent.setPackage("com.heytap.wearable.breeno")`。
- 表盘图标隐式 action：`launcher watchface/complications/providers/BreenoProviderService.java#getTapAction()` 返回 action 字符串；`ComplicationTapUtils.buildTapIntent` 无 "/" 无 PKG_PREFIX → `intent.setAction(str)`。
- F1 长按：`framework com.android.server.policy.LongPressNaviKeyCtrl`（keycode 131，800ms，`START_PKG_NAME/START_CLASS_NAME` 写死 breeno；广播 action `heytap.intent.action.LONG_PRESS_NAVI` 无 setPackage 无 permission）。
- F2 短按/双击：`framework com.android.server.policy.ShortcutKeyCtrl`（keycode 132；`startActivity(1/2)` 读 `single_shortcut_*`/`double_shortcut_*` 显式启动；launcher+非 Recents 特判为 goHome）。
- 电源长按空壳：`framework com.android.server.policy.LegacyGlobalActions#getAssistAction()`，onPress 只打日志。
- assist 通道：`framework com.android.server.search.SearchManagerService#getLegacyAssistComponent()`（query VIDS intent 取第一个，无系统应用过滤）→ `AMS#reportAssistContextExtras()` 里 `startServiceAsUser(VIDS intent)`。

## 4. 已做真机实测（全部无痕，已复位）

| 测试 | 结果 |
|---|---|
| `settings` 读取：`voice_interaction_service`/`assistant`/`wakeup_enable` | 全部 null（未配置/未开启） |
| 注入 `input keyevent 219`（KEYCODE_ASSIST） | 静默无反应（无 VIDS 可选，`getLegacyAssistComponent` 返回 null）——证实 assist 通道空置 |
| 注入电源长按 | 打出 LegacyGlobalActions 空壳日志——证实死路 |
| F2(132) 注入（亮屏） | 走到 `ShortcutKeyCtrl`（日志证实注入键与物理键同路径）；**充电时被 `SysUI.Charging` 覆盖窗吞掉**（该窗口 tag 在 dispatch 名单里，充电状态按键全被转发） |
| `settings put system single_shortcut_*` 指向 `com.xiaozhi.watch` → 注入 F2 | 因充电覆盖窗吞键未完成闭环验证；**代码级证据充分**（显式组件启动，无 filter/权限要求），需拔充电后人工按一次确认 |
| `am start -a com.heytap.wearable.breeno.intent.action.SPEECH` | 解析到且仅解析到 `breeno/.MainActivity`（唯一 resolver）——表盘图标接管机制成立 |
| 清理 | 卸载 `com.xiaozhi.probe`、`com.probe.healthcheck`（用户要求）；`single_shortcut_*`/`double_shortcut_*` 已复位原值；breeno 未禁用、所有设置复位 |

## 5. 推荐落地方案（按侵入性排序）

### 方案 A：F2 短按 → 小智（零代码，5 分钟，推荐起步）
```bash
adb shell settings put system single_shortcut_package_name com.xiaozhi.watch
adb shell settings put system single_shortcut_class_name com.xiaozhi.watch.MainActivity
```
- 0 代码改动；恢复原值即可撤销（原值 = `com.heytap.wearable.launcher` / `com.android.quickstep.RecentsActivity`）。
- 副作用：F2 短按不再=最近任务（双击仍是）。
- 待办：拔充电后实按一次确认（充电时该键被覆盖窗吞掉属正常行为）。

### 方案 B：表盘小布图标 → 小智（需改 manifest 重建 APK）
- `com.xiaozhi.watch` 的 manifest 给 MainActivity（或 alias）加：
  `<action android:name="com.heytap.wearable.breeno.intent.action.SPEECH"/><category android:name="android.intent.category.DEFAULT"/>`
- 重建安装后：小布健在 → 点图标弹一次选择框（选"始终"即固化）；`pm disable-user --user 0 com.heytap.wearable.breeno` → 图标**直达小智**，无弹窗。
- disable 小布的副作用清单：左滑卡片点击无反应、QuickCenter 小布建议停更、F1 长按无小布、唤醒词空转（保持 `wakeup_enable` 关闭即可）。launcher 不依赖 breeno 进程存活（卡片 UI 是 launcher 自己画的），预期稳定，但 disable 后需观察一天。
- 恢复：`pm enable com.heytap.wearable.breeno`。

### 方案 C：F1 长按 → 小智（与 B 二选一或叠加）
- app 注册广播 receiver：action `heytap.intent.action.LONG_PRESS_NAVI`（隐式、无权限保护，任何 app 可收）。
- 小布 disable 后：F1 长按 = 只弹小智 = **彻底接管这颗键**。

### 方案 D：assist 服务扩展位（备案）
- 声明 `android.service.voice.VoiceInteractionService` intent-filter 的 Service，系统 assist 通道会直接 startService 它（不读设置、不查系统标志）。当前无物理入口，留着兼容未来（如 ROM 更新解锁按键）。

### 确定做不到的（别再尝试）
- 左滑 QuickCenter 卡片重定向（显式组件名写死在 launcher，无 root 不可改）。
- 唤醒词重定向（ovoicemanager 显式 setPackage 广播；且唤醒默认关闭）。
- 重打包/替换 breeno、ovoicemanager、launcher（priv-app，签名校验，无 root）。
- MITM 小布云协议替换后端（证书/签名校验，需 root）。

## 6. 与小智主线的衔接

夺舍只解决"**入口**"：进来的就是 `com.xiaozhi.watch.MainActivity`，后续复用 P1/P2 已通的 `XiaozhiClient`（WS→官方云/自建）+ 表情球。服务端分组瓶颈与本报告独立，互不影响。

## 7. 遗留与下一步

- [ ] 拔充电后实按闭环方案 A（`logcat -s ShortcutKeyCtrl` 确认下键短按拉起小智；充电覆盖窗会吞键属正常）。
- [ ] 决策：A（纯改设置）先跑，还是直接 B+C（改 manifest + disable 小布，连上键长按一起收编）。
- [ ] 若 B：表盘需有小布组件图标（部分表盘才提供 complication，必要时换表盘）。
- [ ] disable 小布后 24h 稳定性观察（launcher/耗电）。

## 8. 参考链接（按键功能文档）

- [OPPO Watch 2 42mm 产品参数（OPPO 官网）](https://www.oppo.com/cn/accessories/oppo-watch-2/specs/42mm/)
- [oppowatch 怎么操作（太平洋百科/新浪转载）：上键短按应用列表、长按小布；下键短按运动/长按入水锁定](https://k.sina.cn/article_7857141524_1d452771401901khqq.html)
- [OPPO Watch 2 体验（新浪众测）：导航键长按 1 秒打开 Breeno](https://zhongce.sina.com.cn/iframe/article/view/101588/)
- [OPPO Watch 2 评测（知乎）：两枚按键设计、无旋转表冠](https://zhuanlan.zhihu.com/p/394374030)
- [OPPO Watch 2 深度体验（知乎）：表盘左滑健康中心、右滑快捷中心、下键回到上个任务](https://zhuanlan.zhihu.com/p/400962682)
- [OPPO Watch 使用教程（砍柴网）：「设置 → 功能键」自定义单击应用](https://m.ikanchai.com/pcarticle/343623)

## 9. 【已实现并实测通过】上键长按 → 小智 接管

> 用户拍板：只做上键长按这一个入口，接受原生小布卡片作废。本节是实现交接，含实测踩到的四个关键坑，接手前务必读完。

### 9.1 改了什么（`app/` 下）
| 文件 | 作用 |
|---|---|
| `src/com/xiaozhi/watch/EntryKeepAlive.java` | **常驻前台哑服务**：MIN 级静音通知只为抬住进程；`onCreate` 里 `registerReceiver` 动态注册上键长按接收器。**接管的关键载体** |
| `src/com/xiaozhi/watch/NaviKeyReceiver.java` | 收 `heytap.intent.action.LONG_PRESS_NAVI` → 亮屏则 `startActivity(MainActivity, talkms=8000)`（模拟"长按即说"自动开麦 8s）；息屏忽略（对齐小布官方行为） |
| `src/com/xiaozhi/watch/BootReceiver.java` | `BOOT_COMPLETED` → 拉起 EntryKeepAlive（开机自启） |
| `AndroidManifest.xml` | 加 `RECEIVE_BOOT_COMPLETED`；声明 `EntryKeepAlive` 服务 + `BootReceiver`。**注意：`LONG_PRESS_NAVI` 清单 receiver 无效（见坑②），不要走回头路** |
| `MainActivity.java` | `onCreate` 里 `startService(EntryKeepAlive)`；补 `import android.content.Intent` |

### 9.2 四个关键坑（血泪，别再踩）
1. **`LONG_PRESS_NAVI` 是受保护广播**：`am broadcast` 发它会 `SecurityException: not allowed to send`（uid=2000 也被拒），**只有 system_server 能发**。好事：入口不会被第三方 app 伪造触发。**无法用 am 测，只能 sendevent 模拟真按键测**（见 9.4）。
2. **Android 8 隐式广播限制 = 决定性障碍**：清单 `<receiver>` 收不到这个隐式广播，`logcat` 报 `BroadcastQueue: Background execution not allowed`。**deviceidle 白名单、前台服务抬进程优先级都治不了它**（试过，无效）。**唯一解**：进程存活时用 `Context.registerReceiver` 运行时注册（运行时接收器豁免该限制）→ 所以必须有个常驻前台服务托底，接收器挂在该服务里。
3. **组件级禁用小布 shell 无权**：`pm disable breeno/.MainActivity` 报 `SecurityException: Shell cannot change component state ... to 2`。只能**整包** `pm disable-user --user 0 com.heytap.wearable.breeno`。整包禁用后 framework 那句显式 `startActivity(breeno/.MainActivity)` 抛 ActivityNotFoundException 被 `LongPressNaviKeyCtrl` 内部 catch 静默失败，只剩我们。
4. **两个入口会抢前台**：小布不禁用时，上键长按 = 我们的接收器 startActivity 和小布的 startActivity 竞态（实测多数时候我们赢，但不保证）。**要确定性单入口，必须 disable 小布**。

### 9.3 启用/回滚命令
```bash
ADB="E:/code/code tools/platform-tools/adb.exe"; export MSYS_NO_PATHCONV=1
# 启用接管（装机后一次性）：
"$ADB" install -r app/out/app.apk
"$ADB" shell am start -n com.xiaozhi.watch/.MainActivity   # 拉起一次，前台服务+接收器就位
"$ADB" shell pm disable-user --user 0 com.heytap.wearable.breeno   # 禁用小布，去竞态
# 回滚（恢复官方小布）：
"$ADB" shell pm enable com.heytap.wearable.breeno
```

### 9.4 实测方法（无 root 模拟上键长按）
上键 = KEYCODE_F1(131)，硬件是 gpio-keys（`/dev/input/event2`，扫描码 59）。长按 = DOWN 保持 >800ms 再 UP：
```bash
"$ADB" shell input keyevent 224                                   # 先亮屏
"$ADB" shell sendevent /dev/input/event2 1 59 1; "$ADB" shell sendevent /dev/input/event2 0 0 0   # F1 DOWN
sleep 2                                                            # 保持>800ms 触发 framework 长按
"$ADB" shell sendevent /dev/input/event2 1 59 0; "$ADB" shell sendevent /dev/input/event2 0 0 0   # F1 UP
"$ADB" logcat -d -s XZW | grep NaviKey                            # 应见"上键长按 → 拉起小智"
"$ADB" shell dumpsys activity activities | grep mResumedActivity  # 应为 com.xiaozhi.watch
```
**实测结果（09-13）**：小布禁用后，上键长按 → 仅 `NaviKey: 上键长按 → 拉起小智`，ResumedActivity=`com.xiaozhi.watch`。接管成功。

### 9.5 遗留 / 下一步
- [ ] 真人在表上按一次上键长按做最终体感确认（sendevent 已证链路，但真机手感/开麦时机待人体检）。
- [ ] 常驻前台服务的耗电实测（哑通知，理论极低，需跑 24h 看续航）。
- [ ] `talkms=8000` 自动开麦首轮体验调参（是否合适、要不要 loop、要不要先 TTS 提示音）。
- [ ] 进程被系统杀后重建：靠 `START_STICKY` + `BootReceiver`；若发现长时间息屏后接收器掉线，考虑 `onTaskRemoved` 自拉起或 WorkManager 兜底。
- [ ] 左滑卡片：整包禁用小布后变死卡片。已确认**换不了整张头卡**（launcher 内置显式启动），但可长按左滑页 →「编辑」→ 在**常用应用区**（≤5 个，支持第三方）把"小智"钉进去作为替代入口。用户已接受失去原生卡片，此项按需。
- [ ] 下键短按（方案 A，`single_shortcut_*` 改指小智）用户当前**不做**，保留给"回到上个任务"。

## 10. 卡片路线深挖（左滑 QuickCenter 头卡 vs 表盘 complication）

用户追问"能不能把整个卡片换成小智"。把"卡片"拆成三个不同的 surface 逐一定性（全部代码级 + 真机验证）：

### 10.1 左滑页的「小布语音头卡」= 死路（确认不可换）
- 点击走 `la.java → AbstractViewOnClickListenerC0265aa.a()`：`new Intent(MAIN).setComponent(breeno/.MainActivity)` **显式启动**，无隐式回退 → 声明同 action 也拦不到。
- 卡片内容（推荐词 `breeno_suggestion*`）由 `heytap.wearable.intent.action.breeno.DATA_CHANGE` 喂，但该广播 **`permission=ACCESS_RECOMMEND`（signature|privileged）**，第三方无权发 → 喂不了假数据。
- 头卡是无条件 `addView` 的，无隐藏/替换开关。禁用小布后 → 死卡（点击 ActivityNotFoundException 静默失败）。
- **结论：这张卡本身无法变成小智**。唯一补偿：同页下方的**常用应用区**可长按编辑钉入小智（官方、可逆、支持第三方，`getActivityList` 黑名单只排 modemtestmode/ECG/用户隐藏项）。缺点：是普通 app 图标 tile，非对话卡，且小智无自定义图标（当前显系统默认）。

### 10.2 表盘上的「小布 complication 图标」= 可夺舍（已实现+实测通过）★推荐
- 与头卡不同，表盘图标走**隐式**：`BreenoProviderService.getTapAction()` 返回字符串 `com.heytap.wearable.breeno.intent.action.SPEECH`，launcher `ComplicationTapUtils.buildTapIntent()` 见它无 "/"、无 pkg 前缀 → `intent.setAction(str)`（**纯隐式**）。
- 所以给 `MainActivity` 声明同 action + DEFAULT 类目即可抢 resolver；**禁用小布后小智是唯一 resolver**。
- 真机验证（09-13）：`resolve-activity -a …SPEECH` → 禁用态下返回 `com.xiaozhi.watch.MainActivity`；`am start -a …SPEECH`（模拟点表盘小布图标）→ 小智开 + 自动开麦（新增：SPEECH action 进入默认 `talkms=8000 loop=2`）+ 表情球 `@listening`。接管成功。
- 代价/前提：① 需**禁用小布**（与 §9 上键长按共用，一次禁用两个入口都收）；② 该表盘得真的摆了小布 complication 槽位（部分表盘没有，需选带小布图标的表盘）；③ 小智常驻后台才秒开（否则冷启延迟）。
- 与上键长按正交，互不影响：头卡（显式）仍死，表盘图标（隐式）已被夺。

### 10.3 小智自注册 complication provider（表盘自定义位）= 暂不做
- 机制上可行：launcher `ProviderInfoManager.q.b()` 用 `queryIntentServices(ACTION_COMPLICATION_UPDATE_REQUEST)` **跨全包**枚举，任意 app 声明该 action 的 Service + metadata 即成表盘可选 provider。
- 但门槛高：① `getInstalledProviders` 要求 metadata `SUPPORTED_MODES` 且按 `PREVIEW_SMALL/MEDIUM/LARGE(+_SINGLE)` **资源图标 id**（`Icon.createWithResource`，非外部 Icon）——本工程无 res/，要动 aapt2 compile 链路；② 运行时数据用 `LauncherProviderService.buildComplicationData`（Icon 外部可），但继承的是 heytap SDK（`sdk-watchface.jar` 在 tools/）；③ 最终还得用户的表盘有**可编辑空槽**且编辑器列出外部 provider（未证实 OPPO 官方表盘开放此口）。
- **结论：重 + 不确定，收益不优于 10.2，暂缓**。若将来要"表盘上放小智自己的圆形图标"再啃这块。

### 10.4 一句话给决策
> 想要"表盘一个图标点了就跟小智说话"→ **10.2 已就绪**（已并入当前 APK，只需在带小布图标的表盘上、且小布保持禁用）。左滑那张头卡本身换不了（10.1），退一步可把它旁边的应用区钉上小智，或直接用已夺舍的上键长按/表盘图标入口。


