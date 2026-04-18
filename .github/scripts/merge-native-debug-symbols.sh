#!/usr/bin/env bash
# Merge per-module native-debug-symbols.zip files into a single combined zip.
# Usage: merge-native-debug-symbols.sh <input_dir> <output_zip>
#
# <input_dir>  – directory containing phone.zip and/or tv.zip (the per-module
#                native-debug-symbols.zip files, renamed by the caller)
# <output_zip> – destination path for the merged zip (created by this script)
set -euo pipefail

INPUT_DIR="${1:?Usage: $0 <input_dir> <output_zip>}"
OUTPUT_ZIP="${2:?Usage: $0 <input_dir> <output_zip>}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

for zip_file in "$INPUT_DIR"/*.zip; do
    [ -f "$zip_file" ] || continue
    unzip -oq "$zip_file" -d "$WORK_DIR"
done

if [ -z "$(ls -A "$WORK_DIR")" ]; then
    echo "No native debug symbol zips found in $INPUT_DIR — skipping merge."
    exit 0
fi

(cd "$WORK_DIR" && zip -qr "$OUTPUT_ZIP" .)
echo "Merged native debug symbols written to $OUTPUT_ZIP"
