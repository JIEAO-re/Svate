package com.immersive.ui.agent

/**
 * Local rule-based fallback engine.
 *
 * Cloud planner/reviewer is the primary path. When cloud is unavailable,
 * this component returns a conservative action from local UI semantics only.
 */
object LocalGeminiDecisionEngine {

    fun decideNextAction(
        ctx: AgentContext,
        frames: List<CapturedFrame>,
        uiNodes: List<UiNode>,
        foregroundPackage: String?,
        observationReason: ObservationReason,
    ): AgentAction? {
        if (frames.isEmpty()) return null

        val pruned = UiNodePruner.prune(uiNodes, maxNodes = 60).nodes
        if (pruned.isEmpty()) {
            return waitAction(
                targetDesc = "wait_for_ui",
                reason = "local_fallback:no_nodes:$observationReason",
            )
        }

        if (ctx.taskSpec.mode == TaskMode.SEARCH && ctx.taskSpec.searchQuery.isNotBlank()) {
            val hasTyped = ctx.history.any { it.action.intent == ActionIntent.TYPE && it.success }
            val hasSubmitted = ctx.history.any { it.action.intent == ActionIntent.SUBMIT_INPUT && it.success }
            val editable = pruned.firstOrNull { it.isEditable }

            if (!hasTyped && editable != null) {
                return AgentAction(
                    intent = ActionIntent.TYPE,
                    targetDesc = "local_search_type",
                    targetBbox = null,
                    targetSomId = 1,
                    selector = selectorFrom(editable, boundsHint = null),
                    inputText = ctx.taskSpec.searchQuery,
                    packageName = foregroundPackage,
                    intentSpec = null,
                    riskLevel = RiskLevel.SAFE,
                    elderlyNarration = "Typing the search query.",
                    reasoning = "local_fallback:search_type",
                )
            }

            if (hasTyped && !hasSubmitted) {
                return AgentAction(
                    intent = ActionIntent.SUBMIT_INPUT,
                    targetDesc = "local_search_submit",
                    targetBbox = null,
                    packageName = foregroundPackage,
                    elderlyNarration = "Submitting search now.",
                    reasoning = "local_fallback:search_submit",
                )
            }
        }

        val clickable = pruned.firstOrNull { it.isClickable || it.isEditable }
        if (clickable != null) {
            return AgentAction(
                intent = ActionIntent.CLICK,
                targetDesc = "local_click_${clickable.className.substringAfterLast('.')}",
                targetBbox = null,
                targetSomId = 1,
                selector = selectorFrom(clickable, boundsHint = null),
                packageName = foregroundPackage,
                riskLevel = RiskLevel.SAFE,
                elderlyNarration = "Trying a safe tap on the highlighted control.",
                reasoning = "local_fallback:semantic_click",
            )
        }

        return waitAction(
            targetDesc = "wait_for_next_frame",
            reason = "local_fallback:no_clickable:$observationReason",
        )
    }

    private fun selectorFrom(node: UiNode, boundsHint: IntArray?): ActionSelector {
        return ActionSelector(
            packageName = node.packageName,
            resourceId = node.viewIdResourceName,
            text = node.text,
            contentDesc = node.contentDesc,
            className = node.className,
            boundsHint = boundsHint,
        )
    }

    private fun waitAction(targetDesc: String, reason: String): AgentAction {
        return AgentAction(
            intent = ActionIntent.WAIT,
            targetDesc = targetDesc,
            targetBbox = null,
            riskLevel = RiskLevel.SAFE,
            elderlyNarration = "Waiting for the screen to stabilize.",
            reasoning = reason,
        )
    }
}
