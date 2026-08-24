#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 PLATFORM BSPATCH_SOURCE BSPATCH_INCLUDE_DIR" >&2
  exit 64
fi

platform="$1"
bspatch_source="$2"
bspatch_include_dir="$3"
package_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixtures_dir="${package_root}/test/delta-fixtures"
expected_hash="$(awk '$2 == "new.bundle" { print $1 }' "${fixtures_dir}/SHA256SUMS")"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/nitropush-${platform}-bspatch.XXXXXX")"
trap 'rm -rf "${tmp_dir}"' EXIT

cc_bin="${CC:-clang}"
"${cc_bin}" \
  -std=c11 \
  -D_DARWIN_C_SOURCE \
  -I"${bspatch_include_dir}" \
  "${package_root}/test/native/bspatch_driver.c" \
  "${bspatch_source}" \
  -lbz2 \
  -o "${tmp_dir}/bspatch-golden"

assert_expected_hash() {
  local candidate="$1"
  local actual_hash
  actual_hash="$(shasum -a 256 "${candidate}" | awk '{ print $1 }')"
  if [[ "${actual_hash}" != "${expected_hash}" ]]; then
    echo "${platform}: output hash mismatch: expected ${expected_hash}, got ${actual_hash}" >&2
    return 1
  fi
}

"${tmp_dir}/bspatch-golden" \
  "${fixtures_dir}/old.bundle" \
  "${fixtures_dir}/old-to-new.bsdiff" \
  "${tmp_dir}/new.bundle"
cmp "${fixtures_dir}/new.bundle" "${tmp_dir}/new.bundle"
assert_expected_hash "${tmp_dir}/new.bundle"

cp "${fixtures_dir}/old-to-new.bsdiff" "${tmp_dir}/corrupt.bsdiff"
printf 'X' | dd of="${tmp_dir}/corrupt.bsdiff" bs=1 seek=0 conv=notrunc status=none
if "${tmp_dir}/bspatch-golden" \
  "${fixtures_dir}/old.bundle" \
  "${tmp_dir}/corrupt.bsdiff" \
  "${tmp_dir}/corrupt-output.bundle"; then
  echo "${platform}: corrupt patch unexpectedly succeeded" >&2
  exit 1
fi

wrong_base_rc=0
"${tmp_dir}/bspatch-golden" \
  "${fixtures_dir}/wrong-base.bundle" \
  "${fixtures_dir}/old-to-new.bsdiff" \
  "${tmp_dir}/wrong-base-output.bundle" || wrong_base_rc=$?
if [[ ${wrong_base_rc} -eq 0 ]] && assert_expected_hash "${tmp_dir}/wrong-base-output.bundle" 2>/dev/null; then
  echo "${platform}: wrong base unexpectedly produced the expected output hash" >&2
  exit 1
fi

echo "${platform}: golden patch, corrupt patch, and wrong-base checks passed"
