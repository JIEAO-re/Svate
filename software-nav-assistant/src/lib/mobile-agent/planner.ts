import {
  NextStepRequest,
  PlannerOutput,
} from "@/lib/schemas/mobile-agent";
import {
  runLivePlannerTurn,
  type FrameWindowOptions,
} from "@/lib/mobile-agent/live-turn-client";

export async function runPlanner(
  request: NextStepRequest,
  options: FrameWindowOptions = {},
): Promise<{
  model: string;
  output: PlannerOutput;
  latencyMs: number;
  connectLatencyMs: number;
  inferenceLatencyMs: number;
  frameCount: number;
  gcsFrameCount: number;
  usedLive: boolean;
}> {
  const live = await runLivePlannerTurn(request, options);
  return {
    model: live.model,
    output: live.output,
    latencyMs: live.connectLatencyMs + live.inferenceLatencyMs,
    connectLatencyMs: live.connectLatencyMs,
    inferenceLatencyMs: live.inferenceLatencyMs,
    frameCount: live.frameCount,
    gcsFrameCount: live.gcsFrameCount,
    usedLive: live.usedLive,
  };
}
