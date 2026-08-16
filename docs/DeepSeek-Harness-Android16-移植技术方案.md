---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 15f38f971c262f8445109e510a77b77a_79cd6318999111f1a98a525400f8a581
    ReservedCode1: 0vHX7AAQwuox0CFTeMO3QMR165hqh9FDmmxa1jT5xfLBh31xB9PKZ6nM6ILFB9ZSUD0+c56QJAu3DpADaGmAD94xWcD8hMGWHkzvujOZnjZs6Jweg3xGSqL0T5Ny5tPwYFQyh3G4QATz73pk/kVxlnyS+UCGmNED9lyVmOjkDlKVuQgdsca78WdyzSs=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 15f38f971c262f8445109e510a77b77a_79cd6318999111f1a98a525400f8a581
    ReservedCode2: 0vHX7AAQwuox0CFTeMO3QMR165hqh9FDmmxa1jT5xfLBh31xB9PKZ6nM6ILFB9ZSUD0+c56QJAu3DpADaGmAD94xWcD8hMGWHkzvujOZnjZs6Jweg3xGSqL0T5Ny5tPwYFQyh3G4QATz73pk/kVxlnyS+UCGmNED9lyVmOjkDlKVuQgdsca78WdyzSs=
---

# DeepSeek Harness 原生移植至 Android 16 平板 —— 技术方案

> 版本：v1.0 ｜ 目标平台：Android 16 (API 36) 平板 ｜ 交付物：APK + 本文档
> 基线：DeepSeek Harness (dsh) v0.1.0-rc.5（2026-08-13 开源，MIT 协议，开发者预览版）

---

## 1. 项目背景与核心理解

DeepSeek Harness（dsh）是 DeepSeek AI 开源的智能体框架，核心设计哲学是 **"一切皆插件"（Everything is a Plugin）**。模型适配器、工具注册表、会话日志乃至核心的智能体循环（agent loop）本身都是可插拔、可替换的插件。这一设计建立在 **Cordis 插件框架**（dsh 内部以 vendor 方式引入）之上：

- **Service & Context 架构**：插件通过声明式依赖（inject）协同工作；
- **类型化事件**：支持 emit / waterfall / parallel / serial 等事件模式；
- **可逆副作用**：所有插件注册都是可逆的，保证热重载与卸载时的状态一致性。

dsh 通过组合 **Profile** 与 **组合包（Bundle）** 构建插件树。例如 `web` profile 加载 `dsh-base`（核心能力）与 `dsh-web-app`（Web UI）。默认 `npx @deepseek-ai/dsh web` 启动运行在 `http://127.0.0.1:3080` 的 Web UI。一个完整智能体回合（turn）流经 `session → system-prompt → tools → agent → agent-loop` 等核心包。

**移植核心启示**：移植 dsh 不是简单地把 Node.js 应用打包进 APK，关键在于**完整保留并优雅暴露其"一切皆插件"的架构能力**，让用户在平板上同样享受配置、组合与扩展的灵活性。

---

## 2. 技术选型

### 2.1 总体方案：内嵌运行时快照 + WebView 容器

| 维度 | 选型 | 理由 |
|------|------|------|
| 运行时 | **内嵌 Termux 运行时快照**（Node.js + bash + coreutils + dsh 本体打包进 APK） | 开箱即用，不依赖 Termux 等外部环境；首次启动解压约 10 秒 |
| UI 容器 | **Android WebView** 承载 dsh Web UI | 复用 dsh 官方 Web UI 全部能力，零 UI 重写成本 |
| 双向通信 | **JS Bridge**（`window.androidBridge`） | Web UI 调用原生能力（文件选择、通知、屏幕常亮、窗口模式等） |
| 后台保活 | **前台服务（Foreground Service）** + **看门狗（Watchdog）** | 引擎进程不被系统杀死，异常退出自动重启 |
| 在线更新 | **清单驱动的运行时快照更新** | 不更新 APK 即可单独升级 dsh 引擎与插件 |
| 文件访问 | **SAF（Storage Access Framework）目录桥接** | 安全访问用户选择的平板存储目录 |
| 沙箱 | **bubblewrap 降级方案**（详见 §6.1） | 规避 Android SELinux 对 user namespace 的拦截 |

### 2.2 参考项目整合

| 参考项目 | 借鉴内容 | 本方案的增强 |
|----------|----------|--------------|
| `deepseek-harness-termux` | Android Bionic libc 适配补丁、node-pty 编译方案 | 直接复用其补丁集，保证 PTY/子进程能力 |
| `dsh-mobile-apk` | 内嵌快照、WebView + JS Bridge、前台服务、看门狗、SAF 桥 | 针对 Android 16 平板深度定制（见 §5） |

### 2.3 构建环境

- JDK 17、Gradle 8.13、Android SDK（platform 36 / build-tools 36.0.0）
- compileSdk = 36（Android 16），minSdk = 26，targetSdk = 34
- **targetSdk 保持 34 的原因**：Android 15/16 对 `exec()` 系统调用施加了限制（`targetSdk >= 35` 时禁止对非应用自有 native 库执行 exec）。快照内嵌的 node/bash 及每个子命令都需要 linker64 包装器才能绕过；保持 34 可让内嵌引擎在 Android 15/16 设备上正常 exec（详见 §6.2）。

---

## 3. 架构设计：Native + WebView 交互

### 3.1 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Android 16 平板 (API 36)                  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  MainActivity (WebView 容器)                          │  │
│  │  ┌─────────────────────────────────────────────────┐  │  │
│  │  │  dsh Web UI (http://127.0.0.1:3080)            │  │  │
│  │  │  window.androidBridge  ←→ 原生层                │  │  │
│  │  └─────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  EngineService (前台服务)                             │  │
│  │  ├─ 引擎进程管理 (node dsh web)                       │  │
│  │  ├─ 看门狗 Watchdog (异常退出自动重启)                │  │
│  │  └─ 快照解压/校验 (首次启动 ~10s)                     │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  内嵌运行时快照 (app 私有目录)                         │  │
│  │  ├─ node / bash / coreutils (Termux 构建)             │  │
│  │  ├─ dsh 本体 + 插件 (node_modules)                    │  │
│  │  ├─ termux-exec (LD_PRELOAD 绕过 exec 限制)           │  │
│  │  └─ dshdata (会话/配置持久化)                         │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  UpdateManager (清单驱动在线更新)                     │  │
│  │  SAF 目录桥 (Storage Access Framework)                │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 模块职责

| 模块 | 职责 |
|------|------|
| `MainActivity` | WebView 容器、JS Bridge 注册、平板/桌面窗口布局适配、方向控制 |
| `EngineService` | 前台服务，管理 dsh 引擎进程生命周期 |
| `EngineManager` | 快照解压、引擎启动/停止、健康检查、看门狗重启 |
| `AndroidBridge` | JS Bridge 接口层（文件选择、通知、常亮、窗口模式、屏幕信息、方向） |
| `UpdateManager` | 清单驱动的运行时快照在线更新 |
| `ConsoleActivity` | 终端控制台（PTY 会话，用于调试/命令行操作） |
| `LogCollector` | 开发者调试日志（按天写入 dshdata/log/） |

### 3.3 JS Bridge 接口（`window.androidBridge`）

| 方法 | 方向 | 说明 |
|------|------|------|
| `pickDirectory(callbackId)` | Web→Native | 通过 SAF 选择目录并桥接为 Termux 可见路径 |
| `keepScreenOn(enable)` | Web→Native | 屏幕常亮控制 |
| `notify(title, text)` | Web→Native | 系统通知 |
| `requestAllFilesAccess()` | Web→Native | 请求所有文件访问权限 |
| `getSystemDark()` | Web→Native | 读取系统深色模式 |
| `restartEngine()` / `reloadWebUI()` | Web→Native | 引擎重启 / UI 刷新 |
| `openConsole()` | Web→Native | 打开终端控制台 |
| `getDevLogEnabled()` / `setDevLogEnabled()` | Web→Native | 开发者日志开关 |
| **`getWindowMode()`** | Web→Native | **（新增）** 返回 phone / tablet / desktop |
| **`getScreenInfo()`** | Web→Native | **（新增）** 返回屏幕/窗口尺寸、密度、多窗口状态 JSON |
| **`setOrientation(mode)`** | Web→Native | **（新增）** 锁定/解锁屏幕方向（portrait/landscape/auto） |

---

## 4. 核心功能实现路径

### 4.1 dsh web 服务

- 快照内嵌完整 dsh 安装（`node_modules` 含 `@deepseek-ai/dsh` 及全部依赖）。
- `EngineManager` 以 `node <dsh>/bin/dsh web` 启动引擎，监听 `127.0.0.1:3080`。
- WebView 加载 `http://127.0.0.1:3080`，`setJavaScriptEnabled(true)` + `addJavascriptInterface(androidBridge, "androidBridge")`。
- 引擎就绪前显示引导页（`guideView`），就绪后自动加载 Web UI。

### 4.2 dsh headless 模式

- 快照内 dsh 完整支持 `dsh headless` 命令，可在 `ConsoleActivity` 终端中直接运行。
- 也可通过 JS Bridge 扩展暴露 headless 会话入口（后续版本）。

### 4.3 插件管理（dsh plugin）

- 快照内 `dsh plugin` 命令完整可用（install / uninstall / list）。
- 插件安装写入 `dshdata` 持久化目录，重启后保留。
- 在线更新机制（§4.7）可单独升级插件集。

### 4.4 热重载（HMR）

- 开发模式下以 `--expose-internals` 启动引擎，启用 Cordis 热重载。
- 插件文件变更后引擎自动重载，无需重启 APK。

### 4.5 子进程与 PTY（Subprocess & PTY）

- 复用 `deepseek-harness-termux` 的 **node-pty 编译方案**（针对 Android Bionic libc 适配）。
- Bash 沙箱等依赖 node-pty 的组件在快照内原生运行，是代码执行等高级功能的基础。
- 沙箱隔离层（bubblewrap）的 Android 适配见 §6.1。

### 4.6 会话持久化

- 会话数据写入 `dshdata`（app 私有目录），应用重启后由引擎自动恢复。
- 快照更新（§4.7）不触碰 `dshdata`，保证会话/配置跨版本保留。

### 4.7 在线运行时更新（清单驱动）

- `UpdateManager` 拉取远程清单（manifest），包含快照版本、SHA-256、下载 URL。
- 校验通过后下载新快照，原子替换（旧快照保留为回滚点）。
- 仅更新引擎与插件，不更新 APK 外壳，适配 dsh 快速迭代（开发者预览阶段破坏性变更频繁）。

### 4.8 Python SDK 支持

- 快照内可安装 Python（Termux 构建），`python/sdk` 可直接运行。
- 或通过 SAF 目录桥 + JS Bridge 提供 Python 驱动 dsh 的调用桥梁（跨设备场景）。

---

## 5. Android 16 特性适配方案

### 5.1 桌面窗口模式（Desktop Windowing）

Android 16 引入桌面窗口模式，应用窗口可自由调整大小。本方案：

1. **Manifest 声明**：`MainActivity` / `ConsoleActivity` 设置 `resizeableActivity="true"`，支持窗口自由缩放。
2. **配置变更不重建**：`configChanges` 扩展 `density|smallestScreenSize|fontScale`，窗口拖动/分屏/旋转时不重建 Activity，避免 WebView 状态丢失。
3. **`onMultiWindowModeChanged` 回调**：进入/退出多窗口模式时重算布局。
4. **平板限宽布局**（`applyTabletLayout`）：
   - `smallestScreenWidthDp >= 600`（平板）或处于多窗口/桌面窗口模式时，WebView 容器**限宽居中**（最大 1280dp），两侧深色背景，模拟桌面浏览器体验；
   - 手机/窄窗口下恢复全屏。
5. **JS Bridge 环境感知**：`getWindowMode()` / `getScreenInfo()` 让 Web UI 插件感知窗口状态，实现响应式触控布局。

### 5.2 大屏/分屏/自由窗口触控优化

- Web UI 通过 `getScreenInfo()` 获取窗口尺寸与密度，动态调整触控目标尺寸、侧栏折叠、内容列数。
- 分屏模式下自动切换为紧凑布局，自由窗口下跟随窗口形状自适应。

### 5.3 后台保活与稳定性

- **前台服务**：`EngineService` 常驻，引擎进程在后台不被系统杀死。
- **看门狗**：`EngineManager` 周期性健康检查（HTTP 探活 `127.0.0.1:3080`），引擎异常退出自动重启。
- **通知**：前台服务常驻通知，用户可感知引擎运行状态。

### 5.4 16KB 页大小（Android 16 强制）

Android 16 要求所有 native 库 16KB 对齐。对策：

- 快照内 native 库（node、bash、libtermux-exec 等）需为 16KB 对齐构建；
- 若个别库未对齐，`targetSdk=34` 可规避部分强制校验（Android 16 对 targetSdk<35 的应用放宽 16KB 检查）；
- 后续快照构建流程加入 `zipalign -P 16` 对齐步骤，彻底解决。

### 5.5 屏幕方向控制

- JS Bridge `setOrientation()` 支持 portrait / landscape / auto。
- 平板桌面窗口模式下建议 auto（窗口自由缩放，方向由窗口形状决定）。

---

## 6. 面临的挑战与对策

### 6.1 SELinux 对 bubblewrap 沙箱的拦截（重点）

**问题**：dsh 的 Bash 沙箱依赖 bubblewrap（bwrap），其核心是 user namespace（`unshare(CLONE_NEWUSER)`）。Android 的 SELinux 策略默认**禁止**普通应用创建 user namespace，bwrap 会直接失败。

**对策（优雅降级链）**：

1. **首选：禁用沙箱直跑**。dsh 配置中关闭 bubblewrap 沙箱，Bash 工具直接以子进程方式执行。功能完整，隔离性由 Android 应用沙箱（app 私有目录 + SELinux 应用域）兜底。
2. **次选：proot 替代**。proot 通过 ptrace 模拟 chroot，不依赖 user namespace，可在 Android 上运行，提供轻量文件系统隔离。
3. **备选：Termux 的 termux-am 方案**。利用 Termux 的 `termux-am` 辅助机制在受限场景下提升能力。
4. **兜底：检测降级**。引擎启动时探测 bwrap 可用性，不可用则自动降级到方案 1/2，并记录日志，保证功能不因沙箱缺失而中断。

### 6.2 Android 15/16 exec() 限制

**问题**：`targetSdk >= 35` 时，Android 禁止对非应用自有 native 库执行 `exec()`，会破坏内嵌 node/bash 及所有子命令。

**对策**：
- 保持 `targetSdk = 34`（本方案当前选择，最稳妥）；
- 同时内嵌 **termux-exec**（`LD_PRELOAD` 包装器），将 `exec*` 调用重定向到 app 私有目录下的真实二进制，为未来提升 targetSdk 预留路径。

### 6.3 快照体积与首次启动

- 快照 ~75MB，APK 总大小 ~78MB。
- 首次启动解压约 10 秒，解压进度在引导页展示。
- 解压后校验 SHA-256，失败自动重试/回滚。

### 6.4 dsh 快速迭代（开发者预览）

- dsh 处于 Developer Preview，可能存在破坏性变更。
- 通过**清单驱动的在线快照更新**（§4.7）独立升级引擎与插件，不依赖 APK 更新。
- 快照更新保留回滚点，升级失败自动回退。

### 6.5 许可证合规

- dsh 本体：MIT 协议，可自由移植与分发。
- 第三方依赖：Termux 运行时（GPLv3，仅运行时分发不构成衍生作品）、node-pty（MIT）、bubblewrap（LGPL）等，均满足分发要求。
- 本移植项目不修改 dsh 源码，仅打包分发，符合 MIT 许可。

---

## 7. 构建与安装指南

### 7.1 构建

```powershell
# 环境：JDK 17 + Android SDK (platform 36) + Gradle 8.13
$env:JAVA_HOME = "<jdk17 路径>"
$env:ANDROID_HOME = "<sdk 路径>"
gradle -p project\dsh-mobile-apk assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`

### 7.2 安装

```powershell
adb install -r dsh-mobile-a16-tablet-arm64.apk
```

或直接拷贝 APK 到平板点击安装（已用 debug 签名，可直接安装）。

### 7.3 首次使用

1. 打开 dsh 应用，首次启动自动解压运行时快照（约 10 秒）；
2. 引擎启动后 WebView 自动加载 `http://127.0.0.1:3080`；
3. 在 Web UI 中配置模型、工具与插件，开始使用。

### 7.4 验证清单

| 功能 | 验证方式 |
|------|----------|
| dsh web 服务 | Web UI 正常加载并完成一轮对话 |
| headless 模式 | ConsoleActivity 中运行 `dsh headless` |
| 插件管理 | `dsh plugin list` / install / uninstall |
| HMR | `--expose-internals` 启动后修改插件文件，观察热重载 |
| PTY/子进程 | 触发 Bash 工具执行代码 |
| 会话持久化 | 重启应用后会话恢复 |
| 桌面窗口模式 | 平板桌面窗口下自由缩放窗口，布局自适应 |
| 后台保活 | 切后台后引擎不被杀死，看门狗正常 |
| 在线更新 | 触发清单更新，快照原子替换 |

---

## 8. 交付物清单

| 交付物 | 路径 | 说明 |
|--------|------|------|
| APK 安装包 | `dsh-mobile-a16-tablet-arm64.apk` | Android 16 平板 arm64，可直接安装 |
| 技术方案文档 | 本文档 | 技术选型、架构、实现路径、适配方案、挑战与对策 |
| 定制源码 | `project/dsh-mobile-apk/` | 基于 dsh-mobile-apk 的 Android 16 平板深度定制版 |

---

## 9. 后续演进路线

1. **响应式 UI 插件**：将 `getWindowMode()` / `getScreenInfo()` 封装为 dsh 插件，Web UI 原生感知平板窗口状态。
2. **16KB 对齐快照**：构建流程加入 16KB 对齐，彻底适配 Android 16 强制要求。
3. **targetSdk 提升**：termux-exec 方案成熟后提升 targetSdk 至 35/36，通过 Play 合规。
4. **沙箱增强**：探索 Android 上可用的 user namespace 方案（如 root 设备），恢复 bubblewrap 完整隔离。
5. **Python SDK 桥**：完善 Python 驱动 dsh 的跨设备调用桥梁。
6. **多 ABI 支持**：构建 x86_64 快照版本，支持 Android 模拟器调试。
*（内容由AI生成，仅供参考）*
