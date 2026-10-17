#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "Usage: $0 USER_POOL_ID EMAIL member|admin [EXISTING_WATCHPOINT_LOGIN]" >&2
  exit 2
fi

USER_POOL_ID="$1"
EMAIL="$2"
GROUP_NAME="$3"
EXISTING_LOGIN="${4:-}"

if [[ "$GROUP_NAME" != "member" && "$GROUP_NAME" != "admin" ]]; then
  echo "Group must be member or admin." >&2
  exit 2
fi

USER_ATTRIBUTES=("Name=email,Value=$EMAIL" "Name=email_verified,Value=true")
if [[ -n "$EXISTING_LOGIN" ]]; then
  USER_ATTRIBUTES+=("Name=preferred_username,Value=$EXISTING_LOGIN")
fi

aws cognito-idp admin-create-user \
  --user-pool-id "$USER_POOL_ID" \
  --username "$EMAIL" \
  --desired-delivery-mediums EMAIL \
  --user-attributes "${USER_ATTRIBUTES[@]}"

aws cognito-idp admin-add-user-to-group \
  --user-pool-id "$USER_POOL_ID" \
  --username "$EMAIL" \
  --group-name "$GROUP_NAME"
