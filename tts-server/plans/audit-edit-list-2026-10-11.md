# TTS 列表、分组与编辑器交互审计

日期：2026-10-11。方法：ECC click-path-audit，先通过 CodeGraph 获取当前代码及调用顺序，再检查共享状态与最终结果。此次仅静态审计，未执行 Android 真机交互、外网合成或测试。所有行号基于审计时源码；相对路径均以 TTS Server 为根。

## 状态与副作用映射

| 状态 | 写入入口 | 旁路与副作用 |
| --- | --- | --- |
| 编辑草稿 SystemTts/tts/speechRule | BasicInfoEditView、各 ParamsEditView、各编辑器 VM | 许多输入事件直接修改草稿；Base.onSave 再计算标签名并通过 RESULT_OK 返回 |
| 列表 Room 配置 | SysTtsListItemHelper 开关/快捷编辑/删除、ListGroupPageFragment 分组操作 | 多选规则可能先批量取消其他配置；UI 监听 Room Flow 刷新 |
| 当前运行配置 | 父审计确认的 RuntimeConfigWriter.publish/Coordinator 事务 | 不可将 Room UI 已刷新视为运行版本已发布；修改入口需纳入统一发布协议 |
| 试听输入 AppConfig.testSampleText | BaseTtsEditActivity.onStop | BGM 无试听输入却写空字符串，影响后续所有列表试听 |
| 编辑器试听播放器与引擎 | Base.playAudio/stopPlay/onDestroy；Local VM doTest | 引擎失败被兼容 API 折叠为 null/false；编辑器只订阅成功回调 |
| 列表试听 | SysTtsListItemHelper.listen | onLoad → direct startPlay 或 getAudio → finally dismiss+onDestroy → 非 direct 播放；直接模式已有专门成功分支 |
| 快捷编辑提交 | QuickEditBottomSheet.onDismiss → saveData → dismissCallback | 任何 dismissal 均提交；旋转重建依赖构造参数，需复测 |
| 导出内容 | VM.exportConfig → ConfigExportBottomSheet.tvConfig | String 同时承载 JSON 与错误信息；保存、复制、上传均消费同一文本 |

## 确证缺陷（confirmed，静态可证明；未动态复现）

### EL-01 / P1：本地与 MiMo 编辑器试听失败无法退出等待状态

- 证据：`app/src/main/java/com/github/jing332/tts_server_android/ui/systts/edit/local/LocalTtsEditActivity.kt:42`，`:43`，`:53`；`ui/systts/edit/local/LocalTtsViewModel.kt:319`，`:332`，`:335`；`model/speech/tts/LocalTTS.kt:521`，`:545`（后两类省略相同 app/src/main/java/com/github/jing332/tts_server_android 前缀）。
- 调用顺序：onTest 校验目录 → waitDialog.show → VM.doTest → direct 的 startPlay 返回 false 或非 direct 的 getAudio 返回 null → 两个成功回调均不执行 → waitDialog 没有 dismiss，错误没有展示。VM 没有失败回调或 finally；异常同样没有统一收尾。
- 条件：目录加载成功后子引擎初始化失败、即时 API 拒绝、回调失败、空合成音频等；MiMo 是 LocalTTS 的外部 Android 引擎，因此适用其鉴权/网络错误。
- 修复：试听使用结构化 EngineSynthesisOutcome，处理 Success/Failure/Stopped，提供失败反馈，finally 收尾等待状态，取消请求关联生命周期。
- 验收：注入 init failure、false/null、回调错误和取消，等待必消失、错误可见、页面仍可修改并重试；direct/non-direct 成功仍可播放。

### EL-02 / P1：清空或溢出采样率使编辑器崩溃

- 证据：`ui/systts/edit/local/LocalTtsParamsEditView.kt:106`，`:107`；`ui/systts/edit/http/HttpTtsEditActivity.kt:83`，`:111`，`:127`，`:132`。布局 `app/src/main/res/layout/systts_local_params_edit_view.xml:61` 与 `systts_http_edit_activity.xml:62` 是可编辑数字输入。
- 调用顺序：Local 每次文字变化 → toInt；HTTP 点击试听/保存 → updateValueToTts → toInt。没有捕获 NumberFormatException。
- 条件：清空字段，或键入大于 Int 范围的数字。inputType=number 不阻止空串或数字溢出。
- 修复：toIntOrNull+有效范围校验；编辑中的暂态空串不改写模型，提交时显示字段错误并阻止提交。
- 验收：全选删除、超大数字、零、合法采样率均不崩；非法字段阻止保存/试听；合法值持久化且重新打开一致。

### EL-03 / P2：空白试听回填后仍合成空文本

- 证据：`ui/systts/edit/BaseTtsEditActivity.kt:94`—`:97`。
- 顺序：缓存 textStr → 若空白 setText(AppConfig.testSampleText) → onTest(textStr) 使用旧值。
- 修复：先选定 effectiveText，再同步输入并将同一个值交给 onTest；全局样本也空时使用资源默认文本。
- 验收：清空输入点击试听，显示内容与实际合成内容一致且非空，所有继承编辑器均覆盖。

### EL-04 / P2：进入/退出 BGM 编辑器清空全局试听样本

- 证据：`ui/systts/edit/bgm/BgmTtsEditActivity.kt:65`—`:66`；`ui/systts/edit/BaseTtsEditActivity.kt:83`，`:102`—`:105`；列表 `ui/systts/list/SysTtsListItemHelper.kt:193`，`:205`。
- 顺序：BGM 设置 liteMode → setEditContentView 提前 return（testInputLayout 未设置）→ onStop 将 null 转成 "" 写 AppConfig → 后续列表试听使用空样本。
- 修复：无试听输入的页面不覆盖全局样本；只保存合法非空文本，保留显式默认文本。
- 验收：先设置样本 A，进入 BGM 后返回，普通列表试听仍合成 A。

### EL-05 / P2：取消一个分组会同时关闭其他分组

- 证据：`ui/systts/list/ListGroupPageFragment.kt:61`—`:69`；`data/dao/SystemTtsDao.kt:67`，`:73`。
- 顺序：分组多选关闭 → 无论 v.isChecked 真或假均 setAllTtsEnabled(false) → setTtsEnabledInGroup(当前组, v.isChecked) → 通知。
- 条件：单组模式下点击未启用组的取消态/存在历史、导入或其他入口产生的跨组启用状态时，会不必要取消其他组；应特别通过历史状态复现，普通纯单组选中组取消时表象不明显。
- 修复：只有启用组时才取消其他组；启停事务化，并统一处理多语音/备用/自定义标签约束。
- 验收：关闭 B 只改变 B，启用 B 按单组选项关闭 A；多组模式互不干扰；部分选中态操作有明确约定。

### EL-06 / P2：删除分组没有配置通知且删除过程不原子

- 证据：`ui/systts/list/ListGroupPageFragment.kt:261`—`:264`；`data/dao/SystemTtsDao.kt:104`—`:106`。同文件单条删除 `ui/systts/list/SysTtsListItemHelper.kt:391`—`:395` 有通知。
- 顺序：确认删除 → deleteTtsByGroup → deleteGroup → 返回；缺少事务，也不调用 notifyUpdateConfig/统一发布入口。
- 结论边界：删除入口缺少发布/通知已确证；运行服务是否立即自动更新由父审计核对新版运行配置读取机制，不能仅凭 UI Room 刷新断言生效。
- 修复：统一配置变更发布入口内事务删除并在 committed 后通知；失败保持原状态；不得简单添加广播而绕过已存在发布协议。
- 验收：已启用组删除后 UI/运行快照同一版本且不再选中已删路由；注入第二步失败验证无部分删除；重启结果一致。

### EL-07 / P2：导出失败字符串可作为 JSON 保存/上传

- 证据：`ui/systts/list/SysTtsListViewModel.kt:14`—`:19`；`ui/systts/list/SysTtsListFragment.kt:313`—`:317`；`ui/systts/ConfigExportBottomSheetFragment.kt:49`—`:53`，`:86`—`:101`。
- 顺序：序列化/数据库异常 → catch 返回 "导出失败：…" → onUpdateConfig 将非空文本显示为配置 → 保存为 ttsrv-list.json/复制/上传均接受此文本。
- 修复：导出结果用 Result/密封类型区分 payload 与错误；失败显示错误并禁用操作；保存/上传前保证成功有效配置。
- 验收：注入读取及序列化失败，不能生成假 JSON 或上传错误文本；恢复后可重试；成功 JSON 能回导。

## 风险与需验证事项（risk，不作为动态已确认 bug）

1. Microsoft 异步选择竞态：`ui/systts/edit/microsoft/MsTtsEditViewModel.kt:102`—`:124` 每次 reload 新 coroutine 写同一 mAllVoiceList，locale/voice 变化又独立 launch（`:147`，`:154`），没有 generation/mutex。快速切 API/地区可能旧结果覆盖新选择。用可控延迟 repository 验证，再添加最新代次约束；Local VM 已有 generation/mutex 可参照。
2. 列表预览与播放器生命周期：SysTtsListItemHelper 有惰性 AudioPlayer，没有已追踪到的销毁 hook；fragment.lifecycleScope 与 view 生命周期不同。离开页面/旋转/连续试听需验证是否继续播放、晚到回调显示旧窗口。需完整追踪 owner 生命周期后定案。
3. ConfigExportBottomSheetFragment/QuickEditBottomSheet 将数据、回调、View 放构造参数，系统重建用默认构造可能丢失配置或立即 dismiss。旋转/后台杀进程需要 Android 验证；改用 arguments+ViewModel+FragmentResult。
4. BGM 权限对话框“退出” `ui/systts/edit/bgm/BgmTtsEditActivity.kt:154`—`:156` 调用 onSave，会返回成功。是否应保留空配置属于产品行为决策；计划中明确退出/保存契约。
5. BasicInfoEditView.saveData `:122`—`:135` 捕获标签名失败显示错误，但 Base.onSave `:55`—`:57` 继续成功结束页面。需确认失败是否属于阻止保存错误，再定义显式返回值。
6. AudioPlayer.onPlayerError `help/audio/AudioPlayer.kt:46`—`:48` 使用取消原因 "onPlayerError"，playInternal `:83` 判断 MSG_PLAYER_ERROR，错误传播分支不匹配。这是静态确定的错误反馈漏洞，但属于公共音频链，交父审计合并，真机坏音频验证。

## 覆盖清单与验证边界

| 交互区域 | 状态 | 已追踪内容/剩余工作 |
| --- | --- | --- |
| 全部/分组列表 | 已追踪 | Room Flow、单条开关规则、快捷编辑提交、编辑/复制/删除菜单、分组开关/展开/重命名/导出/音频参数/删除；拖拽只扫描实现，未动态验证 |
| 新建/复制为其他类型 | 已追踪主要入口，返回登记仅扫描 | ms/local/http/plugin/BGM 发起编辑；复制生成 id 清名称且禁用；ActivityResult 入库回调需主审计补齐 |
| Local/MiMo 编辑 | 已追踪 | 引擎目录代次保护、缺失地区/语音禁止保存、引擎切换 rebasing、直接/文件试听与失败折叠、基础/额外参数；MiMo 实际鉴权及路由指令合成未外网验证 |
| HTTP 编辑 | 已追踪 | URL/请求头/采样率/解码选项初始化、帮助、试听/保存校验/参数更新；HTTP VM 及网络合成只扫描调用关系 |
| Microsoft 编辑 | 已追踪 UI/VM 主要路径 | API 目录回调、地区/语音/风格/角色/二级语言选择、自动名称、试听/保存；真实目录/网络未验证 |
| Plugin 编辑 | 已追踪 activity，VM 仅扫描 | loadData/loadUI、试听成功/失败、保存采样率查询失败阻止返回、finish broadcast；具体插件脚本/动态 UI 未验证 |
| BGM 编辑 | 已追踪 | 添加目录/文件、文件选择回调、点击播放列表、删除、liteMode、保存/权限退出；文件权限/播放器真机未验证 |
| 公共编辑 | 已追踪 | Base save/result/stop/destroy/test/input 持久化、BasicInfo 草稿写入/标签名失败、快捷编辑 dismissal 提交 |
| 导出 | 已追踪 | 整表/组导出、预览、复制、保存、上传入口与异常字符串；上传目标脚本执行未验证 |
| 全局播放器/BGM/音频设置 | 已追踪 | 表单初始化、reset、dismiss/confirm 写 SysTtsConfig+notify；新版运行快照消费由父审计补齐 |

## 已存在测试及建议补充

仅发现测试文件/CodeGraph 调用覆盖，没有运行，也未把文件存在视为 UI 通过。

- 单测：LocalTtsEngineSwitchPolicyTest、LocalTtsRequestStateTest、LocalTtsRequestPolicyTest、LocalTtsRequestParamsPolicyTest、LocalTtsParameterPolicyTest、HttpTtsUrlUnitTest、SpeechRuleMiMoProfileParserTest。
- Android 测试：HttpTtsUrlTest、AudioDecodeTest、MimoRestartCompatibilityTest、MimoExternalEngineBindingTest、TalkifyLocalTtsCompatibilityTest、LocalTtsRuleBundleTest、SpeechRuleEngineMiMoProfileInstrumentedTest。
- 未发现命名覆盖 Base 空试听、BGM 样本持久化、编辑器非法采样率、Local 失败等待收尾、分组取消/删除、导出失败禁用动作的交互回归测试。
- 实施优先顺序：先补 Local 失败终态与非法输入回归，再统一分组/编辑发布协议，再修空试听与样本状态，随后修导出错误类型和生命周期/异步选择风险。
- 验收策略：纯逻辑与结果状态优先本地单测；涉及文本输入、dialog、ActivityResult、旋转、权限和播放器的用 Android instrumented 测试/真机步骤。外部 MiMo/微软/插件网络通过受控 fake 先验证失败/成功终态，再单独执行已授权环境验收。
