import { z } from "zod";

/**
 * Structured output schema.
 * Every describe() string becomes part of the system prompt seen by the model.
 */
export const LLMOutputSchema = z.object({
  // 0. Black-box audit step (force CoT-style reasoning before emitting the structured result)
  audit_metadata: z.object({
    reasoning_trace: z.string().describe("思维链：你看到了什么，上一步成功了吗，是否存在风险，下一步该干嘛？"),
  }),

  // 1. Screen perception
  screen_state: z.object({
    page_summary: z.string().describe("当前页面的简要描述，例如'微信首页'或'支付密码输入页'"),
    confidence: z.number().describe("对当前页面理解的置信度 (0.0 到 1.0)"),
    has_popup: z.boolean().describe("是否有弹窗、系统更新、广告或系统授权遮挡了主要内容"),
    risk_detected: z.boolean().describe("🚨 当前页面是否涉及：支付、密码、转账、删除、授权等高危操作"),
  }),

  // 2. Verifier - the core protection against losing context
  verification: z.object({
    is_previous_step_successful: z.boolean().describe("对比上一轮的 checkpoint，长辈当前是否来到了预期页面？(第一步恒为 true)"),
    is_deviation: z.boolean().describe("长辈是否误触进入了完全无关的页面（偏航）？"),
    reasoning: z.string().describe("判定成功、偏航或遇到弹窗的具体理由（无责备语气）"),
  }),

  // 3. Planner
  next_step: z.object({
    intent: z.enum(["CLICK", "SCROLL", "WAIT", "GO_BACK", "CLOSE_POPUP", "FINISH", "UNKNOWN"]).describe("下一步技术意图"),
    target_element_desc: z.string().describe("目标元素的技术描述（内部日志用）"),

    // Bounding box returned to the frontend for red-box rendering; Gemini uses native 0-1000 ratio coordinates
    target_bbox: z.array(z.number())
      .length(4)
      .nullable()
      .describe("若需点击，返回目标元素边框 [ymin, xmin, ymax, xmax]，范围0-1000。如果不确定绝不瞎编！填 null。"),

    // Elder-friendly instruction core: plain-language narration tailored for elderly users
    elderly_instruction: z.string().describe(
      "长辈大白话指令。必须包含空间方位，极度简短。绝对不能使用'Icon'、'Tab'、'导航栏'等词。"
    ),

    risk_level: z.enum(["SAFE", "WARNING", "HIGH"]).describe("这一步操作的风险级别"),
  }),

  // 4. Checkpoint seed for the next verifier pass
  next_screen_checkpoint: z.object({
    expected_page_type: z.string(),
    expected_elements: z.array(z.string())
  }).describe("如果用户按指令操作了，下一张截图你应该看到什么？（作为下一轮的标尺）"),

  // 5. Recovery and dialog support
  dialog_support: z.object({
    if_cannot_see: z.string().describe("针对'我看不到'的备用话术：提供更详细、多方位的空间参照物（颜色、相对位置）"),
    if_too_fast: z.string().describe("针对'太快了'的备用话术：安抚情绪并温和重复"),
  }),

  // 6. Uncertainty handling - admit uncertainty instead of hallucinating
  uncertainty_handling: z.object({
    is_uncertain: z.boolean().describe("你是否对屏幕识别或当前坐标极度不确定？"),
    safe_action: z.string().describe("如果不确定，给出的兜底建议（如：'系统没看清，请点一下返回重试'）")
  })
});

// Export the TypeScript type for backend API usage
export type AgentDecision = z.infer<typeof LLMOutputSchema>;
