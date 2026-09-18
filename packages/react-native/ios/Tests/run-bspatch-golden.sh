#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
package_root="$(cd "${script_dir}/../.." && pwd)"

exec "${package_root}/test/native/run-bspatch-golden.sh" \
  ios \
  "${package_root}/ios/bspatch/bspatch.c" \
  "${package_root}/ios/bspatch"
