import { ActionCommand } from "@/lib/schemas/mobile-agent";

export type ShadowDiff = {
  same_intent: boolean;
  same_target_desc: boolean;
  candidate_would_fail_reason: string | null;
};

export function compareShadowActions(
  control: ActionCommand | undefined,
  candidate: ActionCommand,
): ShadowDiff | null {
  if (!control) return null;
  const sameIntent = control.intent === candidate.intent;
  const sameTargetDesc = control.target_desc.trim() === candidate.target_desc.trim();

  let reason: string | null = null;
  if (!sameIntent) {
    reason = `intent_diff:${control.intent}->${candidate.intent}`;
  } else if (!sameTargetDesc) {
    reason = "target_desc_diff";
  } else if (candidate.risk_level === "HIGH") {
    reason = "candidate_high_risk";
  }

  return {
    same_intent: sameIntent,
    same_target_desc: sameTargetDesc,
    candidate_would_fail_reason: reason,
  };
}

