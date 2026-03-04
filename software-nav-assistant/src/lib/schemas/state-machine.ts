/**
 * 系统的全局 FSM（有限状态机）节点
 * 物理围栏：任何时刻，系统只能处于以下某一个状态
 */
export enum SessionState {
  IDLE = "IDLE", // 初始状态，等待设置目标
  PROCESSING = "PROCESSING", // 正在调用 AI 解析截图 (UI 显示扫描波纹动画)
  WAITING_USER = "WAITING_USER", // 已给出下一步指引，等待长辈操作并传新图
  RECOVERING = "RECOVERING", // 发生偏航/用户求助，正在执行降级话术
  RISK_PAUSED = "RISK_PAUSED", // 🚨 触发高危动作，系统已硬性物理熔断
  COMPLETED = "COMPLETED", // 任务顺利完成（如停在拨出视频前）
}

/**
 * 长辈的快捷物理反馈按键枚举
 */
export enum UserFeedback {
  NONE = "NONE", // 正常推进
  CANT_SEE = "CANT_SEE", // "我找不到你说的地方"
  TOO_FAST = "TOO_FAST", // "太快了没跟上 / 重复一遍"
  WRONG_PAGE = "WRONG_PAGE", // "这不是我要的页面 / 好像点错了"
}

/**
 * 验证检查点 (Checkpoint)
 * 用于 Verifier 判断上一步长辈是否操作成功的唯一“标尺”
 */
export interface StepCheckpoint {
  expected_page_type: string; // 期望进入的页面类型 (如："聊天详情页")
  expected_elements: string[]; // 期望在下一屏看到的标志性文字或特征
}

/**
 * 会话上下文 Context (Fat Payload)
 * 每次请求 API 时，前端需将此对象随最新截图一起 POST 给后端
 */
export interface TaskContext {
  session_id: string;
  global_goal: string; // 宏观目标 (例："给小明打视频")
  current_step_index: number; // 当前是第几步 (防死循环拦截，超过10步强制终止)
  state: SessionState;

  // 🛡️ 核心防线：上一步我们让长辈干了什么？期待看到什么？
  last_action_desc: string | null;
  last_checkpoint: StepCheckpoint | null;

  user_feedback: UserFeedback; // 用户刚才按了什么反馈键
  retry_count: number; // 当前步骤重试次数，超过3次主动建议呼叫家人
}
