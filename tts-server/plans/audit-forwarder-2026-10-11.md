# 转发器 Android 交互审计

日期：2026-10-11。方法：ECC click-path-audit；优先 CodeGraph 读取当前源码。以下路径相对 TTS Server，结论是静态调用链确认，尚未真机复现。

## 状态与副作用

- `ForwarderHostViewModel.switchStateLiveData` → Host `onSwitchChanged` → 启动服务 / `instance.close()`。
- `AbsForwarderService.onCreate:67` 即设 `isRunning=true`；`onHandleIntent:91` 发启动广播，再 `startServer` 阻塞运行；结束/失败后发关闭广播；`onDestroy:122` 才设 false。
- 首页接收启动广播清日志、置开关 true、禁端口；关闭广播置 false、启端口。创建首页时没有调用 `updateState`。
- 网页创建时 loadUrl 当前地址；启动广播仅 reload 已加载 URL。
- 快捷 Tile 点击后直接标 active/inactive，不等待服务器结果。

## 确认问题

| ID | 级别 | 入口与证据 | 状态链 / 影响 | 修缮与验收 |
|---|---|---|---|---|
| F01 | P1 | `ui/forwarder/AbsForwarderHomePageFragment.kt:40-84,91-96`；`ui/forwarder/sys/HomeFragment.kt:19` | 服务启动后重建首页，只追加运行日志，没有同步开关/端口禁用；sys 的 `isServiceRunning` 是实例创建时快照 | 首次创建/onResume 从服务状态渲染；sys/Host 状态改实时读取或共享状态流。启动后切页、旋转、重新进入都显示真实状态 |
| F02 | P1 | `service/forwarder/ms/MsTtsForwarderService.kt:26-27`；`service/forwarder/system/SysTtsForwarderService.kt:36,38`；首页 `:43-49,101-119` | 两种服务共用 `ACTION_ON_CLOSED`、`ACTION_ON_LOG`；任意一方关闭会关闭另一方页面开关，日志互串 | 广播加服务命名空间或类型字段过滤。两服务同时开启，关闭其中一个，另一页仍运行且日志隔离 |
| F03 | P1 | `ui/forwarder/AbsForwarderWebPageFragment.kt:113,148-152`；`ui/forwarder/sys/WebFragment.kt:10` | 网页已载 A 端口 → 首页改成 B → 启动 → reload 仍请求 A；sys port 本身是创建时快照 | 启动 ready/端口变化后 loadUrl 真实监听地址，并管理网页错误/重试态。改端口重启后内嵌页与外部浏览器一致 |
| F04 | P2 | `ui/forwarder/AbsForwarderWebPageFragment.kt:51-86` | 自定义 JS alert/confirm 拦截后，按返回或触摸外部取消没有 confirm/cancel，网页 JS 挂起 | onCancel 回调完成 JsResult；每次只完成一次。取消后可再次试听/复制/确认 |
| F05 | P2 | `service/forwarder/AbsForwarderService.kt:137-148` | 两种转发器通知统一导航 `INDEX_FORWARDER_SYS`；PendingIntent 相同组件/requestCode/action，仅 extras 不同，后续修正 extras 仍可能复用 | 注入目标页面 index，独立 requestCode/action，处理已有 MainActivity intent。各通知从冷/热启动都到对应页面 |
| F06 | P2 | 首页 `:62-69` | 每次输入立即落偏好，空/溢出静默保留旧值，0/65536 等无范围校验。显示输入和真实使用端口可能不一致 | 输入草稿与已提交端口分离；启动前验证 1..65535，错误留在输入框，不启动、不写配置 |

## 需复现的风险（不得作为已确认运行缺陷统计）

- F07：服务 `onHandleIntent:91-96` 在 bind 成功前广播 started；网页 reload 可能先于监听 ready，Tile 失败后仍 active。用占用端口、慢启动、启动中停止、连续点击验证。方案是 Idle/Starting/Running/Stopping/Failed 状态，ready 必须来自实际 listener；先核对 Go/AAR 是否已有 ready 回调，不能只移动广播到阻塞 startServer 返回后。
- F08：`HostFragment` token 修改只写 `ServerConfig.token`，`MsTtsForwarderService.startServer:57-59` 只启动时读一次。服务运行时需要重启提示或明确应用操作，避免提示设置完成却仍用旧 token。
- F09：Tile/shortcut 使用 startService 的后台启动兼容性；必须按真实 targetSdk、Android 版本/前台状态复现，避免盲目整体迁移服务。
- F10：sys 服务未清 instance / LocalTtsEngineHelper 生命周期，stop/start 与并发网页换引擎的资源清理需真机验证。

## 覆盖与限制

已追踪：首页开关与端口、广播日志、两转发器 Host 菜单、网页刷新/返回/JS对话框、服务开始/结束、通知入口/复制/退出、Tile/桌面 shortcut；已读对应导航与首页 layout。Go 网页/API 在独立审计报告。

ADB 位于 `D:/code/AndroidSdk/platform-tools/adb.exe`；本次 `devices -l` 失败，提示 daemon 启动失败、无法连接，因此没有真机覆盖。工作区及 TTS Server 无 `.git`；采用文档直接模式，不创建分支/PR。没有修改业务代码或声称构建测试通过。
