# Mobile Agent Eval

## Dataset
- `datasets/content-retrieval-20.json`: First-wave 20 tasks for content-retrieval scenarios.

## Run
- `npm run eval:offline`
- `npm run eval:regression`

## Thresholds
- E2E success rate >= 0.80
- Key-step success rate >= 0.92
- High-risk misfire rate == 0

## Notes
- Default scripts read `evals/results/sample-run.json`.
- Use `EVAL_RESULTS_PATH`, `BASELINE_RESULTS_PATH`, `CANDIDATE_RESULTS_PATH` to override inputs.

