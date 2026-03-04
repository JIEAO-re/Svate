import { ActionCommand, NextStepRequest } from "@/lib/schemas/mobile-agent";
import { SEARCH_INTENT_HINTS, SEARCH_SUBMIT_HINTS } from "@/lib/mobile-agent/i18n";

export function buildPlannerPrompt(request: NextStepRequest): string {
  const nodes = request.observation.ui_nodes.slice(0, 80).map((n, idx) => {
    return `[${idx}] pkg=${n.package_name} cls=${n.class_name} rid=${n.resource_id} text=${n.text} desc=${n.content_desc} bounds=${n.bounds.join(",")} clickable=${n.clickable}`;
  });

  const history = request.history_tail
    .slice(-8)
    .map((item, idx) => `${idx + 1}. ${item.action_intent} | ${item.target_desc} | ${item.result}`)
    .join("\n");
  const frameCount = request.observation.media_window?.frames.length ?? (request.observation.screenshot_base64 ? 1 : 0);
  const observationSource = request.observation.media_window?.source ?? "SCREENSHOT";

  return `
You are Planner for a mobile AI agent. Generate 1 to 3 candidate actions only.
Do not output final decision, only candidates.

Output contract:
- Strict JSON only.
- Every candidate must include:
  action_id, intent, target_desc, selector, input_text, package_name, risk_level, narration, checkpoint.
- checkpoint is required for all intents except WAIT and FINISH.

Safety:
- Any payment/password/transfer/authorization/delete action must be HIGH risk.
- If uncertain, use WAIT.
- Never output blind bbox-only behavior.

Search hard constraints (mode=SEARCH):
- Required sequence: OPEN_APP (if needed) -> CLICK search box -> TYPE query -> SUBMIT_INPUT -> OPEN first result.
- Do not emit FINISH until TYPE and SUBMIT_INPUT are completed and a result page is opened.
- TYPE must carry non-empty input_text.
- CLICK/TYPE must provide selector.
- Search box hints: ${SEARCH_INTENT_HINTS.join(", ")}.
- Submit hints: ${SEARCH_SUBMIT_HINTS.join(", ")}.

Return strict JSON:
{
  "candidates": [ActionCommand, ...]
}

Task:
- goal: ${request.goal}
- mode: ${request.task_spec.mode}
- search_query: ${request.task_spec.search_query}
- ask_on_uncertain: ${request.task_spec.ask_on_uncertain}
- turn_index: ${request.turn_index}
- observation_reason: ${request.observation.observation_reason}
- observation_source: ${observationSource}
- frame_count: ${frameCount}
- previous_action_result: ${request.observation.previous_action_result}
- previous_checkpoint_match: ${request.observation.previous_checkpoint_match}
- foreground_package: ${request.observation.foreground_package}

History tail:
${history || "(empty)"}

UI nodes:
${nodes.join("\n")}
  `.trim();
}

export function buildReviewerPrompt(
  request: NextStepRequest,
  candidates: ActionCommand[],
): string {
  const hasType = request.history_tail.some((item) => item.action_intent === "TYPE");
  const hasSubmit = request.history_tail.some((item) => item.action_intent === "SUBMIT_INPUT");
  const frameCount = request.observation.media_window?.frames.length ?? (request.observation.screenshot_base64 ? 1 : 0);
  const observationSource = request.observation.media_window?.source ?? "SCREENSHOT";
  const candidateText = candidates
    .map((c, i) => {
      return `${i}. intent=${c.intent}, desc=${c.target_desc}, risk=${c.risk_level}, selector=${JSON.stringify(c.selector)}, input=${JSON.stringify(c.input_text)}, checkpoint=${JSON.stringify(c.checkpoint)}`;
    })
    .join("\n");

  return `
You are Reviewer for a mobile AI agent. Review candidates independently.
Rules:
- APPROVE only if one candidate is safe and executable on current screen.
- ESCALATE for high-risk actions (payment/password/transfer/authorization/delete) or unsafe uncertainty.
- REPLAN if candidates are low quality but not high risk.
- APPROVE exactly one index when verdict is APPROVE.
- Reject any candidate with missing required fields for its intent.
- For mode=SEARCH:
  - If TYPE has not happened, do not allow FINISH.
  - If SUBMIT_INPUT has not happened, do not allow FINISH.
  - Prefer actions that move the chain toward TYPE -> SUBMIT_INPUT -> open result.

Return strict JSON:
{
  "verdict": "APPROVE" | "REPLAN" | "ESCALATE",
  "reason": "short reason",
  "approved_action_index": 0 | 1 | 2 | null
}

Context:
- goal: ${request.goal}
- mode: ${request.task_spec.mode}
- foreground_package: ${request.observation.foreground_package}
- observation_reason: ${request.observation.observation_reason}
- observation_source: ${observationSource}
- frame_count: ${frameCount}
- previous_action_result: ${request.observation.previous_action_result}
- previous_checkpoint_match: ${request.observation.previous_checkpoint_match}
- history_has_type: ${hasType}
- history_has_submit: ${hasSubmit}

Candidates:
${candidateText}
  `.trim();
}
