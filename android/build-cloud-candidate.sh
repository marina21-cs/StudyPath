#!/usr/bin/env bash
set -euo pipefail
studyPathCloudScript="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/build-cloud-candidate.py"
exec python3 "$studyPathCloudScript" "$@"
