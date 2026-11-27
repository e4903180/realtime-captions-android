#!/usr/bin/env bash
# Downloads the prebuilt native libraries and data the iPhone app links against into ios/Vendor/
# (not checked in). Every file is pinned by SHA-256. Run from anywhere; safe to run again.
set -euo pipefail

SHERPA_VERSION=2.0.3          # same sherpa-onnx version as the Android app (buildSrc/DownloadFile.kt)
ORT_VERSION=1.28.2            # onnxruntime used by sherpa-onnx's own build-ios.sh for that version
OPENCC4J_VERSION=1.14.0       # same dictionaries as the Android app's opencc4j dependency
AZURE_SPEECH_VERSION=1.49.1   # same Azure Speech SDK version as the Android app

here="$(cd "$(dirname "$0")/.." && pwd)"
vendor="$here/Vendor"
cache="$vendor/.downloads"
mkdir -p "$cache"

fetch() { # url sha256 output
  local url=$1 sha=$2 out=$3
  if [ -f "$out" ] && echo "$sha  $out" | shasum -a 256 -c - >/dev/null 2>&1; then return; fi
  echo "Downloading $url"
  curl -fL --retry 4 --retry-delay 5 -o "$out.part" "$url"
  echo "$sha  $out.part" | shasum -a 256 -c - >/dev/null || {
    echo "SHA-256 mismatch for $url: got $(shasum -a 256 "$out.part" | cut -d' ' -f1)" >&2; rm -f "$out.part"; exit 1; }
  mv "$out.part" "$out"
}

# sherpa-onnx C API as a static xcframework (module SherpaOnnxC). Since 2.x these are published
# under the release tag "xcframework" instead of the version tag.
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/sherpa-onnx-v$SHERPA_VERSION-ios-static.xcframework.zip" \
  36603efc0908a18a3b4b5ec1a81a15accddbe9948720bf4d931ee14506f42481 "$cache/sherpa.zip"
fetch "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$ORT_VERSION/onnxruntime-ios-static-xcframework-$ORT_VERSION.xcframework.zip" \
  2c2299acbb461d26d4bac4bc85985d40e7c7177ed6072703ae0846d88b0b4599 "$cache/onnxruntime.zip"
# sherpa-onnx's Swift wrapper over the C API, from the same version.
fetch "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$SHERPA_VERSION/swift-api-examples/SherpaOnnx.swift" \
  fe8d3dfc007c6bdaed5b221c3e66a10a2bed3f9e41cd952169186879373011ea "$cache/SherpaOnnx.swift"
fetch "https://repo1.maven.org/maven2/com/github/houbb/opencc4j/$OPENCC4J_VERSION/opencc4j-$OPENCC4J_VERSION.jar" \
  5a22347f9f3f0811c201e19681b1237b36165ceb2041ed2efd49ba40358a598a "$cache/opencc4j.jar"
# Azure AI Speech SDK for the cloud engine (Microsoft's official iOS xcframework).
fetch "https://csspeechstorage.blob.core.windows.net/drop/$AZURE_SPEECH_VERSION/MicrosoftCognitiveServicesSpeech-XCFramework-$AZURE_SPEECH_VERSION.zip" \
  0000000000000000000000000000000000000000000000000000000000000000 "$cache/azure-speech.zip"

stamp="$vendor/.stamp-$SHERPA_VERSION-$ORT_VERSION-$OPENCC4J_VERSION-$AZURE_SPEECH_VERSION"
if [ ! -f "$stamp" ]; then
  rm -rf "$vendor/sherpa-onnx.xcframework" "$vendor/onnxruntime.xcframework" "$vendor/opencc" "$vendor/MicrosoftCognitiveServicesSpeech.xcframework"
  unzip -q -o "$cache/sherpa.zip" -d "$vendor"
  unzip -q -o "$cache/onnxruntime.zip" -d "$vendor"
  unzip -q -o "$cache/azure-speech.zip" -d "$vendor/.azure"
  mv "$(find "$vendor/.azure" -maxdepth 3 -type d -name MicrosoftCognitiveServicesSpeech.xcframework | head -1)" "$vendor/"
  rm -rf "$vendor/.azure"
  # The wrapper only imports the C module inside a Swift package; the app links the xcframework.
  sed 's/^#if SWIFT_PACKAGE$/#if true/' "$cache/SherpaOnnx.swift" > "$vendor/SherpaOnnx.swift"
  mkdir -p "$vendor/opencc"
  for f in TWPhrases TWVariants STPhrases STCharacters; do
    unzip -q -o -j "$cache/opencc4j.jar" "data/dictionary/$f.txt" -d "$vendor/opencc"
  done
  rm -f "$vendor"/.stamp-*
  touch "$stamp"
fi
echo "Vendor ready: $vendor"
