#!/bin/bash
set -e

echo "=== Cleaning Maven Local cache for kotlin-roku plugin ==="
rm -rf ~/.m2/repository/com/example/kotlin-roku

echo "=== Building and publishing kotlin-roku plugin to Maven Local ==="
./gradlew clean publishToMavenLocal --no-daemon

echo ""
echo "=== kotlin-roku plugin published to Maven Local ==="
echo ""
echo "Published artifact:"
echo "  - com.example:kotlin-roku:1.0.0-SNAPSHOT"
