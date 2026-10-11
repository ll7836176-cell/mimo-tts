# TTS Server 规则、脚本与数据交互审计

日期：2026-10-11。方法：ECC click-path-audit，优先 CodeGraph 定位和读取；索引报告 SystemTtsService / TextToSpeechManager 已变更，因此相关引用用当前文件搜索核对。仅静态调用链审计，没有 Android 设备实测，没有修改产品代码。

## 状态与副作用映射

- RuntimeConfigWriter.publish → Coordinator mutex → Room.withTransaction 写 current rows 和 APPLIED marker → 构造 committed candidate（当前 Writer 使用 Unit）→ CAS publication root → notifyUpdateConfig → SystemTtsService LocalReceiver → TextToSpeechManager.load。
- 替换管理器：DAO 写 replaceRule / group → Room Flow 更新列表；TextReplacer 自有 rules 缓存只在 load 更新。规则排序和总开关发送 ACTION_UPDATE_REPLACER；当前接收器未注册此 action。
- 插件管理器：保存返回、删除、开关、排序、导入、变量弹窗关闭直接写 plugin DAO → Flow 更新列表。PluginTTS 在构造时读取 plugin，并缓存 pluginEngine；不能仅以缺少 Writer 就断言所有插件 metadata 操作即时运行失败，需要验证运行实例刷新机制。
- 朗读规则管理器：选择、开关、删除、排序经 persist → Writer；persist 没有处理失败结果或异常。朗读规则导入则检查 publish 结果并显示失败。
- 通用导入：来源单选状态、fileUri、文件编辑框 tag 内容缓存；importAction 优先 tag，再按来源读取；类型检测位于读取阶段的 catch 之外；列表确认回调之后才写数据库。
- 脚本：编辑器 buffer → 保存/本地调试时 updateCode → onSave/onDebug；远程 push 也 updateCode。远程同步设置先写 enabled，再 toInt 解析并写 port；启动/关闭服务仅在 Activity onCreate/onDestroy。
- 恢复：ZIP 解压到固定 restorePath → 所有 JSON 预解析 → Writer 事务写入 → 偏好复制 → isRestart；UI 把异常转换为 false，false 同时表示正常无须重启。

## 确证问题与修复计划

### RD-01 / P1 替换规则热更新广播未注册

证据：`app/src/main/java/com/github/jing332/tts_server_android/service/systts/SystemTtsService.kt:68` 发送 replacer 专用 action；`:106` 注册 IntentFilter 仅包含 ACTION_UPDATE_CONFIG；`:520` 有专用处理分支却不可达。`ui/systts/replace/ReplaceManagerActivity.kt:317`、`:388` 发送该广播。

触发：服务已运行，调整替换排序或总开关，之后继续朗读；广播不抵达 loadReplacer。启用之前未加载过 rules 时尤其明显。

修复：接收器加入 ACTION_UPDATE_REPLACER，并把影响运行内容的替换 CRUD/导入统一走原子写入与刷新路径。分组展开、名称等轻量 metadata 单独处理，不强行增加 APPLIED generation。

验收：服务持续运行，启用/禁用、编辑/删除/导入、规则置顶/置底/拖拽，下一请求使用最新规则；失败写入保持原数据、列表和服务一致。

### RD-02 / P1 恢复失败仍弹出恢复完成

证据：`ui/preference/backup_restore/BackupRestoreFragment.kt:115` → catch `:119` 显示错误后返回 false → `:134` else → `:136` restore_finished。

触发：损坏 ZIP、JSON 解码失败或 Writer/文件复制抛异常。实际先显示错误，随后仍显示成功。

修复：使用成功/失败及是否需重启的独立结果类型；catch 后直接结束，不进入成功 UI。

验收：损坏包、写入失败、偏好复制失败均只有失败反馈；正常数据库恢复显示成功；偏好恢复显示重启提示。

### RD-03 / P1 多次恢复重复混入上一次恢复的文件

证据：`ui/preference/backup_restore/BackupRestoreViewModel.kt:46` 每次解压到同一 restorePath，`:52` 读取目录所有文件；没有在 restore 开始前清理目录。`:65` 只清理 shared_prefs，JSON 留存；仅 `:121` onCleared 清理整目录。

触发：同一页面先恢复包含 list.json 的包 A，再恢复只有 replaceRules.json 的包 B，B 的操作集合仍包含 A 的 list.json；失败恢复留下的文件也会污染下次。

修复：每次使用独立临时解压目录，finally 清理；读取仅本次有效包内列出的类型；无支持内容的包显示错误。

验收：连续 A/B 恢复、失败后恢复、空 ZIP、无关 ZIP，均不重放旧内容。

### RD-04 / P1 同版本插件/朗读规则备份无法恢复变量及用户编辑

证据：`ui/preference/backup_restore/BackupRestoreViewModel.kt:85` / `:98` 使用 insertOrUpdate。`data/dao/PluginDao.kt:42` 和 `data/dao/SpeechRuleDao.kt:44` 仅当备份版本大于当前版本才 update。

触发：备份插件变量或脚本 → 修改变量/代码但版本不变 → 恢复备份。当前 row 已存在同版，恢复静默跳过，UI 仍显示成功。

修复：分离“安装更新”的版本策略和“用户确认恢复”的覆盖策略；恢复按逻辑 ID 查找现有行并覆盖恢复字段，明确禁用状态的匹配方式。

验收：同版变量、同版代码、禁用规则及旧版备份的恢复按明确契约产生可见结果，不再假成功。

### RD-05 / P1 未知导入类型抛出未捕获协程异常

证据：`ui/base/import1/BaseImportConfigBottomSheetFragment.kt:124` 在 lifecycleScope.launch 内读取；读取 catch 在 `:135`，而未知类型在 `:147` 直接 throw，位于 catch 之外。

触发：空剪贴板、不识别 JSON 或普通文本，点击导入；不进入 displayErrorDialog，异常上抛。

修复：读取、类型检测、解码及提交均归入统一错误边界；保留输入和来源，等待用户修正。

验收：空白、未知格式、错类型、网络错误、损坏 JSON 均显示错误且页面仍可操作。

### RD-06 / P1 文件/目录选择失败无限递归重试同一个系统选择器

证据：`ui/FilePickerActivity.kt:224` → docSelector.launch → catch `:230` useSystem=true → `:231` selectFile；目录路径 `:250`、`:256`、`:257` 同样。保存路径 `:214` 则正确切换 false。

触发：设备缺少可处理 intent 的系统 picker 或 launcher 同步失败。fallback 仍选择原路径，反复失败递归直到崩溃。

修复：将 fallback 切换为内置 picker，并限制最多一次回退；两者失败显示错误并结束请求。

验收：模拟系统 picker launch 抛异常，文件/目录请求转内置；双重失败有单一错误反馈，不递归。

### RD-07 / P2 内置文件选择器不支持 text/* MIME 通配

证据：通用导入 `ui/base/import1/BaseImportConfigBottomSheetFragment.kt:102` 请求 application/json、text/*；`ui/FilePickerActivity.kt:241` 仅用 fileMimes.contains(File(...).mimeType) 作精确匹配。

触发：用户选内置 picker 试图导入 JavaScript/文本配置；text/javascript 或 text/plain 不等于 text/*，文件被过滤。

修复：使用支持 MIME 通配匹配的工具方法，兼容扩展名/无法检测 MIME 的可接受文件；目录保持可见。

验收：系统/内置 picker 对 JSON、TXT、JS 的允许集合一致，不允许类型仍被过滤。

### RD-08 / P1 远程同步端口输入异常且先写部分设置

证据：`ui/systts/base/BaseScriptEditorActivity.kt:209` 先写 isRemoteSyncEnabled，`:211` 输入直接 toInt。

触发：清空端口、非数字或整型溢出后确认；NumberFormatException 未处理，同时 enabled 已变更。

修复：先校验全部输入（合法端口 1–65535），再一次提交；错误时对话框不关闭，显示字段错误。

验收：空、负、0、65536、溢出、合法边界输入；无效输入不改变任何设置。

### RD-09 / P2 插件代码“预览并保存”调用零参数 DAO update

证据：`ui/systts/plugin/PluginEditorActivity.kt:130` previewUi 调 updatePluginCode(..., isSave=true)；`PluginEditorViewModel.kt:55` 修改内存代码后 `:58` appDb.pluginDao.update()；`data/dao/PluginDao.kt:29` 为 vararg update。

实际：零参数 update 不更新任何 row。预览可通过携带 pluginTTS 在内存中继续，不能因此宣称整个预览坏掉，但 isSave 对应持久化明确未执行。

修复：先明确预览是否应持久化；需要保存则传 pluginInfo 并经适当发布路径，否则移除误导的 isSave 分支并保持预览独立。

验收：编辑现有插件 → 预览 → 返回/取消/明确保存 → 重开，持久化行为符合约定。

### RD-10 / P1 ZIP 解压路径未限制在目标目录

证据：`utils/ZipUtils.kt:66` / `:67` 直接拼接 entryName，`:73`–`:80` 删除/创建/写入；没有 canonicalPath 目录边界检查。

触发：用户恢复带 ../ 路径的 ZIP，可写出临时恢复目录。

修复：解压前验证规范化路径位于指定目录，限制展开大小和条目数；异常中止且清理本次临时目录。

验收：../、多级穿越、绝对路径、不正常条目和正常包；非法包不触碰目标目录以外文件。

## 风险与待验证（不要写成已复现 bug）

- 通用导入 `BaseImportConfigBottomSheetFragment.kt:129` 优先文件编辑框 tag，未按 sourceType 区分；选择文件之后改为 URL/剪贴板可能仍导入旧内容。CodeGraph 本轮未完整返回文件选择 callback 的缓存赋值体，需要补取该体并设备验证。
- 替换规则 `ReplaceManagerActivity.kt:88`、`:140`、`:259`、`:315` 与替换导入 `replace/ImportConfigBottomSheetFragment.kt:60`/`:61` 直接 DAO，未见本路径的运行刷新；TextReplacer.kt:12 自有缓存，`:14` load 才刷新。服务运行时 CRUD 可能直到另一次完整配置 reload 才生效，需要逐入口实测；metadata 更改不要误判。
- 插件 `PluginManagerActivity.kt:60`、`:132`、`:145`、`:179`，变量弹窗 `PluginVarsSettingsBottomSheetFragment.kt:47`，导入 `plugin/ImportBottomSheetFragment.kt:22` 直接写 DAO。PluginTTS.kt:44 构造时读插件，`:74`/:86 缓存 engine，当前实例是否刷新需 Android 验证。
- 朗读规则 manager `SpeechRuleManagerActivity.kt:54` persist 不检查 Writer 返回也不处理抛异常；真实 DB 失败可能无反馈/未处理协程异常，需要注入失败验收。正常路径已走 Writer，不能说所有朗读规则保存都未生效。
- 远程同步设置只写偏好，没有在确认后启动/关闭当前服务；BaseScriptEditorActivity.kt:113 仅 onCreate 启动，`:137` 仅 onDestroy 关闭。需要确认 UI 是否说明“重新打开生效”，若未说明应立即应用或给明确状态提示。
- ConfigExportBottomSheetFragment 构造器携带闭包（:26）不能自动在进程重建恢复；恢复后配置可能变空。需要旋转/进程死亡验证，并改 arguments/可恢复模型。
- 插件导出 `ExportBottomSheetFragment.kt:22` lateinit pluginList 在异步 withIO (:52) 之后初始化，复选框 (:79) 可提前 updateConfig 并访问它；需延迟数据查询验证竞态。导出全部使用 allEnabled，是否应包含禁用插件需产品确认。
- 备份选择 PluginVars 自动勾选 Plugin 只更新 listView，没有同步 checkedItems；当前 ViewModel.backup 在含 PluginVars 时自行去除 Plugin，实际数据仍包含插件，因此只是选择状态一致性隐患，不认定数据丢失。
- FilePickerActivity.kt:146 输出流 null 使用安全调用跳过写入，但 :148 仍报告 save_success；模拟 provider 返回 null 验证。

## 覆盖清单

| 范围 | 状态 | 本轮覆盖 |
|---|---|---|
| 替换规则 | 已追踪静态主链 | 分组展开/勾选/重命名/删除、单条编辑/删除/置顶置底、拖拽、搜索目标、总开关、导入、导出、服务刷新 |
| 朗读规则 | 已追踪静态主链 | 行选择启用、开关、删除、排序、编辑入口、脚本保存、导入 Writer 成功/失败、导出 |
| 插件管理/变量 | 已追踪静态主链 | 编辑返回保存、删除、开关、排序、变量输入与关闭保存、导入、导出是否含变量 |
| 脚本编辑/同步 | 已追踪静态主链 | 保存、调试、预览、清缓存、导出脚本、主题/换行、同步设置/启停生命周期/push/pull/action |
| 备份/恢复 | 已追踪静态主链 | 类型选择、备份序列化、ZIP 解压、预解析、事务发布、偏好复制、错误和成功 UI、多次恢复 |
| 直链上传 | 已追踪静态主链 | 枚举上传函数、选择目标、IO invoke、空 URL/异常反馈、复制链接、进入配置页、配置保存/调试 |
| 文件选择器 | 已追踪静态主链 | system/builtin 分支、失败回退、MIME 过滤、导出写入、取消路径 |
| 规则编辑辅助/脚本执行细节 | 仅扫描 | 替换正则测试、代码引擎声明解析、PluginTTS 缓存；未逐一深追全部动态脚本代码 |
| 所有上列入口的运行表现 | 未验证 | 无模拟器/真机点击，无网络导入/上传真实请求，无文件 provider 故障注入，无旋转/进程死亡测试 |

## 建议执行顺序

1. 先修复 P1 崩溃/假成功/重复恢复/路径穿越：RD-02/03/04/05/06/08/10，新增针对故障和连续操作的回归用例。
2. 修复 RD-01 刷新闭环，定义哪些数据属于运行内容、哪些仅列表 metadata；按运行内容逐一迁移写入和提交反馈，避免无依据的全量迁移。
3. 修复 MIME 过滤与预览保存语义；核实旧来源缓存、变量变更热更新、远程同步启停。
4. Android 设备按覆盖清单逐触点验证，以最终 UI + DB + 下一次合成行为为成功标准。
