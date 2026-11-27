#!/usr/bin/env bash
# What CI runs on a macOS runner; also works on a Mac with Xcode for a full local check.
#   1. builds the shared Kotlin core for iOS and runs its tests on the simulator
#   2. downloads the native libraries, generates the Xcode project
#   3. builds the app for a real iPhone (unsigned) and runs the app's tests on a simulator,
#      including an end-to-end run of the offline models on recorded speech.
# Set CAPTIONS_MODEL_DOWNLOADS to a folder for the model files (downloaded if missing).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

./gradlew -PcoreOnly=true :core:iosSimulatorArm64Test
ios/scripts/build-core.sh release
ios/scripts/fetch-deps.sh

models=${CAPTIONS_MODEL_DOWNLOADS:-$root/ios/Vendor/models}
mkdir -p "$models"
get() { # url sha file
  if [ -f "$models/$3" ] && echo "$2  $models/$3" | shasum -a 256 -c - >/dev/null 2>&1; then return; fi
  curl -fL --retry 4 --retry-delay 5 -o "$models/$3" "$1"
  echo "$2  $models/$3" | shasum -a 256 -c -
}
R=https://github.com/k2-fsa/sherpa-onnx/releases/download
get "$R/asr-models/sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2" \
  8a6fca056e1a342546edd78be4d50274e2c01898e7b8ae8fc336f6410319c399 asr.tar.bz2
get "$R/asr-models/silero_vad.onnx" 9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6 silero_vad.onnx
get "$R/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx" \
  53cab20ba8f2eb2f6e2d89ecc18e88ef3222169b61b4419cbc13666744a4574b speaker.onnx

cd ios
xcodegen generate

# API surface of the vendored SDKs, printed so a compile error can be matched against it.
grep -rh -E "Transcribing|resultReceivedHandler" Vendor/MicrosoftCognitiveServicesSpeech.xcframework/ios-arm64*/MicrosoftCognitiveServicesSpeech.framework/Headers/SPXConversationTranscriber.h 2>/dev/null | head -20 || true
awk '/func sherpaOnnxOffline(ModelConfig|RecognizerConfig|SenseVoiceModelConfig|ParaformerModelConfig)\(/{p=1} p{print NR": "$0} /\) -> /{p=0}' Vendor/SherpaOnnx.swift | head -80 || true

# Unsigned build for a real iPhone: checks that every library links for arm64 devices.
xcodebuild build -project Captions.xcodeproj -scheme Captions -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/device \
  CODE_SIGNING_ALLOWED=NO | tail -n 30

sim=$(xcrun simctl list devices available | grep -m1 -oE 'iPhone[^(]*\(([0-9A-F-]{36})\)' | grep -oE '[0-9A-F-]{36}')
echo "Simulator: $sim"
TEST_RUNNER_CAPTIONS_MODEL_DOWNLOADS="$models" xcodebuild test -project Captions.xcodeproj -scheme Captions \
  -destination "id=$sim" -derivedDataPath build/sim CODE_SIGNING_ALLOWED=NO 2>&1 | tee build/test.log | grep -E "test[0-9]\.wav|unpack:|Azure with|Test Case|error:|warning: .*Captions/|\*\* TEST" || true
grep -q "\*\* TEST SUCCEEDED \*\*" build/test.log
