# Mobile Agent Eval

## Current status: scaffold only

These scripts are gate **wiring**, not a real evaluation harness yet:

- `evals/results/sample-run.json` is a hand-written static fixture. No agent
  is executed anywhere in this pipeline.
- `offline-eval.js` scores that fixture against the dataset thresholds and
  prints a `SCAFFOLD WARNING` to make this explicit.
- `regression-eval.js` compares a baseline run with a candidate run. If both
  paths resolve to the same file it exits non-zero, unless `--allow-same` is
  passed (CI passes it and the script prints a `SCAFFOLD WARNING`), because a
  self-comparison can never detect a regression.
- The CI jobs in `.github/workflows/mobile-agent-ci.yml` are named
  accordingly ("static fixture scaffold" / "baseline == candidate") so green
  checks are not mistaken for real model regression coverage.

## Dataset

- `datasets/content-retrieval-20.json`: First-wave 20 tasks for
  content-retrieval scenarios.

## Run

- `npm run eval:offline`
- `npm run eval:regression` (add `-- --allow-same` when intentionally
  comparing a file against itself, e.g. to smoke-test the gate)

## Thresholds

- E2E success rate >= 0.80
- Key-step success rate >= 0.92
- High-risk misfire rate == 0

## Inputs

- Default scripts read `evals/results/sample-run.json`.
- Use `EVAL_RESULTS_PATH`, `BASELINE_RESULTS_PATH`, `CANDIDATE_RESULTS_PATH`
  to override inputs.

## What is missing to make this a real harness

1. **A runner that produces results**: execute the agent (device farm or
   emulator + the Android client, or a replay harness over recorded UI
   traces) against each dataset task and emit a run file in the
   `{ "runs": [{ id, success, key_steps_total, key_steps_success, retries,
   reviewer_block_correct, reviewer_block_total, risk_misfire }] }` shape the
   scripts already consume.
2. **Baseline management**: persist the run file of the main branch (CI
   artifact or GCS object) and feed it to `regression-eval.js` via
   `BASELINE_RESULTS_PATH`, with the PR run as `CANDIDATE_RESULTS_PATH`;
   then drop `--allow-same` from CI.
3. **Grading**: success/key-step labels must come from automated checkers
   (screen assertions, expected-answer matching) instead of being hand-typed
   in the fixture.
4. **Statistical headroom**: 20 tasks cannot distinguish a 3pp drop from
   noise; grow the dataset and/or run multiple seeds before trusting the
   `maxDrop` gate.
