# Software Navigation Assistant 技术报告

日期：2026-02-27
范围：Android 客户端（`app/`）+ Next.js 服务端（`software-nav-assistant/`）

## 1. 结论摘要
这是一个“双端协作”的手机代理系统：
1. Android 端负责感知（截图/UI 树）与执行（手势/输入/Intent）。
2. Next.js 端负责规划、复核、仲裁、存储与可观测。

整体架构先进，已经具备：事件驱动观察、SoM 标注、Planner-Reviewer-Arbiter 链路、安全硬拦截、人机协同确认。

当前最需要优先处理的是：
1. 安全面过宽（公开访问、内部路由鉴权边界、明文本地密钥）。
2. 新旧链路并存（`/api/analyze-screen` 旧链路 vs `/api/mobile-agent/next-step` 新链路）导致默认可用性与维护复杂度问题。
3. 质量基线不一致（测试与 eval 通过，但 lint 当前不通过）。

## 2. 项目盘点

### 2.1 规模
1. Android 主源码：34 个 Kotlin 文件，约 8,885 行。
2. Web 主源码：35 个 TS/TSX 文件，约 4,157 行。
3. 大体量核心文件：
1. `AgentOrchestrator.kt`（约 1,771 行）
2. `MainActivity.kt`（约 1,709 行）
3. `arbiter.ts`（约 421 行）
4. `persistence.ts`（约 381 行）

### 2.2 运行时组件
1. Android 服务：
1. `AgentCaptureService`：MediaProjection 截图
2. `AgentAccessibilityService`：UI 树读取 + 手势执行
3. `OverlayGuideService`：目标高亮引导
4. `AgentStopOverlayService`：悬浮停止按钮
2. 后端接口：
1. `/api/mobile-agent/next-step`：主决策接口
2. `/api/mobile-agent/telemetry`：埋点入库
3. `/api/mobile-agent/internal/gemini-json`：通用 JSON 生成
4. `/api/mobile-agent/internal/session-recap-video`：内部视频任务
5. `/api/chat-goal`：目标澄清
6. `/api/analyze-screen`：旧 Demo 链路（默认关闭）

## 3. 架构与主流程

### 3.1 Android 主循环（AgentOrchestrator）
核心循环是：
1. 等待截图服务就绪。
2. 任务分解（TaskPlanner）并注入历史成功记忆（TaskMemory）。
3. 读取 UI 树 + 抓取帧。
4. 进行节点裁剪与 SoM 标注。
5. 调用云端 next-step。
6. 执行动作前做安全清洗（风险、关键词、系统 UI、Intent 白名单）。
7. 执行动作并做 checkpoint 验证。
8. 记录 telemetry，失败时重试与反思（reflection）。
9. 完成/失败收敛并回写记忆。

设计亮点：
1. 安全是多层硬约束，不是单点提示。
2. 高风险动作有 WAITING_CONFIRM 人工确认闭环。
3. 不确定场景支持请求用户决策（DecisionRequest）。

### 3.2 服务端决策链路（Pipeline）
`runMobileAgentPipeline` 的顺序是：
1. Planner 产出候选动作。
2. Reviewer 独立评审。
3. Arbiter 做最终裁决与业务约束。
4. 写入 turn 事件、shadow diff、live 指标、媒体记录。
5. FINISH 时异步排队 session recap video。

设计亮点：
1. Zod Schema 做入参与出参强校验。
2. REPLAN 有一次重试策略。
3. 对旧客户端保留兼容字段（`target_bbox`、`screenshot_base64`）。

### 3.3 存储
1. Android 本地：Room（session/message/agent_memory）+ SharedPreferences（会话兜底与用户画像）。
2. 服务端：PostgreSQL 7 张核心表（turn、summary、shadow、telemetry、live metrics、generated media、media jobs）。

## 4. 安全与合规现状
已实现的安全措施：
1. 点击框合法性校验、硬风险关键词阻断。
2. Intent action/package allowlist + extras 限制。
3. 高风险进入人工确认分支。
4. Reviewer + Arbiter 双层决策守门。

需要关注：
1. `QUERY_ALL_PACKAGES` 可能触发分发平台合规审核要求。

## 5. 当前质量基线（已实测）

### 5.1 Android 单测
命令：`./gradlew test --console=plain`
结果：
1. 构建成功（BUILD SUCCESSFUL）。
2. 当前单测套件 14 个测试全部通过。

### 5.2 Mobile-Agent Eval
命令：
1. `npm run eval:offline`
2. `npm run eval:regression`

结果：
1. 覆盖率：20/20
2. E2E 成功率：0.85
3. 关键步骤成功率：0.9595
4. 平均重试次数：0.45
5. Reviewer 阻断准确率：0.75
6. 风险误触发率：0
7. 回归跌幅：0

结论：
1. 离线阈值门已通过。
2. 安全误触发表现较好。
3. Reviewer 阻断准确率还有优化空间。

### 5.3 Lint 现状
命令：`npm run lint`
结果：
1. 失败（13 errors + 2 warnings）。
2. 主要问题：
1. eval/scripts 使用 CommonJS `require` 与 ESLint 规则冲突。
2. `DevPanel.tsx` 存在 JSX 未转义字符。
3. 旧 orchestrator 存在 `any`。

命令：`npm run lint:encoding`
结果：通过。

## 6. 关键问题分级

### P0（立即处理）
1. 密钥风险：本地配置存在明文 API Key，建议立刻轮换并改为安全注入流程。
2. 云暴露面偏大：当前模板含公开访问配置（Cloud Run 公共调用 + Cloud SQL IPv4 直开）。
3. 链路分裂：前端默认调用旧接口 `/api/analyze-screen`，而该接口默认返回 410（关闭），导致默认体验与认知不一致。

### P1（短期）
1. 编排层过大：`AgentOrchestrator.kt`、`MainActivity.kt` 过于集中，变更风险高。
2. lint 基线未达标，不利于持续演进。
3. 生成引导图在主决策路径同步等待，会拉长 turn 延迟并增加成本波动。

### P2（中期）
1. 旧 schema/旧 orchestrator 与新 mobile-agent schema 并存，维护成本高。
2. 缺少 Android 请求体与后端 schema 的契约集成测试。

## 7. 建议改造路线

### 阶段 0（1-3 天）：先做安全与可用性
1. 轮换密钥，清理明文敏感配置。
2. 内部路由与 telemetry 增加鉴权策略。
3. 收紧云访问边界（调用方、入口、网络路径）。
4. 统一默认路由策略：Web 端明确走新链路或显式开启 demo，不再模糊。

### 阶段 1（1-2 周）：夯实工程基线
1. 修复 lint 到全绿。
2. 建立 CI Gate：Android test + Web lint + 两个 eval。
3. 增加 Android ↔ Backend schema 契约测试。

### 阶段 2（2-4 周）：做架构拆分
1. 将 `AgentOrchestrator` 拆为 Observation/Planning/Safety/Execution/Verification/Telemetry 子模块。
2. `MainActivity` 进一步下沉业务逻辑到 ViewModel/UseCase。
3. 下线或隔离旧链路，减少双栈维护负担。

### 阶段 3（4+ 周）：性能与产品优化
1. 引导图生成改为异步后置，避免阻塞主决策。
2. 建立按模型/帧数/来源的延迟指标看板。
3. 扩充 eval 集：高风险边界、恢复场景、对抗样本。
4. 提升 Reviewer 阻断准确率目标。

## 8. 可直接执行的优先待办
1. 先改安全：密钥轮换 + 内部接口鉴权 + 公网暴露面收敛。
2. 修复路由割裂：统一 `analyze-screen` 与 `mobile-agent/next-step` 使用策略。
3. 修复 lint 并接入 CI 门禁。
4. 拆分 Android 编排核心，降低单文件复杂度。
5. 补齐端到端契约测试。
6. 解耦媒体生成与在线决策延迟。

## 9. 主要证据文件
1. Android 入口与权限：`app/src/main/AndroidManifest.xml`
2. Android 可配置开关：`app/build.gradle.kts`
3. Android 核心编排：`app/src/main/java/com/immersive/ui/agent/AgentOrchestrator.kt`
4. Android 云决策适配：`app/src/main/java/com/immersive/ui/agent/CloudDecisionClient.kt`
5. Android Intent 安全：`app/src/main/java/com/immersive/ui/agent/IntentGuard.kt`
6. 新链路 API：`software-nav-assistant/src/app/api/mobile-agent/next-step/route.ts`
7. 新链路流水线：`software-nav-assistant/src/lib/mobile-agent/pipeline.ts`
8. 仲裁与守卫：`software-nav-assistant/src/lib/mobile-agent/arbiter.ts`
9. 持久化：`software-nav-assistant/src/lib/mobile-agent/persistence.ts`
10. 旧链路调用点：`software-nav-assistant/src/lib/context/TaskProvider.tsx`
11. 旧接口关闭逻辑：`software-nav-assistant/src/app/api/analyze-screen/route.ts`
12. 基础设施模板：`software-nav-assistant/infra/terraform/main.tf`
13. 构建部署模板：`software-nav-assistant/cloudbuild.yaml`
14. 评测脚本：`software-nav-assistant/evals/offline-eval.js`、`software-nav-assistant/evals/regression-eval.js`

## 10. 总结
系统底座已经具备“可用的智能代理能力”，下一阶段不是重写算法，而是先把安全边界、路由一致性、工程门禁和模块化做好。完成这四项后，后续迭代速度与稳定性会显著提升。
