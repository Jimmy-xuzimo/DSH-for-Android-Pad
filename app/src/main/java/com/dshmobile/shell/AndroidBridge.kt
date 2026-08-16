package com.dshmobile.shell

import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JS bridge injected as window.androidBridge (protocol v1, see
 * docs/apk-shell-design.md). All methods are callable from the page; results
 * that arrive asynchronously are delivered back through
 * window.__dshBridge.onDirectoryPicked(callbackId, path) on the main thread.
 */
class AndroidBridge(
  private val onPickRequest: (callbackId: String) -> Unit,
  private val onKeepScreen: (enable: Boolean) -> Unit,
  private val onNotify: (title: String, text: String) -> Unit,
  private val onAllFilesAccessRequest: () -> Unit = {},
  private val onDebugLogsRequest: () -> Unit = {},
  private val onGetSystemDark: () -> Boolean = { false },
  private val pickToken: String? = null,
  private val onRestartEngine: () -> Unit = {},
  private val onReloadWebUI: () -> Unit = {},
  private val onOpenConsole: () -> Unit = {},
  private val onGetDevLogEnabled: () -> Boolean = { false },
  private val onSetDevLogEnabled: (Boolean) -> Unit = {},
  // Android 16 平板适配：窗口模式/屏幕信息/方向控制（Web UI 插件响应式布局用）。
  private val onGetWindowMode: () -> String = { "phone" },
  private val onGetScreenInfo: () -> String = { "{}" },
  private val onSetOrientation: (String) -> Unit = {},
) {

  @JavascriptInterface
  fun version(): String = "1.0"

  /** 系统深色状态同步查询（H1：首帧主题桥启动时拉取真实 uiMode，
   *  绕过厂商 WebView matchMedia 卡 light 的问题）。 */
  @JavascriptInterface
  fun getSystemDark(): Boolean = onGetSystemDark()

  @JavascriptInterface
  fun checkEngine(): String = EngineProbe.check().toString()

  @JavascriptInterface
  fun keepScreenOn(enable: Boolean) {
    onKeepScreen(enable)
  }

  @JavascriptInterface
  fun showNotification(title: String, text: String) {
    onNotify(title, text)
  }

  @JavascriptInterface
  fun pickDirectory(callbackId: String) {
    onPickRequest(callbackId)
  }

  /** 调试日志导出：引擎日志 + 环境信息打包 zip（走会话导出同款下载/弹窗链路）。 */
  @JavascriptInterface
  fun downloadDebugLogs() {
    onDebugLogsRequest()
  }

  /** True when the app holds All Files Access (external workspace requirement). */
  @JavascriptInterface
  fun hasAllFilesAccess(): Boolean {
    // isExternalStorageManager 仅 API 30+ 存在；低版本无该权限模型。
    if (android.os.Build.VERSION.SDK_INT < 30) return false
    return android.os.Environment.isExternalStorageManager()
  }

  /** Open the system screen granting All Files Access (special permission). */
  @JavascriptInterface
  fun requestAllFilesAccess() {
    onAllFilesAccessRequest()
  }

  /** 目录选择桥的一次性会话 token（引擎侧 pick 端点校验；null = 未启用）。 */
  @JavascriptInterface
  fun getPickToken(): String? = pickToken

  /** 重启引擎服务进程：kill 引擎进程，EngineService 看门狗自动拉起。 */
  @JavascriptInterface
  fun restartEngine() {
    onRestartEngine()
  }

  /** 刷新 Web UI（重载当前引擎页面，issue apk#29 需求 1）。 */
  @JavascriptInterface
  fun reloadWebUI() {
    onReloadWebUI()
  }

  /** 打开内置控制台（快照 bash 交互终端，引擎未运行时也可排查）。 */
  @JavascriptInterface
  fun openConsole() {
    onOpenConsole()
  }

  /** 开发者调试日志开关状态（默认关；SharedPreferences 持久化）。 */
  @JavascriptInterface
  fun getDevLogEnabled(): Boolean = onGetDevLogEnabled()

  /** 设置开发者调试日志开关；开启后按天写入 dshdata/log/。 */
  @JavascriptInterface
  fun setDevLogEnabled(enabled: Boolean) {
    onSetDevLogEnabled(enabled)
  }

  /**
   * 当前窗口模式（Android 16 平板/桌面窗口适配）：phone / tablet /
   * desktop（多窗口或桌面窗口模式）。Web UI 插件据此切换响应式布局。
   */
  @JavascriptInterface
  fun getWindowMode(): String = onGetWindowMode()

  /**
   * 屏幕与窗口信息 JSON：{widthPx, heightPx, density, widthDp, heightDp,
   * smallestWidthDp, isTablet, isMultiWindow}。Web UI 插件用于大屏/分屏/
   * 自由窗口下的触控与布局优化。
   */
  @JavascriptInterface
  fun getScreenInfo(): String = onGetScreenInfo()

  /**
   * 锁定/解锁屏幕方向：portrait / landscape / auto（跟随系统）。
   * 平板桌面窗口模式下建议 auto（窗口自由缩放，方向由窗口形状决定）。
   */
  @JavascriptInterface
  fun setOrientation(mode: String) {
    onSetOrientation(mode)
  }

  companion object {
    /**
     * Map an ACTION_OPEN_DOCUMENT_TREE result onto a Termux-visible real path
     * when possible: "primary:rel/path" -> /storage/emulated/0/rel/path.
     * Non-primary volumes fall back to the raw content:// tree URI (the page
     * can still use it as an opaque handle).
     * @param uri the tree URI from the system picker.
     * @returns the mapped real path or the original URI string.
     */
    fun resolvePickedPath(uri: Uri): String {
      return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val idx = docId.indexOf(':')
        val volume = if (idx > 0) docId.substring(0, idx) else ""
        val rel = if (idx > 0) docId.substring(idx + 1) else docId
        // M5：路径清洗——拒绝 `..` 段/绝对路径（防越界），空 rel 拒绝。
        if (rel.isEmpty() || rel.split("/").any { it == ".." } || rel.startsWith("/")) {
          return uri.toString()
        }
        if (volume == "primary") "/storage/emulated/0/$rel" else uri.toString()
      } catch (_: Exception) {
        uri.toString()
      }
    }
  }
}

/** JSON string literal escaping for evaluateJavascript payloads. */
internal fun jsString(value: String): String = JSONObject.quote(value)