#!/usr/bin/env bash
# Download every rural agricultural jantri (ASR-2011) PDF from garvi.gujarat.gov.in.
#
# The remote filenames are NOT consistent: most districts use "<NAME>-AGRI.pdf",
# a second group uses "<NAME>_AGRI.pdf", and Surat is plain "SURAT.pdf". The server
# is case-insensitive but has directory listing disabled, so the mapping below was
# established by probing and is recorded in ../../data/jantri/sources.txt.
set -euo pipefail
cd "$(dirname "$0")/../../data/jantri"
mkdir -p pdf
base="https://garvi.gujarat.gov.in/PDF/RURAL"
while read -r district remote; do
  [ -z "$district" ] && continue
  if [ -s "pdf/$district.pdf" ]; then
    printf '%-15s cached\n' "$district"
    continue
  fi
  curl -sk --fail -o "pdf/$district.pdf" "$base/$remote.pdf" \
    && printf '%-15s %s pages\n' "$district" "$(pdfinfo "pdf/$district.pdf" | awk '/^Pages/{print $2}')" \
    || { printf '%-15s FAILED (%s)\n' "$district" "$remote"; rm -f "pdf/$district.pdf"; }
done < sources.txt
