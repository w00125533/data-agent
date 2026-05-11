#!/bin/bash
# M6 — E2E Eval Regression Check
# Compares current eval results against baseline, blocks if >= 5% regression.
set -euo pipefail

BASELINE_FILE="${BASELINE_FILE:-target/eval-report/baseline.json}"
CURRENT_FILE="${CURRENT_FILE:-target/eval-report/eval-report.json}"

echo "=== M6 E2E Eval Regression Check ==="

# Run eval suite
mvn test -P eval -q 2>&1 | tail -5

# Find the generated report
CURRENT=$(find target/eval-report -name "eval-report.json" -type f 2>/dev/null | head -1)
if [ -z "$CURRENT" ]; then
    echo "[FAIL] No eval report generated. Check E2EEvalSetTest output."
    exit 1
fi

echo "[INFO] Current report: $CURRENT"

if [ ! -f "$BASELINE_FILE" ]; then
    echo "[INFO] No baseline found at $BASELINE_FILE. Creating baseline from current run."
    cp "$CURRENT" "$BASELINE_FILE"
    echo "[PASS] Baseline created. No regression check on first run."
    exit 0
fi

echo "[INFO] Baseline: $BASELINE_FILE"

# Extract scores using grep/sed (lightweight alternative to jq)
CURRENT_SCORE=$(grep -o '"average_score" : [0-9.]*' "$CURRENT" | head -1 | awk '{print $3}')
BASELINE_SCORE=$(grep -o '"average_score" : [0-9.]*' "$BASELINE_FILE" | head -1 | awk '{print $3}')

if [ -z "$CURRENT_SCORE" ] || [ -z "$BASELINE_SCORE" ]; then
    echo "[FAIL] Could not extract scores from reports."
    exit 1
fi

# Calculate regression using awk
REGRESSION=$(awk -v curr="$CURRENT_SCORE" -v base="$BASELINE_SCORE" 'BEGIN { printf "%.4f", (base - curr) / base }')

echo "  Baseline score: $BASELINE_SCORE"
echo "  Current score:  $CURRENT_SCORE"
echo "  Regression:     $(awk -v r="$REGRESSION" 'BEGIN { printf "%.2f%%\n", r * 100 }')"

# Block if regression >= 5%
if awk -v r="$REGRESSION" 'BEGIN { exit (r >= 0.05) ? 1 : 0 }'; then
    echo "[PASS] Regression within acceptable range (< 5%)."
else
    echo "[FAIL] Regression >= 5%! Blocking merge."
    echo "  Review failing cases and fix regressions before merging."
    exit 1
fi
