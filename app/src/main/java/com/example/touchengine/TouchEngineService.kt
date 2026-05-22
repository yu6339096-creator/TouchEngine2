package com.example.touchengine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
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

    // ── 滚动状态（主线程访问，无需加锁）────────────────────
    private var scrollConfig = ScrollConfig()
    private var scrollState  = ScrollState.IDLE   // 当前滚动阶段
    private var edgeCount    = 0                  // 边缘帧计数
    private var edgeDir: ScrollDir? = null        // 当前边缘方向
    private var scrollHintJob: Job? = null
    private var scrollBJob:    Job? = null
    // 翻页前焦点位置，用于翻页后就近定位
    private var prevFocusCx  = 0f
    private var prevFocusCy  = 0f

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
            val root = rootInActiveWindow ?: return ""
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
                                className.contains("GridView",  ignoreCase = true)
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

    /**
     * 查找当前焦点所在的可滚动容器，支持方向隔离。
     *
     * [FIX 2] 修复 BFS 队列中间节点泄漏问题。
     * 原版只 recycle 了 root，BFS 过程中展开的所有中间节点全部泄漏。
     * 现在每个节点出队后立即处理：不是目标则 recycle；找到目标则清空剩余队列后返回。
     */
    private fun findScrollableNode(dir: ScrollDir): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isScrollable && supportsScrollDirection(node, dir)) {
                    // 找到目标：先清空队列中剩余节点，再返回
                    queue.forEach { it.recycle() }
                    val result = AccessibilityNodeInfo.obtain(node)
                    node.recycle()
                    return result
                }
                // 没找到：展开子节点，当前节点 recycle
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
                node.recycle()
            }
        } catch (e: Exception) {
            // 异常时清空所有未 recycle 的节点，防止泄漏
            queue.forEach { runCatching { it.recycle() } }
            Log.e("TouchEngine", "findScrollableNode error: ${e.message}")
        }
        return null
    }

    /**
     * 执行方案A（ACTION_SCROLL），返回是否成功。
     *
     * [FIX 7] 对于纵向的连续滚动容器（RecyclerView / ListView / ScrollView），
     * ACTION_SCROLL_FORWARD 每次跳一整页，体验很生硬，看起来像"翻页"。
     * 这类容器应由方案B的手势滑动来处理，更自然流畅。
     * 方案A只保留给真正的翻页容器（ViewPager、HorizontalScrollView 等）
     * 以及横向滚动动作（LEFT / RIGHT）。
     */
    private fun tryPlanA(dir: ScrollDir): Boolean {
        val scrollNode = findScrollableNode(dir) ?: return false

        // [FIX 7] 纵向方向下，过滤掉连续滚动容器，交给方案B处理
        if (dir == ScrollDir.UP || dir == ScrollDir.DOWN) {
            val className = scrollNode.className?.toString() ?: ""
            val isContinuousScroller =
                className.contains("RecyclerView",  ignoreCase = true) ||
                className.contains("ListView",      ignoreCase = true) ||
                className.contains("GridView",      ignoreCase = true) ||
                (className.contains("ScrollView",   ignoreCase = true) &&
                 !className.contains("HorizontalScrollView", ignoreCase = true))
            if (isContinuousScroller) {
                scrollNode.recycle()
                return false  // 不用方案A，让调用方走方案B手势滑动
            }
        }

        val actionId = getScrollActionId(scrollNode, dir) ?: run {
            scrollNode.recycle()
            return false
        }
        val success = scrollNode.performAction(actionId)
        scrollNode.recycle()
        return success
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

    /**
     * 处理边缘检测结果（在 onDrag 结果为 atEdge=true 时调用）。
     *
     * [FIX 9] 重构翻页/滑动时序：
     *   - 方案A不再立即执行，统一走倒计时，防止悬浮球一碰边缘就翻页
     *   - 新增方向锁死：翻页后 1.5s 内封锁反向翻页，防止乒乓跳页
     */
    private fun handleEdge(dir: ScrollDir, ratio: Float) {
        if (!scrollConfig.autoScrollEnabled) return
        if (ratio < 0.28f) return

        // [FIX 9] 方向锁：翻页后短时间内封锁反向，防止立即翻回
        val lockedDir = when (lastFlipDir) {
            ScrollDir.LEFT  -> ScrollDir.RIGHT
            ScrollDir.RIGHT -> ScrollDir.LEFT
            ScrollDir.UP    -> ScrollDir.DOWN
            ScrollDir.DOWN  -> ScrollDir.UP
            null            -> null
        }
        if (lockedDir == dir && System.currentTimeMillis() - lastFlipTimeMs < FLIP_LOCK_MS) return

        // [FIX CIRCULAR] 若当前页面在该方向已被标记为循环死胡同，直接跳过
        if (circularDeadEnds[currentPageFp]?.contains(dir) == true) return

        if (dir == edgeDir) {
            edgeCount++
        } else {
            edgeDir   = dir
            edgeCount = 1
            cancelScroll()
        }

        if (edgeCount >= scrollConfig.edgeTriggerFrames) {
            edgeCount = 0
            prevFocusCx = currentFocusState.value?.centerX ?: 0f
            prevFocusCy = currentFocusState.value?.centerY ?: 0f
            // [FIX 9] 方案A不再立即执行，直接走倒计时
            // 倒计时结束时由 startPlanB 判断：翻页容器→方案A，连续滚动容器→方案B
            startHint(dir)
        }
    }

    /**
     * 处理点拨到边缘（onRelease 时 atEdge=true 且推力足够）。
     * 快速点拨仍直接走方案A（翻页），不需要倒计时。
     */
    private fun handleFlickEdge(dir: ScrollDir, ratio: Float) {
        if (!scrollConfig.autoScrollEnabled) return
        if (ratio < 0.5f) return
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
        if (tryPlanA(dir)) {
            lastFlipDir    = dir
            lastFlipTimeMs = System.currentTimeMillis()
            edgeCount = 0; edgeDir = null
            meshHandler.postDelayed({ afterScroll(prevFocusCx, prevFocusCy) }, 400L)
        }
    }

    /**
     * 预判断即将触发的操作类型（翻页 or 滑动），用于提示文字显示。
     * 逻辑与 tryPlanA 的过滤条件对称：能翻页的返回 PAGE_FLIP，否则 SCROLL。
     */
    private fun detectHintType(dir: ScrollDir): HintType {
        val scrollNode = findScrollableNode(dir) ?: return HintType.SCROLL
        val className  = scrollNode.className?.toString() ?: ""
        scrollNode.recycle()
        // 纵向连续滚动容器 → 滑动
        if (dir == ScrollDir.UP || dir == ScrollDir.DOWN) {
            val isContinuousScroller =
                className.contains("RecyclerView",  ignoreCase = true) ||
                className.contains("ListView",      ignoreCase = true) ||
                className.contains("GridView",      ignoreCase = true) ||
                (className.contains("ScrollView",   ignoreCase = true) &&
                 !className.contains("HorizontalScrollView", ignoreCase = true))
            if (isContinuousScroller) return HintType.SCROLL
        }
        return HintType.PAGE_FLIP
    }

    /**
     * 显示提示气泡并开始倒计时，倒计时结束后进入执行阶段。
     *
     * [FIX 9] 预先检测操作类型，让提示文字告知用户即将翻页还是滑动。
     */
    private fun startHint(dir: ScrollDir) {
        if (scrollState != ScrollState.IDLE) return
        scrollState = ScrollState.HINT
        // 提前判断类型，提示条上显示 "即将翻页" 或 "即将滑动"
        val hintType = detectHintType(dir)
        scrollHintJob?.cancel()
        scrollHintJob = serviceScope.launch {
            val startMs = System.currentTimeMillis()
            while (true) {
                val elapsed  = System.currentTimeMillis() - startMs
                val progress = (elapsed.toFloat() / scrollConfig.hintDurationMs).coerceAtMost(1f)
                scrollHintState.value = ScrollHintData(dir, progress, hintType)
                if (progress >= 1f) {
                    startPlanB(dir)
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
    private fun startPlanB(dir: ScrollDir) {
        scrollHintState.value = null
        scrollBJob?.cancel()
        val timeoutAt = System.currentTimeMillis() + 30_000L
        scrollBJob = serviceScope.launch {
            // [FIX CIRCULAR] 翻页前保存当前页面指纹，用于 afterScroll 中对比
            prePlanAFp = computeScreenFingerprint()
            // 先试方案A（翻页），倒计时结束时执行
            if (tryPlanA(dir)) {
                // [FIX 9] 记录翻页方向和时间，用于方向锁
                lastFlipDir    = dir
                lastFlipTimeMs = System.currentTimeMillis()
                scrollState = ScrollState.IDLE
                meshHandler.postDelayed({ afterScroll(prevFocusCx, prevFocusCy) }, 400L)
                return@launch
            }
            // 方案A无效（连续滚动容器）→ 持续手势滑动
            scrollState = ScrollState.PLAN_B
            while (scrollState == ScrollState.PLAN_B
                   && System.currentTimeMillis() < timeoutAt) {
                doPlanBGesture(dir)
                delay(scrollConfig.scrollGestureDurationMs + 50)
            }
            if (scrollState == ScrollState.PLAN_B) {
                scrollState = ScrollState.IDLE
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
        scrollHintJob?.cancel()
        scrollBJob?.cancel()
        scrollState = ScrollState.IDLE
        scrollHintState.value = null
        edgeCount = 0
        edgeDir = null
    }

    /**
     * 方案B松手：触发惯性后停止。
     *
     * [FIX 1] 使用 serviceScope。
     * [FIX 3] 修复原版先清空 edgeDir 再读取导致惯性手势永远不执行的 Bug：
     *          现在先将 edgeDir 保存到局部变量 dir，再清空字段。
     */
    private fun stopPlanB() {
        if (scrollState != ScrollState.PLAN_B) return
        val dir = edgeDir  // [FIX 3] 先读，再清；原版先清后读，dir 永远是 null
        scrollBJob?.cancel()
        scrollState = ScrollState.IDLE
        scrollHintState.value = null
        edgeCount = 0
        edgeDir = null
        // 惯性：再执行一次手势后重建 NavMesh
        serviceScope.launch {
            delay(scrollConfig.scrollGestureDurationMs / 2)
            if (dir != null) doPlanBGesture(dir)
            delay(200)
            meshHandler.post { rebuildNavMesh() }
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
        rebuildNavMesh { newNodes ->
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
                    tryPlanA(reverseDir)  // 自动翻回原页面
                    meshHandler.postDelayed({
                        currentFocusState.value = null
                        navEngine.reset()
                        rebuildNavMesh { nodes ->
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
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 整页切换：立即清空焦点状态 + 停止滚动
                currentFocusState.value = null
                // 【智能优化】保留 currentNodes 作为新旧页面过渡垫片，绝不暴力清空！
                navEngine.reset()
                cancelScroll() // 切页时停止所有滚动状态
                
                // 激活新窗口动态稳定探测机制
                isWindowSettled = false
                stateChangedTimeMs = System.currentTimeMillis()
                
                // 首帧抢跑：30ms 后极速执行第一次基础建图（对秒开设备最友好）
                meshHandler.removeCallbacks(meshRunnable)
                meshHandler.postDelayed(meshRunnable, DEBOUNCE_STATE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
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
    private fun rebuildNavMesh(onDone: ((List<NavNode>) -> Unit)? = null) {
        refreshScrollConfig()
        val version = ++navMeshVersion  // 本次重建的版本号

        // 第一阶段：主线程采集节点
        val rawNodes: List<NavNode>
        try {
            rawNodes = collectNodesFromWindows()
        } catch (e: Exception) {
            Log.e("TouchEngine", "collectNodes error: ${e.message}")
            return
        }

        // 【智能稳定判定核心】比对可见节点的几何结构、个数及所在窗口ID
        val hasChanged = (rawNodes.size != currentNodes.size) || 
            rawNodes.zip(currentNodes).any { (new, old) -> 
                new.bounds != old.bounds || new.windowId != old.windowId 
            }
        
        if (!hasChanged) {
            // 节点没有发生改变，证明页面已完全稳定（Settled）！
            // 立即标记为稳定状态，日常 CONTENT_CHANGED 自动失效，CPU 进入极致休眠
            isWindowSettled = true
            onDone?.invoke(currentNodes)
            return
        }

        // 第二阶段：说明有交互新节点产生，启动后台线程构建图，回主线程应用
        serviceScope.launch(Dispatchers.Default) {
            // [FIX 8] 连接距离改为屏幕高度的 45%，适配不同手机分辨率。
            val maxDist = (screenHeight * 0.45f).coerceAtLeast(600f)
            NavMeshBuilder.build(rawNodes, maxConnectDist = maxDist)

            withContext(Dispatchers.Main.immediate) {
                // 如果在此期间有更新的重建请求，直接丢弃本次结果
                if (version != navMeshVersion) return@withContext

                currentNodes = rawNodes

                // [FIX 5] 用 20px 中心点容差匹配焦点节点
                val prevFocus = currentFocusState.value
                if (prevFocus != null) {
                    val sameNode = rawNodes.firstOrNull { n ->
                        hypot(
                            (n.centerX - prevFocus.centerX).toDouble(),
                            (n.centerY - prevFocus.centerY).toDouble()
                        ) < 20.0
                    }
                    currentFocusState.value = sameNode
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
            val root = rootInActiveWindow ?: return emptyList()
            // 过滤服务自身的悬浮球和高亮窗口
            if (root.packageName?.toString() == packageName) {
                root.recycle()
                return emptyList()
            }
            try { allNodes.addAll(parseAccessibilityTree(root)) }
            finally { root.recycle() }
        } else {
            val sortedWins = wins.sortedByDescending { it.layer }
            var foundAppWindow = false
            for (window in sortedWins) {
                // 跳过状态栏、导航栏等系统装饰窗口
                if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) continue
                // [FIX 4] 跳过输入法窗口，防止键盘节点混入主 NavMesh
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue

                val root = window.root ?: continue
                // 【核心修复】跳过当前服务自身的悬浮球和高亮框窗口，避免自干扰和死循环重建
                if (root.packageName?.toString() == packageName) {
                    root.recycle()
                    continue
                }
                try {
                    allNodes.addAll(parseAccessibilityTree(root))
                } finally {
                    root.recycle()
                }
                // 遇到 App 主窗口后停止，不再往下翻被完全遮挡的窗口
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    if (foundAppWindow) break
                    foundAppWindow = true
                }
            }
        }
        return allNodes
    }

    /**
     * 递归遍历无障碍树，采集可点击节点。
     *
     * [FIX 2] child.recycle() 改为放在 finally 块中，
     *          保证遍历过程中即使发生异常也不会泄漏 ANI 对象。
     */
    private fun parseAccessibilityTree(root: AccessibilityNodeInfo?): List<NavNode> {
        if (root == null) return emptyList()
        val result = mutableListOf<NavNode>()
        fun traverse(node: AccessibilityNodeInfo) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val area = rect.width() * rect.height()
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            if (node.isVisibleToUser
                && node.isClickable
                && area > 400
                && area < screenArea * 0.75f
                && rect.width() > 0 && rect.height() > 0
                // [FIX SCROLL v2] 用中心点判断是否在屏幕内：
                // 原来用 bounds 交叉判断，导致屏幕底部边缘少量伸出的节点还是会入表。
                // 改为中心点在屏幕内才收录，很少会有半身在屏幕外的节点满足条件
                && cy > 0f && cy < screenHeight.toFloat()
                && cx > 0f && cx < screenWidth.toFloat()
            ) {
                result.add(
                    NavNode(
                        bounds      = rect,
                        description = node.contentDescription?.toString()
                            ?: node.text?.toString(),
                        windowId    = node.windowId
                    )
                )
                // [FIX CHILD DUPE] 找到合法可点击节点后立即返回，不再递归子节点。
                // 问题：父节点（如QQ整行对话）和其内部小元素（群标签「狂风」、角标"11"）
                // 都可点击，两者都被收录，导致NavMesh里有大量微小子节点，
                // 产生"选了根本不存在的节点"的效果，且导航在小元素之间乱跳。
                // 修复：以父节点为准，不向下探查子树。
                // 当父节点不满足过滤条件时（如area太小）仍继续递归，确保合法子节点不被漏掉。
                return
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    traverse(child)
                } finally {
                    child.recycle() // [FIX 2] 改为 finally，异常时也能 recycle
                }
            }
        }
        traverse(root)
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
                        edgeTimerJob?.cancel()

                        // 【极致优化：触碰瞬间零卡顿响应】
                        // 如果当前窗口处于未稳定状态（如切页或滚动后的 450ms 内），手一碰球直接强刷重建以捕获新节点。
                        // 如果早已稳定（isWindowSettled = true），则直接 0ms 原地利用缓存的 currentNodes 起飞，
                        // 彻底免去主线程重复遍历无障碍树的几十毫秒卡顿，彻底解决“拉球阻力感/卡顿”的终极痛点！
                        if (!isWindowSettled) {
                            meshHandler.removeCallbacks(meshRunnable)
                            rebuildNavMesh()
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

                                        // 【优化：拖拽中途动态再锚定】若按下瞬间无可用节点（例如在刚切页，网格还在异步重建中）
                                        // 在拖动过程中，一旦 currentNodes 异步重建载入新节点，瞬间补获最近节点，使选择框立刻现形，绝不卡手！
                                        if (dragStartNode == null && currentFocusState.value == null) {
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
                                                if (escapeNode != null) {
                                                    currentFocusState.value = escapeNode
                                                    navEngine.reset()
                                                    edgeCount = 0; edgeDir = null
                                                    edgeHandled = true
                                                }
                                            }

                                            if (!edgeHandled && result.atEdge && ratio >= 0.28f
                                                && isRealEdge(result.node, result.intentAngle)
                                                && scrollState == ScrollState.IDLE) {
                                                val dir = dirFromAngle(result.intentAngle)
                                                handleEdge(dir, ratio) // [FIX 1] 无 scope 参数
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
                                            handleFlickEdge(dirFromAngle(ang), ratio)
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
        val root = rootInActiveWindow ?: return
        val native = findNativeNodeByBounds(root, target.bounds)
        if (native != null) {
            val clicked = native.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                // 直接点击失败，向上遍历父节点寻找真正响应点击的容器
                var parent = native.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        break
                    }
                    val grandParent = parent.parent
                    parent.recycle()
                    parent = grandParent
                }
            }
            native.recycle()
        }
        root.recycle()
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
