#!/usr/bin/env bash
set -euo pipefail
studyPathMockScript="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/build-mock-candidate.py"
exec python3 "$studyPathMockScript" "$@"
