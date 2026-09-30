#!/usr/bin/env bash
# 수도권 OTP graph (P3/P4). Kept for existing docs/commands; the pipeline now lives
# in build_otp_graph.sh (profiles: seoul | national).
exec "$(dirname "${BASH_SOURCE[0]}")/build_otp_graph.sh" --profile seoul "$@"
