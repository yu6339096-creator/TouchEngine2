package com.example.touchengine

import android.graphics.Rect

/**
 * 导航节点
 *
 * 字段说明：
 *  - bounds      原始屏幕坐标矩形，用于焦点框绘制和点击执行
 *  - centerX/Y   预计算的中心点，供 com.example.touchengine.com.example.touchengine.ContagionNavEngine 和 com.example.touchengine.com.example.touchengine.NavMeshBuilder 使用
 *  - description 节点文字描述，调试用
 *  - windowId    所属窗口 ID，用于 Z 轴层级隔离（弹窗/抽屉防穿透）
 *  - neighbors   邻居节点列表，由 com.example.touchengine.com.example.touchengine.NavMeshBuilder.build() 填充
 */
data class NavNode(
    val bounds: Rect,
    val description: String?,
    val windowId: Int = 0,
    // 预计算中心点，避免每帧重复计算
    val centerX: Float = bounds.exactCenterX(),
    val centerY: Float = bounds.exactCenterY()
) {
    val neighbors = mutableListOf<NavNode>()
}