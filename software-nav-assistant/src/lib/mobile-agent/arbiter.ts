import {
  ActionCommand,
  NextStepRequest,
  ReviewerOutput,
} from "@/lib/schemas/mobile-agent";
import {
  containsAnyHint,
  SEARCH_INTENT_HINTS,
  SEARCH_SUBMIT_HINTS,
} from "@/lib/mobile-agent/i18n";

export type ArbiterResult = {
  finalAction: ActionCommand;
  blockReason: string | null;
};

// ============================================================================
// clampBbox: clamp (ymin, xmin, ymax, xmax) into the [0, 1000] range
// Prevent out-of-bounds coordinates from causing bad taps or rendering issues
// ============================================================================
export function clampBbox(
  bbox: [number, number, number, number],
): [number, number, number, number] {
  const clamp = (v: number) => Math.max(0, Math.min(1000, Math.round(v)));
  const [ymin, xmin, ymax, xmax] = bbox;
  const cYmin = clamp(ymin);
  const cXmin = clamp(xmin);
  const cYmax = clamp(ymax);
  const cXmax = clamp(xmax);
  // Ensure min < max after clamping
  return [
    Math.min(cYmin, cYmax),
    Math.min(cXmin, cXmax),
    Math.max(cYmin, cYmax),
    Math.max(cXmin, cXmax),
  ];
}

/**
 * Clamp an ActionCommand's bbox fields in-place and return the sanitized action.
 * Applies to target_bbox and selector.bounds_hint_0_1000.
 */
export function clampActionBbox(action: ActionCommand): ActionCommand {
  const clamped = { ...action };
  if (clamped.target_bbox) {
    clamped.target_bbox = clampBbox(clamped.target_bbox as [number, number, number, number]);
  }
  if (clamped.selector?.bounds_hint_0_1000) {
    clamped.selector = {
      ...clamped.selector,
      bounds_hint_0_1000: clampBbox(
        clamped.selector.bounds_hint_0_1000 as [number, number, number, number],
      ),
    };
  }
  return clamped;
}

type MobileUiNode = NextStepRequest["observation"]["ui_nodes"][number];
type IntentSpec = NonNullable<ActionCommand["intent_spec"]>;

const ALLOWED_INTENT_ACTIONS = new Set([
  "android.intent.action.view",
  "android.intent.action.search",
  "android.intent.action.web_search",
]);
const ALLOWED_INTENT_PACKAGES = new Set([
  "com.tencent.mm",
  "com.ss.android.ugc.aweme",
  "com.taobao.taobao",
  "tv.danmaku.bili",
  "com.xingin.xhs",
  "com.google.android.youtube",
  "com.android.chrome",
  "com.google.android.googlequicksearchbox",
]);

function buildWaitAction(
  _reason: string,
  risk: "SAFE" | "WARNING" | "HIGH" = "SAFE",
): ActionCommand {
  return {
    action_id: `wait_${Date.now()}`,
    intent: "WAIT",
    target_desc: "wait",
    target_som_id: null,
    selector: null,
    input_text: null,
    package_name: null,
    intent_spec: null,
    risk_level: risk,
    narration: "I will wait for a safer next step.",
    checkpoint: null,
  };
}

function hasIntent(request: NextStepRequest, intent: string): boolean {
  return request.history_tail.some((item) => item.action_intent === intent);
}

function extractSearchQuery(request: NextStepRequest): string {
  const direct = request.task_spec.search_query.trim();
  if (direct) return direct;

  const goal = request.goal.trim();
  if (!goal) return "";

  const quoted = goal.match(/["'“”‘’]([^"'“”‘’]{1,80})["'“”‘’]/u)?.[1]?.trim();
  if (quoted) return quoted;

  const cn = goal.match(/(?:搜索|查找|查询)\s*[:：]?\s*([^\s,，。?!！？]{1,80})/u)?.[1]?.trim();
  if (cn) return cn;

  const en = goal.match(/(?:search|find|lookup)\s+([^\s,，。?!！？]{1,80})/iu)?.[1]?.trim();
  return en || "";
}

function toSelector(node: MobileUiNode): NonNullable<ActionCommand["selector"]> {
  return {
    package_name: node.package_name,
    resource_id: node.resource_id,
    text: node.text,
    content_desc: node.content_desc,
    class_name: node.class_name,
    bounds_hint_0_1000: null,
    node_signature: `${node.package_name}|${node.class_name}|${node.resource_id}|${node.text}|${node.content_desc}`
      .slice(0, 256),
  };
}

function defaultCheckpoint(request: NextStepRequest): NonNullable<ActionCommand["checkpoint"]> {
  return {
    expected_package: request.observation.foreground_package,
    expected_page_type: "SEARCH",
    expected_elements: [],
  };
}

function scoreSearchInputNode(node: MobileUiNode): number {
  let score = 0;
  const hintText = `${node.text} ${node.content_desc} ${node.resource_id}`;
  const className = node.class_name.toLowerCase();

  if (node.editable) score += 5;
  if (node.clickable) score += 1;
  if (containsAnyHint(hintText, SEARCH_INTENT_HINTS)) score += 4;
  if (
    className.includes("edittext") ||
    className.includes("searchview") ||
    className.includes("textfield") ||
    className.includes("input")
  ) {
    score += 2;
  }

  return score;
}

function findSearchInputNode(request: NextStepRequest): MobileUiNode | null {
  const nodes = request.observation.ui_nodes;
  let best: MobileUiNode | null = null;
  let bestScore = 0;

  for (const node of nodes) {
    const score = scoreSearchInputNode(node);
    if (score > bestScore) {
      best = node;
      bestScore = score;
    }
  }

  return bestScore >= 5 ? best : null;
}

function findSearchSubmitNode(request: NextStepRequest): MobileUiNode | null {
  const nodes = request.observation.ui_nodes;
  let best: MobileUiNode | null = null;
  let bestScore = 0;

  for (const node of nodes) {
    if (!node.clickable) continue;
    const text = `${node.text} ${node.content_desc} ${node.resource_id}`;
    if (!containsAnyHint(text, SEARCH_SUBMIT_HINTS)) continue;

    const score = 1 + (node.resource_id ? 1 : 0) + (node.text ? 1 : 0);
    if (score > bestScore) {
      best = node;
      bestScore = score;
    }
  }

  return best;
}

function buildSearchTypeAction(request: NextStepRequest, node: MobileUiNode): ActionCommand | null {
  const query = extractSearchQuery(request);
  if (!query) return null;

  return {
    action_id: `search_type_${Date.now()}`,
    intent: "TYPE",
    target_desc: "type search query",
    target_som_id: null,
    selector: toSelector(node),
    input_text: query,
    package_name: null,
    intent_spec: null,
    risk_level: "SAFE",
    narration: "Typing the search query now.",
    checkpoint: {
      ...defaultCheckpoint(request),
      expected_elements: [query],
    },
  };
}

function buildSearchFocusAction(request: NextStepRequest, node: MobileUiNode): ActionCommand {
  return {
    action_id: `search_focus_${Date.now()}`,
    intent: "CLICK",
    target_desc: "focus search box",
    target_som_id: null,
    selector: toSelector(node),
    input_text: null,
    package_name: null,
    intent_spec: null,
    risk_level: "SAFE",
    narration: "Opening the search box first.",
    checkpoint: defaultCheckpoint(request),
  };
}

function buildSearchSubmitAction(request: NextStepRequest): ActionCommand {
  const query = extractSearchQuery(request);
  const submitNode = findSearchSubmitNode(request);

  if (submitNode) {
    return {
      action_id: `search_submit_click_${Date.now()}`,
      intent: "CLICK",
      target_desc: "submit search",
      target_som_id: null,
      selector: toSelector(submitNode),
      input_text: null,
      package_name: null,
      intent_spec: null,
      risk_level: "SAFE",
      narration: "Submitting the search now.",
      checkpoint: {
        ...defaultCheckpoint(request),
        expected_elements: query ? [query] : [],
      },
    };
  }

  return {
    action_id: `search_submit_ime_${Date.now()}`,
    intent: "SUBMIT_INPUT",
    target_desc: "submit search via keyboard",
    target_som_id: null,
    selector: null,
    input_text: null,
    package_name: null,
    intent_spec: null,
    risk_level: "SAFE",
    narration: "Submitting from keyboard action.",
    checkpoint: {
      ...defaultCheckpoint(request),
      expected_elements: query ? [query] : [],
    },
  };
}

function buildSearchRecoveryAction(
  request: NextStepRequest,
  reason: string,
): ActionCommand | null {
  const typed = hasIntent(request, "TYPE");
  const submitted = hasIntent(request, "SUBMIT_INPUT");

  if (!typed && (
    reason === "search_focus_missing" ||
    reason === "search_stage_before_type" ||
    reason === "search_type_missing"
  )) {
    const inputNode = findSearchInputNode(request);
    if (!inputNode) return null;
    return buildSearchTypeAction(request, inputNode) ?? buildSearchFocusAction(request, inputNode);
  }

  if (typed && !submitted && (
    reason === "search_stage_before_submit" ||
    reason === "search_submit_missing"
  )) {
    return buildSearchSubmitAction(request);
  }

  return null;
}

function normalizeIntentAction(action: string | null | undefined): string {
  return (action || "").trim().toLowerCase();
}

function intentSpecContainsQuery(spec: IntentSpec | null, query: string): boolean {
  if (!spec || !query) return false;
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) return false;
  const haystack = [
    spec.data_uri || "",
    ...Object.values(spec.extras || {}).map((value) => String(value ?? "")),
  ]
    .join(" ")
    .toLowerCase();
  return haystack.includes(normalizedQuery);
}

function getActionSearchHintText(
  request: NextStepRequest,
  action: ActionCommand,
): string {
  const parts: string[] = [action.target_desc];
  if (action.selector) {
    parts.push(action.selector.text || "");
    parts.push(action.selector.content_desc || "");
    parts.push(action.selector.resource_id || "");
  }
  if (action.target_som_id != null) {
    const marker = request.observation.som_markers?.find((item) => item.id === action.target_som_id);
    if (marker) {
      parts.push(marker.text || "");
      parts.push(marker.content_desc || "");
      parts.push(marker.resource_id || "");
      parts.push(marker.class_name || "");
    }
  }
  return parts.join(" ");
}

function validateOpenIntentAction(
  request: NextStepRequest,
  action: ActionCommand,
): string | null {
  if (action.intent !== "OPEN_INTENT") return null;
  if (!action.intent_spec) return "open_intent_missing_spec";

  const normalizedAction = normalizeIntentAction(action.intent_spec.action);
  if (!ALLOWED_INTENT_ACTIONS.has(normalizedAction)) {
    return "open_intent_action_not_allowed";
  }
  const pkg = (action.intent_spec.package_name || action.package_name || "").trim();
  if (pkg && !ALLOWED_INTENT_PACKAGES.has(pkg)) {
    return "open_intent_package_not_allowed";
  }
  const query = extractSearchQuery(request);

  if (
    request.task_spec.mode === "SEARCH" &&
    query &&
    !intentSpecContainsQuery(action.intent_spec, query)
  ) {
    return "open_intent_missing_search_query";
  }

  return null;
}

function validateSearchAction(
  request: NextStepRequest,
  action: ActionCommand,
): string | null {
  if (request.task_spec.mode !== "SEARCH") return null;
  if (action.intent === "OPEN_INTENT") {
    return validateOpenIntentAction(request, action);
  }

  const typed = hasIntent(request, "TYPE") || action.intent === "TYPE";
  const submitted = hasIntent(request, "SUBMIT_INPUT") || action.intent === "SUBMIT_INPUT";

  if (!typed && action.intent === "FINISH") return "search_type_missing";
  if (!submitted && action.intent === "FINISH") return "search_submit_missing";

  if (!typed) {
    const allowed = new Set(["OPEN_APP", "OPEN_INTENT", "CLICK", "TYPE", "WAIT"]);
    if (!allowed.has(action.intent)) return "search_stage_before_type";
    if (action.intent === "CLICK") {
      const hint = getActionSearchHintText(request, action);
      if (!containsAnyHint(hint, SEARCH_INTENT_HINTS)) {
        return "search_focus_missing";
      }
    }
  } else if (!submitted) {
    const allowed = new Set(["SUBMIT_INPUT", "TYPE", "OPEN_INTENT", "CLICK", "WAIT"]);
    if (!allowed.has(action.intent)) return "search_stage_before_submit";
  }

  return null;
}

export function arbitrateDecision(
  request: NextStepRequest,
  candidates: ActionCommand[],
  reviewer: ReviewerOutput,
  replanExhausted: boolean,
): ArbiterResult {
  if (reviewer.verdict === "ESCALATE") {
    return {
      finalAction: buildWaitAction("escalated_by_reviewer", "HIGH"),
      blockReason: reviewer.reason || "escalated_by_reviewer",
    };
  }

  if (reviewer.verdict === "APPROVE") {
    const idx = reviewer.approved_action_index ?? -1;
    const approved = candidates[idx];
    if (!approved) {
      return {
        finalAction: buildWaitAction("invalid_approved_index"),
        blockReason: "invalid_approved_index",
      };
    }
    if (approved.risk_level === "HIGH") {
      return {
        finalAction: buildWaitAction("high_risk_blocked", "HIGH"),
        blockReason: "high_risk_blocked",
      };
    }
    const openIntentIssue = validateOpenIntentAction(request, approved);
    if (openIntentIssue) {
      return {
        finalAction: buildWaitAction(openIntentIssue),
        blockReason: openIntentIssue,
      };
    }
    const searchGuard = validateSearchAction(request, approved);
    if (searchGuard) {
      const recovery = buildSearchRecoveryAction(request, searchGuard);
      if (recovery) {
        return {
          finalAction: clampActionBbox(recovery),
          blockReason: null,
        };
      }
      return {
        finalAction: buildWaitAction(searchGuard),
        blockReason: searchGuard,
      };
    }
    return { finalAction: clampActionBbox(approved), blockReason: null };
  }

  if (replanExhausted) {
    return {
      finalAction: buildWaitAction("replan_exhausted_manual_required"),
      blockReason: "replan_exhausted_manual_required",
    };
  }

  return {
    finalAction: buildWaitAction("replan_requested"),
    blockReason: "replan_requested",
  };
}
