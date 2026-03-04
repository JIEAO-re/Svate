# 软件技术报告（含关键代码片段）

生成时间：2026-02-27
项目根目录：`C:\Users\26257\Desktop\UI`
覆盖范围：
1. Android 执行端（`app/`）
2. Next.js 决策与服务端（`software-nav-assistant/`）

## 1. 系统总览
这是一个“端上执行 + 云端决策”的手机智能导航系统。

主链路：
1. Android 端采集 UI 树与屏幕帧。
2. Android 端把任务上下文+观测数据发往 `/api/mobile-agent/next-step`。
3. 服务端执行 Planner -> Reviewer -> Arbiter。
4. 服务端返回最终动作 + checkpoint。
5. Android 执行动作并校验结果，写 telemetry 与长期记忆。

核心特性：
1. SoM（Set-of-Mark）标注与 selector 双重定位。
2. 风险分级与高风险硬拦截。
3. 任务模式（GENERAL/SEARCH/RESEARCH/HOMEWORK）驱动行为约束。
4. 观察驱动循环（event-driven + visual diff + checkpoint verification）。

---

## 2. Android 端技术细节

### 2.1 权限与服务编排
- `SYSTEM_ALERT_WINDOW`：悬浮引导/停止按钮。
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`：持续截图。
- `BIND_ACCESSIBILITY_SERVICE`：执行点击、滑动、输入。
- `QUERY_ALL_PACKAGES`：扫描已安装 app（候选 app 推理）。

关键代码片段：`app/src/main/AndroidManifest.xml`
```xml
   5:     <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
   7:     <uses-permission android:name="android.permission.RECORD_AUDIO" />
  11:     <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
  44:         <service android:name=".guide.GuideCaptureService" ... />
  49:         <service android:name=".agent.AgentCaptureService" ... />
  54:         <service android:name=".agent.AgentAccessibilityService" ... />
```

### 2.2 运行时开关（BuildConfig）
该项目把大量策略做成可切换配置，便于灰度和 A/B。

关键代码片段：`app/build.gradle.kts`
```kotlin
  50:             "MOBILE_AGENT_BASE_URL",
  55:             "MOBILE_AGENT_MODE",
  60:             "MOBILE_AGENT_ENABLED",
  65:             "MOBILE_AGENT_TELEMETRY_ENABLED",
  75:             "EVENT_DRIVEN",
  80:             "SOM_CLOUD",
  85:             "OPEN_INTENT",
  90:             "UI_PRUNE",
  95:             "VISUAL_DIFF",
  98:         buildConfigField("int", "MAX_RETRY_ATTEMPTS", ...)
```

### 2.3 Agent 主循环（Orchestrator）
`AgentOrchestrator` 是执行核心：分解计划、观测、决策、执行、验证、重试、反思。

关键代码片段：`app/src/main/java/com/immersive/ui/agent/AgentOrchestrator.kt`
```kotlin
 152: updatePhase(AgentPhase.DECOMPOSING, "Decomposing the task into steps...")
 160: val plan = TaskPlanner.decompose(...)
 174: if (tryDirectOpenApp()) { ... }
 178: while (isRunning.get()) {
 188:   if (ctx.isAllSubStepsDone()) {
 192:     updatePhase(AgentPhase.REPORTING, "Compiling the research summary...")
 219:     updatePhase(AgentPhase.COMPLETED, "All planned steps are complete.")
 225:   val uiNodes = getUiNodes()
 229:   if (waitForPageStableIfNeeded(...)) continue
 232:   val popup = PopupRecovery.detect(uiNodes)
```

### 2.4 动作安全清洗（硬约束）
在执行前统一走 `sanitizeAction`：
1. 高风险直接转 `WAIT`。
2. 命中支付/提交等关键词直接阻断。
3. HOMEWORK 模式下禁止提交发布。
4. CLICK/OPEN_APP/OPEN_INTENT 分别做专门校验。

关键代码片段：`app/src/main/java/com/immersive/ui/agent/AgentOrchestrator.kt`
```kotlin
1218: if (action.riskLevel == RiskLevel.HIGH) return toSafeWait(...)
1221: if (AgentActionSafety.containsHardBlockedKeyword(...)) return toSafeWait(...)
1224: if (ctx.taskSpec.mode == TaskMode.HOMEWORK && ...submit/publish...) return toSafeWait(...)
1235: val bboxCheck = ...validateClickBbox(...)
1257: if (hitsSystemUi && !isCrashDismissAction(action)) "system_ui_target"
1271: if (!AgentActionSafety.isKnownLauncherPackage(...)) "invalid_package"
1279: val check = IntentGuard.validate(...)
```

### 2.5 Intent 白名单防线
`IntentGuard` 限制 action、package、uri scheme、extras 数量与类型。

关键代码片段：`app/src/main/java/com/immersive/ui/agent/IntentGuard.kt`
```kotlin
 11: private val allowedActions = setOf(Intent.ACTION_VIEW, Intent.ACTION_SEARCH, Intent.ACTION_WEB_SEARCH)
 16: private val allowedPackages = setOf("com.tencent.mm", "com.ss.android.ugc.aweme", ...)
 35: if (spec.action !in allowedActions) return SafetyCheckResult(false, "intent_action_blocked")
 41: if (!pkg.isNullOrBlank() && pkg !in allowedPackages) return SafetyCheckResult(false, "intent_package_blocked")
 51: return SafetyCheckResult(false, "intent_data_uri_scheme_blocked")
 55: if (spec.extras.size > MAX_EXTRA_ENTRIES) return SafetyCheckResult(false, "intent_extras_too_many")
```

### 2.6 Android -> Cloud 请求负载
Android 发送结构化 observation，兼容新媒体窗口与旧 screenshot 字段。

关键代码片段：`app/src/main/java/com/immersive/ui/agent/CloudDecisionClient.kt`
```kotlin
161: put("session_id", ...)
166: put("task_spec", JSONObject().apply { put("mode", ...); put("search_query", ...); ... })
174: put("observation", JSONObject().apply {
179:   put("media_window", JSONObject().apply { put("source", "SCREEN_RECORDING"); put("frames", ...) })
191:   put("ui_node_stats", ...)
200:   put("frame_fingerprint", frameFingerprint)
203:   // Compatibility field during staged backend rollout.
204:   put("screenshot_base64", frames.last().imageBase64)
205:   put("ui_nodes", toUiNodesJson(uiNodes))
})
```

### 2.7 任务规划标准化
`TaskPlanner.normalizePlan` 会按模式重写步骤，SEARCH/RESEARCH/HOMEWORK 有固定流程约束。

关键代码片段：`app/src/main/java/com/immersive/ui/agent/TaskPlanner.kt`
```kotlin
102: val normalizedSteps = when (taskSpec.mode) {
103:   TaskMode.SEARCH -> buildSearchPlan(app, query)
104:   TaskMode.RESEARCH -> buildResearchPlan(app, query, taskSpec.researchDepth)
105:   TaskMode.HOMEWORK -> buildHomeworkPlan(app, goal)
106:   TaskMode.GENERAL -> normalizeGeneralPlan(app, goal, baseSteps)
}
```

---

## 3. 服务端技术细节（Next.js）

### 3.1 主 API 入口
`/api/mobile-agent/next-step` 做请求校验并调用统一 pipeline。

关键代码片段：`software-nav-assistant/src/app/api/mobile-agent/next-step/route.ts`
```ts
 10: const parsed = NextStepRequestSchema.safeParse(body);
 22: const response = await runMobileAgentPipeline(parsed.data);
 31: success: false,
 32: error: "mobile_agent_next_step_failed",
```

### 3.2 Zod 契约：动作与观测
`mobile-agent.ts` 是系统最关键的契约文件，约束了动作可执行性。

关键代码片段 A（动作约束）：`software-nav-assistant/src/lib/schemas/mobile-agent.ts`
```ts
 62: export const ActionCommandSchema = z.object({ ... }).superRefine((action, ctx) => {
 84: const allowNoCheckpoint = action.intent === "WAIT" || action.intent === "FINISH";
 93: if ((action.intent === "CLICK" || action.intent === "TYPE") && action.selector == null && ... ) {
100:   message: "selector or target_som_id or target_bbox is required for CLICK/TYPE actions"
104: if (action.intent === "TYPE" && !action.input_text?.trim()) { ... }
```

关键代码片段 B（观测约束）：
```ts
177: export const MobileObservationSchema = z.object({ ... }).superRefine((observation, ctx) => {
191: const hasMediaWindow = !!observation.media_window?.frames?.length;
192: const hasLegacyScreenshot = !!observation.screenshot_base64?.trim();
193: if (!hasMediaWindow && !hasLegacyScreenshot) {
197:   message: "media_window or screenshot_base64 is required"
```

### 3.3 Pipeline：Planner -> Reviewer -> Arbiter

关键代码片段：`software-nav-assistant/src/lib/mobile-agent/pipeline.ts`
```ts
25: let plannerRun = await runPlanner(request);
26: let reviewerRun = await runReviewer(request, plannerRun.output.candidates);
30: if (reviewerRun.output.verdict === "REPLAN") { ... } // replan once
36: const arbiter = arbitrateDecision(...)
61: const response: NextStepResponse = {
75:   final_action: arbiter.finalAction,
81:   guard: { risk_level: ..., block_reason: ... },
85:   live_runtime: { used_live: true, ... }
}
94: const parsed = NextStepResponseSchema.parse(response);
```

### 3.4 仲裁器规则（高价值）
Arbiter 除了 reviewer verdict 外，还做 OPEN_INTENT allowlist、SEARCH 强约束。

关键代码片段：`software-nav-assistant/src/lib/mobile-agent/arbiter.ts`
```ts
20: const ALLOWED_INTENT_ACTIONS = new Set(["android.intent.action.view", ...])
25: const ALLOWED_INTENT_PACKAGES = new Set(["com.tencent.mm", ...])
298: function validateOpenIntentAction(...) {
306:   if (!ALLOWED_INTENT_ACTIONS.has(normalizedAction)) return "open_intent_action_not_allowed";
310:   if (pkg && !ALLOWED_INTENT_PACKAGES.has(pkg)) return "open_intent_package_not_allowed";
316:   if (request.task_spec.mode === "SEARCH" && query && !intentSpecContainsQuery(...)) {
320:     return "open_intent_missing_search_query";
338: if (!typed && action.intent === "FINISH") return "search_type_missing";
339: if (!submitted && action.intent === "FINISH") return "search_submit_missing";
```

### 3.5 Live Turn 规划执行
使用 Gemini Live + tools 机制输出候选动作。

关键代码片段：`software-nav-assistant/src/lib/mobile-agent/live-turn-client.ts`
```ts
62: export async function runLivePlannerTurn(request: NextStepRequest) {
71: const frameWindow = getFrameWindow(request);
73: if (frameWindow.length === 0) throw new Error("live planner requires at least one frame");
96: session = await ai.live.connect({
101:   tools: [{ functionDeclarations: [{ name: EMIT_CANDIDATES_TOOL, ... }] }],
```

### 3.6 持久化层
Postgres 连接池 + 事务写入 turn 与 telemetry。

关键代码片段 A：`software-nav-assistant/src/lib/mobile-agent/persistence.ts`
```ts
81: pool = new Pool({ connectionString: POSTGRES_URL, ssl: ..., max: ... })
93: export async function saveTurnEvent(record: TurnEventRecord) {
97:   await client.query("BEGIN");
99:   await client.query(`INSERT INTO agent_turn_events ...`)
```

关键代码片段 B：
```ts
313: export async function saveTelemetryEvents(events: TelemetryEvent[]) {
319:   await client.query("BEGIN");
323:   INSERT INTO agent_telemetry_events (...)
342:   await client.query("COMMIT");
```

### 3.7 环境变量 Fail-Fast
服务端启动时会做关键依赖校验，不满足即抛错。

关键代码片段：`software-nav-assistant/src/lib/mobile-agent/env.ts`
```ts
16: if (!GOOGLE_GENAI_USE_VERTEXAI && !GEMINI_API_KEY) throw new Error(...)
19: if (GOOGLE_GENAI_USE_VERTEXAI) { ...project/location required... }
40: if (!POSTGRES_URL) throw new Error("POSTGRES_URL is required for mobile-agent persistence")
44: if (ENABLE_GUIDE_MEDIA && !GUIDE_MEDIA_BUCKET) throw new Error(...)
```

### 3.8 会话回顾视频（异步任务）
FINISH 后可通过 Cloud Tasks 触发 recap 视频任务。

关键代码片段：`software-nav-assistant/src/lib/mobile-agent/session-recap-video.ts`
```ts
31: export async function enqueueSessionRecapVideo(...) {
36:   if (!ENABLE_SESSION_RECAP_VIDEO) return null;
45:   await createMediaJob({ status: "PENDING", ... })
55:   if (!CLOUD_TASKS_PROJECT || !CLOUD_TASKS_LOCATION || !CLOUD_TASKS_QUEUE || !SESSION_RECAP_JOB_URL) {
56:     return jobId;
59:   const client = getCloudTasksClient();
64:   if (INTERNAL_JOB_TOKEN) headers.Authorization = `Bearer ${INTERNAL_JOB_TOKEN}`;
68:   await client.createTask({ ... })
```

---

## 4. 数据库模型（后端）

关键代码片段：`software-nav-assistant/db/mobile_agent_tables.sql`
```sql
  3: CREATE TABLE IF NOT EXISTS agent_turn_events (
 17:   shadow_diff JSONB NULL,
 18:   created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

 24: CREATE TABLE IF NOT EXISTS agent_session_summary (...)
 31: CREATE TABLE IF NOT EXISTS agent_shadow_diff (...)
 45: CREATE TABLE IF NOT EXISTS agent_telemetry_events (... payload JSONB ...)
 58: CREATE TABLE IF NOT EXISTS agent_live_turn_metrics (...)
```

表职责：
1. `agent_turn_events`：每轮 planner/reviewer/final action 归档。
2. `agent_shadow_diff`：shadow 对比与失败原因。
3. `agent_telemetry_events`：端上埋点事件。
4. `agent_live_turn_metrics`：live 连接与推理延迟。
5. `agent_generated_media` / `agent_media_jobs`：媒体产物与任务状态。

---

## 5. 新旧链路并存现状（关键注意）
Web 前端当前默认调用旧接口 `/api/analyze-screen`，但该接口是 demo-only 且默认关闭。

关键代码片段 A：`software-nav-assistant/src/lib/context/TaskProvider.tsx`
```ts
42: const response = await fetch("/api/analyze-screen", { ... })
```

关键代码片段 B：`software-nav-assistant/src/app/api/analyze-screen/route.ts`
```ts
5: // Legacy demo-only endpoint. Mobile production traffic must use /api/mobile-agent/next-step.
8: const DEMO_ENABLED = process.env.ENABLE_ANALYZE_SCREEN_DEMO?.trim().toLowerCase() === "true";
11: if (!DEMO_ENABLED) { ... status: 410 }
```

结论：
1. 当前 Web Demo 默认路径与生产移动代理路径不一致。
2. 若未开启 demo 开关，前端调用将直接 410。

---

## 6. 部署与安全边界（已收口）

### 6.1 Cloud Run 鉴权
`software-nav-assistant/cloudbuild.yaml` 已改为 `--no-allow-unauthenticated`，生产部署不再允许匿名访问。

### 6.2 Cloud SQL 私网化
`software-nav-assistant/infra/terraform/main.tf` 中 `ipv4_enabled = false`，数据库仅通过私网或 Cloud SQL Connector 访问。`allUsers` invoker 已移除。

### 6.3 接口鉴权
- `/api/mobile-agent/next-step`：生产环境强制 Firebase App Check（JWKS 签名校验）或 Android JWT（RS256 公钥校验）。开发环境可通过 `SKIP_AUTH_DEV=true` 跳过。
- `/api/mobile-agent/internal/*`：Bearer `INTERNAL_JOB_TOKEN` 校验，生产环境 token 为空时拒绝所有请求。

### 6.4 密钥管理
- 仓库内禁止明文密钥，`.gitignore` 已覆盖 `.env*`。
- 生产密钥通过 Google Secret Manager 注入（`POSTGRES_URL`、`INTERNAL_JOB_TOKEN`）。
- 已暴露的 Gemini API Key 需在 Google Cloud Console 中轮换。

---

## 7. 测试与评估现状

### 7.1 CI 门禁配置（`.github/workflows/mobile-agent-ci.yml`）

CI 在每次 PR 和 push to main/master 时自动触发，包含以下必过门禁：

| Job | 命令 | 说明 |
|-----|------|------|
| `node-lint` | `cd software-nav-assistant && npm ci && npm run lint` | ESLint 代码规范检查 |
| `node-test` | `cd software-nav-assistant && npm ci && npm test` | 端云契约测试 + 回归测试（vitest） |
| `eval-offline` | `cd software-nav-assistant && npm ci && npm run eval:offline` | 离线评估门限（e2e ≥ 80%, key-step ≥ 92%, risk misfire = 0） |
| `eval-regression` | `cd software-nav-assistant && npm ci && npm run eval:regression` | 回归评估（成功率下降 ≤ 3pp） |
| `android` | `./gradlew test` | Android 单元测试（ActionSafety、IntentGuard 等） |

任何一个 job 失败都会阻止 PR 合并。

### 7.2 端云契约测试（`contract.test.ts`）

位置：`software-nav-assistant/src/lib/mobile-agent/__tests__/contract.test.ts`

验证 Android `CloudDecisionClient.kt` 发送的 payload 结构与服务端 Zod schema 的一致性：
- `NextStepRequestSchema`：请求体字段对齐（session_id, observation, media_window 等）
- `ActionCommandSchema`：动作约束（CLICK/TYPE 必须有定位方式、spatial_coordinates 范围等）
- `NextStepResponseSchema`：响应体结构完整性
- `TelemetryBatchRequestSchema`：遥测事件禁止携带 base64 图片数据
- Android payload 全字段对齐验证（含 GCS URI 引用模式）

### 7.3 回归测试（`regression.test.ts`）

位置：`software-nav-assistant/src/lib/mobile-agent/__tests__/regression.test.ts`

覆盖场景：
1. `/analyze-screen` 无 demo header 时返回 301 重定向至 `/api/mobile-agent/next-step`
2. `/next-step` 正常处理：schema 校验通过 → arbiter 产出 final action
3. 鉴权失败路径：无 token → 401（missing_auth_credentials）
4. 高风险动作阻断：HIGH risk → WAIT、ESCALATE → block、replan 耗尽 → manual required
5. 危险 intent 拦截：DELETE/UNINSTALL 等非白名单 action 被 arbiter 拦截

### 7.4 本地运行测试

```bash
cd software-nav-assistant
npm ci          # 首次或依赖变更后
npm test        # 运行 vitest（契约 + 回归）
npm run lint    # ESLint
npm run eval:offline     # 离线评估
npm run eval:regression  # 回归评估
```

### 7.5 Android 单元测试
已执行：`./gradlew test --console=plain`
结果：通过（BUILD SUCCESSFUL）。

关键测试片段：`app/src/test/java/com/immersive/ui/agent/ActionSafetyTest.kt`
```kotlin
11: val result = AgentActionSafety.validateClickBbox(intArrayOf(100, 100, 260, 280))
17: val result = AgentActionSafety.validateClickBbox(intArrayOf(-1, 100, 260, 280))
53: assertTrue(AgentActionSafety.containsHardBlockedKeyword("please submit and publish"))
57: assertFalse(AgentActionSafety.containsHardBlockedKeyword("submit search query"))
```

### 7.2 离线评估门限
关键代码片段：`software-nav-assistant/evals/offline-eval.js`
```js
10: e2eSuccessRate: 0.8,
11: keyStepSuccessRate: 0.92,
12: riskMisfireRate: 0.0,
70: e2eSuccessRate >= THRESHOLDS.e2eSuccessRate && ...
75: console.error("[eval:offline] FAILED threshold gate");
```

已执行结果（本地）：
1. coverage: `20/20`
2. e2eSuccessRate: `0.85`
3. keyStepSuccessRate: `0.9595`
4. riskMisfireRate: `0`
5. regression drop: `0`

---

## 8. 问题清单与修改建议

### 8.1 P0（立即）
1. 安全收口：公网可达与内部路由鉴权边界需收紧。
2. 路由统一：前端默认调用链路切到 `/api/mobile-agent/next-step` 或明确 demo 开关策略。
3. 敏感信息治理：避免本地/日志出现明文密钥。

### 8.2 P1（短期）
1. 将 `AgentOrchestrator.kt` 做模块化拆分（观察/规划/安全/执行/验证/遥测）。
2. 修复 Web lint 错误，确保 CI 全绿。
3. 将可选媒体生成从关键路径后移，降低在线决策延迟。

### 8.3 P2（中期）
1. 清理 legacy 栈，减少双体系并行维护成本。
2. 增加 Android 请求体与服务端 schema 的契约回归测试。

---

## 9. 结语
该项目已经具备“可执行、可恢复、可约束、可观测”的智能手机代理核心能力。下一步最有价值的工程动作，不是改模型，而是：
1. 先统一链路与安全边界。
2. 再把超大编排类拆分。
3. 最后把测试门禁与部署策略固化到 CI/CD。

这三步完成后，系统可维护性和迭代效率会明显提升。
