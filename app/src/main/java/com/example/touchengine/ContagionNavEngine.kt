package com.example.touchengine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * ContagionNavEngine v3.1 — 方案B：短暂确认后跳
 *
 * 交互模型：
 *  - 快速点拨（按住 < PRE_DELAY_MS 后松手）→ 跳一格，精确微操
 *  - 按住超过 PRE_DELAY_MS               → 首跳触发，继续按住则无极加速连跳
 *  - 拖住悬浮球转向（偏转 > DIR_RESET_DEG）→ 立即响应新方向，加速进度重置
 *  - 松手                                → 所有状态归零
 *
 * 调参建议：
 *  - 感觉确认太慢    → 降低 PRE_DELAY_MS（最低约 50ms）
 *  - 点拨误触发连跳  → 降低 PRE_DELAY_MS 或缩短 FLICK_WINDOW_MS
 *  - 加速太猛        → 增大 INTERVAL_SLOW_MS 或增大 ACCEL_DURATION_MS
 *  - 加速太慢        → 缩小 INTERVAL_FAST_MS 或缩短 ACCEL_DURATION_MS
 *  - 转向太灵敏      → 增大 DIR_RESET_DEG
 *  - 转向太迟钝      → 减小 DIR_RESET_DEG
 */

// 导航引擎返回结果
data class DragResult(
    val node: NavNode,      // 本帧应高亮的节点
    val atEdge: Boolean,    // 该方向是否真的没有邻居（用于触发自动滚动）
    val intentAngle: Double // 当前意图角度（度），用于判断滚动方向
)

class ContagionNavEngine {
    // ── 确认延迟 ──────────────────────────────────────────
    var PRE_DELAY_MS: Long = 80L
    // ── 加速系统 ──────────────────────────────────────────
    var ACCEL_DURATION_MS: Long = 800L
    var INTERVAL_SLOW_MS: Long  = 500L
    var INTERVAL_FAST_MS: Long  = 100L
    // ── 点拨判定 ──────────────────────────────────────────
    var FLICK_WINDOW_MS: Long = 300L
    // ── 摇杆死区 ──────────────────────────────────────────
    var DEAD_ZONE_RATIO: Float = 0.28f
    // ── 扇区角度 ──────────────────────────────────────────
    var CONE_HALF_ANGLE: Double = 45.0
    // ── 转向重置 ──────────────────────────────────────────
    var DIR_RESET_DEG: Double = 35.0
    // ── 内部状态 ──────────────────────────────────────────
    private var pressing       = false
    private var pressStartTime = 0L
    private var lockedAngle    = Double.NaN
    private var preConfirmed   = false
    private var firstJumped    = false
    private var accelStartTime = 0L
    private var lastJumpTime   = 0L

    // ─────────────────────────────────────────────────────

    fun onDrag(
        currentNode: NavNode,
        dragX: Float,
        dragY: Float,
        maxRadius: Float
    ): DragResult {
        val ratio = hypot(dragX.toDouble(), dragY.toDouble()).toFloat() / maxRadius

        if (ratio < DEAD_ZONE_RATIO) {
            resetState()
            return DragResult(currentNode, atEdge = false, intentAngle = 0.0)
        }

        val now   = System.currentTimeMillis()
        val angle = Math.toDegrees(atan2(dragY.toDouble(), dragX.toDouble()))

        // 方向突变检测
        if (!lockedAngle.isNaN()) {
            var diff = abs(angle - lockedAngle)
            if (diff > 180) diff = 360 - diff
            if (diff > DIR_RESET_DEG) {
                pressStartTime = now
                preConfirmed   = false
                firstJumped    = false
                accelStartTime = 0L
                lockedAngle    = angle
            }
        }

        if (!pressing) {
            pressing       = true
            pressStartTime = now
            lockedAngle    = angle
            preConfirmed   = false
            firstJumped    = false
            accelStartTime = 0L
        }

        val heldMs = now - pressStartTime

        if (!preConfirmed) {
            if (heldMs < PRE_DELAY_MS) return DragResult(currentNode, atEdge = false, intentAngle = angle)
            preConfirmed = true
        }

        if (!firstJumped) {
            firstJumped    = true
            accelStartTime = now
            lastJumpTime   = now
            val next = findBestNeighbor(currentNode, lockedAngle)
            return DragResult(
                node        = next ?: currentNode,
                atEdge      = next == null,
                intentAngle = lockedAngle
            )
        }

        val speedProgress = min(
            (now - accelStartTime).toDouble() / ACCEL_DURATION_MS, 1.0
        )
        val currentInterval = (INTERVAL_SLOW_MS *
                (INTERVAL_FAST_MS.toDouble() / INTERVAL_SLOW_MS).pow(speedProgress)
                ).toLong()

        return if (now - lastJumpTime >= currentInterval) {
            lastJumpTime = now
            val next = findBestNeighbor(currentNode, lockedAngle)
            DragResult(
                node        = next ?: currentNode,
                atEdge      = next == null,
                intentAngle = lockedAngle
            )
        } else {
            // 冷却中：检查该方向是否真的没有邻居
            val stillAtEdge = findBestNeighbor(currentNode, lockedAngle) == null
            DragResult(currentNode, atEdge = stillAtEdge, intentAngle = lockedAngle)
        }
    }

    fun onRelease(
        currentNode: NavNode,
        dragX: Float,
        dragY: Float,
        maxRadius: Float
    ): NavNode {
        val pressDuration = System.currentTimeMillis() - pressStartTime
        val ratio = hypot(dragX.toDouble(), dragY.toDouble()).toFloat() / maxRadius

        val isFlick = !preConfirmed
                && pressDuration <= FLICK_WINDOW_MS
                && ratio >= DEAD_ZONE_RATIO

        resetState()

        return if (isFlick) {
            val angle = Math.toDegrees(atan2(dragY.toDouble(), dragX.toDouble()))
            findBestNeighbor(currentNode, angle) ?: currentNode
        } else {
            currentNode
        }
    }

    fun reset() = resetState()

    private fun findBestNeighbor(currentNode: NavNode, intentAngle: Double): NavNode? {
        var bestNode: NavNode? = null
        var minAngleDiff = Double.MAX_VALUE

        for (neighbor in currentNode.neighbors) {
            val dx = neighbor.centerX - currentNode.centerX
            val dy = neighbor.centerY - currentNode.centerY
            val neighborAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))

            var angleDiff = abs(intentAngle - neighborAngle)
            if (angleDiff > 180) angleDiff = 360 - angleDiff

            if (angleDiff <= CONE_HALF_ANGLE && angleDiff < minAngleDiff) {
                minAngleDiff = angleDiff
                bestNode = neighbor
            }
        }

        return bestNode
    }

    private fun resetState() {
        pressing       = false
        pressStartTime = 0L
        lockedAngle    = Double.NaN
        preConfirmed   = false
        firstJumped    = false
        accelStartTime = 0L
        lastJumpTime   = 0L
    }
}

// ═══════════════════════════════════════════════════════════════
// NavMeshBuilder v3.1
//
// 改动说明：
//  旧版用"视线遮挡"过滤邻居，threshold太小会误切大量连接。
//  新版改为"每个方向只保留最近邻居"策略：
//   - 把360度分成8个扇区（每45度一个）
//   - 每个扇区内只保留距离最近的那个节点作为邻居
//   - 彻底解决稀疏布局下邻居数量不足的问题
//   - 同时天然避免了"跨越中间节点直连远端"的问题
//
// [FIX 5] 新增 windowId 同窗口过滤：
//   - 只在属于同一个窗口的节点之间建边
//   - 防止弹窗后面的 App 节点被误选（弹窗穿透）
//   - 防止摇杆在弹窗和底部 App 节点之间乱跳
// ═══════════════════════════════════════════════════════════════
object NavMeshBuilder {

    /**
     * 构建邻居图。
     *
     * 策略：8方向扇区，每个方向只保留最近的邻居节点。
     * 同窗口隔离：不同 windowId 的节点之间不建边。
     *
     * @param nodes          当前屏幕上所有可点击节点
     * @param maxConnectDist 视野上限（像素），超出范围的节点不考虑
     */
    fun build(nodes: List<NavNode>, maxConnectDist: Float = 600f) {
        // 检测是否有多个不同windowId（即有弹窗），有弹窗才隔离，没弹窗允许跨window建边
        val windowIds = nodes.map { it.windowId }.toSet()
        val strictWindowIsolation = windowIds.size > 1
        for (a in nodes) {
            a.neighbors.clear()

            // 8个扇区，每个扇区记录（最近距离，最近节点）
            // 扇区索引 = floor((angle + 180 + 22.5) / 45) % 8
            val sectorBest = Array<Pair<Float, NavNode>?>(8) { null }

            for (b in nodes) {
                if (a === b) continue
                // [FIX 5] 只在同一窗口内建边，防止跨窗口焦点穿透
                if (strictWindowIsolation && b.windowId != a.windowId) continue

                val dx   = b.centerX - a.centerX
                val dy   = b.centerY - a.centerY
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dist >= maxConnectDist) continue

                // 计算方向角（-180 ~ 180）→ 扇区索引（0 ~ 7）
                val angleDeg  = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                val sectorIdx = (((angleDeg + 180 + 22.5) / 45).toInt()) % 8

                val current = sectorBest[sectorIdx]
                if (current == null || dist < current.first) {
                    sectorBest[sectorIdx] = Pair(dist, b)
                }
            }

            // 把每个扇区的最近邻居加入列表
            for (best in sectorBest) {
                if (best != null) a.neighbors.add(best.second)
            }
        }
    }
}
