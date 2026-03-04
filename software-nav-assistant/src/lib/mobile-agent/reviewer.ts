import { buildReviewerPrompt } from "@/lib/mobile-agent/prompts";
import { generateJsonWithImage } from "@/lib/mobile-agent/model-client";
import {
  ActionCommand,
  NextStepRequest,
  pickLatestObservationFrame,
  ReviewerOutput,
  ReviewerOutputSchema,
} from "@/lib/schemas/mobile-agent";
import { REVIEWER_MODEL } from "@/lib/mobile-agent/env";

/** Safe fallback: when reviewer model fails, default to REPLAN so planner retries. */
const REVIEWER_SAFE_FALLBACK: ReviewerOutput = {
  verdict: "REPLAN",
  reason: "reviewer_model_unavailable_fallback",
  approved_action_index: null,
};

export async function runReviewer(
  request: NextStepRequest,
  candidates: ActionCommand[],
): Promise<{ model: string; output: ReviewerOutput; latencyMs: number }> {
  const latestFrame = pickLatestObservationFrame(request.observation);
  if (!latestFrame.imageBase64) {
    throw new Error("reviewer requires at least one observation frame");
  }
  const prompt = buildReviewerPrompt(request, candidates);
  const { output, latencyMs } = await generateJsonWithImage({
    model: REVIEWER_MODEL,
    prompt,
    imageBase64: latestFrame.imageBase64,
    schema: ReviewerOutputSchema,
    safeFallback: REVIEWER_SAFE_FALLBACK,
  });
  return { model: REVIEWER_MODEL, output, latencyMs };
}
