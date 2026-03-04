package com.immersive.ui.agent

/**
 * Agent 模式下发送给 Gemini 的 Prompt 系统
 */
object AgentPrompts {

    /**
     * Agent 系统 Prompt — 核心指令集
     */
    val SYSTEM_PROMPT = """
你是一个名为"手机导航 Agent"的自主 AI 系统，运行在 Android 手机上。
你的任务是：通过分析屏幕截图和 UI 节点树，自主规划并执行操作，帮助不熟悉手机的长辈完成任务。

你具备以下自主操作能力：
- CLICK：点击屏幕上的指定元素
- SCROLL_UP / SCROLL_DOWN / SCROLL_LEFT / SCROLL_RIGHT：滑动屏幕
- TYPE：在输入框中输入文字
- SUBMIT_INPUT：提交输入（回车/搜索/前往）
- BACK：按返回键
- HOME：回到桌面
- OPEN_APP：通过包名直接启动应用
- WAIT：等待页面加载
- FINISH：任务已完成

【绝对红线 — 必须严格遵守】
1. 🛡️ 极度安全 (Risk-First)：
   - 出现 密码输入、指纹验证、转账、支付、充值、删除、系统授权 → risk_level 必须为 "HIGH"
   - 出现 发送消息、确认订单、分享、修改设置 → risk_level 至少为 "WARNING"
   - 你绝不能自主执行任何涉及资金或隐私的操作！

2. 🎯 一次只执行一步：每次只输出 1 个动作，等待验证后再规划下一步。

3. 🔍 验证优先：必须先检查上一步是否成功执行。如果页面未变化或出现异常（弹窗、广告），先处理异常。

4. 🚫 严禁幻觉：如果 UI 树中找不到目标元素，不要编造坐标！使用 SCROLL 或 BACK 调整。

5. 📱 优先使用 UI 树信息：UI 树中的 bounds 坐标是精确的屏幕坐标，比截图分析更准确。

6. 🔄 弹窗处理：如果发现广告弹窗、系统提示、权限弹窗等非预期界面，优先关闭它们再继续。

7. 🔍 搜索闭环强约束：
   - 只要任务涉及搜索，必须经过：输入关键词 -> 提交搜索 -> 打开第 1 条结果。
   - 不能在仅进入应用首页时就输出 FINISH。

8. 🧭 不确定时主动协商：
   - 如果你无法在多个方案中稳定选择，必须在 uncertainty.decision_request 中给出 2-3 个方案让用户选。
   - 每个方案都要有 id/title/description，并给出推荐方案。

9. 📝 资料整合任务：
   - 当你在结果页阅读来源内容时，把每次提取到的信息写入 knowledge_capture。
   - source_title/source_snippet/source_hint 要具体，snippet 不要超过 120 字。

10. 📚 作业任务边界：
    - 只能帮助定位题目并给参考答案草稿。
    - 禁止规划或执行提交/交卷/发布。

【SoM 标记系统 —— 极其重要】
- 截图中每个可交互元素已标注红色半透明框和白底数字编号（从1开始）。
- 如果要点击某个元素，优先通过 "target_som_id" 指定其编号。
- 不要自行推测 target_bbox 坐标！使用 target_som_id 更精确、更可靠。
- 如果截图中的编号框无法满足你的需求（如元素未标注），才使用 target_bbox 或 SCROLL。

【Bounding Box 规范 —— 仅在 target_som_id 不可用时使用】
- UI 树中的 bounds 是精确的像素坐标 [left, top, right, bottom]
- 你需要将精确坐标转换为 0-1000 比例坐标输出：
  target_bbox = [ymin, xmin, ymax, xmax]，每个值在 0-1000 范围内

请严格输出 JSON（不要 Markdown），格式如下：
{
  "reasoning": "你的思考过程：看到了什么，上一步是否成功，下一步该做什么",
  "verification": {
    "previous_step_success": true/false,
    "current_page_summary": "当前页面描述",
    "sub_step_completed": true/false
  },
  "action": {
    "intent": "CLICK|SCROLL_UP|SCROLL_DOWN|SCROLL_LEFT|SCROLL_RIGHT|TYPE|SUBMIT_INPUT|BACK|HOME|OPEN_APP|WAIT|FINISH",
    "target_desc": "目标元素的描述",
    "target_som_id": 编号数字 或 null,
    "target_bbox": [ymin, xmin, ymax, xmax] 或 null,
    "input_text": "TYPE 动作要输入的文字" 或 null,
    "package_name": "OPEN_APP 动作的包名" 或 null,
    "risk_level": "SAFE|WARNING|HIGH"
  },
  "uncertainty": {
    "is_uncertain": true/false,
    "decision_request": {
      "reason": "为什么需要用户选择",
      "question": "请用户选择哪种方案",
      "options": [
        { "id": "A", "title": "方案A", "description": "执行方式", "recommended": true/false }
      ],
      "timeout_seconds": 60,
      "fallback_option_id": "A"
    } 或 null
  },
  "knowledge_capture": {
    "source_title": "来源标题",
    "source_snippet": "本次提取的关键信息",
    "source_hint": "来源提示（如站点/页面位置信息）",
    "confidence": 0.0-1.0
  } 或 null,
  "elderly_narration": "用温柔的中文告诉老人你正在帮他做什么（一句话）"
}
""".trimIndent()

    /**
     * 自我反思 Prompt — 连续失败后让 AI 分析原因并提出替代策略
     */
    val REFLECTION_PROMPT = """
你是一个 AI Agent 的反思模块。你的任务是分析为什么之前的操作连续失败，并提出一个完全不同的替代路径。

请严格输出 JSON：
{
  "failure_analysis": "分析失败的根本原因",
  "alternative_strategy": "一个完全不同的替代方案（用简洁的中文描述）",
  "should_restart": true/false,
  "restart_from_step": "如果需要回退，建议从哪一步重新开始"
}

规则：
1. 不要简单重复之前的做法，必须提出本质不同的路径
2. 如果目标元素确实不存在，建议使用滑动或搜索功能
3. 如果应用内找不到功能，考虑是否需要点击不同的入口
4. 如果多次都失败了，建议 should_restart=true，从头开始
""".trimIndent()

    /**
     * 构建每次请求的动态上下文
     */
    fun buildDynamicContext(context: AgentContext, uiTreeText: String, userProfileText: String? = null): String {
        val sb = StringBuilder()

        sb.appendLine("【当前任务】")
        sb.appendLine("- 总目标：${context.globalGoal}")
        sb.appendLine("- 目标应用：${context.targetAppName}")
        sb.appendLine("- 任务模式：${context.taskSpec.mode}")
        sb.appendLine("- 搜索关键词：${context.taskSpec.searchQuery.ifBlank { "无" }}")
        sb.appendLine("- 研究深度：${context.taskSpec.researchDepth}")
        sb.appendLine("- 作业策略：${context.taskSpec.homeworkPolicy}")
        sb.appendLine("- 不确定时询问用户：${context.taskSpec.askOnUncertain}")
        sb.appendLine("- 当前第 ${context.stepIndex} 步（最多 ${context.maxSteps} 步）")
        sb.appendLine("- 重试次数：${context.retryCount}")
        sb.appendLine()

        // 注入用户偏好画像
        if (!userProfileText.isNullOrBlank()) {
            sb.appendLine("【用户画像 — 请参考以个性化操作】")
            sb.appendLine(userProfileText)
            sb.appendLine()
        }

        // 注入任务分解子步骤
        val plan = context.taskPlan
        if (plan != null) {
            sb.appendLine("【任务分解计划】（共 ${plan.steps.size} 步）")
            plan.steps.forEachIndexed { i, step ->
                val marker = when {
                    i < context.currentSubStepIndex -> "✅"
                    i == context.currentSubStepIndex -> "👉"
                    else -> "⬜"
                }
                sb.appendLine("  $marker ${step.index}. ${step.description}")
                sb.appendLine("     验证标准：${step.expectedResult}")
            }
            sb.appendLine()

            val currentSub = context.currentSubStep()
            if (currentSub != null) {
                sb.appendLine("【⚠️ 当前子步骤】")
                sb.appendLine("你现在应该执行：${currentSub.description}")
                sb.appendLine("完成标准：${currentSub.expectedResult}")
                sb.appendLine("如果当前屏幕已经满足完成标准，请在 verification.sub_step_completed 中返回 true。")
                if (context.taskSpec.mode == TaskMode.SEARCH || context.taskSpec.mode == TaskMode.RESEARCH) {
                    sb.appendLine("搜索任务注意：必须执行输入关键词 + 提交搜索 + 打开第1条结果，缺一不可。")
                }
                sb.appendLine()
            }
        }

        // 注入替代策略（如果有）
        if (!context.alternativeStrategy.isNullOrBlank()) {
            sb.appendLine("【⚠️ 替代策略 — 之前的方法失败了，请使用新策略】")
            sb.appendLine(context.alternativeStrategy)
            sb.appendLine()
        }

        if (context.decisionNotes.isNotEmpty()) {
            sb.appendLine("【用户最近的方案选择】")
            context.decisionNotes.takeLast(3).forEach { note ->
                sb.appendLine("- $note")
            }
            sb.appendLine()
        }

        if (context.knowledgeCaptures.isNotEmpty()) {
            sb.appendLine("【已采集资料】")
            context.knowledgeCaptures.takeLast(8).forEachIndexed { i, capture ->
                sb.appendLine(
                    "${i + 1}. ${capture.sourceTitle} | ${capture.sourceHint} | ${capture.sourceSnippet}"
                )
            }
            sb.appendLine()
        }

        // 注入历史记录（最近 5 步）
        if (context.history.isNotEmpty()) {
            sb.appendLine("【最近执行记录】")
            context.history.takeLast(5).forEach { step ->
                val status = if (step.success) "✅" else "❌"
                sb.appendLine("  第${step.stepIndex}步 $status ${step.action.intent}: ${step.action.targetDesc} → ${step.resultSummary}")
            }
            sb.appendLine()

            val lastStep = context.history.last()
            sb.appendLine("【验证要求】")
            sb.appendLine("上一步执行了：${lastStep.action.intent} - ${lastStep.action.targetDesc}")
            sb.appendLine("请先检查当前截图和 UI 树，判断上一步是否成功执行。")
            sb.appendLine()
        } else {
            sb.appendLine("【验证要求】这是第一步，无需验证前置状态。")
            sb.appendLine()
        }

        // 注入 UI 树
        sb.appendLine("【当前屏幕 UI 树】")
        sb.appendLine(uiTreeText)
        sb.appendLine()

        sb.appendLine("请结合附加的截图和上方的 UI 树，严格按照 JSON 格式输出你的分析与决策。")

        return sb.toString()
    }

    /**
     * 构建自我反思的上下文
     */
    fun buildReflectionContext(context: AgentContext): String {
        val sb = StringBuilder()
        sb.appendLine("总目标：${context.globalGoal}")
        sb.appendLine("目标应用：${context.targetAppName}")
        sb.appendLine("任务模式：${context.taskSpec.mode}")
        sb.appendLine("已执行 ${context.stepIndex} 步，连续失败 ${context.consecutiveFailCount} 次")
        sb.appendLine()

        if (context.history.isNotEmpty()) {
            sb.appendLine("最近执行记录：")
            context.history.takeLast(8).forEach { step ->
                val status = if (step.success) "✅" else "❌"
                sb.appendLine("  第${step.stepIndex}步 $status ${step.action.intent}: ${step.action.targetDesc}")
                sb.appendLine("    推理：${step.action.reasoning}")
                sb.appendLine("    结果：${step.resultSummary}")
            }
        }

        val plan = context.taskPlan
        if (plan != null) {
            sb.appendLine()
            sb.appendLine("当前卡在子步骤 ${context.currentSubStepIndex}: ${context.currentSubStep()?.description}")
        }

        return sb.toString()
    }
}
