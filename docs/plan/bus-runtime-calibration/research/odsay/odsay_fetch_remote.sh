# runs ON the VPS via: ssh vps 'bash -s' < odsay_fetch_remote.sh  (OD lines appended below by caller)
# key stays in a shell var on the VPS; responses containing the key are blocked.
K=$(docker exec nextroute-api-1 printenv ODSAY_API_KEY)
while read id region name sx sy ex ey; do
  [ -z "$id" ] && continue
  R=$(curl -s -m 30 -G "https://api.odsay.com/v1/api/searchPubTransPathT" --data-urlencode "apiKey=$K" \
      --data "SX=$sx" --data "SY=$sy" --data "EX=$ex" --data "EY=$ey" --data "OPT=0")
  if printf '%s' "$R" | grep -qF "$K"; then R='{"blocked":"response contained key"}'; fi
  printf '%s\t%s\n' "$id" "$(printf '%s' "$R" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin),ensure_ascii=False))' 2>/dev/null || echo '{"parse_error":1}')"
  sleep 1
done
