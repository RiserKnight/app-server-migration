#!/usr/bin/env bash

set -o pipefail
set -o nounset
set -o errexit
# DESCRIPTION:
#
# Takes java project path and destination path (ending with html file) as input parameters.
# Destination path will have the generated report
#
# USAGE:
# chmod +x run.sh
# .run.sh <path to java project> <directory path for output html report file> <arangoDb-username> <arangoDb-pwd> <rule-names>

SRC_PATH="$1"
OUT_PATH="$2"
DB_USER="$3"
DB_PASS="$4"
RULE_SET="$5"

echo "📦 Starting App Server Migration Tool"
echo "📁 Source: $SRC_PATH"
echo "📂 Output: $OUT_PATH"
echo "🧠 Rules:  $RULE_SET"

mkdir -p "$OUT_PATH"

if [ -d "src/main/resources/lib" ]; then
  echo "📁 Copying HTML assets..."
  cp -r src/main/resources/lib "$OUT_PATH/lib"
else
  echo "⚠️  src/main/resources/lib not found. Skipping copy."
fi

echo "🚀 Running analysis..."
java --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -jar target/app-server-migration-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
     "$SRC_PATH" "$OUT_PATH" "$DB_USER" "$DB_PASS" "$RULE_SET"

echo "✅ Done! Report: $OUT_PATH"
