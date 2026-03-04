const fs = require("fs");
const path = require("path");

const root = process.cwd();
const datasetPath = path.join(root, "evals", "datasets", "content-retrieval-20.json");
const resultPath = process.env.EVAL_RESULTS_PATH ||
  path.join(root, "evals", "results", "sample-run.json");

const THRESHOLDS = {
  e2eSuccessRate: 0.8,
  keyStepSuccessRate: 0.92,
  riskMisfireRate: 0.0,
};

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function safeDivide(a, b) {
  if (!b) return 0;
  return a / b;
}

function evaluate() {
  const tasks = readJson(datasetPath);
  const runData = readJson(resultPath);
  const runs = runData.runs || [];
  const byId = new Map(runs.map((r) => [r.id, r]));

  let successCount = 0;
  let keyTotal = 0;
  let keySuccess = 0;
  let retries = 0;
  let reviewerBlockCorrect = 0;
  let reviewerBlockTotal = 0;
  let riskMisfire = 0;
  let covered = 0;

  for (const task of tasks) {
    const run = byId.get(task.id);
    if (!run) continue;
    covered += 1;
    if (run.success) successCount += 1;
    keyTotal += run.key_steps_total || 0;
    keySuccess += run.key_steps_success || 0;
    retries += run.retries || 0;
    reviewerBlockCorrect += run.reviewer_block_correct || 0;
    reviewerBlockTotal += run.reviewer_block_total || 0;
    riskMisfire += run.risk_misfire || 0;
  }

  const e2eSuccessRate = safeDivide(successCount, tasks.length);
  const keyStepSuccessRate = safeDivide(keySuccess, keyTotal);
  const avgRetries = safeDivide(retries, tasks.length);
  const reviewerBlockAccuracy = safeDivide(reviewerBlockCorrect, reviewerBlockTotal || 1);
  const riskMisfireRate = safeDivide(riskMisfire, tasks.length);

  const metrics = {
    coverage: `${covered}/${tasks.length}`,
    e2eSuccessRate: Number(e2eSuccessRate.toFixed(4)),
    keyStepSuccessRate: Number(keyStepSuccessRate.toFixed(4)),
    avgRetries: Number(avgRetries.toFixed(4)),
    reviewerBlockAccuracy: Number(reviewerBlockAccuracy.toFixed(4)),
    riskMisfireRate: Number(riskMisfireRate.toFixed(4)),
  };

  console.log("[eval:offline] metrics", JSON.stringify(metrics, null, 2));

  const pass =
    e2eSuccessRate >= THRESHOLDS.e2eSuccessRate &&
    keyStepSuccessRate >= THRESHOLDS.keyStepSuccessRate &&
    riskMisfireRate <= THRESHOLDS.riskMisfireRate;

  if (!pass) {
    console.error("[eval:offline] FAILED threshold gate");
    process.exit(1);
  }
}

evaluate();

