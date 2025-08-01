#!/bin/bash

API_KEY="$1"
NAME_PREFIX="$2"

if [[ -z "$API_KEY" || -z "$NAME_PREFIX" ]]; then
  echo "Usage: $0 <TAILSCALE_API_KEY> <HOSTNAME_PREFIX>"
  exit 1
fi

# Auto-detect tailnet from the auth key
TAILNET=$(curl -s -H "Authorization: Bearer $API_KEY" https://api.tailscale.com/api/v2/whoami | jq -r '.Tailnet.name')

if [[ -z "$TAILNET" || "$TAILNET" == "null" ]]; then
  echo "Failed to detect tailnet. Check your API key."
  exit 1
fi

echo "Detected tailnet: $TAILNET"
echo "Fetching devices with hostname prefix: $NAME_PREFIX..."

DEVICE_IDS=$(curl -s -H "Authorization: Bearer $API_KEY" \
  "https://api.tailscale.com/api/v2/tailnet/$TAILNET/devices" | \
  jq -r '.devices[] | select(.hostname | startswith("'"$NAME_PREFIX"'")) | "\(.hostname) \(.id)"')

if [[ -z "$DEVICE_IDS" ]]; then
  echo "No matching devices found."
  exit 0
fi

echo "$DEVICE_IDS" | while read -r HOSTNAME DEVICE_ID; do
  echo "Deleting device: $HOSTNAME ($DEVICE_ID)"
  curl -s -X DELETE -H "Authorization: Bearer $API_KEY" \
       "https://api.tailscale.com/api/v2/device/$DEVICE_ID"
done