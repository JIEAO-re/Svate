import { z } from "zod";

export const AgentIntentSchema = z.enum([
  "CLICK",
  "TYPE",
  "SUBMIT_INPUT",
  "SCROLL_UP",
  "SCROLL_DOWN",
  "SCROLL_LEFT",
  "SCROLL_RIGHT",
  "BACK",
  "HOME",
  "OPEN_APP",
  "OPEN_INTENT",
  "WAIT",
  "FINISH",
]);

export const AgentRiskLevelSchema = z.enum(["SAFE", "WARNING", "HIGH"]);
export const ObservationReasonSchema = z.enum([
  "APP_START",
  "UI_CHANGED",
  "AFTER_ACTION",
  "RECOVERY",
  "TIMEOUT",
]);

export const ObservationSourceSchema = z.enum(["SCREENSHOT", "SCREEN_RECORDING"]);

export const ActionSelectorSchema = z.object({
  package_name: z.string().default(""),
  resource_id: z.string().default(""),
  text: z.string().default(""),
  content_desc: z.string().default(""),
  class_name: z.string().default(""),
  bounds_hint_0_1000: z
    .tuple([z.number(), z.number(), z.number(), z.number()])
    .nullable(),
  node_signature: z.string().default(""),
});

export const IntentExtraValueSchema = z.union([
  z.string(),
  z.number(),
  z.boolean(),
  z.null(),
]);

export const IntentSpecSchema = z.object({
  action: z.string().min(1),
  data_uri: z.string().nullable().default(null),
  package_name: z.string().nullable().default(null),
  extras: z.record(z.string(), IntentExtraValueSchema).default({}),
});

export const ActionCheckpointSchema = z.object({
  expected_package: z.string().default(""),
  expected_page_type: z.string().default(""),
  expected_elements: z.array(z.string()).default([]),
});

// ============================================================================
// P1 视觉基座革命：Spatial Grounding 归一化坐标
// ============================================================================
// Gemini 2.0+ 的 Spatial Grounding 能力直接输出 0.0-1.0 归一化坐标，
// 彻底解决游戏、小程序、Flutter 等跨端 UI 的"致盲"问题。
//
// 坐标语义：
//   spatial_coordinates: [x, y] 其中 x, y ∈ [0.0, 1.0]
//   - (0.0, 0.0) = 屏幕左上角
//   - (1.0, 1.0) = 屏幕右下角
//   - (0.5, 0.5) = 屏幕正中心
//
// Android 端换算公式：
//   absoluteX = spatial_coordinates[0] * screenWidth
//   absoluteY = spatial_coordinates[1] * screenHeight
// ============================================================================

export const SpatialCoordinatesSchema = z
  .tuple([
    z.number().min(0).max(1), // x: 0.0 ~ 1.0
    z.number().min(0).max(1), // y: 0.0 ~ 1.0
  ])
  .describe("Gemini Spatial Grounding 归一化坐标 [x, y]，范围 0.0-1.0");

export const ActionCommandSchema = z.object({
  action_id: z.string().min(1),
  intent: AgentIntentSchema,
  target_desc: z.string().min(1),

  // ========== P1 新增：Spatial Grounding 原生坐标 ==========
  // 当 Gemini 返回纯视觉定位坐标时，此字段为首选执行路径。
  // 优先级：spatial_coordinates > target_som_id > selector > target_bbox
  spatial_coordinates: SpatialCoordinatesSchema.nullable().optional(),

  // Deprecated but kept for dual-stack Android compatibility window.
  target_bbox: z
    .tuple([z.number(), z.number(), z.number(), z.number()])
    .nullable()
    .optional(),
  target_som_id: z.number().int().positive().nullable().default(null),
  selector: ActionSelectorSchema.nullable(),
  input_text: z.string().nullable(),
  package_name: z.string().nullable(),
  intent_spec: IntentSpecSchema.nullable().default(null),
  risk_level: AgentRiskLevelSchema,
  narration: z.string().min(1),
  checkpoint: ActionCheckpointSchema.nullable().default({
    expected_package: "",
    expected_page_type: "",
    expected_elements: [],
  }),
}).superRefine((action, ctx) => {
  const allowNoCheckpoint = action.intent === "WAIT" || action.intent === "FINISH";
  if (!allowNoCheckpoint && action.checkpoint == null) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "checkpoint is required for non-WAIT/FINISH actions",
      path: ["checkpoint"],
    });
  }

  // ========== P1 放宽校验：spatial_coordinates 作为一等公民 ==========
  // 只要存在 spatial_coordinates，即使完全缺失 selector/target_som_id/target_bbox，
  // 也允许校验通过并执行 CLICK/TYPE（纯视觉盲打模式）
  if (action.intent === "CLICK" || action.intent === "TYPE") {
    const hasSpatialCoords = action.spatial_coordinates != null;
    const hasSelector = action.selector != null;
    const hasSomId = action.target_som_id != null;
    const hasBbox = action.target_bbox != null;

    if (!hasSpatialCoords && !hasSelector && !hasSomId && !hasBbox) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message:
          "CLICK/TYPE requires at least one targeting method: " +
          "spatial_coordinates (preferred), target_som_id, selector, or target_bbox",
        path: ["spatial_coordinates"],
      });
    }
  }

  if (action.intent === "TYPE" && !action.input_text?.trim()) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "input_text is required for TYPE action",
      path: ["input_text"],
    });
  }
  if (action.intent === "OPEN_APP" && !action.package_name?.trim()) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "package_name is required for OPEN_APP action",
      path: ["package_name"],
    });
  }
  if (action.intent === "OPEN_INTENT" && action.intent_spec == null) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "intent_spec is required for OPEN_INTENT action",
      path: ["intent_spec"],
    });
  }
});

export const MobileTaskSpecSchema = z.object({
  mode: z.enum(["GENERAL", "SEARCH", "RESEARCH", "HOMEWORK"]),
  search_query: z.string().default(""),
  ask_on_uncertain: z.boolean().default(true),
});

export const MobileUiNodeSchema = z.object({
  class_name: z.string(),
  text: z.string(),
  content_desc: z.string(),
  resource_id: z.string(),
  package_name: z.string(),
  bounds: z.tuple([z.number(), z.number(), z.number(), z.number()]),
  clickable: z.boolean(),
  editable: z.boolean(),
  scrollable: z.boolean(),
  visible_to_user: z.boolean().optional(),
  within_screen: z.boolean().optional(),
});

// ============================================================================
// P1/P2 媒体异步化：GCS URI 引用支持
// ============================================================================
// Android 端可选择两种媒体传输模式：
//   1. 内联模式：image_base64 直接包含图片数据（兼容旧版）
//   2. 引用模式：gcs_uri 指向 GCS 对象，云端按需拉取
//
// 引用模式优势：
//   - 绕过 Cloud Run 带宽瓶颈（Android 直传 GCS）
//   - 支持大尺寸高清截图
//   - 便于后续视频流扩展
// ============================================================================

export const GcsUriSchema = z
  .string()
  .regex(/^gs:\/\/[a-z0-9][-a-z0-9_.]*[a-z0-9]\/.*$/, "Invalid GCS URI format")
  .describe("GCS 对象 URI，格式：gs://bucket-name/path/to/object");

export const LiveFrameSchema = z.object({
  frame_id: z.string().min(1),
  ts_ms: z.number().int().nonnegative(),
  // 内联模式：直接包含 base64 图片数据
  image_base64: z.string().optional(),
  // 引用模式：指向 GCS 对象（优先使用）
  gcs_uri: GcsUriSchema.optional(),
  ui_signature: z.string().min(1),
}).superRefine((frame, ctx) => {
  // 至少需要一种媒体来源
  if (!frame.image_base64?.trim() && !frame.gcs_uri?.trim()) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "Either image_base64 or gcs_uri is required",
      path: ["image_base64"],
    });
  }
});

export const SomMarkerSchema = z.object({
  id: z.number().int().positive(),
  text: z.string().default(""),
  content_desc: z.string().default(""),
  resource_id: z.string().default(""),
  class_name: z.string().default(""),
  package_name: z.string().default(""),
  bounds: z.tuple([z.number(), z.number(), z.number(), z.number()]),
  clickable: z.boolean().default(false),
  editable: z.boolean().default(false),
  scrollable: z.boolean().default(false),
});

export const UiNodeStatsSchema = z.object({
  raw_count: z.number().int().nonnegative(),
  pruned_count: z.number().int().nonnegative(),
});

export const ObservationMediaWindowSchema = z.object({
  source: ObservationSourceSchema,
  frames: z.array(LiveFrameSchema).min(1).max(6),
});

export const MobileObservationSchema = z.object({
  observation_reason: ObservationReasonSchema,
  foreground_package: z.string(),
  media_window: ObservationMediaWindowSchema.optional(),
  // Deprecated: keep for one compatibility version during Android rollout.
  screenshot_base64: z.string().min(1).optional(),
  som_annotated_image_base64: z.string().min(1).optional(),
  som_markers: z.array(SomMarkerSchema).max(60).optional(),
  ui_node_stats: UiNodeStatsSchema.optional(),
  frame_fingerprint: z.string().min(1).optional(),
  ui_nodes: z.array(MobileUiNodeSchema),
  previous_action_result: z.enum(["SUCCESS", "FAILED", "NOT_EXECUTED"]),
  previous_checkpoint_match: z.boolean(),
}).superRefine((observation, ctx) => {
  const hasMediaWindow = !!observation.media_window?.frames?.length;
  const hasLegacyScreenshot = !!observation.screenshot_base64?.trim();
  if (!hasMediaWindow && !hasLegacyScreenshot) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ["media_window"],
      message: "media_window or screenshot_base64 is required",
    });
  }
});

export const MobileHistoryItemSchema = z.object({
  action_intent: z.string(),
  target_desc: z.string(),
  result: z.string(),
});

export const NextStepRequestSchema = z.object({
  session_id: z.string().min(1),
  turn_index: z.number().int().min(0),
  mode: z.enum(["shadow", "active"]),
  goal: z.string().min(1),
  task_spec: MobileTaskSpecSchema,
  observation: MobileObservationSchema,
  history_tail: z.array(MobileHistoryItemSchema),
  shadow_control_action: ActionCommandSchema.optional(),
});

export const PlannerOutputSchema = z.object({
  candidates: z.array(ActionCommandSchema).min(1).max(3),
});

export const ReviewerOutputSchema = z.object({
  verdict: z.enum(["APPROVE", "REPLAN", "ESCALATE"]),
  reason: z.string().min(1),
  approved_action_index: z.number().int().min(0).max(2).nullable(),
});

export const NextStepResponseSchema = z.object({
  trace_id: z.string().min(1),
  planner: z.object({
    model: z.string().min(1),
    candidates: z.array(ActionCommandSchema).max(3),
    latency_ms: z.number().int().nonnegative(),
  }),
  reviewer: z.object({
    model: z.string().min(1),
    verdict: z.enum(["APPROVE", "REPLAN", "ESCALATE"]),
    reason: z.string().min(1),
    approved_action_index: z.number().int().nullable(),
    latency_ms: z.number().int().nonnegative(),
  }),
  final_action: ActionCommandSchema,
  checkpoint: ActionCheckpointSchema,
  guard: z.object({
    risk_level: AgentRiskLevelSchema,
    block_reason: z.string().nullable(),
  }),
  live_runtime: z.object({
    used_live: z.literal(true),
    model: z.string().min(1),
    connect_latency_ms: z.number().int().nonnegative(),
    inference_latency_ms: z.number().int().nonnegative(),
  }),
  guide_media: z.object({
    type: z.literal("IMAGE"),
    storage_uri: z.string().min(1),
    signed_url: z.string().min(1),
    expires_at: z.string().datetime({ offset: true }),
  }).nullable(),
});

const FORBIDDEN_TELEMETRY_KEYS = [
  "screenshot_base64",
  "image_base64",
  "frame_base64",
  "screen_base64",
  "ui_tree",
  "ui_tree_text",
  "ui_nodes",
  "accessibility_tree",
  "gcs_uri",
  "inline_data",
];

// Max allowed string length in telemetry payloads (prevents base64 blobs sneaking in)
const MAX_TELEMETRY_STRING_LENGTH = 2048;

function findForbiddenTelemetryKey(value: unknown): string | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findForbiddenTelemetryKey(item);
      if (found) return found;
    }
    return null;
  }
  if (typeof value === "string" && value.length > MAX_TELEMETRY_STRING_LENGTH) {
    return `<string_too_long:${value.length}>`;
  }
  if (value && typeof value === "object") {
    for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
      const normalized = key.trim().toLowerCase();
      if (FORBIDDEN_TELEMETRY_KEYS.includes(normalized)) {
        return key;
      }
      const found = findForbiddenTelemetryKey(child);
      if (found) return found;
    }
  }
  return null;
}

export const TelemetryEventSchema = z.object({
  trace_id: z.string().min(1),
  session_id: z.string().min(1),
  turn_index: z.number().int().min(0),
  event_type: z.string().min(1),
  payload: z.record(z.string(), z.any()),
  ts: z.string().datetime({ offset: true }).optional(),
}).superRefine((event, ctx) => {
  const forbiddenKey = findForbiddenTelemetryKey(event.payload);
  if (!forbiddenKey) return;
  ctx.addIssue({
    code: z.ZodIssueCode.custom,
    path: ["payload"],
    message: `telemetry payload contains forbidden key: ${forbiddenKey}`,
  });
});

export const TelemetryBatchRequestSchema = z.object({
  events: z.array(TelemetryEventSchema).min(1).max(200),
});

export type ActionCommand = z.infer<typeof ActionCommandSchema>;
export type NextStepRequest = z.infer<typeof NextStepRequestSchema>;
export type NextStepResponse = z.infer<typeof NextStepResponseSchema>;
export type PlannerOutput = z.infer<typeof PlannerOutputSchema>;
export type ReviewerOutput = z.infer<typeof ReviewerOutputSchema>;
export type TelemetryEvent = z.infer<typeof TelemetryEventSchema>;
export type MobileObservation = z.infer<typeof MobileObservationSchema>;
export type LiveFrame = z.infer<typeof LiveFrameSchema>;

/**
 * P1/P2 媒体异步化：从 LiveFrame 获取图片数据
 *
 * 优先级：gcs_uri > image_base64
 * 当使用 gcs_uri 时，需要额外调用 resolveGcsUri 获取实际图片数据
 */
export function pickLatestObservationFrame(
  observation: MobileObservation,
): {
  imageBase64: string | null;
  gcsUri: string | null;
  source: z.infer<typeof ObservationSourceSchema>;
  frameCount: number;
  useGcsReference: boolean;
} {
  const frames = observation.media_window?.frames ?? [];
  if (frames.length > 0) {
    const latest = frames[frames.length - 1];
    const hasGcsUri = !!latest.gcs_uri?.trim();
    return {
      imageBase64: latest.image_base64 ?? null,
      gcsUri: latest.gcs_uri ?? null,
      source: observation.media_window?.source ?? "SCREENSHOT",
      frameCount: frames.length,
      useGcsReference: hasGcsUri,
    };
  }
  return {
    imageBase64: observation.screenshot_base64 ?? null,
    gcsUri: null,
    source: "SCREENSHOT",
    frameCount: observation.screenshot_base64 ? 1 : 0,
    useGcsReference: false,
  };
}
