#!/usr/bin/env bash
# Generates small, tagged, synthetic audio tracks used as test fixtures and for
# manual testing on the phone / in the car. Output is deterministic for a given ffmpeg.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/fixtures"
assets="$root/app/src/androidTest/assets"
mkdir -p "$out" "$assets"

# name|title|track|chord frequencies (Hz)
tracks=(
  "01-a-major|Fixture Tone A|1|440 554.37 659.25"
  "02-d-major|Fixture Tone D|2|293.66 369.99 440"
  "03-e-major|Fixture Tone E|3|329.63 415.30 493.88"
)

for spec in "${tracks[@]}"; do
  IFS='|' read -r name title track freqs <<<"$spec"
  read -r f1 f2 f3 <<<"$freqs"
  # A soft three-note chord with a slow tremolo and fades, quiet enough to not startle anyone.
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "sine=frequency=$f1:sample_rate=44100:duration=12" \
    -f lavfi -i "sine=frequency=$f2:sample_rate=44100:duration=12" \
    -f lavfi -i "sine=frequency=$f3:sample_rate=44100:duration=12" \
    -filter_complex "[0][1][2]amix=inputs=3,volume=-12dB,tremolo=f=2:d=0.3,afade=t=in:d=0.5,afade=t=out:st=11:d=1,aformat=channel_layouts=stereo" \
    -c:a libmp3lame -b:a 128k \
    -metadata title="$title" \
    -metadata artist="CarPlayer Fixtures" \
    -metadata album="Test Tones" \
    -metadata track="$track/${#tracks[@]}" \
    -metadata genre="Test" \
    "$out/$name.mp3"
  cp "$out/$name.mp3" "$assets/$name.mp3"
done

ls -l "$out"
