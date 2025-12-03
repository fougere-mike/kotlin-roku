#!/bin/bash
set -e

echo "=== Building and publishing kotlin-roku plugin to Maven Local ==="

./gradlew publishToMavenLocal --no-daemon

echo ""
echo "=== kotlin-roku plugin published to Maven Local ==="
echo ""
echo "Published artifact:"
echo "  - com.example:kotlin-roku:1.0.0-SNAPSHOT"
