const fs = require("fs");
const path = require("path");

const root = process.cwd();
const baselinePath = process.env.BASELINE_RESULTS_PATH ||
  path.join(root, "evals", "results", "sample-run.json");
const candidatePath = process.env.CANDIDATE_RESULTS_PATH ||
  path.join(root, "evals", "results", "sample-run.json");
const maxDrop = 0.03; // 3pp

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function toSuccessMap(result) {
  const map = new Map();
  for (const run of result.runs || []) {
    map.set(run.id, !!run.success);
  }
  return map;
}

function evaluateRegression() {
  const baseline = toSuccessMap(readJson(baselinePath));
  const candidate = toSuccessMap(readJson(candidatePath));

  const ids = Array.from(new Set([...baseline.keys(), ...candidate.keys()]));
  let baseSuccess = 0;
  let candidateSuccess = 0;

  for (const id of ids) {
    if (baseline.get(id)) baseSuccess += 1;
    if (candidate.get(id)) candidateSuccess += 1;
  }

  const baseRate = ids.length ? baseSuccess / ids.length : 0;
  const candidateRate = ids.length ? candidateSuccess / ids.length : 0;
  const drop = baseRate - candidateRate;

  console.log(
    "[eval:regression]",
    JSON.stringify(
      {
        tasks: ids.length,
        baselineRate: Number(baseRate.toFixed(4)),
        candidateRate: Number(candidateRate.toFixed(4)),
        drop: Number(drop.toFixed(4)),
      },
      null,
      2,
    ),
  );

  if (drop > maxDrop) {
    console.error(`[eval:regression] FAILED, drop=${drop.toFixed(4)} > ${maxDrop}`);
    process.exit(1);
  }
}

evaluateRegression();

