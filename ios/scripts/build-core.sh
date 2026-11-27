#!/usr/bin/env bash
# Builds the shared Kotlin core (../core) into CaptionsCore.xcframework for the iPhone app.
# Needs a JDK 17 and Xcode's command line tools. Usage: scripts/build-core.sh [debug|release]
set -euo pipefail
config=${1:-release}
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
task="assembleCaptionsCore$(tr '[:lower:]' '[:upper:]' <<< "${config:0:1}")${config:1}XCFramework"
./gradlew -PcoreOnly=true ":core:$task"
if [ "$config" != release ]; then
  # project.yml links the release path; point it at this build.
  mkdir -p core/build/XCFrameworks/release
  rm -rf core/build/XCFrameworks/release/CaptionsCore.xcframework
  cp -R "core/build/XCFrameworks/$config/CaptionsCore.xcframework" core/build/XCFrameworks/release/
fi
echo "Built core/build/XCFrameworks/release/CaptionsCore.xcframework"
