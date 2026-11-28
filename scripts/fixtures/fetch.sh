#!/usr/bin/env bash
# Fetch real race fixtures (F03b) from the private S3-compatible bucket (Cloudflare R2 or GCS interop)
# into testing/fixtures/real/. Real footage is never committed (see CLAUDE.md, docs/FIXTURES.md).
#
# Required env (CI: FIXTURES_S3_BUCKET and FIXTURES_S3_ENDPOINT are repository variables, the keys are secrets):
#   FIXTURES_S3_BUCKET, FIXTURES_S3_ENDPOINT, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
# Optional: FIXTURES_S3_PREFIX (default "golden/"), BELLLAP_REAL_FIXTURES (destination dir).
#
# Exit 0 with a "SKIPPED" message when no secret is configured, so forks and PRs stay green (result: inconclusive).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
dest="${BELLLAP_REAL_FIXTURES:-$root/testing/fixtures/real}"

if [[ -z "${FIXTURES_S3_BUCKET:-}" || -z "${FIXTURES_S3_ENDPOINT:-}" || -z "${AWS_ACCESS_KEY_ID:-}" || -z "${AWS_SECRET_ACCESS_KEY:-}" ]]; then
  echo "SKIPPED: fixture bucket credentials not set; no real fixtures fetched (inconclusive)."
  exit 0
fi
command -v aws >/dev/null || { echo "error: aws CLI not found" >&2; exit 1; }

prefix="${FIXTURES_S3_PREFIX:-golden/}"
mkdir -p "$dest"
aws s3 sync "s3://${FIXTURES_S3_BUCKET}/${prefix}" "$dest" \
  --endpoint-url "$FIXTURES_S3_ENDPOINT" --region "${AWS_DEFAULT_REGION:-auto}" --only-show-errors

count=$(find "$dest" -mindepth 2 -maxdepth 2 -name labels.json | wc -l)
echo "Fetched $count real fixture(s) into $dest"
