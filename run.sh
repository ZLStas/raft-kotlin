#!/bin/bash

set -e  # Exit on error

echo "🛑 Shutting down existing cluster..."
docker-compose down

echo "🧹 Cleaning build and removing old JAR..."
rm -f ./key-value-example/build/libs/key-value-example-1.jar
./gradlew clean

echo "🏗️ Building JAR..."
./gradlew jar --rerun-tasks

echo "🐳 Rebuilding and starting cluster..."
docker-compose build --no-cache
docker-compose up
