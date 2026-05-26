package com.example.touchengine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeInteractionRulesTest {
    @Test
    fun heldDirectionContinuesOnlyInsideDeadZoneAndAngleLimit() {
        assertTrue(EdgeInteractionRules.isHeldInDirection(90.0, 0f, 80f, 100f))
        assertFalse(EdgeInteractionRules.isHeldInDirection(90.0, 80f, 0f, 100f))
        assertFalse(EdgeInteractionRules.isHeldInDirection(90.0, 0f, 10f, 100f))
    }

    @Test
    fun genericHorizontalTargetAcceptsLocalWideModulesAndHomeButNotLists() {
        assertTrue(EdgeInteractionRules.acceptsGenericHorizontalTarget(900, 300, 2000, false, false))
        assertTrue(EdgeInteractionRules.acceptsGenericHorizontalTarget(900, 1600, 2000, false, true))
        assertFalse(EdgeInteractionRules.acceptsGenericHorizontalTarget(900, 1600, 2000, false, false))
        assertFalse(EdgeInteractionRules.acceptsGenericHorizontalTarget(900, 300, 2000, true, false))
    }

    @Test
    fun taobaoBandsIgnoreHeaderAndBottomTabsButSplitContent() {
        assertTrue(
            EdgeInteractionRules.taobaoContentBand(300f, 2000) ==
                EdgeInteractionRules.TaobaoContentBand.UPPER
        )
        assertTrue(
            EdgeInteractionRules.taobaoContentBand(900f, 2000) ==
                EdgeInteractionRules.TaobaoContentBand.MIDDLE
        )
        assertTrue(
            EdgeInteractionRules.taobaoContentBand(1500f, 2000) ==
                EdgeInteractionRules.TaobaoContentBand.LOWER
        )
        assertTrue(EdgeInteractionRules.taobaoContentBand(100f, 2000) == null)
        assertTrue(EdgeInteractionRules.taobaoContentBand(1900f, 2000) == null)
    }

    @Test
    fun taobaoFallbackOnlyBackfillsAContentBandWithARealHole() {
        assertTrue(EdgeInteractionRules.shouldBackfillTaobaoBand(0, 4))
        assertTrue(EdgeInteractionRules.shouldBackfillTaobaoBand(1, 4))
        assertFalse(EdgeInteractionRules.shouldBackfillTaobaoBand(2, 4))
        assertFalse(EdgeInteractionRules.shouldBackfillTaobaoBand(0, 0))
    }

    @Test
    fun douyinPackageAndSearchCandidatesRequireContentAboveKeyboard() {
        assertTrue(EdgeInteractionRules.isDouyinPackage("com.ss.android.ugc.aweme"))
        assertFalse(EdgeInteractionRules.isDouyinPackage("com.taobao.taobao"))
        assertTrue(EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(260, 72, 720f, 2000, 1200))
        assertFalse(EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(260, 72, 1350f, 2000, 1200))
        assertFalse(EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(260, 72, 720f, 2000, null))
    }

    @Test
    fun complexContentUsesTighterCollectionBudgets() {
        assertTrue(EdgeInteractionRules.traversalBudget(true) < EdgeInteractionRules.traversalBudget(false))
        assertTrue(EdgeInteractionRules.nodeLimit(true) < EdgeInteractionRules.nodeLimit(false))
    }
}
