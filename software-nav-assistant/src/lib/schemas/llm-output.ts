import { z } from "zod";

/**
 * 结构化输出 Schema
 * 每一个 describe 都会被大模型解析为 System Prompt 的一部分！
 */
export const LLMOutputSchema = z.object({
  // 0. 黑匣子审计 (强制 AI 先做 CoT 推理，提升后续输出的准确度)
  audit_metadata: z.object({
    reasoning_trace: z.string().describe("思维链：你看到了什么，上一步成功了吗，是否存在风险，下一步该干嘛？"),
  }),

  // 1. 屏幕感知 (Perception)
  screen_state: z.object({
    page_summary: z.string().describe("当前页面的简要描述，例如'微信首页'或'支付密码输入页'"),
    confidence: z.number().describe("对当前页面理解的置信度 (0.0 到 1.0)"),
    has_popup: z.boolean().describe("是否有弹窗、系统更新、广告或系统授权遮挡了主要内容"),
    risk_detected: z.boolean().describe("🚨 当前页面是否涉及：支付、密码、转账、删除、授权等高危操作"),
  }),

  // 2. 验证器 (Verifier) - 架构防瞎眼核心
  verification: z.object({
    is_previous_step_successful: z.boolean().describe("对比上一轮的 checkpoint，长辈当前是否来到了预期页面？(第一步恒为 true)"),
    is_deviation: z.boolean().describe("长辈是否误触进入了完全无关的页面（偏航）？"),
    reasoning: z.string().describe("判定成功、偏航或遇到弹窗的具体理由（无责备语气）"),
  }),

  // 3. 规划器 (Planner)
  next_step: z.object({
    intent: z.enum(["CLICK", "SCROLL", "WAIT", "GO_BACK", "CLOSE_POPUP", "FINISH", "UNKNOWN"]).describe("下一步技术意图"),
    target_element_desc: z.string().describe("目标元素的技术描述（内部日志用）"),

    // Bounding Box：返回给前端画红框。Gemini 视觉原生支持 0-1000 比例坐标
    target_bbox: z.array(z.number())
      .length(4)
      .nullable()
      .describe("若需点击，返回目标元素边框 [ymin, xmin, ymax, xmax]，范围0-1000。如果不确定绝不瞎编！填 null。"),

    // 💡 适老化核心：长辈专属话术（右脑翻译）
    elderly_instruction: z.string().describe(
      "长辈大白话指令。必须包含空间方位，极度简短。绝对不能使用'Icon'、'Tab'、'导航栏'等词。"
    ),

    risk_level: z.enum(["SAFE", "WARNING", "HIGH"]).describe("这一步操作的风险级别"),
  }),

  // 4. 埋点核心：为下一轮的验证器设定目标
  next_screen_checkpoint: z.object({
    expected_page_type: z.string(),
    expected_elements: z.array(z.string())
  }).describe("如果用户按指令操作了，下一张截图你应该看到什么？（作为下一轮的标尺）"),

  // 5. 异常与对话支持 (Recovery & Dialog)
  dialog_support: z.object({
    if_cannot_see: z.string().describe("针对'我看不到'的备用话术：提供更详细、多方位的空间参照物（颜色、相对位置）"),
    if_too_fast: z.string().describe("针对'太快了'的备用话术：安抚情绪并温和重复"),
  }),

  // 6. 不确定性处理 (Uncertainty Handling) - 不懂就说不懂
  uncertainty_handling: z.object({
    is_uncertain: z.boolean().describe("你是否对屏幕识别或当前坐标极度不确定？"),
    safe_action: z.string().describe("如果不确定，给出的兜底建议（如：'系统没看清，请点一下返回重试'）")
  })
});

// 导出 TypeScript 类型供后端接口使用
export type AgentDecision = z.infer<typeof LLMOutputSchema>;
