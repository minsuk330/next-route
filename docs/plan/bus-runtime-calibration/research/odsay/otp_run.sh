#!/bin/bash
# usage: otp_run.sh TAG olat olon dlat dlon when   -> raw_otp/TAG.json  (sequential; increments otp_calls.txt)
cd "$(dirname "$0")"
TAG=$1; shift
python3 otpq2.py "$@" PT60M > /tmp/.otpbody.$$ || exit 1
ssh vps "curl -s -m 90 -X POST -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:18080/otp/routers/default/index/graphql" < /tmp/.otpbody.$$ > raw_otp/$TAG.json
rm -f /tmp/.otpbody.$$
n=$(cat otp_calls.txt); echo $((n+1)) > otp_calls.txt
sleep 1
