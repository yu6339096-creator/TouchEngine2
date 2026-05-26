package com.example.touchengine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

internal object EdgeInteractionRules {
    enum class TaobaoContentBand { UPPER, MIDDLE, LOWER }

    fun isDouyinPackage(packageName: String?): Boolean {
        return packageName?.contains("aweme", ignoreCase = true) == true ||
            packageName?.contains("douyin", ignoreCase = true) == true
    }

    fun isHeldInDirection(
        expectedAngle: Double,
        dragX: Float,
        dragY: Float,
        maxRadius: Float,
        deadZoneRatio: Float = 0.28f,
        toleranceDegrees: Double = 45.0
    ): Boolean {
        if (hypot(dragX.toDouble(), dragY.toDouble()) / maxRadius < deadZoneRatio) return false
        val angle = Math.toDegrees(atan2(dragY.toDouble(), dragX.toDouble()))
        var difference = abs(angle - expectedAngle)
        if (difference > 180.0) difference = 360.0 - difference
        return difference <= toleranceDegrees
    }

    fun acceptsGenericHorizontalTarget(
        width: Int,
        height: Int,
        screenHeight: Int,
        isKnownVerticalContainer: Boolean,
        isDefaultHome: Boolean
    ): Boolean {
        if (width <= 0 || height <= 0 || isKnownVerticalContainer) return false
        if (isDefaultHome) return true
        return width > height * 1.2f && height <= screenHeight * 0.55f
    }

    fun taobaoContentBand(centerY: Float, screenHeight: Int): TaobaoContentBand? {
        if (screenHeight <= 0) return null
        return when (centerY / screenHeight.toFloat()) {
            in 0.12f..<0.36f -> TaobaoContentBand.UPPER
            in 0.36f..<0.62f -> TaobaoContentBand.MIDDLE
            in 0.62f..<0.88f -> TaobaoContentBand.LOWER
            else -> null
        }
    }

    fun shouldBackfillTaobaoBand(
        regularNodeCount: Int,
        semanticCandidateCount: Int,
        minimumCoverage: Int = 2
    ): Boolean {
        return semanticCandidateCount > 0 && regularNodeCount < minimumCoverage
    }

    fun acceptsDouyinSearchSemanticTarget(
        width: Int,
        height: Int,
        centerY: Float,
        screenHeight: Int,
        keyboardTop: Int?
    ): Boolean {
        if (keyboardTop == null || width < 44 || height < 20 || screenHeight <= 0) return false
        val maxBottom = keyboardTop - 12
        return centerY > screenHeight * 0.10f &&
            centerY < maxBottom &&
            width * height < screenHeight * screenHeight * 0.12f
    }

    fun traversalBudget(isComplexContent: Boolean): Int {
        return if (isComplexContent) 1800 else 3600
    }

    fun nodeLimit(isComplexContent: Boolean): Int {
        return if (isComplexContent) 96 else 180
    }
}
