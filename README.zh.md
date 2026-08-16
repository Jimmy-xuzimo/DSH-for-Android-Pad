# DSH for Android

**[English](README.md) | [中文](README.zh.md)**

![版本](https://img.shields.io/badge/version-v0.11.0--a16--tablet-blue)
![平台](https://img.shields.io/badge/platform-Android%2016%20(API%2036)-green)
![架构](https://img.shields.io/badge/arch-arm64-orange)
![协议](https://img.shields.io/badge/license-MIT-yellow)

**DeepSeek Harness (dsh) 原生移植至 Android 16 平板** —— 基于 `dsh-mobile-apk` 的深度定制版，将 DeepSeek Harness 的完整 **"一切皆插件"（Everything is a Plugin）** 架构带到平板，开箱即用。

> dsh 是 DeepSeek AI 开源的智能体框架（v0.1.0-rc.5，MIT 协议，开发者预览版）。本项目将其与内嵌 Termux 运行时快照、WebView 外壳打包，让你在 Android 16 平板上直接获得完整的 dsh 体验——模型适配器、工具注册表、智能体循环、插件系统——无需 Termux 或任何外部依赖。

---

## 目录

- [特性](#特性)
- [架构](#架构)
- [技术路径](#技术路径)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [从源码构建](#从源码构建)
- [关键技术要点](#关键技术要点)
- [在线更新](#在线更新)
- [路线图](#路线图)
- [许可证](#许可证)

---

## 特性

| 能力 | 说明 |
|------|------|
| **开箱即用** | APK 直接安装，首次启动自动解压内嵌运行时快照（~75MB，约 10 秒），不依赖 Termux 等外部环境 |
| **Android 16 桌面窗口模式** | `resizeableActivity` + 扩展 `configChanges`，窗口自由缩放/分屏/旋转不重建 Activity |
| **平板限宽布局** | sw600dp+ 或桌面窗口模式下 WebView 限宽居中（最大 1280dp），两侧深色背景，模拟桌面浏览器体验 |
| **JS Bridge 环境感知** | `getWindowMode()` / `getScreenInfo()` / `setOrientation()` 供 Web UI 插件实现响应式布局 |
| **dsh 核心功能完整** | Web 服务、headless 模式、插件管理、HMR（`--expose-internals`）、node-pty 子进程/PTY、会话持久化 |
| **后台保活** | 前台服务 + 看门狗自动重启异常退出的引擎进程 |
| **在线更新** | 清单驱动的运行时快照更新，不更新 APK 即可单独升级 dsh 引擎与插件 |
| **文件访问** | SAF (Storage Access Framework) 目录桥接，安全访问平板存储 |

---

## 架构

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

**核心模块**

| 模块 | 职责 |
|------|------|
| `MainActivity` | WebView 容器、JS Bridge 注册、平板/桌面窗口布局适配、方向控制 |
| `EngineService` | 前台服务，管理 dsh 引擎进程生命周期 |
| `EngineManager` | 快照解压、引擎启动/停止、健康检查、看门狗重启 |
| `AndroidBridge` | JS Bridge 接口层（文件选择、通知、常亮、窗口模式、屏幕信息、方向） |
| `UpdateManager` | 清单驱动的运行时快照在线更新 |
| `ConsoleActivity` | 终端控制台（PTY 会话，用于调试/命令行操作） |
| `LogCollector` | 开发者调试日志（按天写入 dshdata/log/） |

---

## 技术路径

dsh 如何从 Node.js CLI 变成原生 Android 平板应用：

```
dsh (Node.js CLI, "一切皆插件")
        │
        ▼
① 内嵌 Termux 运行时快照
   └─ node + bash + coreutils + dsh + 插件，打包进 APK assets
        │
        ▼
② 用 WebView 承载 dsh Web UI
   └─ 引擎以 `node dsh web` 启动，监听 127.0.0.1:3080
   └─ WebView 加载 UI，JS Bridge (window.androidBridge) 调用原生能力
        │
        ▼
③ 保活
   └─ 前台服务 + 看门狗（HTTP 探活，异常自动重启）
        │
        ▼
④ Android 16 适配
   └─ 桌面窗口模式（resizeableActivity + configChanges）
   └─ 平板限宽居中布局（sw600dp+ / 桌面模式，最大 1280dp）
   └─ JS Bridge：getWindowMode() / getScreenInfo() / setOrientation()
        │
        ▼
⑤ 发布与更新
   └─ debug 签名 release APK，可直接安装
   └─ 清单驱动在线快照更新（引擎/插件，不更新 APK）
```

**移植决策**

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 运行时 | 内嵌 Termux 快照 | 开箱即用，无外部依赖，首次启动解压约 10 秒 |
| UI | WebView 承载官方 dsh Web UI | 零 UI 重写成本，功能完全对齐 |
| 通信 | JS Bridge（`window.androidBridge`） | Web UI 调用原生能力（文件选择、通知、窗口模式等） |
| 保活 | 前台服务 + 看门狗 | 引擎后台不被杀死，崩溃自动重启 |
| 更新 | 清单驱动快照更新 | dsh 处于快速迭代的开发者预览期，不更新 APK 即可升级引擎/插件 |
| 沙箱 | bubblewrap 降级链 | Android SELinux 禁止 user namespace，优雅降级（见下） |

---

## 目录结构

```
DSH-for-Android/
├── app/                          # Android 工程源码
│   └── src/main/
│       ├── java/com/dshmobile/shell/   # MainActivity / EngineService / AndroidBridge 等
│       └── assets/               # 运行时快照（snapshot.tar.xz，构建时放入）
├── docs/
│   ├── DeepSeek-Harness-Android16-Port-Plan.md        # 完整技术方案（英文）
│   └── DeepSeek-Harness-Android16-移植技术方案.md      # 完整技术方案（中文）
├── releases/
│   └── dsh-mobile-a16-tablet-arm64.apk                # 可直接安装的 APK
├── gradle/                       # Gradle wrapper
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── README.md
└── LICENSE                       # MIT
```

---

## 快速开始

### 安装

```powershell
adb install -r releases/dsh-mobile-a16-tablet-arm64.apk
```

或直接拷贝 APK 到平板点击安装。APK 已用 debug 签名，无需额外步骤即可直接安装。

### 首次使用

1. 打开 dsh 应用，首次启动自动解压运行时快照（约 10 秒，引导页显示进度）；
2. 引擎启动后 WebView 自动加载 `http://127.0.0.1:3080`；
3. 在 Web UI 中配置模型、工具与插件，开始使用。

### 验证清单

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

## 从源码构建

环境要求：JDK 17、Android SDK（platform 36 / build-tools 36.0.0）、Gradle 8.13。

```powershell
# 1. 配置 local.properties 指向本地 SDK
# sdk.dir=C:/path/to/android-sdk

# 2. 将运行时快照放入 assets（arm64 平板 ABI）
# 快照来源：dsh-mobile-apk 项目 releases 的 snapshot-arm64.tar.xz
# 并更新 app/src/main/assets/snapshot.sha256 为对应 SHA-256

# 3. 构建
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`（release 已配置 debug 签名，可直接安装）。

> **快照说明**：`app/src/main/assets/snapshot.tar.xz`（~75MB）为内嵌运行时快照，因体积原因**未提交**到本仓库。构建前需从 [dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) 的 releases 下载 `snapshot-arm64.tar.xz` 放入 assets 目录，并更新 `snapshot.sha256` 指纹。已构建好的 APK（`releases/`）已包含快照，可直接使用。

---

## 关键技术要点

### Android 15/16 `exec()` 限制

`targetSdk >= 35` 时，Android 禁止对非应用自有 native 库执行 `exec()`，会破坏内嵌的 node/bash 及所有子命令。本项目：

- 保持 `targetSdk = 34`（当前最稳妥的选择）；
- 同时内嵌 **termux-exec**（`LD_PRELOAD` 包装器），将 `exec*` 调用重定向到 app 私有目录下的真实二进制，为未来提升 targetSdk 预留路径。

### SELinux 与 bubblewrap 沙箱

dsh 的 Bash 沙箱依赖 bubblewrap（bwrap），其核心是 user namespace（`unshare(CLONE_NEWUSER)`）。Android 的 SELinux 策略禁止普通应用创建 user namespace，bwrap 会直接失败。本项目提供**优雅降级链**：

1. **首选**：禁用沙箱直跑——隔离性由 Android 应用沙箱（app 私有目录 + SELinux 应用域）兜底；
2. **次选**：proot（通过 ptrace 模拟 chroot，不依赖 user namespace）；
3. **备选**：Termux 的 `termux-am` 辅助机制；
4. **兜底**：启动时探测能力，不可用则自动降级并记录日志，功能永不因沙箱缺失而中断。

### 16KB 页大小（Android 16 强制）

Android 16 要求所有 native 库 16KB 对齐。`targetSdk=34` 可放宽部分强制校验；快照构建流程计划加入 `zipalign -P 16` 对齐步骤彻底解决。

### 构建环境

- compileSdk = 36（Android 16），minSdk = 26，targetSdk = 34
- JDK 17、Gradle 8.13、Android SDK platform 36 / build-tools 36.0.0

---

## 在线更新

`UpdateManager` 拉取远程清单（快照版本、SHA-256、下载 URL），校验后下载新快照并原子替换（旧快照保留为回滚点）。仅更新引擎与插件，不更新 APK 外壳，适配 dsh 在开发者预览期的快速迭代。

---

## 路线图

1. **响应式 UI 插件**：将 `getWindowMode()` / `getScreenInfo()` 封装为 dsh 插件，Web UI 原生感知平板窗口状态。
2. **16KB 对齐快照**：构建流程加入 16KB 对齐，彻底适配 Android 16 强制要求。
3. **targetSdk 提升**：termux-exec 方案成熟后提升 targetSdk 至 35/36，通过 Play 合规。
4. **沙箱增强**：探索 Android 上可用的 user namespace 方案（如 root 设备），恢复 bubblewrap 完整隔离。
5. **Python SDK 桥**：完善 Python 驱动 dsh 的跨设备调用桥梁。
6. **多 ABI 支持**：构建 x86_64 快照版本，支持 Android 模拟器调试。

---

## 许可证

MIT License。dsh 本体为 MIT 协议；第三方依赖（Termux 运行时、node-pty、bubblewrap 等）均满足分发要求。
