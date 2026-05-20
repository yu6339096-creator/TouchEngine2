package com.example.touchengine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

class TouchEngineService : AccessibilityService() {

    // ── Engine ────────────────────────────────────────────
    private val navEngine = ContagionNavEngine()

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
    // 从 ScrollConfig 读取配置
    private var scrollConfig = ScrollConfig()
    private var scrollState  = ScrollState.IDLE   // 当前滚动阶段
    private var edgeCount    = 0                  // 边缘帧计数
    private var edgeDir: ScrollDir? = null        // 当前边缘方向
    private var scrollHintJob: kotlinx.coroutines.Job? = null
    private var scrollBJob:    kotlinx.coroutines.Job? = null
    // 翻页前焦点位置，用于翻页后就近定位
    private var prevFocusCx  = 0f
    private var prevFocusCy  = 0f

    enum class ScrollState { IDLE, HINT, PLAN_B }
    enum class ScrollDir   { UP, DOWN, LEFT, RIGHT }

    // 提示气泡状态（传给 FocusHighlightUI 层显示）
    val scrollHintState  = mutableStateOf<ScrollHintData?>(null)

    data class ScrollHintData(
        val dir:      ScrollDir,
        val progress: Float   // 0.0 ~ 1.0 倒计时进度
    )

    // ── NavMesh 防抖 ──────────────────────────────────────
    private val meshHandler  = Handler(Looper.getMainLooper())
    private val meshRunnable = Runnable { rebuildNavMesh() }
    private val DEBOUNCE_MS  = 300L
    private var currentNodes: List<NavNode> = emptyList()
    private val screenArea = screenWidth * screenHeight

    // =====================================================
    // Service lifecycle
    // =====================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initFloatingUI()
    }

    override fun onInterrupt() {}

    // =====================================================
    // 滚动逻辑（方案A + 方案B）
    // =====================================================

    /** 从 SharedPreferences 刷新滚动配置（每次 NavMesh 重建时调用）*/
    private fun refreshScrollConfig() {
        scrollConfig = ScrollConfig.load(this)
    }

    /** 判断某角度方向是否真的没有邻居（30度窄扇区确认，防误判）*/
    private fun isRealEdge(node: NavNode, angleDeg: Double): Boolean {
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

    /** 查找当前焦点所在的可滚动容器，用于方案A */
    private fun findScrollableNode(dir: ScrollDir): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        try {
            val action = when (dir) {
                ScrollDir.DOWN, ScrollDir.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScrollDir.UP,  ScrollDir.LEFT   -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            // BFS 查找最近的可滚动容器
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isScrollable) {
                    val actions = node.actionList.map { it.id }
                    if (action in actions) {
                        return AccessibilityNodeInfo.obtain(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } finally {
            root.recycle()
        }
        return null
    }

    /** 执行方案A（ACTION_SCROLL），返回是否成功 */
    private fun tryPlanA(dir: ScrollDir): Boolean {
        val scrollNode = findScrollableNode(dir) ?: return false
        val action = when (dir) {
            ScrollDir.DOWN, ScrollDir.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDir.UP,  ScrollDir.LEFT   -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val success = scrollNode.performAction(action)
        scrollNode.recycle()
        return success
    }

    /** 执行方案B（dispatchGesture 模拟手势），持续调用 */
    private fun doPlanBGesture(dir: ScrollDir) {
        val sw = screenWidth.toFloat()
        val sh = screenHeight.toFloat()
        val dist = sh * scrollConfig.scrollSensitivity

        val (startX, startY, endX, endY) = when (dir) {
            ScrollDir.DOWN  -> arrayOf(sw/2, sh*0.65f, sw/2, sh*0.65f - dist)
            ScrollDir.UP    -> arrayOf(sw/2, sh*0.35f, sw/2, sh*0.35f + dist)
            ScrollDir.RIGHT -> arrayOf(sw*0.65f, sh/2, sw*0.65f - dist, sh/2)
            ScrollDir.LEFT  -> arrayOf(sw*0.35f, sh/2, sw*0.35f + dist, sh/2)
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
     * 处理边缘检测结果（在 onDrag 结果为 atEdge=true 时调用）
     * 对应演示模型里的 onMove 边缘检测分支
     */
    private fun handleEdge(
        scope: kotlinx.coroutines.CoroutineScope,
        dir: ScrollDir,
        ratio: Float
    ) {
        if (!scrollConfig.autoScrollEnabled) return
        if (ratio < 0.28f) return

        if (dir == edgeDir) {
            edgeCount++
        } else {
            edgeDir   = dir
            edgeCount = 1
            cancelHint()
        }

        if (edgeCount >= scrollConfig.edgeTriggerFrames) {
            edgeCount = 0
            prevFocusCx = currentFocusState.value?.centerX ?: 0f
            prevFocusCy = currentFocusState.value?.centerY ?: 0f

            // 尝试方案A
            if (tryPlanA(dir)) {
                scrollState = ScrollState.IDLE
                scrollHintState.value = null
                // 等 NavMesh 重建后就近定位焦点
                meshHandler.postDelayed({
                    afterScroll(prevFocusCx, prevFocusCy)
                }, 400L)
                return
            }

            // 方案A失败 → 显示提示，倒计时后执行方案B
            startHint(scope, dir)
        }
    }

    /**
     * 处理点拨到边缘（onRelease 时 atEdge=true 且推力足够）
     * 单次点拨只触发方案A，不触发方案B
     */
    private fun handleFlickEdge(dir: ScrollDir, ratio: Float) {
        if (!scrollConfig.autoScrollEnabled) return
        if (ratio < 0.5f) return  // 点拨翻页需要更高推力

        prevFocusCx = currentFocusState.value?.centerX ?: 0f
        prevFocusCy = currentFocusState.value?.centerY ?: 0f

        if (tryPlanA(dir)) {
            edgeCount = 0; edgeDir = null
            meshHandler.postDelayed({
                afterScroll(prevFocusCx, prevFocusCy)
            }, 400L)
        }
        // 方案A失败（无可滚动容器）：静默忽略，不触发方案B
    }

    /** 显示提示气泡并开始倒计时，倒计时结束后启动方案B */
    private fun startHint(scope: kotlinx.coroutines.CoroutineScope, dir: ScrollDir) {
        if (scrollState != ScrollState.IDLE) return
        scrollState = ScrollState.HINT
        scrollHintJob?.cancel()
        scrollHintJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (true) {
                val elapsed  = System.currentTimeMillis() - startMs
                val progress = (elapsed.toFloat() / scrollConfig.hintDurationMs).coerceAtMost(1f)
                scrollHintState.value = ScrollHintData(dir, progress)
                if (progress >= 1f) {
                    startPlanB(scope, dir)
                    return@launch
                }
                delay(16)
            }
        }
    }

    /** 启动方案B持续滑动 */
    private fun startPlanB(scope: kotlinx.coroutines.CoroutineScope, dir: ScrollDir) {
        scrollState = ScrollState.PLAN_B
        scrollBJob?.cancel()
        scrollBJob = scope.launch {
            while (scrollState == ScrollState.PLAN_B) {
                doPlanBGesture(dir)
                delay(scrollConfig.scrollGestureDurationMs + 50)
            }
        }
    }

    /** 取消提示气泡（用户改变方向或松手时）*/
    private fun cancelHint() {
        if (scrollState == ScrollState.HINT) {
            scrollHintJob?.cancel()
            scrollState = ScrollState.IDLE
            scrollHintState.value = null
        }
    }

    /** 方案B松手：触发惯性后停止 */
    private fun stopPlanB(scope: kotlinx.coroutines.CoroutineScope) {
        if (scrollState != ScrollState.PLAN_B) return
        scrollBJob?.cancel()
        scrollState = ScrollState.IDLE
        scrollHintState.value = null
        edgeCount = 0; edgeDir = null
        // 惯性：再执行一次手势后重建 NavMesh
        val dir = edgeDir
        scope.launch {
            delay(scrollConfig.scrollGestureDurationMs / 2)
            if (dir != null) doPlanBGesture(dir)
            delay(200)
            meshHandler.post { rebuildNavMesh() }
        }
    }

    /** 翻页完成后，重建 NavMesh 并就近定位焦点 */
    private fun afterScroll(prevCx: Float, prevCy: Float) {
        currentFocusState.value = null
        navEngine.reset()
        edgeCount = 0; edgeDir = null
        rebuildNavMesh()
        // NavMesh 重建是同步的，完成后找最近节点
        val nearest = currentNodes.minByOrNull { n ->
            hypot((n.centerX - prevCx).toDouble(), (n.centerY - prevCy).toDouble())
        }
        currentFocusState.value = nearest
    }

    override fun onDestroy() {
        super.onDestroy()
        meshHandler.removeCallbacks(meshRunnable)
        joystickView?.let { windowManager.removeView(it) }
        focusBoxView?.let { windowManager.removeView(it) }
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
        // 窗口尺寸固定为底座大小（64dp），让内部小球的 translationX 不会移动整个窗口
        // FLAG_LAYOUT_NO_LIMITS 允许窗口探出屏幕边缘，实现半球吸附效果
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
    // Accessibility
    // =====================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 整页切换：立即重建，清空焦点
                meshHandler.removeCallbacks(meshRunnable)
                currentFocusState.value = null
                navEngine.reset()
                rebuildNavMesh()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 内容变化：防抖后重建
                meshHandler.removeCallbacks(meshRunnable)
                meshHandler.postDelayed(meshRunnable, DEBOUNCE_MS)
            }
        }
    }

    private fun rebuildNavMesh() {
        refreshScrollConfig() // 每次重建时读取最新配置
        try {
            val allNodes = mutableListOf<NavNode>()

            // 遍历所有可见窗口（输入法、弹窗、App 都能抓到）
            // 按 layer 从高到低排序，层级高的窗口（输入法/弹窗）优先加入
            val wins = windows
            if (wins.isNullOrEmpty()) {
                // 兜底：windows 拿不到时退回单窗口模式
                val root = rootInActiveWindow ?: return
                try { allNodes.addAll(parseAccessibilityTree(root)) }
                finally { root.recycle() }
            } else {
                val sortedWins = wins.sortedByDescending { it.layer }
                var foundAppWindow = false

                for (window in sortedWins) {
                    // 跳过状态栏、导航栏等系统装饰窗口
                    if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) continue

                    val root = window.root ?: continue
                    try {
                        val nodes = parseAccessibilityTree(root)
                        allNodes.addAll(nodes)
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

            NavMeshBuilder.build(allNodes, maxConnectDist = 600f)
            currentNodes = allNodes

            // 焦点节点消失则清空
            if (currentFocusState.value != null &&
                allNodes.none { it.bounds == currentFocusState.value?.bounds }
            ) {
                currentFocusState.value = null
            }

        } catch (e: Exception) {
            Log.e("TouchEngine", "rebuildNavMesh error: ${e.message}")
        }
    }

    // =====================================================
    // Parse Accessibility Tree
    // =====================================================

    private fun parseAccessibilityTree(root: AccessibilityNodeInfo?): List<NavNode> {
        if (root == null) return emptyList()
        val result = mutableListOf<NavNode>()

        fun traverse(node: AccessibilityNodeInfo) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val area = rect.width() * rect.height()

            if (node.isVisibleToUser
                && node.isClickable
                && area > 400
                && area < screenArea * 0.75f
                && rect.width() > 0 && rect.height() > 0
            ) {
                result.add(
                    NavNode(
                        bounds      = rect,
                        description = node.contentDescription?.toString()
                            ?: node.text?.toString(),
                        windowId    = node.windowId
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child)
                child.recycle()
            }
        }

        traverse(root)
        return result
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
        val scope      = rememberCoroutineScope()
        val density    = LocalDensity.current
        val view       = LocalView.current
        val viewConfig = LocalViewConfiguration.current

        // ── 尺寸常量 ──────────────────────────────────────
        val baseSizeDp     = 64.dp
        val ballSizeDp     = 26.dp
        val baseSizePx     = with(density) { baseSizeDp.toPx() }
        // 小球最大偏移 = 底座半径 - 小球半径 - 4dp 余量
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
        // 底座按压时轻微缩小
        val baseScale by animateFloatAsState(
            targetValue   = if (isPressed) 0.90f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label         = "baseScale"
        )
        // 边缘吸附时整体透明度
        val baseAlpha by animateFloatAsState(
            targetValue   = if (isEdgeHidden) 0.45f else 1f,
            animationSpec = tween(300),
            label         = "baseAlpha"
        )
        // 半球效果：通过把 windowX 设置到屏幕边界外实现
        // FLAG_LAYOUT_NO_LIMITS 允许窗口超出屏幕，系统边缘自然裁剪产生半球效果
        // edgeTranslationX 保留为0，不再用 translationX 实现半球
        val edgeTranslationX = 0f
        // 确认待点击时：呼吸灯（透明度 + 缩放循环）
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

        // 小球松手后弹回中心
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

        // 同步窗口位置到 WindowManager
        LaunchedEffect(windowX, windowY) {
            joystickParams.x = windowX.roundToInt()
            joystickParams.y = windowY.roundToInt()
            joystickView?.let { windowManager.updateViewLayout(it, joystickParams) }
        }

        // ── 边缘吸附逻辑 ──────────────────────────────────
        // 松手后若靠近边缘，1.2秒后自动吸附到边缘呈半球状
        fun checkAndStartEdgeTimer() {
            edgeTimerJob?.cancel()
            // 只有非常贴近边缘（30px以内）才触发吸附，防止正常使用时误触
            val margin    = 30f
            val nearLeft  = windowX < margin
            val nearRight = windowX > screenWidth - baseSizePx - margin
            if (nearLeft || nearRight) {
                edgeTimerJob = scope.launch {
                    delay(1200)
                    isEdgeHidden = true
                    // 半球效果：窗口X超出屏幕边界，超出约半个底座宽度
                    // FLAG_LAYOUT_NO_LIMITS 下系统屏幕边缘会自然裁剪，产生半球形状
                    windowX = if (nearLeft)
                        -(baseSizePx * 0.5f)         // 左边：探出半个底座
                    else
                        screenWidth - baseSizePx * 0.5f  // 右边：探出半个底座
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
                            // 执行点击，立即清空所有状态
                            // 点击后页面会跳转，焦点框必须立即消失
                            // 下次用户拖摇杆时从悬浮球位置重新就近唤醒
                            performClickOnTarget(currentFocusState.value)
                            isConfirmPending = false
                            currentFocusState.value = null
                            confirmTimerJob?.cancel()
                            navEngine.reset()
                            // 主动触发 NavMesh 重建，清空旧页面节点
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
        // 结构：外层 Box = 底座圆盘（固定不动，带毛玻璃质感）
        //        内层 Box = 操控小球（在底座内圆形活动）
        // ══════════════════════════════════════════════════
        Box(
            modifier = Modifier
                .size(baseSizeDp)
                .graphicsLayer {
                    scaleX       = baseScale
                    scaleY       = baseScale
                    alpha        = baseAlpha
                    translationX = edgeTranslationX
                }
                // 底座背景：深色半透明圆形，有微妙内发光边框
                .background(
                    color = Color(0xFF1C1C1E).copy(alpha = 0.82f),
                    shape = CircleShape
                )
                .border(
                    width = 1.5.dp,
                    color = when {
                        isConfirmPending -> Color(0xFF34C759).copy(alpha = 0.9f)  // 确认中：绿色
                        isDraggingWindow -> Color(0xFFFFCC00).copy(alpha = 0.8f)  // 拖窗口：黄色
                        else             -> Color(0xFFFFFFFF).copy(alpha = 0.18f) // 默认：微白边
                    },
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        edgeTimerJob?.cancel()

                        // ── 唤醒边缘吸附状态 ──────────────────────────
                        // 半球状态下任何触摸只触发弹出，不做任何其他操作
                        if (isEdgeHidden) {
                            isEdgeHidden = false
                            windowX = if (windowX < screenWidth / 2)
                                with(density) { 16.dp.toPx() }
                            else
                                screenWidth - baseSizePx - with(density) { 16.dp.toPx() }
                            // 等待松手，期间不响应任何手势
                            do { awaitPointerEvent() }
                            while (awaitPointerEvent().changes.any { it.pressed })
                            isPressed = false
                            // 弹出后不重新触发边缘计时，用户需主动拖到边缘才会再次吸附
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
                                        // 用逐帧增量，和摇杆坐标完全隔离
                                        windowX = (windowX + change.positionChange().x)
                                            .coerceIn(0f, screenWidth - baseSizePx)
                                        windowY = (windowY + change.positionChange().y)
                                            .coerceIn(0f, (screenHeight - baseSizePx).toFloat())
                                        ballOffsetX = 0f
                                        ballOffsetY = 0f

                                    } else {
                                        // ── 摇杆模式 ──────────────────────────
                                        // 圆形限制：超出最大偏移半径则等比缩到圆周上
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

                                        // ── 调用导航引擎 ──────────────────────
                                        val startNode = currentFocusState.value
                                            ?: currentNodes.minByOrNull { n ->
                                                hypot(
                                                    (n.centerX - (windowX + baseSizePx / 2)).toDouble(),
                                                    (n.centerY - (windowY + baseSizePx / 2)).toDouble()
                                                )
                                            }

                                        if (startNode != null) {
                                            val result = navEngine.onDrag(
                                                currentNode = startNode,
                                                dragX       = clampedX,
                                                dragY       = clampedY,
                                                maxRadius   = MAX_BALL_OFFSET
                                            )
                                            if (result.node !== currentFocusState.value) {
                                                currentFocusState.value = result.node
                                                // 焦点跳了，重置边缘计数
                                                edgeCount = 0; edgeDir = null
                                                cancelHint()
                                            }

                                            // 边缘检测
                                            val ratio = hypot(clampedX.toDouble(), clampedY.toDouble()).toFloat() / MAX_BALL_OFFSET
                                            if (result.atEdge && ratio >= 0.28f
                                                && isRealEdge(result.node, result.intentAngle)
                                                && scrollState == ScrollState.IDLE) {
                                                val dir = dirFromAngle(result.intentAngle)
                                                handleEdge(scope, dir, ratio)
                                            } else if (!result.atEdge) {
                                                if (result.node !== startNode) {
                                                    edgeCount = 0; edgeDir = null
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

                            if (dragStarted && !longPressTriggered) {
                                // ── 摇杆松手 ──────────────────────────────

                                // 方案B松手：触发惯性后停止
                                if (scrollState == ScrollState.PLAN_B) {
                                    stopPlanB(scope)
                                } else {
                                    cancelHint()
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
                                        // 正常点拨：跳到新节点
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
                            // 只有长按拖动窗口松手后才触发边缘吸附检测
                            // 摇杆操作和单击不触发，防止误吸附
                            if (longPressTriggered) {
                                checkAndStartEdgeTimer()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // ── 底座内部纹理：细微放射渐变增加立体感 ────────
            Box(
                modifier = Modifier
                    .size(baseSizeDp - 8.dp)
                    .background(
                        color = Color(0xFF2C2C2E).copy(alpha = 0.6f),
                        shape = CircleShape
                    )
            )

            // ── 操控小球 ─────────────────────────────────────
            // graphicsLayer 只作用于小球，底座完全不动
            // 确认待点击时：呼吸灯效果（透明度 + 轻微缩放循环）
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
                            isConfirmPending -> Color(0xFF34C759)  // 确认中：绿色呼吸
                            isDraggingWindow -> Color(0xFFFFCC00)  // 拖窗口：黄色
                            else             -> Color(0xFFFFFFFF)  // 默认：白色
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
        val accentColor = Color(0xFF00E5FF)

        // ── 提示气泡（方案B倒计时）────────────────────────
        AnimatedVisibility(
            visible = hintData != null,
            enter   = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically { it/2 },
            exit    = fadeOut(tween(200)) + androidx.compose.animation.slideOutVertically { it/2 }
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
                    val arrowText = when(hint.dir) {
                        ScrollDir.DOWN -> "↓"; ScrollDir.UP -> "↑"
                        ScrollDir.RIGHT -> "→"; ScrollDir.LEFT -> "←"
                    }
                    Text(arrowText, color = Color.White, fontSize = 20.sp)
                    Spacer(Modifier.height(4.dp))
                    val dirText = when(hint.dir) {
                        ScrollDir.DOWN -> "向下"; ScrollDir.UP -> "向上"
                        ScrollDir.RIGHT -> "向右"; ScrollDir.LEFT -> "向左"
                    }
                    Text("继续拉动将${dirText}滑动屏幕",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    // 倒计时进度条
                    Box(
                        modifier = Modifier
                            .width(160.dp).height(3.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(hint.progress)
                                .background(
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        listOf(Color(0xFF00E5FF), Color(0xFFFFCC00))
                                    ),
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

        // ── Bug修复：用 AnimatedVisibility 包裹，点击后有淡出动效再彻底消失 ──
        // 使用 remember 缓存最后一个非空的节点，防止消失动画期间内容变成 null 导致崩溃
        val lastNode = remember(targetNode) { targetNode } ?: return

        AnimatedVisibility(
            visible       = targetNode != null,
            enter         = fadeIn(tween(120)),
            exit          = fadeOut(tween(200))
        ) {
            val node = lastNode

            val targetLeft   = with(density) { node.bounds.left.toDp() }
            val targetTop    = with(density) { node.bounds.top.toDp() }
            val targetWidth  = with(density) { node.bounds.width().toDp() }
            val targetHeight = with(density) { node.bounds.height().toDp() }

            // 位置跟随动画
            val animSpec = spring<Dp>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness    = Spring.StiffnessMedium
            )
            val animLeft   by animateDpAsState(targetLeft,   animSpec, label = "l")
            val animTop    by animateDpAsState(targetTop,    animSpec, label = "t")
            val animWidth  by animateDpAsState(targetWidth,  animSpec, label = "w")
            val animHeight by animateDpAsState(targetHeight, animSpec, label = "h")

            val infiniteTransition = rememberInfiniteTransition(label = "lockOn")

            // ── 呼吸灯：透明度 + 线宽缓慢循环 ──────────────
            val breathAlpha by infiniteTransition.animateFloat(
                initialValue  = 1f,
                targetValue   = 0.25f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "breathAlpha"
            )
            val breathStroke by infiniteTransition.animateFloat(
                initialValue  = 3f,
                targetValue   = 1.5f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "breathStroke"
            )
            val bgAlpha by infiniteTransition.animateFloat(
                initialValue  = 0.12f,
                targetValue   = 0.03f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bgAlpha"
            )

            // ── Bug修复：流动旋转 ─────────────────────────
            // 原版用 rotationZ 让整个Canvas绕中心圆周旋转，视觉上是圆形转动。
            // 新版：用 progress(0→1) 驱动四个角的L形括号沿矩形路径平移，
            // 形成"括号在框体上流动"的效果。
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
                    // 背景半透明填充
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                accentColor.copy(alpha = bgAlpha),
                                RoundedCornerShape(6.dp)
                            )
                    )

                    // 四角L形流动瞄准框
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w      = size.width
                        val h      = size.height
                        val stroke = breathStroke * density.density
                        val color  = accentColor.copy(alpha = breathAlpha)
                        val armX   = w * 0.20f
                        val armY   = h * 0.20f

                        // 矩形周长 = 2*(w+h)，progress 驱动偏移距离
                        val perimeter = 2f * (w + h)
                        val offset    = flowProgress * perimeter

                        val paint = androidx.compose.ui.graphics.Paint().apply {
                            this.color       = color
                            this.strokeWidth = stroke
                            this.style       = androidx.compose.ui.graphics.PaintingStyle.Stroke
                            strokeCap        = androidx.compose.ui.graphics.StrokeCap.Round
                        }

                        // 四个角的L形，每个角偏移 perimeter/4 的倍数，
                        // 这样四个角均匀分布在矩形周长上并同步流动
                        drawContext.canvas.apply {
                            for (i in 0..3) {
                                val cornerOffset = (offset + i * perimeter / 4f) % perimeter

                                // 把 cornerOffset 映射到矩形上的坐标点（顺时针）
                                // 0→w: 上边  w→w+h: 右边  w+h→2w+h: 下边  2w+h→2w+2h: 左边
                                val cx: Float
                                val cy: Float
                                when {
                                    cornerOffset < w -> { cx = cornerOffset; cy = 0f }
                                    cornerOffset < w + h -> { cx = w; cy = cornerOffset - w }
                                    cornerOffset < 2*w + h -> { cx = w - (cornerOffset - w - h); cy = h }
                                    else -> { cx = 0f; cy = h - (cornerOffset - 2*w - h) }
                                }

                                // 以 (cx, cy) 为顶点，沿矩形路径画L形括号
                                // 根据顶点在矩形哪条边上，决定L形的方向
                                val isTop    = cy == 0f
                                val isRight  = cx == w
                                val isBottom = cy == h
                                val isLeft   = cx == 0f

                                when {
                                    isTop -> {
                                        drawLine(Offset(cx - armX * 0.5f, cy), Offset(cx + armX * 0.5f, cy), paint)
                                    }
                                    isRight -> {
                                        drawLine(Offset(cx, cy - armY * 0.5f), Offset(cx, cy + armY * 0.5f), paint)
                                    }
                                    isBottom -> {
                                        drawLine(Offset(cx - armX * 0.5f, cy), Offset(cx + armX * 0.5f, cy), paint)
                                    }
                                    isLeft -> {
                                        drawLine(Offset(cx, cy - armY * 0.5f), Offset(cx, cy + armY * 0.5f), paint)
                                    }
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

    private fun performClickOnTarget(target: NavNode?) {
        if (target == null) return
        val root = rootInActiveWindow ?: return
        val native = findNativeNodeByBounds(root, target.bounds)
        native?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        native?.recycle()
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

        // 修改这里：使用 view.xxx 的形式调用
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




