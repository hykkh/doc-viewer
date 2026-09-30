#!/bin/bash
# Runs hwp_one_main.mjs on every sample in its own process (a wasm panic can poison the instance).
cd "$(dirname "$0")"
OUT=../research/hwp_bulk_main.jsonl; : > $OUT
find ../research/rhwp/samples -type f \( -iname '*.hwp' -o -iname '*.hwpx' -o -iname '*.hml' \) | sort | while read -r f; do
  r=$(timeout 120 node hwp_one_main.mjs "$f" 2>/dev/null | tail -1)
  [ -z "$r" ] && r="{\"f\":\"$f\",\"ok\":false,\"err\":\"timeout or crash\"}"
  echo "$r" >> $OUT
done
echo DONE >> $OUT
