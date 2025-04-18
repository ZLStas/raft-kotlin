#!/bin/bash

set -e  # Exit on error

echo "🛑 Shutting down existing cluster..."
docker-compose down

#echo "🧹 Cleaning build..."
#./gradlew clean
#
#echo "🏗️ Building JAR..."
#./gradlew jar

echo "🐳 Rebuilding and starting cluster..."
docker-compose up --build
