#!/usr/bin/env bash
# Downloads the sherpa-onnx Android library (AAR) and the three AI models into the app.
set -euo pipefail

A=app/src/main/assets
L=app/libs
mkdir -p "$A/whisper" "$L"

AUTH=()
if [ -n "${GITHUB_TOKEN:-}" ]; then AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN"); fi

echo "== sherpa-onnx AAR"
FOUND=""
for V in 1.12.40 1.12.39 1.12.38 1.12.37 1.12.36 1.12.30 1.12.20; do
  URL=$(curl -fsSL "${AUTH[@]}" "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/v$V" 2>/dev/null \
        | grep -o '"browser_download_url": *"[^"]*\.aar"' | head -1 | sed 's/.*"\(https[^"]*\)"/\1/' || true)
  if [ -n "${URL:-}" ]; then
    echo "using v$V: $URL"
    curl -fL "$URL" -o "$L/sherpa-onnx.aar"
    FOUND=$V
    break
  fi
done
if [ -z "$FOUND" ]; then echo "ERROR: could not find a sherpa-onnx AAR release"; exit 1; fi

R=https://github.com/k2-fsa/sherpa-onnx/releases/download

echo "== Silero VAD (is it speech?)"
curl -fL "$R/asr-models/silero_vad.onnx" -o "$A/silero_vad.onnx"

echo "== Speaker model (whose voice?)"
curl -fL "$R/speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx" -o "$A/speaker.onnx"

echo "== Whisper tiny.en (what was said?)"
curl -fL "$R/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2" -o /tmp/whisper.tar.bz2
tar xjf /tmp/whisper.tar.bz2 -C /tmp
W=/tmp/sherpa-onnx-whisper-tiny.en
cp "$W/tiny.en-encoder.int8.onnx" "$A/whisper/enc.onnx"
cp "$W/tiny.en-decoder.int8.onnx" "$A/whisper/dec.onnx"
cp "$W/tiny.en-tokens.txt"        "$A/whisper/tokens.txt"

echo "== done"
ls -lh "$L" "$A" "$A/whisper"
