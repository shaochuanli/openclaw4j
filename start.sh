#!/bin/bash
set -e

JAR="target/openclaw4j-1.0.0.jar"

if [ ! -f "$JAR" ]; then
  echo "[ERROR] JAR not found. Run: mvn package -DskipTests"
  exit 1
fi

echo ""
echo "🦞 OpenClaw4j — Starting..."
echo ""
exec java -jar "$JAR" "$@"
