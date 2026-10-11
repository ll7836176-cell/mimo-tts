# TTS Server Web 与外围交互审计

日期：2026-10-11（Asia/Shanghai）。方法：ECC `click-path-audit`，先 CodeGraph、再对未索引/索引漂移文件直接检查。仅审计与制定修复方案，未修改业务代码。

## 证据边界

- 下述 Web 协议缺陷是**当前 Go / HTML / Kotlin 源码之间的确认不一致**。Android `app/build.gradle:205` 实际使用 `app/libs/*.aar`；`tts-server-lib/tts_server_lib.aar` 与 `app/libs/tts_server_lib.aar` 是否由当前源码生成未验证，因此不能直接声称已安装 APK 必然呈现所有 Go 缺陷。
- 微软 Web 来自 `github.com/jing332/tts-server-go` 固定依赖（`tts-server-lib/go.mod:6`），本仓仅有 `ms_tts_server.go` 包装。本次未获取其完整外部网页源码，未将其按钮标为审计完成。需要复核固定版本源码、重建 AAR 并从真实运行包访问网页。
- 未启动 Android/Go 服务、未执行真实 TTS、未导入第三方应用、未运行全套构建。已用 Node 的标准 URL 解析与字符串语义验证 URL 截断、二层链接参数丢失和 `null.trim()`。
- 下列路径均相对 `D:/code/MiMo TTS Export/TTS Server`，行号来自当前文件。

## 状态与副作用地图

| 状态 | 写入动作 | 副作用/约束 |
|---|---|---|
| Web engine | `engineChanged` | 持久化并异步取 voices；未取消旧请求、未隔离响应所属引擎 |
| Web locale/voice | `localeChanged` / `voiceChanged` | 持久化但启动时的 `storageLocale/storageVoice` 快照不再更新；重新装载列表可回退到旧值 |
| Web custom | `switchCustomChanged` | 只展开/折叠；没有保存 isCustom |
| Web sampleText | onbeforeunload | 保存文本；初次 onload 对 null 调用 trim |
| Web test | `onBtnTestClick` | 禁用按钮、显示加载、GET、Blob 播放、finally 复位；无显式取消 |
| Go TTS | `ttsAPIHandler` | 校验、排队、Go callback、响应或请求取消时 `OnCancelAudio(engine)` |
| Android 音频 | `SysTtsForwarderService.getAudio/cancelAudio` | 单共享 LocalTTS；换引擎销毁旧实例；取消按 engine 匹配 |
| FilePicker useSystem | select/save | 保存失败会设 false；选择文件/目录失败却设 true 再递归 |
| Settings | preference change | 写入各 Kotpref；列表对话框调用 change listener 后更新 value/summary |

## 确认问题与修复任务

### WEB-01 · P1 · 默认试听与导出的阅读配置没有 voice，当前服务拒绝

- 入口：`tts-server-lib/sys_tts_server/public/index.html:242` 试听、`:218` 阅读链接。
- 调用链：custom 默认关闭 → HTML `:255-258` 不传 voice → `/api/tts` → `server.go:380-383` 强制 voice 非空 → 400 `request.invalid`。
- 阅读调用链：HTML `:226` 仅 custom 开启时加 voice → `logic.go:24-25` 生成空 voice → 阅读请求也被拒绝。
- 修复：明确支持“引擎默认声音”语义；不要盲目传空字符串到严格 LocalTTS 校验。可在转发器边界解析引擎默认 voice，或者网页必须选择有效 voice 并清晰解释；保持旧阅读配置兼容。
- 验收：custom 关闭和开启均可试听/导入；空目录显示可恢复提示；旧空 voice 配置有明确兼容处理或错误。

### WEB-02 · P1 · 音调范围与单位跨层断裂

- HTML `:69-71` 允许 1..300，默认 100；`server.go:382` 只接受 -100..100。
- 调用链：音调调到 150 → GET pitch=150 → Go parse 校验 → 400。
- Android `LocalTtsParameterPolicy.kt:9` 以 value/100 解释持久化音调，`:13` 系统 pitch 为 (value+100)/100；应核对转发器调用的 `synthesizeWithOutcome` 最终使用哪一套语义，默认 100 可能被理解成 2x。
- 修复：定义唯一转发器参数协议与单位，在 HTTP → Android 边界显式转换；同步 HTML、Go 验证、Legado 模板与默认值。不要仅扩大服务范围掩盖单位差异。
- 验收：默认正常音调，最小/最大和中间值的倍率符合说明；网页和导入配置相同参数产生相同声音。

### WEB-03 · P1 · 原始拼接 GET 与二层阅读链接导致参数被截断

- 试听 `index.html:255` 将 text、engine、voice 原样插入 URL；`&` 变参数边界、`+` 变空格、`#` 后面的内容成为 fragment。
- 阅读 `:224-226` 未按参数编码 name/engine/voice/api；一键导入 `:215` 将完整内层 URL 原样写入 src。
- Node 验证：文本 `A&B+C#D` 被解析成 `A`，engine 丢失；二层链接 src 只保留 `/api/legado?api=.../api/tts`，name/engine/pitch 成了外层参数。
- 修复：试听使用 JSON POST 或 URLSearchParams；普通 URL 参数和 `legado://...src=` 分层编码，生成 `logic.go:24-25` 的 TTS 模板时同时处理固定值与阅读占位表达式。
- 验收：中文、空格、`& + # ? %`、换行、含符号引擎名/音色名完整往返；阅读 JSON 与一键导入均保持所有参数。

### WEB-04 · P2 · 初次进入页面 onload 抛 TypeError

- `index.html:138` localStorage 缺 key 返回 null，`:152` 直接 `storageSampleText.trim()`。
- 实际：onload 中断，默认文本恢复代码失效；独立初始化 fetch 已发出，故不能表述为全部网页立即失效。
- 修复：为空值设默认字符串，对空白值统一处理。
- 验收：清空存储首次进入、空字符串、空白字符串和已保存文本均不报错并显示正确文本。

### WEB-05 · P2 · 自定义开关不能保存，旧字符串 false 被恢复成开启

- `index.html:140-144` 只控制 collapse，未 localStorage.setItem；`:147-149` 将存储字符串直接赋布尔 checked。
- Node 验证 `Boolean("false") === true`。
- 修复：显式布尔序列化/比较；更改开关即持久化；处理旧存储与默认值。
- 验收：分别开启/关闭后刷新仍一致；旧 `"false"` 正确关闭。

### WEB-06 · P2 · 快速切换引擎时旧响应覆盖新引擎声音

- 调用链：`engineChanged:159-162` → `fetchVoices:305-318` → 任意完成响应都写共享 voices 并刷新 UI。
- 触发：A 请求未返回时切 B，B 先完成而 A 后完成；最终 engine=B，列表却来自 A。
- 同处无空目录防护：`getSelectedOption` 返回 undefined，`:306/:350` 取 value 会抛错。
- 修复：请求 generation 或 AbortController 并在提交响应时核验 engine；加载中禁用试听/导入，空目录显示明确状态；恢复选择按当前引擎归属校验。
- 验收：逆序响应最终只显示当前引擎；空 engines/voices 不报未捕获异常；失败可重试。

### FILE-01 · P1 · 系统选择器失败后递归重复相同失败动作

- `FilePickerActivity.kt:224-232` 与 `:250-258`：launch 抛错 → useSystem=true → 递归 selectFile/selectDir。
- 实际：不能切换内置选择器，持久失败时不断弹提示并递归，最终可能栈溢出。作为对照，saveFile `:216` 正确设 false。
- 修复：有限次尝试，失败切到内置或显示可关闭错误；文件与目录共享一致降级逻辑。
- 验收：注入 ActivityNotFoundException，最多一个错误提示且内置只启动一次；再次失败仍可退出；取消不重试。

### FILE-02 · P1 · 内置 MIME 筛选没有匹配通配符

- `FilePickerActivity.kt:242` 用 `fileMimes.contains(actualMime)`。
- 来源：导入 `BaseImportConfigBottomSheetFragment.kt:102` 传 application/json + text/*；BGM `BgmTtsEditActivity.kt:77` 传 audio/*。
- 实际：text/plain、audio/mpeg 等均不等于通配符，文件被隐藏；BGM 的“添加文件”在内置模式可能只看到目录。
- 修复：实现 MIME 主类型通配匹配、*/* 与未知 MIME 的明确规则，统一系统与内置过滤意图。
- 验收：text/* 可选文本，audio/* 可选 mp3/wav，application/zip 仍只选预期文件；目录导航始终可用。

### FILE-03 · P2 · 选择取消返回成功，保存空 stream 也可提示成功

- 选择回调 `FilePickerActivity.kt:59-68` 对 null 仍 resultAndFinish；`:71-76` 无条件 RESULT_OK。
- `AppActivityResultContracts.kt:50-56` 忽略 resultCode；BGM `:42-45` 只判断 Pair 自身是否 null，因此取消可能弹“路径为空”。
- 保存 `FilePickerActivity.kt:146-148` 若 openOutputStream 返回 null，safe call 不写数据却仍 toast 保存成功。
- 修复：定义明确成功/取消/失败结果；空 stream 抛可读错误；只在落盘成功后提示。
- 验收：所有模式取消后无副作用/错误提示；不可写 provider 绝不显示保存成功；成功返回有效 URI。

### IMPORT-01 · P1 · 未知配置类型在主线程协程抛未捕获异常

- `BaseImportConfigBottomSheetFragment.kt:124-147` 的 try 只覆盖 readConfig；getConfigType 后 UNKNOWN 分支直接 throw。
- 调用链：导入未知文本 → 主线程 lifecycleScope.launch → wait dialog 在 finally 收起 → UNKNOWN throw，不进入 internalImport 的 runCatching (`:167-172`)。
- 修复：类型识别/校验/实际导入置于一致错误边界，显示错误并允许再次选择来源。
- 验收：未知 JSON/纯文本/错误结构均显示可恢复错误，Activity 不退出，紧接着导入有效配置可完成。

### NAV-01 · P2 · 转发器通知入口映射错误且热启动忽略页面索引

- `AbsForwarderService.kt:145` 两种服务都写 INDEX_FORWARDER_SYS；`MainActivity.kt:212-215` onNewIntent 只处理导入，不应用 KEY_FRAGMENT_INDEX。
- 实际：微软通知冷启动可能进入系统转发器；复用已存在 Activity 时目标页面索引无效果。
- 修复：服务提供自己的导航目的地，区分 PendingIntent identity，统一 cold/newIntent 导航处理；与主审计的服务任务合并，避免重复修复。
- 验收：两种通知在冷启动、热启动、两服务同时存在场景都进入对应页面。

## 风险与待运行确认（不计作已复现缺陷）

1. **AAR 发布链**：必须确认当前 AAR 对应源码与网页；修复 Go/HTML 后重新 gomobile bind、更新 app/libs AAR，保留构建版本/哈希，并验证 APK 中实际页面/API。
2. **系统转发器 LAN 地址**：`server.go:91-94` 默认监听 127.0.0.1，而 Android `AbsForwarderService.kt:60-61` 广播/复制 outbound IP；`SysTtsForwarderService.kt:57-118` 未配置 listen host。若当前 Go 编译进 AAR，其他设备按复制地址将访问失败。应把监听策略、安全配置、展示地址作为一个明确功能设计。
3. **鉴权链未贯通**：Go 提供 token 设置且 authorized 只读取 Header (`server.go:311-319`)，HTML 所有 fetch 和阅读生成均未传 Header；Android 尚未调用相关设置 API。开启 token 后应把网页登录与阅读 Header 配置一起实现，不能只打开服务鉴权。
4. **取消/播放**：网页没有 AbortController 或停止请求按钮，音频控件的暂停仅停止播放，不取消进行中的 HTTP；`:271` Blob URL 未 revoke，`:273` play Promise 未 await/catch。建议明确试听停止语义并回收 Blob。
5. **取消后的并发释放**：`server.go:148/178-182` 请求 context 结束后回调 goroutine 仍可能收尾但已经释放单槽；Android 共享 mLocalTTS。应注入慢取消回调验证新请求不会修改仍执行的旧实例，必要时等待回调完成/使用 request ID。
6. **在线 CDN**：系统页 `:7-8/124-125` 依赖远程 jQuery/Bootstrap；离线或 CDN 不可达会使交互脚本无法初始化。计划将必要资源内嵌并做离线验收。
7. **读写 URI 权限**：`ExtensionUtils.kt:26-30` 无条件申请 persistable READ|WRITE，文件选择/保存回调均未捕获；只读 provider 可能抛 SecurityException。应用实际获授 flags/仅所需读权限，并以真实 provider 验证。
8. **清理 Web 数据与 token**：MS Host 清理全局 WebStorage/Cookies (`:57-68`) 但当前页面未刷新；设置 token (`:89-93`) 仅持久化，运行服务只在 startServer 读取 token。应让“生效时间/需要重启”反馈与唤醒锁设置一致，验证页面清理后不把旧内存值在卸载时写回。
9. **帮助退出**：`AppHelpDocumentActivity.kt:102-117` 故意阻止未滚到底部退出；bottom 只在 scroll 事件计算 (`:128-132`)。若内容不需滚动也可能挡退出。建议按正常导航允许退出，把阅读提醒作为提示；属于 UX 决策而非意外断线。
10. **设置生效策略**：SettingsFragment 直接写配置，部分运行服务是否实时采纳未在本分工验证；应区分立即生效/下一请求/需重启，并逐项追踪实际消费者。

## 覆盖清单

| 区域/触点 | 追踪结果 |
|---|---|
| 系统 Web 引擎载入/切换 | HTML → /api/engines、/api/voices → Kotlin getEngines/getVoices；WEB-06 |
| 自定义开关、地区、声音 | HTML handlers → localStorage/collapse；WEB-05/06 |
| 测试文本、语速、音调、试听 | HTML GET → Go parse/gate → callback → LocalTTS 文件 → WAV → Blob；WEB-01/02/03/04 |
| 音频控件播放/暂停、请求取消 | audio 控件与 context cancellation 分开；待实机验证取消/播放风险 |
| 阅读弹窗、复制、关闭、一键导入 | URL 生成、execCommand copy、bootstrap modal、deep link；WEB-01/03；复制已有失败反馈 |
| 微软转发器网页 | 确认外部依赖入口，未获得完整页面，保留专门补审任务 |
| 微软打开 Web、清数据、token、唤醒锁、快捷方式 | Host handlers 到持久化/启动；生效反馈与全局清理风险 |
| 主抽屉导航、主题、设置、帮助、导入链接/文件、onNewIntent | 追踪 MainActivity 与帮助；NAV-01；服务启停由主审计负责 |
| Settings 列表/开关/语言/编辑器主题/备份与直链设置入口 | click → 具体配置写入或 Activity；对话框更新路径已核查；运行消费者另验 |
| 文件选择模式提示、系统/内置文件/目录、保存、取消 | FILE-01/02/03，URI 权限风险 |
| 导入文件选择、读取、类型识别、导入、取消选择 | IMPORT-01 与 FILE-02；具体配置合并由其他审计分工覆盖 |
| 系统 TTS 日志清空、长按复制、滚到底部 | SysTtsLogPageFragment → BrvLogHelper；静态路径存在；持续 append/生命周期需实机 |
| 转发器日志追加/启动清空/自动滚动 | AbsForwarderHomePageFragment:98-121；共享服务广播与启停由主审计覆盖 |

## 建议执行顺序

1. 冻结并验证源码 → AAR → APK 版本链，获取固定版本微软网页补审。
2. 定义转发器 engine/voice/rate/pitch、默认声音、LAN/鉴权协议，修 WEB-01/02；协议合同用 Go handler 测试覆盖。
3. 修 Web 编码、初始化、选择并发、空目录、试听取消/播放恢复；真实浏览器覆盖 DOM/请求并验证 Legado 导入。
4. 修文件选择降级/MIME/结果与导入错误边界；Android 测试覆盖 launch 失败、取消、只读 provider、未知配置。
5. 与主审计合并通知导航、服务状态、生效反馈；真机按离线/权限拒绝/网络失败/服务重启矩阵验收。
