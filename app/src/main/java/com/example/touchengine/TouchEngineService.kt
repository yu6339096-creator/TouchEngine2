package com.example.touchengine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

class TouchEngineService : AccessibilityService() {

    companion object {
        /** 由 MainActivity 实时写入的外观 & 服务开关，服务启动时从 SharedPreferences 恢复 */
        val liveBallSize    = mutableFloatStateOf(0.65f)
        val liveBallOpacity = mutableFloatStateOf(0.80f)
        val liveEnabled     = mutableStateOf(true)
    }

    // ── Engine ────────────────────────────────────────────
    private val navEngine = ContagionNavEngine()

    // [FIX 1] Service 级 CoroutineScope，生命周期与 Service 一致。
    // 滚动相关的所有协程（scrollHintJob、scrollBJob）都在这里启动，
    // 不再依赖 Compose 的 rememberCoroutineScope，避免 Compose 重组时 Job 被意外取消。
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // ── Window ────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private var joystickView: ComposeView? = null
    private var focusBoxView: ComposeView? = null
    private lateinit var joystickParams: WindowManager.LayoutParams

    // ── State ─────────────────────────────────────────────
    private val currentFocusState = mutableStateOf<NavNode?>(null)
    private val screenWidth  = Resources.getSystem().displayMetrics.widthPixels
    private val screenHeight = Resources.getSystem().displayMetrics.heightPixels
    private val homePackageName: String? by lazy {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    // ── 滚动状态（主线程访问，无需加锁）────────────────────
    private var scrollConfig = ScrollConfig()
    private var scrollState  = ScrollState.IDLE   // 当前滚动阶段
    private var edgeCount    = 0                  // 边缘帧计数
    private var edgeDir: ScrollDir? = null        // 当前边缘方向
    private var rejectedEdgeDir: ScrollDir? = null // 本次持续拉动已判定为无动作的方向
    private var scrollHintJob: Job? = null
    private var scrollBJob:    Job? = null
    // 翻页前焦点位置，用于翻页后就近定位
    private var prevFocusCx  = 0f
    private var prevFocusCy  = 0f
    // 连续滑动会话：滑动中不更新 NavMesh，松手后以启动位置恢复焦点。
    private var autoScrollDir: ScrollDir? = null
    private var autoScrollStartCx = 0f
    private var autoScrollStartCy = 0f
    private var autoScrollPackage: String? = null
    private var autoScrollWindowId: Int? = null
    private var pendingRefreshAfterAutoScroll = false
    private var isJoystickSelecting = false
    private var selectionPackage: String? = null
    private var pendingRefreshAfterSelection = false
    private var meshPackage: String? = null

    enum class ScrollState { IDLE, HINT, PLAN_B }
    enum class ScrollDir   { UP, DOWN, LEFT, RIGHT }

    /** 提示类型：让用户知道即将发生的是翻页还是滑动 */
    enum class HintType { PAGE_FLIP, SCROLL }

    // 提示气泡状态（传给 FocusHighlightUI 层显示）
    val scrollHintState = mutableStateOf<ScrollHintData?>(null)
    data class ScrollHintData(
        val dir:      ScrollDir,
        val progress: Float,       // 0.0 ~ 1.0 倒计时进度
        val type:     HintType = HintType.SCROLL  // 翻页 or 滑动
    )

    // [FIX 9] 方向锁死：翻页后短时间内封锁反向翻页，防止乒乓跳页
    private var lastFlipDir:    ScrollDir? = null
    private var lastFlipTimeMs: Long       = 0L
    private val FLIP_LOCK_MS               = 1500L  // 翻页后 1.5s 内封锁反向

    // [FIX CIRCULAR] 循环翻页检测（针对 MIUI 等循环 ViewPager 桌面）
    private val pageHistory      = ArrayDeque<String>()                       // 最近访问的页面指纹，最多 6 个
    private val circularDeadEnds = HashMap<String, MutableSet<ScrollDir>>()  // 页面指纹 → 该页已证实循环的方向集合
    private var currentPageFp    = ""                                         // 当前页面指纹
    private var prePlanAFp       = ""                                         // Plan A 执行前保存的指纹

    // ── NavMesh 防抖与稳定侦测 ─────────────────────────────
    private val meshHandler  = Handler(Looper.getMainLooper())
    private val meshRunnable = Runnable { rebuildNavMesh() }
    
    // 动态稳定探测参数
    private val DEBOUNCE_STATE_MS   = 30L   // 状态切页首帧抢跑延迟：30ms
    private val DEBOUNCE_DETECT_MS  = 80L   // 空间变化侦测防抖延迟：80ms
    private val MAX_SETTLE_WINDOW_MS = 450L // 稳定判定的最大时间上限，防止微小变化导致无法稳定
    
    @Volatile private var isWindowSettled = true   // 默认窗口已稳定（日常静默待机）
    private var stateChangedTimeMs = 0L            // 记录最近一次切页的时间戳
    
    private var currentNodes: List<NavNode> = emptyList()
    private val screenArea = screenWidth * screenHeight

    // [FIX 4] NavMesh 异步重建版本号，防止旧的异步结果覆盖最新状态
    private var navMeshVersion = 0
    private var lastTaobaoParseDiagnostic: String? = null
    private var lastDouyinParseDiagnostic: String? = null

    // =====================================================
    // Service lifecycle
    // =====================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 从 SharedPreferences 恢复外观与服务开关状态
        val prefs = getSharedPreferences("TouchEnginePrefs", Context.MODE_PRIVATE)
        liveBallSize.floatValue    = prefs.getFloat("ball_size", 0.65f)
        liveBallOpacity.floatValue = prefs.getFloat("ball_opacity", 0.80f)
        liveEnabled.value          = prefs.getBoolean("service_running", true)
        initFloatingUI()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        // [FIX 1] Service 销毁时统一取消所有滚动协程
        serviceScope.cancel()
        meshHandler.removeCallbacks(meshRunnable)
        joystickView?.let { windowManager.removeView(it) }
        focusBoxView?.let { windowManager.removeView(it) }
    }

    // =====================================================
    // 滚动逻辑（方案A + 方案B）
    // =====================================================

    /** 从 SharedPreferences 刷新滚动配置（每次 NavMesh 重建时调用）*/
    private fun refreshScrollConfig() {
        scrollConfig = ScrollConfig.load(this)
    }

    /**
     * 判断某角度方向是否真的没有邻居（30度窄扇区确认，防误判）。
     *
     * [FIX 9] 孤立节点（完全没有邻居）不触发边缘检测：
     * 孤立节点会对所有方向返回 true，导致任何方向推杆都误触翻页。
     * 加此判断后，孤立节点静默忽略，不参与翻页或滑动触发。
     */
    private fun isRealEdge(node: NavNode, angleDeg: Double): Boolean {
        if (node.neighbors.isEmpty()) return false  // 孤立节点，不触发
        for (nb in node.neighbors) {
            val dx = nb.centerX - node.centerX
            val dy = nb.centerY - node.centerY
            val nbAngle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
            var diff = kotlin.math.abs(angleDeg - nbAngle)
            if (diff > 180) diff = 360 - diff
            if (diff <= 30.0) return false
        }
        return true
    }

    /** 根据角度判断滚动方向 */
    private fun dirFromAngle(angleDeg: Double): ScrollDir {
        return when {
            angleDeg > -45  && angleDeg <= 45  -> ScrollDir.RIGHT
            angleDeg > 45   && angleDeg <= 135 -> ScrollDir.DOWN
            angleDeg > 135  || angleDeg <= -135 -> ScrollDir.LEFT
            else                                -> ScrollDir.UP
        }
    }

    /**
     * 计算当前屏幕内容指纹，用于检测 Circular Launcher 的循环翻页。
     * 采集最多 25 个可见节点的 contentDescription/text，排序后拼接。
     * 相同页面内容 → 相同指纹；不同页面 → 不同指纹。
     */
    private fun computeScreenFingerprint(): String {
        return try {
            val root = obtainPrimaryContentRoot() ?: return ""
            val texts = mutableListOf<String>()
            fun traverse(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 6 || texts.size >= 25) return
                val desc = node.contentDescription?.toString()?.trim()
                        ?: node.text?.toString()?.trim()
                if (!desc.isNullOrBlank()) texts.add(desc)
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        traverse(child, depth + 1)
                        child.recycle()
                    }
                }
            }
            traverse(root, 0)
            root.recycle()
            texts.sorted().joinToString("|")
        } catch (e: Exception) { "" }
    }

    private fun obtainPrimaryContentRoot(): AccessibilityNodeInfo? {
        val sortedWindows = windows?.sortedByDescending { it.layer }.orEmpty()
        for (window in sortedWindows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() == packageName) {
                root.recycle()
                continue
            }
            return root
        }
        val root = rootInActiveWindow ?: return null
        return if (root.packageName?.toString() == packageName) {
            root.recycle()
            null
        } else {
            root
        }
    }

    private fun keyboardTop(): Int? {
        val keyboardWindow = windows?.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } ?: return null
        val bounds = Rect()
        keyboardWindow.getBoundsInScreen(bounds)
        return bounds.top.takeIf { it in 1 until screenHeight }
    }

    private fun isIgnoredWindowEvent(event: AccessibilityEvent): Boolean {
        val eventPackage = event.packageName?.toString()
        if (eventPackage == packageName) return true
        val eventWindow = windows?.firstOrNull { it.id == event.windowId }
        if (eventWindow?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            eventWindow?.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
            eventWindow?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        ) {
            return true
        }
        val contentPackage = activeTargetPackage()
        return eventPackage != null && contentPackage != null && eventPackage != contentPackage
    }

    private fun activeTargetPackage(): String? {
        val root = obtainPrimaryContentRoot() ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    private fun isDefaultHomePackage(packageName: CharSequence?): Boolean {
        val targetPackage = packageName?.toString() ?: return false
        return targetPackage == homePackageName
    }

    private fun isDefaultHomeActive(): Boolean = isDefaultHomePackage(activeTargetPackage())

    private fun isTaobaoPackage(packageName: CharSequence?): Boolean {
        return packageName?.toString()?.contains("taobao", ignoreCase = true) == true
    }

    private fun isTaobaoActive(): Boolean = isTaobaoPackage(activeTargetPackage())

    private fun isDouyinPackage(packageName: CharSequence?): Boolean {
        return EdgeInteractionRules.isDouyinPackage(packageName?.toString())
    }

    /** 判断可滚动容器是否支持在给定方向上滚动 */
    private fun supportsScrollDirection(node: AccessibilityNodeInfo, dir: ScrollDir): Boolean {
        val actions = node.actionList.map { it.id }
        // 1. 优先检查 API 21+ 明确的方向性 Action
        val hasScrollUp    = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id in actions
        val hasScrollDown  = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in actions
        val hasScrollLeft  = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id in actions
        val hasScrollRight = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id in actions
        val hasVerticalActions   = hasScrollUp || hasScrollDown
        val hasHorizontalActions = hasScrollLeft || hasScrollRight
        // 2. 如果存在方向性 Action，则按其指示返回
        if (hasVerticalActions || hasHorizontalActions) {
            return when (dir) {
                ScrollDir.UP    -> hasScrollUp
                ScrollDir.DOWN  -> hasScrollDown
                ScrollDir.LEFT  -> hasScrollLeft
                ScrollDir.RIGHT -> hasScrollRight
            }
        }
        // 3. 兜底方案：如果只暴露了通用的 ACTION_SCROLL_FORWARD / ACTION_SCROLL_BACKWARD
        val hasForward  = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions
        val hasBackward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions
        if (!hasForward && !hasBackward) return false
        // 通过类名来排除不支持的滚动方向
        val className = node.className?.toString() ?: ""
        val isHorizontalClass = className.contains("HorizontalScrollView", ignoreCase = true) ||
                                className.contains("ViewPager",            ignoreCase = true) ||
                                className.contains("Workspace",            ignoreCase = true)
        val isVerticalClass   = className.contains("ScrollView", ignoreCase = true) ||
                                 className.contains("ListView",  ignoreCase = true) ||
                                 className.contains("GridView",  ignoreCase = true) ||
                                 className.contains("WebView", ignoreCase = true)
        return when (dir) {
            ScrollDir.UP    -> hasBackward && !isHorizontalClass
            ScrollDir.DOWN  -> hasForward  && !isHorizontalClass
            ScrollDir.LEFT  -> hasBackward && !isVerticalClass
            ScrollDir.RIGHT -> hasForward  && !isVerticalClass
        }
    }

    /** 获取应该在该可滚动容器上执行的具体 Action ID */
    private fun getScrollActionId(node: AccessibilityNodeInfo, dir: ScrollDir): Int? {
        val actions = node.actionList.map { it.id }
        // 优先使用明确的方向 Action
        val specificAction = when (dir) {
            ScrollDir.UP    -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            ScrollDir.DOWN  -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            ScrollDir.LEFT  -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            ScrollDir.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (specificAction.id in actions) return specificAction.id
        // 其次使用通用 Action 兜底
        val fallbackActionId = when (dir) {
            ScrollDir.DOWN, ScrollDir.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDir.UP,  ScrollDir.LEFT   -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (fallbackActionId in actions) return fallbackActionId
        return null
    }

    enum class EdgeActionType { NONE, PAGE_FLIP, GESTURE_SCROLL }

    data class EdgeAction(
        val type: EdgeActionType,
        val dir: ScrollDir
    )

    private fun isHorizontalDir(dir: ScrollDir): Boolean {
        return dir == ScrollDir.LEFT || dir == ScrollDir.RIGHT
    }

    private fun isVerticalDir(dir: ScrollDir): Boolean {
        return dir == ScrollDir.UP || dir == ScrollDir.DOWN
    }

    private fun rectOf(node: AccessibilityNodeInfo): Rect {
        return Rect().also { node.getBoundsInScreen(it) }
    }

    private fun overlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int {
        return (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0)
    }

    private fun hasSpecificHorizontalAction(node: AccessibilityNodeInfo, dir: ScrollDir): Boolean {
        val actions = node.actionList.map { it.id }
        return when (dir) {
            ScrollDir.LEFT  -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id in actions
            ScrollDir.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id in actions
            else            -> false
        }
    }

    private fun hasGenericScrollAction(node: AccessibilityNodeInfo, dir: ScrollDir): Boolean {
        val actions = node.actionList.map { it.id }
        return when (dir) {
            ScrollDir.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions
            ScrollDir.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions
            else -> false
        }
    }

    private fun isHorizontalPageNode(node: AccessibilityNodeInfo, dir: ScrollDir, focus: NavNode?): Boolean {
        if (!isHorizontalDir(dir) || !supportsScrollDirection(node, dir)) return false
        val className = node.className?.toString() ?: ""
        val rect = rectOf(node)
        val wideEnough = rect.width() > rect.height() * 1.2f
        val explicitPageClass =
            className.contains("ViewPager", ignoreCase = true) ||
            className.contains("HorizontalScrollView", ignoreCase = true) ||
            className.contains("Workspace", ignoreCase = true)

        if (explicitPageClass || (hasSpecificHorizontalAction(node, dir) && wideEnough)) return true
        if (!hasGenericScrollAction(node, dir)) return false

        val isKnownVerticalClass =
            className.contains("ScrollView", ignoreCase = true) ||
            className.contains("ListView", ignoreCase = true) ||
            className.contains("GridView", ignoreCase = true) ||
            className.contains("WebView", ignoreCase = true)
        val isHome = isDefaultHomePackage(node.packageName)
        if (!EdgeInteractionRules.acceptsGenericHorizontalTarget(
                rect.width(), rect.height(), screenHeight, isKnownVerticalClass, isHome
            )) {
            return false
        }
        return isHome || (focus != null && isLocallyRelatedToFocus(node, focus))
    }

    private fun isLocallyRelatedToFocus(candidate: AccessibilityNodeInfo, focus: NavNode): Boolean {
        if (candidate.windowId != focus.windowId) return false
        val rect = rectOf(candidate)
        if (rect.isEmpty) return false

        val focusRect = focus.bounds
        val focusCx = focus.centerX.toInt()
        val focusCy = focus.centerY.toInt()
        val pad = (screenWidth * 0.04f).roundToInt().coerceAtLeast(32)
        val expanded = Rect(rect).apply { inset(-pad, -pad) }
        if (expanded.contains(focusCx, focusCy)) return true

        val yOverlap = overlap(rect.top, rect.bottom, focusRect.top, focusRect.bottom)
        val minHeight = minOf(rect.height(), focusRect.height()).coerceAtLeast(1)
        val sameRow = yOverlap >= minHeight * 0.45f
        val centerGap = kotlin.math.abs(rect.exactCenterX() - focus.centerX)
        return sameRow && centerGap <= screenWidth * 0.45f
    }

    private fun nodeScoreToFocus(node: AccessibilityNodeInfo, focus: NavNode): Float {
        val rect = rectOf(node)
        val dx = rect.exactCenterX() - focus.centerX
        val dy = rect.exactCenterY() - focus.centerY
        val containsBonus = if (rect.contains(focus.centerX.toInt(), focus.centerY.toInt())) -screenWidth.toFloat() else 0f
        return hypot(dx.toDouble(), dy.toDouble()).toFloat() + containsBonus
    }

    private fun findBestAccessibilityNode(
        accept: (AccessibilityNodeInfo) -> Boolean,
        score: (AccessibilityNodeInfo) -> Float
    ): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val sortedWindows = windows?.sortedByDescending { it.layer }.orEmpty()
            if (sortedWindows.isNotEmpty()) {
                for (window in sortedWindows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = window.root ?: continue
                    if (root.packageName?.toString() == packageName) {
                        root.recycle()
                        continue
                    }
                    roots.add(root)
                }
            } else {
                val root = obtainPrimaryContentRoot() ?: return null
                roots.add(root)
            }
        } catch (e: Exception) {
            Log.e("TouchEngine", "collect roots error: ${e.message}")
            roots.forEach { runCatching { it.recycle() } }
            return null
        }

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Float.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addAll(roots)
        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (accept(node)) {
                    val s = score(node)
                    if (s < bestScore) {
                        bestNode?.recycle()
                        bestNode = AccessibilityNodeInfo.obtain(node)
                        bestScore = s
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
                node.recycle()
            }
        } catch (e: Exception) {
            queue.forEach { runCatching { it.recycle() } }
            bestNode?.recycle()
            Log.e("TouchEngine", "findBestAccessibilityNode error: ${e.message}")
            return null
        }
        return bestNode
    }

    private fun findLocalHorizontalPageNode(focus: NavNode, dir: ScrollDir): AccessibilityNodeInfo? {
        return findBestAccessibilityNode(
            accept = { node ->
                isHorizontalPageNode(node, dir, focus) &&
                    (isDefaultHomePackage(node.packageName) || isLocallyRelatedToFocus(node, focus))
            },
            score = { node -> nodeScoreToFocus(node, focus) }
        )
    }

    private fun findAnyHorizontalPageNode(dir: ScrollDir): AccessibilityNodeInfo? {
        return findBestAccessibilityNode(
            accept = { node -> isHorizontalPageNode(node, dir, null) },
            score = { node -> rectOf(node).top.toFloat() }
        )
    }

    private fun isMiddleContentFocus(focus: NavNode): Boolean {
        return focus.centerY > screenHeight * 0.12f && focus.centerY < screenHeight * 0.86f
    }

    private fun taobaoHorizontalGestureBounds(focus: NavNode): Rect {
        val halfHeight = maxOf((focus.bounds.height() * 1.5f).roundToInt(), (screenHeight * 0.06f).roundToInt())
        return Rect(
            0,
            (focus.centerY.roundToInt() - halfHeight).coerceAtLeast(0),
            screenWidth,
            (focus.centerY.roundToInt() + halfHeight).coerceAtMost(screenHeight)
        )
    }

    private fun decideEdgeAction(focus: NavNode, dir: ScrollDir): EdgeAction {
        if (isHorizontalDir(dir)) {
            val pageNode = findLocalHorizontalPageNode(focus, dir)
            pageNode?.recycle()
            return if (pageNode != null || isDefaultHomeActive() ||
                (isTaobaoActive() && isMiddleContentFocus(focus))) {
                EdgeAction(EdgeActionType.PAGE_FLIP, dir)
            } else {
                EdgeAction(EdgeActionType.NONE, dir)
            }
        }

        if (isVerticalDir(dir)) {
            return EdgeAction(EdgeActionType.GESTURE_SCROLL, dir)
        }

        return EdgeAction(EdgeActionType.NONE, dir)
    }

    private fun tryPageFlip(focus: NavNode?, dir: ScrollDir): Boolean {
        if (!isHorizontalDir(dir)) return false
        val pageNode = if (focus != null) {
            findLocalHorizontalPageNode(focus, dir)
        } else {
            findAnyHorizontalPageNode(dir)
        }
        if (pageNode != null) {
            val bounds = rectOf(pageNode)
            val success = getScrollActionId(pageNode, dir)?.let { pageNode.performAction(it) } == true
            pageNode.recycle()
            if (success) return true
            return dispatchHorizontalPageGesture(dir, bounds)
        }
        if (focus != null && isTaobaoActive() && isMiddleContentFocus(focus)) {
            return dispatchHorizontalPageGesture(dir, taobaoHorizontalGestureBounds(focus))
        }
        return isDefaultHomeActive() && dispatchHorizontalPageGesture(dir, null)
    }

    private fun dispatchHorizontalPageGesture(dir: ScrollDir, targetBounds: Rect?): Boolean {
        if (!isHorizontalDir(dir)) return false
        val bounds = targetBounds?.takeUnless { it.isEmpty }
            ?: Rect(0, (screenHeight * 0.12f).roundToInt(), screenWidth, (screenHeight * 0.88f).roundToInt())
        val left = bounds.left + bounds.width() * 0.22f
        val right = bounds.left + bounds.width() * 0.78f
        val y = bounds.exactCenterY()
        val (startX, endX) = when (dir) {
            ScrollDir.RIGHT -> Pair(right, left)
            ScrollDir.LEFT -> Pair(left, right)
            else -> return false
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply {
                        moveTo(startX, y)
                        lineTo(endX, y)
                    },
                    0L,
                    scrollConfig.scrollGestureDurationMs
                )
            ).build()
        return dispatchGesture(gesture, null, null)
    }

    /** 执行方案B（dispatchGesture 模拟手势），持续调用 */
    private fun doPlanBGesture(dir: ScrollDir) {
        val sw = screenWidth.toFloat()
        val sh = screenHeight.toFloat()
        val dist = sh * scrollConfig.scrollSensitivity
        val (startX, startY, endX, endY) = when (dir) {
            ScrollDir.DOWN  -> arrayOf(sw / 2, sh * 0.65f, sw / 2, sh * 0.65f - dist)
            ScrollDir.UP    -> arrayOf(sw / 2, sh * 0.35f, sw / 2, sh * 0.35f + dist)
            ScrollDir.RIGHT -> arrayOf(sw * 0.65f, sh / 2, sw * 0.65f - dist, sh / 2)
            ScrollDir.LEFT  -> arrayOf(sw * 0.35f, sh / 2, sw * 0.35f + dist, sh / 2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply {
                        moveTo(startX, startY)
                        lineTo(endX, endY)
                    },
                    0L,
                    scrollConfig.scrollGestureDurationMs
                )
            ).build()
        dispatchGesture(gesture, null, null)
    }

    private fun directionAngle(dir: ScrollDir): Double = when (dir) {
        ScrollDir.UP -> -90.0
        ScrollDir.DOWN -> 90.0
        ScrollDir.LEFT -> 180.0
        ScrollDir.RIGHT -> 0.0
    }

    private fun isHoldingAutoScrollDirection(dragX: Float, dragY: Float, maxRadius: Float): Boolean {
        val dir = autoScrollDir ?: return false
        return EdgeInteractionRules.isHeldInDirection(
            expectedAngle = directionAngle(dir),
            dragX = dragX,
            dragY = dragY,
            maxRadius = maxRadius
        )
    }

    private fun beginContinuousScroll(focus: NavNode, dir: ScrollDir) {
        autoScrollDir = dir
        autoScrollStartCx = focus.centerX
        autoScrollStartCy = focus.centerY
        autoScrollPackage = activeTargetPackage()
        autoScrollWindowId = focus.windowId
        pendingRefreshAfterAutoScroll = false
        pendingRefreshAfterSelection = false
        meshHandler.removeCallbacks(meshRunnable)
        navMeshVersion++ // 丢弃进入连续滑动前仍在计算的 NavMesh 结果
        currentFocusState.value = null
        navEngine.reset()
        scrollState = ScrollState.PLAN_B
    }

    private fun isSameAutoScrollTarget(event: AccessibilityEvent): Boolean {
        val expectedPackage = autoScrollPackage ?: return false
        if (event.packageName?.toString() != expectedPackage) return false
        val expectedWindow = autoScrollWindowId ?: return true
        return event.windowId == expectedWindow || event.windowId < 0
    }

    private fun isSameSelectionTarget(event: AccessibilityEvent): Boolean {
        val expectedPackage = selectionPackage ?: return false
        return event.packageName?.toString() == expectedPackage
    }

    private fun deferRefreshUntilSelectionEnds() {
        if (pendingRefreshAfterSelection) return
        pendingRefreshAfterSelection = true
        meshHandler.removeCallbacks(meshRunnable)
        if (currentNodes.isNotEmpty()) {
            navMeshVersion++ // 已有可用图时，本次手势不允许动态结果覆盖它
        }
    }

    private fun refreshAfterSelectionIfNeeded() {
        if (!pendingRefreshAfterSelection || scrollState == ScrollState.PLAN_B) return
        pendingRefreshAfterSelection = false
        isWindowSettled = false
        stateChangedTimeMs = System.currentTimeMillis()
        meshHandler.removeCallbacks(meshRunnable)
        meshHandler.postDelayed(meshRunnable, DEBOUNCE_DETECT_MS)
    }

    /**
     * 处理边缘检测结果（在 onDrag 结果为 atEdge=true 时调用）。
     *
     * [FIX 9] 重构翻页/滑动时序：
     *   - 方案A不再立即执行，统一走倒计时，防止悬浮球一碰边缘就翻页
     *   - 新增方向锁死：翻页后 1.5s 内封锁反向翻页，防止乒乓跳页
     */
    private fun handleEdge(focus: NavNode, dir: ScrollDir, ratio: Float) {
        if (!scrollConfig.autoScrollEnabled) return
        if (ratio < 0.28f) return
        if (rejectedEdgeDir == dir) return

        if (dir == edgeDir) {
            edgeCount++
        } else {
            cancelScroll()
            edgeDir   = dir
            edgeCount = 1
        }

        if (edgeCount >= scrollConfig.edgeTriggerFrames) {
            edgeCount = 0
            val action = decideEdgeAction(focus, dir)
            if (action.type == EdgeActionType.NONE) {
                rejectedEdgeDir = dir
                edgeDir = null
                return
            }

            // Only real page flips are blocked by flip history.
            val lockedDir = when (lastFlipDir) {
                ScrollDir.LEFT  -> ScrollDir.RIGHT
                ScrollDir.RIGHT -> ScrollDir.LEFT
                ScrollDir.UP    -> ScrollDir.DOWN
                ScrollDir.DOWN  -> ScrollDir.UP
                null            -> null
            }
            if (action.type == EdgeActionType.PAGE_FLIP &&
                lockedDir == dir && System.currentTimeMillis() - lastFlipTimeMs < FLIP_LOCK_MS) {
                rejectedEdgeDir = dir
                edgeDir = null
                return
            }
            if (action.type == EdgeActionType.PAGE_FLIP &&
                circularDeadEnds[currentPageFp]?.contains(dir) == true) {
                rejectedEdgeDir = dir
                edgeDir = null
                return
            }

            prevFocusCx = currentFocusState.value?.centerX ?: 0f
            prevFocusCy = currentFocusState.value?.centerY ?: 0f
            startHint(focus, action)
        }
    }

    /**
     * 处理点拨到边缘（onRelease 时 atEdge=true 且推力足够）。
     * 快速点拨仍直接走方案A（翻页），不需要倒计时。
     */
    private fun handleFlickEdge(focus: NavNode, dir: ScrollDir, ratio: Float) {
        if (!scrollConfig.autoScrollEnabled) return
        if (ratio < 0.5f) return
        val action = decideEdgeAction(focus, dir)
        if (action.type != EdgeActionType.PAGE_FLIP) return
        // 方向锁同样适用于点拨
        val lockedDir = when (lastFlipDir) {
            ScrollDir.LEFT  -> ScrollDir.RIGHT
            ScrollDir.RIGHT -> ScrollDir.LEFT
            ScrollDir.UP    -> ScrollDir.DOWN
            ScrollDir.DOWN  -> ScrollDir.UP
            null            -> null
        }
        if (lockedDir == dir && System.currentTimeMillis() - lastFlipTimeMs < FLIP_LOCK_MS) return
        // [FIX CIRCULAR] 点拨也检查循环死胡同
        if (circularDeadEnds[currentPageFp]?.contains(dir) == true) return
        prevFocusCx = currentFocusState.value?.centerX ?: 0f
        prevFocusCy = currentFocusState.value?.centerY ?: 0f
        prePlanAFp = computeScreenFingerprint()
        if (tryPageFlip(focus, dir)) {
            lastFlipDir    = dir
            lastFlipTimeMs = System.currentTimeMillis()
            edgeCount = 0; edgeDir = null
            meshHandler.postDelayed({ afterScroll(prevFocusCx, prevFocusCy) }, 400L)
        }
    }

    /**
     * 显示提示气泡并开始倒计时，倒计时结束后进入执行阶段。
     *
     * [FIX 9] 预先检测操作类型，让提示文字告知用户即将翻页还是滑动。
     */
    private fun startHint(focus: NavNode, action: EdgeAction) {
        if (scrollState != ScrollState.IDLE) return
        scrollState = ScrollState.HINT
        // 提前判断类型，提示条上显示 "即将翻页" 或 "即将滑动"
        val hintType = when (action.type) {
            EdgeActionType.PAGE_FLIP -> HintType.PAGE_FLIP
            EdgeActionType.GESTURE_SCROLL -> HintType.SCROLL
            EdgeActionType.NONE -> return
        }
        scrollHintJob?.cancel()
        scrollHintJob = serviceScope.launch {
            val startMs = System.currentTimeMillis()
            while (true) {
                val elapsed  = System.currentTimeMillis() - startMs
                val progress = (elapsed.toFloat() / scrollConfig.hintDurationMs).coerceAtMost(1f)
                scrollHintState.value = ScrollHintData(action.dir, progress, hintType)
                if (progress >= 1f) {
                    startEdgeAction(focus, action)
                    return@launch
                }
                delay(16)
            }
        }
    }

    /**
     * 倒计时结束后的执行阶段。
     *
     * [FIX 9] 方案A改为在此处执行（倒计时后），而非立即执行：
     *   - 翻页容器（ViewPager等）→ 调用方案A（ACTION_SCROLL），记录方向锁
     *   - 连续滚动容器（RecyclerView等）→ 持续手势滑动（方案B）
     *   - 无可滚动容器 → 仍尝试手势（可能对 WebView 等有效）
     */
    private fun startEdgeAction(focus: NavNode, action: EdgeAction) {
        scrollHintState.value = null
        scrollBJob?.cancel()
        scrollBJob = serviceScope.launch {
            // 先试方案A（翻页），倒计时结束时执行
            if (action.type == EdgeActionType.PAGE_FLIP) {
                prePlanAFp = computeScreenFingerprint()
            }
            if (action.type == EdgeActionType.PAGE_FLIP && tryPageFlip(focus, action.dir)) {
                // [FIX 9] 记录翻页方向和时间，用于方向锁
                lastFlipDir    = action.dir
                lastFlipTimeMs = System.currentTimeMillis()
                scrollState = ScrollState.IDLE
                meshHandler.postDelayed({ afterScroll(prevFocusCx, prevFocusCy) }, 400L)
                return@launch
            }
            // 方案A无效（连续滚动容器）→ 持续手势滑动
            if (action.type != EdgeActionType.GESTURE_SCROLL) {
                scrollState = ScrollState.IDLE
                return@launch
            }
            beginContinuousScroll(focus, action.dir)
            while (scrollState == ScrollState.PLAN_B && liveEnabled.value) {
                doPlanBGesture(action.dir)
                delay(scrollConfig.scrollGestureDurationMs + 50)
            }
            if (scrollState == ScrollState.PLAN_B) {
                stopPlanB()
            }
        }
    }

    /**
     * 取消任何滚动状态（HINT 或 PLAN_B 均适用）。
     *
     * [FIX 3] 原来只有 cancelHint()，只处理 HINT 状态。
     * 新函数统一处理两种状态，防止 PLAN_B 在焦点跳转时无法停止。
     */
    private fun cancelScroll() {
        if (scrollState == ScrollState.PLAN_B) {
            stopPlanB()
            return
        }
        scrollHintJob?.cancel()
        scrollBJob?.cancel()
        scrollState = ScrollState.IDLE
        scrollHintState.value = null
        edgeCount = 0
        edgeDir = null
        rejectedEdgeDir = null
    }

    /**
     * 连续滑动结束：停止派发手势，并在最新内容上恢复邻近焦点。
     */
    private fun stopPlanB(refreshNodes: Boolean = true) {
        if (scrollState != ScrollState.PLAN_B) return
        val restoreCx = autoScrollStartCx
        val restoreCy = autoScrollStartCy
        scrollBJob?.cancel()
        scrollState = ScrollState.IDLE
        scrollHintState.value = null
        edgeCount = 0
        edgeDir = null
        rejectedEdgeDir = null
        autoScrollDir = null
        autoScrollPackage = null
        autoScrollWindowId = null
        val refreshDelay = if (pendingRefreshAfterAutoScroll) DEBOUNCE_DETECT_MS else 0L
        pendingRefreshAfterAutoScroll = false
        if (refreshNodes) {
            isWindowSettled = false
            stateChangedTimeMs = System.currentTimeMillis()
            meshHandler.removeCallbacks(meshRunnable)
            meshHandler.postDelayed({
                rebuildNavMesh(forceApply = true) { nodes ->
                    currentFocusState.value = nodes.minByOrNull { n ->
                        hypot((n.centerX - restoreCx).toDouble(), (n.centerY - restoreCy).toDouble())
                    }
                }
            }, refreshDelay)
        }
    }

    /**
     * 翻页完成后，重建 NavMesh 并就近定位焦点。
     *
     * [FIX 4] rebuildNavMesh 改为异步，就近定位逻辑移入回调，
     *          保证读取的是最新 NavMesh 而不是旧数据。
     */
    private fun afterScroll(prevCx: Float, prevCy: Float) {
        currentFocusState.value = null
        navEngine.reset()
        edgeCount = 0; edgeDir = null
        val savedFp = prePlanAFp   // 翻页前页面指纹
        val flipDir = lastFlipDir  // 翻页方向（回调内可能被重置）
        prePlanAFp  = ""
        rebuildNavMesh(forceApply = true) { newNodes ->
            // [FIX CIRCULAR] 翻页后内容对比：若新页面在历史中出现 → 循环绕回
            if (flipDir != null && savedFp.isNotBlank()) {
                val newFp = computeScreenFingerprint()
                if (newFp.isNotBlank() && pageHistory.contains(newFp)) {
                    // ── 循环绕回检测：标记死胡同 + 自动翻回 ──────────
                    circularDeadEnds.getOrPut(savedFp) { mutableSetOf() }.add(flipDir)
                    lastFlipDir    = null
                    lastFlipTimeMs = 0L
                    val reverseDir = when (flipDir) {
                        ScrollDir.LEFT  -> ScrollDir.RIGHT
                        ScrollDir.RIGHT -> ScrollDir.LEFT
                        ScrollDir.UP    -> ScrollDir.DOWN
                        ScrollDir.DOWN  -> ScrollDir.UP
                    }
                    tryPageFlip(null, reverseDir)  // 自动翻回原页面
                    meshHandler.postDelayed({
                        currentFocusState.value = null
                        navEngine.reset()
                        rebuildNavMesh(forceApply = true) { nodes ->
                            currentPageFp = savedFp  // 指纹回退到翻页前
                            currentFocusState.value = nodes.minByOrNull { n ->
                                hypot((n.centerX - prevCx).toDouble(),
                                      (n.centerY - prevCy).toDouble())
                            }
                        }
                    }, 350L)
                    return@rebuildNavMesh
                }
                // ── 真实翻页成功：将翻页前页面加入历史 ──────────────
                if (!pageHistory.contains(savedFp)) {
                    pageHistory.addFirst(savedFp)
                    if (pageHistory.size > 6) pageHistory.removeLast()
                }
                currentPageFp = newFp   // 更新当前页面指纹
            }
            // 在 NavMesh 重建完成后找最近节点
            val nearest = newNodes.minByOrNull { n ->
                hypot((n.centerX - prevCx).toDouble(), (n.centerY - prevCy).toDouble())
            }
            currentFocusState.value = nearest
        }
    }

    // =====================================================
    // Accessibility Events
    // =====================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        if (isIgnoredWindowEvent(event)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (scrollState == ScrollState.PLAN_B && isSameAutoScrollTarget(event)) {
                    pendingRefreshAfterAutoScroll = true
                    return
                }
                if (isJoystickSelecting) {
                    if (isSameSelectionTarget(event)) deferRefreshUntilSelectionEnds()
                    return
                }
                if (scrollState == ScrollState.PLAN_B) stopPlanB(refreshNodes = false)

                val changedPackage = event.packageName?.toString()
                val sameAppUpdate = changedPackage != null &&
                    changedPackage == meshPackage &&
                    changedPackage != packageName
                if (!sameAppUpdate) {
                    // 只有真正切换应用/窗口目标时才丢弃旧节点。
                    currentFocusState.value = null
                    currentNodes = emptyList()
                    navEngine.reset()
                }
                if (scrollState == ScrollState.HINT) cancelScroll()
                
                // 激活新窗口动态稳定探测机制
                isWindowSettled = false
                stateChangedTimeMs = System.currentTimeMillis()
                
                // 首帧抢跑：30ms 后极速执行第一次基础建图（对秒开设备最友好）
                meshHandler.removeCallbacks(meshRunnable)
                meshHandler.postDelayed(meshRunnable, DEBOUNCE_STATE_MS)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (scrollState == ScrollState.PLAN_B) {
                    pendingRefreshAfterAutoScroll = true
                    return
                }
                if (isJoystickSelecting) {
                    if (isSameSelectionTarget(event)) deferRefreshUntilSelectionEnds()
                    return
                }
                // 手动滑动保持旧节点作为双节点比较基准，刷新后就近校准焦点。
                if (scrollState == ScrollState.HINT) cancelScroll()
                isWindowSettled = false
                stateChangedTimeMs = System.currentTimeMillis()
                meshHandler.removeCallbacks(meshRunnable)
                meshHandler.postDelayed(meshRunnable, DEBOUNCE_DETECT_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (scrollState == ScrollState.PLAN_B) {
                    pendingRefreshAfterAutoScroll = true
                    return
                }
                if (isJoystickSelecting) {
                    if (isSameSelectionTarget(event)) deferRefreshUntilSelectionEnds()
                    return
                }
                // 核心智能分流：只有在未稳定，且在切页后的黄金 450ms 内，才允许响应内容改变进行侦测比对
                if (!isWindowSettled) {
                    val elapsed = System.currentTimeMillis() - stateChangedTimeMs
                    if (elapsed < MAX_SETTLE_WINDOW_MS) {
                        meshHandler.removeCallbacks(meshRunnable)
                        meshHandler.postDelayed(meshRunnable, DEBOUNCE_DETECT_MS)
                    } else {
                        isWindowSettled = true // 超过 450ms 上限，强制进入休眠状态
                    }
                }
                // 如果 isWindowSettled == true，日常 content change 直接丢弃，CPU 完美休眠
            }
        }
    }

    /**
     * 重建 NavMesh。
     *
     * [FIX 4] 拆分为两个阶段：
     *  - 第一阶段（主线程）：采集节点（ANI 只能在主线程访问）
     *  - 第二阶段（Default 线程）：构建图（O(n²)，不阻塞 UI）
     *  - 完成后回到主线程应用结果，并通过版本号丢弃过期结果。
     *
     * @param onDone 图构建完成后在主线程执行的回调（可选）
     */
    private fun rebuildNavMesh(forceApply: Boolean = false, onDone: ((List<NavNode>) -> Unit)? = null) {
        refreshScrollConfig()
        val version = ++navMeshVersion  // 本次重建的版本号
        val sampledPackage = activeTargetPackage()
        val collectStartedAt = System.nanoTime()

        // 第一阶段：主线程采集节点
        val rawNodes: List<NavNode>
        try {
            rawNodes = collectNodesFromWindows()
        } catch (e: Exception) {
            Log.e("TouchEngine", "collectNodes error: ${e.message}")
            return
        }
        val collectMs = (System.nanoTime() - collectStartedAt) / 1_000_000L

        // 【智能稳定判定核心】比对可见节点的几何结构、个数及所在窗口ID
        val hasChanged = (rawNodes.size != currentNodes.size) || 
            rawNodes.zip(currentNodes).any { (new, old) -> 
                new.bounds != old.bounds || new.windowId != old.windowId 
            }
        if (isTaobaoPackage(sampledPackage)) {
            Log.d("TouchEngineTaobao", "collect=${collectMs}ms nodes=${rawNodes.size} changed=$hasChanged force=$forceApply")
        }
        if (isDouyinPackage(sampledPackage)) {
            Log.d("TouchEngineDouyin", "collect=${collectMs}ms nodes=${rawNodes.size} changed=$hasChanged force=$forceApply pkg=$sampledPackage")
        } else if (collectMs >= 16L) {
            Log.d("TouchEnginePerf", "slowCollect=${collectMs}ms nodes=${rawNodes.size} pkg=$sampledPackage")
        }
        
        if (!forceApply && !hasChanged) {
            // 节点没有发生改变，证明页面已完全稳定（Settled）！
            // 立即标记为稳定状态，日常 CONTENT_CHANGED 自动失效，CPU 进入极致休眠
            isWindowSettled = true
            meshPackage = sampledPackage ?: meshPackage
            onDone?.invoke(currentNodes)
            return
        }

        // 第二阶段：说明有交互新节点产生，启动后台线程构建图，回主线程应用
        serviceScope.launch(Dispatchers.Default) {
            // [FIX 8] 连接距离改为屏幕高度的 45%，适配不同手机分辨率。
            val maxDist = (screenHeight * 0.45f).coerceAtLeast(600f)
            val buildStartedAt = System.nanoTime()
            NavMeshBuilder.build(rawNodes, maxConnectDist = maxDist)
            if (isTaobaoPackage(sampledPackage)) {
                val buildMs = (System.nanoTime() - buildStartedAt) / 1_000_000L
                Log.d("TouchEngineTaobao", "build=${buildMs}ms nodes=${rawNodes.size}")
            }
            if (isDouyinPackage(sampledPackage)) {
                val buildMs = (System.nanoTime() - buildStartedAt) / 1_000_000L
                Log.d("TouchEngineDouyin", "build=${buildMs}ms nodes=${rawNodes.size}")
            }

            withContext(Dispatchers.Main.immediate) {
                // 如果在此期间有更新的重建请求，直接丢弃本次结果
                if (version != navMeshVersion) return@withContext

                currentNodes = rawNodes
                isWindowSettled = true
                meshPackage = sampledPackage ?: meshPackage

                // [FIX 5] 用 20px 中心点容差匹配焦点节点
                val prevFocus = currentFocusState.value
                if (prevFocus != null) {
                    val sameNode = rawNodes.firstOrNull { n ->
                        hypot(
                            (n.centerX - prevFocus.centerX).toDouble(),
                            (n.centerY - prevFocus.centerY).toDouble()
                        ) < 20.0
                    }
                    currentFocusState.value = sameNode ?: rawNodes.minByOrNull { n ->
                        hypot(
                            (n.centerX - prevFocus.centerX).toDouble(),
                            (n.centerY - prevFocus.centerY).toDouble()
                        )
                    }
                }

                onDone?.invoke(rawNodes)
            }
        }
    }

    /**
     * 采集当前所有窗口的可点击节点。
     *
     * [FIX 4] 从 rebuildNavMesh 中抽出，便于单独测试和维护。
     * [FIX 4] 跳过 TYPE_INPUT_METHOD 窗口，防止键盘节点混入主 NavMesh，
     *          避免摇杆焦点意外穿透进输入法候选词区域。
     */
    private fun collectNodesFromWindows(): List<NavNode> {
        val allNodes = mutableListOf<NavNode>()
        val wins = windows
        if (wins.isNullOrEmpty()) {
            // 兜底：windows 拿不到时退回单窗口模式
            val root = obtainPrimaryContentRoot() ?: return emptyList()
            try { allNodes.addAll(parseAccessibilityTree(root)) }
            finally { root.recycle() }
        } else {
            val targetPackage = activeTargetPackage()
            val sortedWins = wins.sortedByDescending { it.layer }
            for (window in sortedWins) {
                // 只采集真实应用内容窗口；键盘、系统栏和悬浮层均不进入 NavMesh。
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue

                val root = window.root ?: continue
                // 【核心修复】跳过当前服务自身的悬浮球和高亮框窗口，避免自干扰和死循环重建
                if (root.packageName?.toString() == packageName) {
                    root.recycle()
                    continue
                }
                if (targetPackage != null && root.packageName?.toString() != targetPackage) {
                    root.recycle()
                    continue
                }
                try {
                    allNodes.addAll(parseAccessibilityTree(root))
                } finally {
                    root.recycle()
                }
            }
        }
        return allNodes.distinctBy {
            "${it.windowId}:${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}"
        }
    }

    private data class SubtreeProbeKey(val nodeHash: Int, val windowId: Int)

    /**
     * 探测一个节点下是否包含至少 2 个独立可见的可点击子节点。
     * 同一次节点采集内缓存已扫描子树，避免内容密集页面的嵌套候选重复深挖。
     */
    private fun hasMultipleClickableChildren(
        node: AccessibilityNodeInfo,
        cache: MutableMap<SubtreeProbeKey, Int>,
        visitLimit: Int = Int.MAX_VALUE
    ): Boolean {
        var remainingVisits = visitLimit
        fun countCapped(n: AccessibilityNodeInfo): Int {
            val key = SubtreeProbeKey(n.hashCode(), n.windowId)
            cache[key]?.let { return it }
            var count = 0
            for (i in 0 until n.childCount) {
                if (remainingVisits-- <= 0) return 2
                val child = n.getChild(i) ?: continue
                try {
                    if (child.isVisibleToUser && child.isClickable) {
                        count++
                        if (count >= 2) {
                            cache[key] = 2
                            return 2
                        }
                    }
                    count += countCapped(child)
                    if (count >= 2) {
                        cache[key] = 2
                        return 2
                    }
                } finally {
                    child.recycle()
                }
            }
            cache[key] = count
            return count
        }
        return countCapped(node) >= 2
    }

    /**
     * 判断一个节点是否是微信的聊天列表项或菜单项等列表行（专克微信无障碍加密）。
     * 仅对微信生效，严格进行包名、物理长宽比、组件类型和文本内容的多维校验，确保极高精确度。
     */
    private fun isWeChatListItem(node: AccessibilityNodeInfo): Boolean {
        if (node.packageName?.toString() != "com.tencent.mm") return false
        val className = node.className?.toString() ?: ""
        // 排除基本的可交互或叶子节点类型，只保留容器类节点
        if (className.contains("TextView") || 
            className.contains("ImageView") || 
            className.contains("Button") || 
            className.contains("EditText")
        ) return false
        
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val w = rect.width()
        val h = rect.height()
        // 微信列表项的尺寸特征：宽度接近屏幕宽，高度在 80px ~ 450px 之间
        if (w < screenWidth * 0.75f || h < 80 || h > 450) return false
        
        var textViewCount = 0
        fun checkChildren(n: AccessibilityNodeInfo) {
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                try {
                    val cls = child.className?.toString() ?: ""
                    if (cls.contains("TextView") || child.text != null) {
                        textViewCount++
                    }
                    if (child.childCount > 0) {
                        checkChildren(child)
                    }
                } finally {
                    child.recycle()
                }
            }
        }
        checkChildren(node)
        
        // 容器里必须有文本，且不能是滑动列表本身
        val isScroll = className.contains("ListView") || 
                      className.contains("RecyclerView") || 
                      className.contains("ScrollView")
        return textViewCount >= 1 && !isScroll
    }

    private data class TaobaoSemanticCandidate(
        val node: NavNode,
        val band: EdgeInteractionRules.TaobaoContentBand,
        val insideScrollContent: Boolean
    )

    private fun selectDouyinSearchFallbackNodes(
        candidates: List<NavNode>,
        existingNodes: List<NavNode>
    ): List<NavNode> {
        val fallback = mutableListOf<NavNode>()
        for (candidate in candidates.sortedWith(compareBy({ it.centerY }, { it.centerX }))) {
            if (fallback.size >= 18) break
            val cx = candidate.centerX.roundToInt()
            val cy = candidate.centerY.roundToInt()
            val covered = existingNodes.any { it.bounds.contains(cx, cy) } ||
                fallback.any {
                    hypot(
                        (it.centerX - candidate.centerX).toDouble(),
                        (it.centerY - candidate.centerY).toDouble()
                    ) < 28.0
                }
            if (!covered) fallback.add(candidate)
        }
        return fallback
    }

    private fun isEligibleTaobaoSemanticBounds(rect: Rect): Boolean {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        return rect.width() >= 44 &&
            rect.height() >= 20 &&
            rect.width() * rect.height() < screenArea * 0.18f &&
            cx > 0f && cx < screenWidth.toFloat() &&
            EdgeInteractionRules.taobaoContentBand(cy, screenHeight) != null
    }

    /**
     * 淘宝的不同频道只会在部分内容带暴露标准可点击节点。仅为空洞内容带补入
     * 少量语义节点，避免顶部按钮掩盖商品区域，也避免正常频道节点翻倍。
     */
    private fun selectTaobaoSemanticFallbackNodes(
        candidates: List<TaobaoSemanticCandidate>,
        existingNodes: List<NavNode>
    ): Pair<List<NavNode>, String> {
        val fallback = mutableListOf<NavNode>()
        val fineExisting = existingNodes.filter {
            it.bounds.width() * it.bounds.height() < screenArea * 0.18f
        }
        val maxPerBand = 10
        val reports = mutableListOf<String>()

        for (band in EdgeInteractionRules.TaobaoContentBand.entries) {
            val regularCount = fineExisting.count {
                EdgeInteractionRules.taobaoContentBand(it.centerY, screenHeight) == band
            }
            val bandCandidates = candidates
                .filter { it.band == band }
                .sortedWith(
                    compareByDescending<TaobaoSemanticCandidate> { it.insideScrollContent }
                        .thenBy { it.node.centerY }
                        .thenBy { it.node.centerX }
                )
            var added = 0
            if (EdgeInteractionRules.shouldBackfillTaobaoBand(regularCount, bandCandidates.size)) {
                for (candidate in bandCandidates) {
                    if (added >= maxPerBand) break
                    val cx = candidate.node.centerX.roundToInt()
                    val cy = candidate.node.centerY.roundToInt()
                    val covered = fineExisting.any { it.bounds.contains(cx, cy) } ||
                        fallback.any {
                            hypot(
                                (it.centerX - candidate.node.centerX).toDouble(),
                                (it.centerY - candidate.node.centerY).toDouble()
                            ) < 28.0
                        }
                    if (!covered) {
                        fallback.add(candidate.node)
                        added++
                    }
                }
            }
            reports.add("${band.name.lowercase()}=$regularCount/${bandCandidates.size}/$added")
        }
        return Pair(fallback, reports.joinToString(","))
    }

    /**
     * 递归遍历无障碍树，采集可点击节点。
     *
     * [FIX 2] child.recycle() 改为放在 finally 块中，
     *          保证遍历过程中即使发生异常也不会泄漏 ANI 对象。
     */
    private fun parseAccessibilityTree(root: AccessibilityNodeInfo?): List<NavNode> {
        if (root == null) return emptyList()
        val isTaobao = isTaobaoPackage(root.packageName)
        val isDouyin = isDouyinPackage(root.packageName)
        val isComplexContent = isTaobao || isDouyin
        val keyboardBoundary = if (isDouyin) keyboardTop() else null
        val traversalBudget = EdgeInteractionRules.traversalBudget(isComplexContent)
        val nodeLimit = EdgeInteractionRules.nodeLimit(isComplexContent)
        val result = mutableListOf<NavNode>()
        val subtreeProbeCache = mutableMapOf<SubtreeProbeKey, Int>()
        val taobaoCandidates = mutableListOf<TaobaoSemanticCandidate>()
        val douyinCandidates = mutableListOf<NavNode>()
        val acceptedBounds = mutableSetOf<String>()
        var visitedNodes = 0

        fun addResult(node: NavNode) {
            if (result.size >= nodeLimit) return
            val key = "${node.windowId}:${node.bounds.left}:${node.bounds.top}:${node.bounds.right}:${node.bounds.bottom}"
            if (acceptedBounds.add(key)) result.add(node)
        }

        fun traverse(
            node: AccessibilityNodeInfo,
            isParentScrollContainer: Boolean,
            insideScrollContent: Boolean
        ) {
            if (++visitedNodes > traversalBudget) return
            val className = node.className?.toString() ?: ""
            // 判断是否是滚动/列表容器，如果是，绝不能收录为可点击目标，必须无条件向下递归
            // 新增 node.collectionInfo 权威系统属性检测，绕过所有混淆
            val isScrollContainer = className.contains("RecyclerView")
                    || className.contains("ListView")
                    || className.contains("ScrollView")
                    || className.contains("GridView")
                    || className.contains("ViewPager")
                    || node.isScrollable
                    || (node.collectionInfo != null)

            // 【核心打捞】自身声明了 clickable，或者是列表容器的直接子节点
            // 新增系统 collectionItemInfo 打捞和微信特异性 isWeChatListItem 智能匹配
            val isClickableTarget = node.isClickable 
                    || (isParentScrollContainer && !isScrollContainer)
                    || (node.collectionItemInfo != null)
                    || isWeChatListItem(node)

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val area = rect.width() * rect.height()
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val label = node.contentDescription?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            if (isTaobao && node.isVisibleToUser && isEligibleTaobaoSemanticBounds(rect)) {
                val band = EdgeInteractionRules.taobaoContentBand(cy, screenHeight)
                if (label != null && band != null) {
                    taobaoCandidates.add(
                        TaobaoSemanticCandidate(
                            NavNode(rect, label, node.windowId),
                            band,
                            insideScrollContent
                        )
                    )
                }
            }
            if (isDouyin && node.isVisibleToUser && label != null &&
                EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(
                    rect.width(), rect.height(), cy, screenHeight, keyboardBoundary
                )
            ) {
                douyinCandidates.add(NavNode(rect, label, node.windowId))
            }
            if (!isScrollContainer
                && node.isVisibleToUser
                && isClickableTarget
                && area > 400
                && area < screenArea * 0.75f
                && rect.width() > 0 && rect.height() > 0
                // [FIX SCROLL v2] 用中心点判断是否在屏幕内：
                // 原来用 bounds 交叉判断，导致屏幕底部边缘少量伸出的节点还是会入表。
                // 改为中心点在屏幕内才收录，很少会有半身在屏幕外的节点满足条件
                && cy > 0f && cy < screenHeight.toFloat()
                && cx > 0f && cx < screenWidth.toFloat()
            ) {
                // 【智能多原子子树探测】
                val visitLimit = if (isComplexContent) 48 else 160
                if (hasMultipleClickableChildren(node, subtreeProbeCache, visitLimit)) {
                    // 如果该可点击容器下有 >= 2 个独立可点击子节点（如支付宝顶部的扫一扫、收付款组合整行），
                    // 我们不在此处截断，而是放行并继续向下递归，以收录更细粒度的原子按钮！
                } else {
                    // 如果底下只有 0 或 1 个可点击子项（如 QQ、支付宝或微信的聊天列表整行 Item），
                    // 直接收录 Parent，并 return 截断子树，防止头像、红点等碎片节点产生！
                    addResult(
                        NavNode(
                            bounds      = rect,
                            description = node.contentDescription?.toString()
                                ?: node.text?.toString(),
                            windowId    = node.windowId
                        )
                    )
                    return
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    traverse(
                        child,
                        isScrollContainer,
                        insideScrollContent || isScrollContainer
                    )
                } finally {
                    child.recycle() // [FIX 2] 改为 finally，异常时也能 recycle
                }
            }
        }
        traverse(root, false, false)
        if (isTaobao) {
            val (fallback, diagnostic) = selectTaobaoSemanticFallbackNodes(taobaoCandidates, result)
            fallback.forEach(::addResult)
            val report = "$diagnostic,total=${result.size},fallback=${fallback.size}"
            if (report != lastTaobaoParseDiagnostic) {
                Log.d("TouchEngineTaobao", "bands $report")
                lastTaobaoParseDiagnostic = report
            }
        }
        if (isDouyin && keyboardBoundary != null) {
            val fallback = selectDouyinSearchFallbackNodes(douyinCandidates, result)
            fallback.forEach(::addResult)
            val report = "imeTop=$keyboardBoundary,regular=${result.size - fallback.size},candidates=${douyinCandidates.size},fallback=${fallback.size},visited=$visitedNodes"
            if (report != lastDouyinParseDiagnostic) {
                Log.d("TouchEngineDouyin", "search $report")
                lastDouyinParseDiagnostic = report
            }
        }
        return result
    }

    // =====================================================
    // UI Init
    // =====================================================

    @SuppressLint("RtlHardcoded")
    private fun initFloatingUI() {
        // Focus overlay — 全屏，不拦截触摸
        val focusParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        focusBoxView = ComposeView(this).apply {
            setupComposeLifecycle(this)
            setContent { FocusHighlightUI(currentFocusState.value, scrollHintState.value) }
        }
        windowManager.addView(focusBoxView, focusParams)

        // Joystick overlay
        val basePx = (64 * Resources.getSystem().displayMetrics.density).toInt()
        joystickParams = WindowManager.LayoutParams(
            basePx,
            basePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = screenWidth / 2 - basePx / 2
            y = (screenHeight * 0.7f).toInt()
        }
        joystickView = ComposeView(this).apply {
            setupComposeLifecycle(this)
            setContent { JoystickUI() }
        }
        windowManager.addView(joystickView, joystickParams)
    }

    // =====================================================
    // Joystick UI
    //
    // 结构：外层 Box = 底座（固定不动）
    //        内层 Box = 小球（跟随手指在底座内移动）
    //
    // 操作模式：
    //   单指短拨   → 点拨跳一格
    //   单指按住   → 首跳 + 无极加速
    //   长按后拖动 → 移动整个悬浮球窗口
    //   单击       → 确认点击 / 返回
    //   双击       → Home
    // =====================================================

    @Composable
    fun JoystickUI() {
        // scope 仍保留用于边缘吸附计时器和确认超时计时器（纯 UI 行为，与滚动状态无关）
        val scope      = rememberCoroutineScope()
        val density    = LocalDensity.current
        val view       = LocalView.current
        val viewConfig = LocalViewConfiguration.current

        // ── 尺寸常量（响应用户外观设置）─────────────────────
        val ballSizeF      = liveBallSize.floatValue          // 0.0~1.0
        val baseSizeDp     = (44f + 32f * ballSizeF).dp      // 44~76dp，默认 0.65→65dp
        val ballSizeDp     = (17f + 14f * ballSizeF).dp      // 17~31dp，默认 0.65→26dp
        val baseSizePx     = with(density) { baseSizeDp.toPx() }
        val MAX_BALL_OFFSET = with(density) { (baseSizeDp / 2 - ballSizeDp / 2 - 4.dp).toPx() }

        // ── 状态 ──────────────────────────────────────────
        var windowX by remember { mutableStateOf(joystickParams.x.toFloat()) }
        var windowY by remember { mutableStateOf(joystickParams.y.toFloat()) }
        var ballOffsetX      by remember { mutableStateOf(0f) }
        var ballOffsetY      by remember { mutableStateOf(0f) }
        var isEdgeHidden     by remember { mutableStateOf(false) }
        var isConfirmPending by remember { mutableStateOf(false) }
        var isPressed        by remember { mutableStateOf(false) }
        var isDraggingWindow by remember { mutableStateOf(false) }
        var edgeTimerJob     by remember { mutableStateOf<Job?>(null) }
        var confirmTimerJob  by remember { mutableStateOf<Job?>(null) }
        var tapCount         by remember { mutableStateOf(0) }
        var tapTimeoutJob    by remember { mutableStateOf<Job?>(null) }

        // ── 动效 ──────────────────────────────────────────
        val baseScale by animateFloatAsState(
            targetValue   = if (isPressed) 0.90f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label         = "baseScale"
        )
        val baseAlpha by animateFloatAsState(
            targetValue   = if (isEdgeHidden) 0.45f else 1f,
            animationSpec = tween(300),
            label         = "baseAlpha"
        )
        val edgeTranslationX = 0f

        val breathTransition = rememberInfiniteTransition(label = "breath")
        val breathAlpha by breathTransition.animateFloat(
            initialValue  = 1f,
            targetValue   = 0.2f,
            animationSpec = infiniteRepeatable(
                animation  = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathAlpha"
        )
        val breathScale by breathTransition.animateFloat(
            initialValue  = 1f,
            targetValue   = 1.2f,
            animationSpec = infiniteRepeatable(
                animation  = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathScale"
        )
        val animBallX by animateFloatAsState(
            targetValue   = ballOffsetX,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label         = "ballX"
        )
        val animBallY by animateFloatAsState(
            targetValue   = ballOffsetY,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label         = "ballY"
        )

        LaunchedEffect(windowX, windowY) {
            joystickParams.x = windowX.roundToInt()
            joystickParams.y = windowY.roundToInt()
            joystickView?.let { windowManager.updateViewLayout(it, joystickParams) }
        }

        // ── 边缘吸附逻辑 ──────────────────────────────────
        fun checkAndStartEdgeTimer() {
            edgeTimerJob?.cancel()
            val margin    = 30f
            val nearLeft  = windowX < margin
            val nearRight = windowX > screenWidth - baseSizePx - margin
            if (nearLeft || nearRight) {
                edgeTimerJob = scope.launch {
                    delay(1200)
                    isEdgeHidden = true
                    windowX = if (nearLeft)
                        -(baseSizePx * 0.5f)
                    else
                        screenWidth - baseSizePx * 0.5f
                }
            }
        }

        // ── 单击 / 双击处理 ──────────────────────────────
        fun handleTap() {
            tapCount++
            tapTimeoutJob?.cancel()
            if (tapCount >= 2) {
                tapCount = 0
                performGlobalAction(GLOBAL_ACTION_HOME)
                checkAndStartEdgeTimer()
            } else {
                tapTimeoutJob = scope.launch {
                    delay(200)
                    if (tapCount == 1) {
                        if (isConfirmPending && currentFocusState.value != null) {
                            performClickOnTarget(currentFocusState.value)
                            isConfirmPending = false
                            currentFocusState.value = null
                            confirmTimerJob?.cancel()
                            navEngine.reset()
                            meshHandler.removeCallbacks(meshRunnable)
                            meshHandler.postDelayed(meshRunnable, 400L)
                        } else {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                    }
                    tapCount = 0
                    checkAndStartEdgeTimer()
                }
            }
        }

        // ══════════════════════════════════════════════════
        // 悬浮球 UI
        // ══════════════════════════════════════════════════
        Box(
            modifier = Modifier
                .size(baseSizeDp)
                .graphicsLayer {
                    scaleX       = baseScale
                    scaleY       = baseScale
                    // 透明度 = 边缘自动调暗 × 用户不透明度（休眠时固定35%）
                    alpha        = baseAlpha * (if (liveEnabled.value) liveBallOpacity.floatValue else 0.35f)
                    translationX = edgeTranslationX
                }
                .background(
                    color = Color(0xFF1C1C1E).copy(alpha = 0.82f),
                    shape = CircleShape
                )
                .border(
                    width = 1.5.dp,
                    color = when {
                        isConfirmPending -> Color(0xFF34C759).copy(alpha = 0.9f)
                        isDraggingWindow -> Color(0xFFFFCC00).copy(alpha = 0.8f)
                        else             -> Color(0xFFFFFFFF).copy(alpha = 0.18f)
                    },
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        isJoystickSelecting = true
                        selectionPackage = activeTargetPackage()
                        pendingRefreshAfterSelection = !isWindowSettled
                        edgeTimerJob?.cancel()

                        // 一次拉球手势使用同一份 NavMesh，避免淘宝动态事件把导航图中途替换掉。
                        meshHandler.removeCallbacks(meshRunnable)
                        navMeshVersion++
                        if (currentNodes.isEmpty()) {
                            // 缺图时排队预热，避免在按下回调中同步扫描复杂页面并卡住手指反馈。
                            meshHandler.post(meshRunnable)
                        }

                        // 缓存起始节点
                        var dragStartNode: NavNode? = currentFocusState.value
                            ?: currentNodes.minByOrNull { n ->
                                hypot(
                                    (n.centerX - (windowX + baseSizePx / 2)).toDouble(),
                                    (n.centerY - (windowY + baseSizePx / 2)).toDouble()
                                )
                            }

                        // ── 唤醒边缘吸附状态 ──────────────────────────
                        if (isEdgeHidden) {
                            isEdgeHidden = false
                            windowX = if (windowX < screenWidth / 2)
                                with(density) { 16.dp.toPx() }
                            else
                                screenWidth - baseSizePx - with(density) { 16.dp.toPx() }
                            do { awaitPointerEvent() }
                            while (awaitPointerEvent().changes.any { it.pressed })
                            isPressed = false
                            isJoystickSelecting = false
                            selectionPackage = null
                            pendingRefreshAfterSelection = false
                            dragStartNode = null
                            return@awaitEachGesture
                        }

                        var dragStarted        = false
                        var longPressTriggered = false
                        var lastDragX          = 0f
                        var lastDragY          = 0f

                        val longPressJob = scope.launch {
                            delay(400)
                            longPressTriggered = true
                            isDraggingWindow   = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }

                        try {
                            do {
                                val event  = awaitPointerEvent()
                                val change = event.changes.first()
                                val rawDx   = change.position.x - down.position.x
                                val rawDy   = change.position.y - down.position.y
                                val rawDist = hypot(rawDx, rawDy)

                                if (!dragStarted && rawDist > viewConfig.touchSlop) {
                                    dragStarted = true
                                    if (!longPressTriggered) longPressJob.cancel()
                                }

                                if (dragStarted) {
                                    if (longPressTriggered) {
                                        // ── 窗口拖拽模式 ──────────────────────
                                        windowX = (windowX + change.positionChange().x)
                                            .coerceIn(0f, screenWidth - baseSizePx)
                                        windowY = (windowY + change.positionChange().y)
                                            .coerceIn(0f, (screenHeight - baseSizePx).toFloat())
                                        ballOffsetX = 0f
                                        ballOffsetY = 0f
                                    } else {
                                        // ── 摇杆模式 ──────────────────────────
                                        val clampedX: Float
                                        val clampedY: Float
                                        if (rawDist > MAX_BALL_OFFSET) {
                                            val s = MAX_BALL_OFFSET / rawDist
                                            clampedX = rawDx * s
                                            clampedY = rawDy * s
                                        } else {
                                            clampedX = rawDx
                                            clampedY = rawDy
                                        }
                                        ballOffsetX = clampedX
                                        ballOffsetY = clampedY
                                        lastDragX   = clampedX
                                        lastDragY   = clampedY

                                        if (scrollState == ScrollState.PLAN_B) {
                                            if (!isHoldingAutoScrollDirection(clampedX, clampedY, MAX_BALL_OFFSET)) {
                                                stopPlanB()
                                                dragStartNode = null
                                            }
                                            change.consume()
                                            continue
                                        }

                                        // 【优化：拖拽中途动态再锚定】若按下瞬间无可用节点（例如在刚切页，网格还在异步重建中）
                                        // 在拖动过程中，一旦 currentNodes 异步重建载入新节点，瞬间补获最近节点，使选择框立刻现形，绝不卡手！
                                        if (dragStartNode == null && currentFocusState.value == null && currentNodes.isNotEmpty()) {
                                            dragStartNode = currentNodes.minByOrNull { n ->
                                                hypot(
                                                    (n.centerX - (windowX + baseSizePx / 2)).toDouble(),
                                                    (n.centerY - (windowY + baseSizePx / 2)).toDouble()
                                                )
                                            }
                                        }

                                        // ── 调用导航引擎 ──────────────────────
                                        val startNode = currentFocusState.value ?: dragStartNode
                                        // [FIX UI] 服务关闭时跳过所有导航逻辑，仅保留球位置显示
                                        if (startNode != null && liveEnabled.value) {
                                            val result = navEngine.onDrag(
                                                currentNode = startNode,
                                                dragX       = clampedX,
                                                dragY       = clampedY,
                                                maxRadius   = MAX_BALL_OFFSET
                                            )
                                            if (result.node !== currentFocusState.value) {
                                                currentFocusState.value = result.node
                                                edgeCount = 0; edgeDir = null
                                                cancelScroll() // [FIX 3] 原来是 cancelHint()，现在覆盖 PLAN_B
                                            }
                                            // 边缘检测
                                            val ratio = hypot(clampedX.toDouble(), clampedY.toDouble()).toFloat() / MAX_BALL_OFFSET

                                            // [FIX STUCK NAV] 固定导航栏脱困：
                                            // 底部/顶部固定UI（底部Tab栏、顶部Title栏）的节点，
                                            // 因偏角超出 CONE_HALF_ANGLE(45°) 而找不到上/下邻居。
                                            // 检测到此情况时，直接全局搜索最近的上/下方节点跳过去，
                                            // 避免用户卡在导航栏里无法回到内容区域。
                                            var edgeHandled = false
                                            if (result.atEdge && ratio >= 0.28f && scrollState == ScrollState.IDLE) {
                                                val escDir  = dirFromAngle(result.intentAngle)
                                                val nodeY   = result.node.centerY
                                                val enteringContent =
                                                    (escDir == ScrollDir.UP && nodeY > screenHeight * 0.82f) ||
                                                    (escDir == ScrollDir.DOWN && nodeY < screenHeight * 0.18f)
                                                val escapeNode: NavNode? = when {
                                                    escDir == ScrollDir.UP &&
                                                    nodeY > screenHeight * 0.82f -> {
                                                        // 底部固定区域 → 向上找最近节点
                                                        currentNodes
                                                            .filter { n -> n !== result.node && n.centerY < nodeY - 30f }
                                                            .minByOrNull { n -> nodeY - n.centerY }
                                                    }
                                                    escDir == ScrollDir.DOWN &&
                                                    nodeY < screenHeight * 0.18f -> {
                                                        // 顶部固定区域 → 向下找最近节点
                                                        currentNodes
                                                            .filter { n -> n !== result.node && n.centerY > nodeY + 30f }
                                                            .minByOrNull { n -> n.centerY - nodeY }
                                                    }
                                                    else -> null
                                                }
                                                if (enteringContent) {
                                                    edgeHandled = true
                                                    if (escapeNode != null) {
                                                        currentFocusState.value = escapeNode
                                                        navEngine.reset()
                                                        edgeCount = 0; edgeDir = null
                                                    } else {
                                                        // 向内容区移动却暂无内容节点时，等待松手刷新，不能误触发滑动。
                                                        pendingRefreshAfterSelection = true
                                                    }
                                                }
                                            }

                                            if (!edgeHandled && result.atEdge && ratio >= 0.28f
                                                && isRealEdge(result.node, result.intentAngle)
                                                && scrollState == ScrollState.IDLE) {
                                                val dir = dirFromAngle(result.intentAngle)
                                                handleEdge(result.node, dir, ratio) // [FIX 1] 无 scope 参数
                                            } else if (!result.atEdge) {
                                                if (result.node !== startNode) {
                                                    edgeCount = 0; edgeDir = null
                                                }
                                            } else if (result.atEdge && scrollState != ScrollState.IDLE) {
                                                // [FIX 6] 摇杆"拉回"检测：
                                                // 处于滚动状态时，如果用户把摇杆拉向与当前边缘方向相反的方向
                                                // （夹角 > 90°），则立即取消滚动状态，释放焦点锁定，
                                                // 让摇杆恢复正常的导航响应，防止焦点卡死在边缘节点出不来。
                                                val currentAngle = Math.toDegrees(
                                                    kotlin.math.atan2(clampedY.toDouble(), clampedX.toDouble())
                                                )
                                                val edgeDirAngle = edgeDir?.let { d ->
                                                    when (d) {
                                                        ScrollDir.UP    -> -90.0
                                                        ScrollDir.DOWN  ->  90.0
                                                        ScrollDir.LEFT  -> 180.0
                                                        ScrollDir.RIGHT ->   0.0
                                                    }
                                                }
                                                if (edgeDirAngle != null) {
                                                    var angleDiff = kotlin.math.abs(currentAngle - edgeDirAngle)
                                                    if (angleDiff > 180) angleDiff = 360 - angleDiff
                                                    if (angleDiff > 90.0) {
                                                        cancelScroll()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            longPressJob.cancel()
                            isPressed        = false
                            isDraggingWindow = false
                            isJoystickSelecting = false
                            selectionPackage = null
                            dragStartNode    = null  // [FIX 5] 手势结束后清空缓存

                            if (dragStarted && !longPressTriggered) {
                                // ── 摇杆松手 ──────────────────────────────
                                if (scrollState == ScrollState.PLAN_B) {
                                    stopPlanB() // [FIX 1] 无 scope 参数
                                } else {
                                    cancelScroll() // [FIX 3] 原来是 cancelHint()
                                }
                                val startNode = currentFocusState.value
                                if (startNode != null) {
                                    val result = navEngine.onRelease(
                                        currentNode = startNode,
                                        dragX       = lastDragX,
                                        dragY       = lastDragY,
                                        maxRadius   = MAX_BALL_OFFSET
                                    )
                                    if (result !== currentFocusState.value) {
                                        currentFocusState.value = result
                                        edgeCount = 0; edgeDir = null
                                    } else {
                                        // 点拨到边缘：尝试单次翻页（仅方案A）
                                        val ratio = hypot(lastDragX.toDouble(), lastDragY.toDouble()).toFloat() / MAX_BALL_OFFSET
                                        val ang   = Math.toDegrees(kotlin.math.atan2(lastDragY.toDouble(), lastDragX.toDouble()))
                                        if (ratio >= 0.5f && isRealEdge(startNode, ang)) {
                                            handleFlickEdge(startNode, dirFromAngle(ang), ratio)
                                        }
                                    }
                                }
                                navEngine.reset()
                                edgeCount = 0; edgeDir = null

                                // 进入确认状态（仅在非滚动状态下）
                                if (currentFocusState.value != null && scrollState == ScrollState.IDLE) {
                                    isConfirmPending = true
                                    confirmTimerJob?.cancel()
                                    confirmTimerJob = scope.launch {
                                        delay(3000)
                                        isConfirmPending = false
                                        currentFocusState.value = null
                                    }
                                }
                            }
                            refreshAfterSelectionIfNeeded()
                            ballOffsetX = 0f
                            ballOffsetY = 0f
                            if (!dragStarted && !longPressTriggered) {
                                handleTap()
                            }
                            if (longPressTriggered) {
                                checkAndStartEdgeTimer()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // ── 底座内部纹理 ─────────────────────────────────
            Box(
                modifier = Modifier
                    .size(baseSizeDp - 8.dp)
                    .background(
                        color = Color(0xFF2C2C2E).copy(alpha = 0.6f),
                        shape = CircleShape
                    )
            )
            // ── 操控小球 ─────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(ballSizeDp)
                    .graphicsLayer {
                        translationX = animBallX
                        translationY = animBallY
                        if (isConfirmPending) {
                            alpha  = breathAlpha
                            scaleX = breathScale
                            scaleY = breathScale
                        }
                    }
                    .background(
                        color = when {
                            isConfirmPending -> Color(0xFF34C759)
                            isDraggingWindow -> Color(0xFFFFCC00)
                            else             -> Color(0xFFFFFFFF)
                        },
                        shape = CircleShape
                    )
            )
        }
    }

    // =====================================================
    // Focus Highlight
    // =====================================================

    @Composable
    fun FocusHighlightUI(targetNode: NavNode?, hintData: ScrollHintData?) {
        val density = LocalDensity.current
        // 选择框强调色：亮青绿色，在深色和浅色背景下都醒目
        val accentColor = Color(0xFF00FFCC)

        // [FIX UI-1] 状态栏高度补偿
        // getBoundsInScreen() 坐标原点 = 屏幕物理顶部（含状态栏）
        // ComposeView 布局坐标原点 = 状态栏底部
        // 两者差一个状态栏高度，不补偿则选择框整体偏下
        val statusBarHeightPx = remember {
            val id = Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) Resources.getSystem().getDimensionPixelSize(id) else 0
        }

        // ── 提示气泡（方案B倒计时）────────────────────────
        AnimatedVisibility(
            visible = hintData != null,
            enter   = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically { it / 2 },
            exit    = fadeOut(tween(200)) + androidx.compose.animation.slideOutVertically { it / 2 }
        ) {
            val hint = hintData ?: return@AnimatedVisibility
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Column(
                    modifier = Modifier
                        .padding(bottom = 80.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 主标题：根据类型显示「即将翻页」或「即将滑动」
                    val actionText = when (hint.type) {
                        HintType.PAGE_FLIP -> "即将翻页"
                        HintType.SCROLL    -> "即将滑动"
                    }
                    val arrowText = when (hint.dir) {
                        ScrollDir.DOWN  -> "↓"
                        ScrollDir.UP    -> "↑"
                        ScrollDir.RIGHT -> "→"
                        ScrollDir.LEFT  -> "←"
                    }
                    // 箭头 + 主标题 + 箭头，三件套横排
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(arrowText, color = Color.White, fontSize = 18.sp)
                        Text(
                            actionText,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(arrowText, color = Color.White, fontSize = 18.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    // 倒计时进度条（翻页=橙色暖调，滑动=青色冷调）
                    val barColors = if (hint.type == HintType.PAGE_FLIP) {
                        listOf(Color(0xFFFFCC00), Color(0xFFFF6B00))
                    } else {
                        listOf(Color(0xFF00FFCC), Color(0xFF00AAFF))
                    }
                    Box(
                        modifier = Modifier
                            .width(160.dp).height(4.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(hint.progress)
                                .background(
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(barColors),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${((1f - hint.progress) * (scrollConfig.hintDurationMs / 1000f)).let { "%.1f".format(it) }}s 后执行",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        // 用 remember 缓存最后一个非空的节点，防止消失动画期间内容变成 null 导致闪烁
        val lastNode = remember(targetNode) { targetNode } ?: return
        AnimatedVisibility(
            visible = targetNode != null,
            enter   = fadeIn(tween(120)),
            exit    = fadeOut(tween(200))
        ) {
            val node = lastNode
            val targetLeft   = with(density) { node.bounds.left.toDp() }
            // [FIX UI-1] 减去状态栏高度，对齐真实 UI 元素位置
            val targetTop    = with(density) { (node.bounds.top - statusBarHeightPx).toDp() }
            val targetWidth  = with(density) { node.bounds.width().toDp() }
            val targetHeight = with(density) { node.bounds.height().toDp() }

            val animSpec = spring<Dp>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness    = Spring.StiffnessMedium
            )
            val animLeft   by animateDpAsState(targetLeft,   animSpec, label = "l")
            val animTop    by animateDpAsState(targetTop,    animSpec, label = "t")
            val animWidth  by animateDpAsState(targetWidth,  animSpec, label = "w")
            val animHeight by animateDpAsState(targetHeight, animSpec, label = "h")

            val infiniteTransition = rememberInfiniteTransition(label = "lockOn")
            // [FIX UI-2] 提升选择框可见度
            // 原来：breathAlpha 最低 0.25（太暗）、stroke 最细 1.5px（太细）、bgAlpha 最低 0.03（几乎不可见）
            // 修改后：最低透明度提高、边框加粗、背景填充更明显
            val breathAlpha by infiniteTransition.animateFloat(
                initialValue  = 1f,
                targetValue   = 0.55f,   // 原 0.25，提高到 0.55，最暗时仍清晰可见
                animationSpec = infiniteRepeatable(
                    animation  = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "breathAlpha"
            )
            val breathStroke by infiniteTransition.animateFloat(
                initialValue  = 4.5f,   // 原 3f，加粗
                targetValue   = 2.5f,   // 原 1.5f，最细也更粗
                animationSpec = infiniteRepeatable(
                    animation  = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "breathStroke"
            )
            val bgAlpha by infiniteTransition.animateFloat(
                initialValue  = 0.28f,  // 原 0.12f，背景填充更明显
                targetValue   = 0.12f,  // 原 0.03f，最淡时也能看到
                animationSpec = infiniteRepeatable(
                    animation  = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bgAlpha"
            )
            val flowProgress by infiniteTransition.animateFloat(
                initialValue  = 0f,
                targetValue   = 1f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(3000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "flow"
            )

            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .absoluteOffset(x = animLeft, y = animTop)
                        .size(width = animWidth, height = animHeight)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                accentColor.copy(alpha = bgAlpha),
                                RoundedCornerShape(6.dp)
                            )
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w      = size.width
                        val h      = size.height
                        val stroke = breathStroke * density.density
                        val color  = accentColor.copy(alpha = breathAlpha)
                        val armX   = w * 0.20f
                        val armY   = h * 0.20f
                        val perimeter = 2f * (w + h)
                        val offset    = flowProgress * perimeter
                        val paint = androidx.compose.ui.graphics.Paint().apply {
                            this.color       = color
                            this.strokeWidth = stroke
                            this.style       = androidx.compose.ui.graphics.PaintingStyle.Stroke
                            strokeCap        = androidx.compose.ui.graphics.StrokeCap.Round
                        }
                        drawContext.canvas.apply {
                            for (i in 0..3) {
                                val cornerOffset = (offset + i * perimeter / 4f) % perimeter
                                val cx: Float
                                val cy: Float
                                when {
                                    cornerOffset < w       -> { cx = cornerOffset; cy = 0f }
                                    cornerOffset < w + h   -> { cx = w; cy = cornerOffset - w }
                                    cornerOffset < 2*w + h -> { cx = w - (cornerOffset - w - h); cy = h }
                                    else                   -> { cx = 0f; cy = h - (cornerOffset - 2*w - h) }
                                }
                                val isTop    = cy == 0f
                                val isRight  = cx == w
                                val isBottom = cy == h
                                when {
                                    isTop    -> drawLine(Offset(cx - armX * 0.5f, cy), Offset(cx + armX * 0.5f, cy), paint)
                                    isRight  -> drawLine(Offset(cx, cy - armY * 0.5f), Offset(cx, cy + armY * 0.5f), paint)
                                    isBottom -> drawLine(Offset(cx - armX * 0.5f, cy), Offset(cx + armX * 0.5f, cy), paint)
                                    else     -> drawLine(Offset(cx, cy - armY * 0.5f), Offset(cx, cy + armY * 0.5f), paint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =====================================================
    // Click
    // =====================================================

    /**
     * 点击目标节点。
     *
     * [FIX 8] 父节点点击回退：
     * 部分 App（如淘宝）的商品图片（ImageView）在无障碍树中标记为可点击，
     * 但实际点击监听器注册在父 ViewGroup（整个商品卡片）上。
     * 直接对 ImageView 执行 ACTION_CLICK 无效，需要向上找到真正的可点击父节点。
     */
    private fun performClickOnTarget(target: NavNode?) {
        if (target == null) return
        val root = obtainPrimaryContentRoot() ?: return
        val native = findNativeNodeByBounds(root, target.bounds)
        var clicked = false
        if (native != null) {
            clicked = native.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                // 直接点击失败，向上安全遍历父节点寻找真正响应点击的容器（规避 Double-Recycle）
                var parent = native.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            parent.recycle()
                            break
                        }
                    }
                    val grandParent = parent.parent
                    parent.recycle()
                    parent = grandParent
                }
            }
            native.recycle()
        }
        root.recycle()

        // 【终极手势物理轻触兜底】如果无障碍 ACTION_CLICK 点击完全失败（常见于微信重度定制/混淆的会话列表），
        // 直接使用系统 dispatchGesture 派发物理中心点的瞬时轻触手势，强行穿透点击！
        if (!clicked) {
            val cx = target.bounds.exactCenterX()
            val cy = target.bounds.exactCenterY()
            val path = Path().apply {
                moveTo(cx, cy)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    private fun findNativeNodeByBounds(
        root: AccessibilityNodeInfo?,
        targetBounds: Rect
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        var result: AccessibilityNodeInfo? = null
        fun traverse(node: AccessibilityNodeInfo) {
            if (result != null) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect == targetBounds && (node.isClickable || node.isFocusable)) {
                result = AccessibilityNodeInfo.obtain(node)
                return
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child)
                child.recycle()
                if (result != null) return
            }
        }
        traverse(root)
        return result
    }

    // =====================================================
    // Compose Lifecycle
    // =====================================================

    private fun setupComposeLifecycle(view: ComposeView) {
        val owner = ComposeLifecycleOwner()
        owner.performRestore(null)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
    }

    private class ComposeLifecycleOwner :
        LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry            = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store                        = ViewModelStore()
        override val lifecycle: Lifecycle
            get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore
            get() = store
        fun handleLifecycleEvent(e: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(e)
        fun performRestore(s: Bundle?)               = savedStateRegistryController.performRestore(s)
    }
}




