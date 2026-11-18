#!/usr/bin/env bash
# 生成压测用户 token，输出 tokens.csv（表头 token,userId）。
# 用法：./gen-tokens.sh [userCount] [baseUrl]
set -euo pipefail

USER_COUNT="${1:-500}"
BASE_URL="${2:-http://127.0.0.1:8081}"
ADMIN_TOKEN="${ADMIN_TOKEN:-hmdp-admin-token}"
OUT_FILE="$(dirname "$0")/tokens.csv"

echo "token,userId" > "$OUT_FILE"
for i in $(seq 1 "$USER_COUNT"); do
  resp=$(curl -s -X POST "${BASE_URL}/admin/test/token?userId=${i}" \
    -H "X-Admin-Token: ${ADMIN_TOKEN}")
  token=$(printf '%s' "$resp" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if [ -z "$token" ]; then
    echo "生成 token 失败：userId=${i} resp=${resp}" >&2
    exit 1
  fi
  echo "${token},${i}" >> "$OUT_FILE"
done

echo "已生成 ${USER_COUNT} 个 token -> ${OUT_FILE}"
