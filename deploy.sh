#!/bin/bash
set -e
APP_DIR="$HOME/goldenhour"
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
echo "[$TIMESTAMP] Starting PhotoCast deployment..."
cd "$APP_DIR"
echo "Pulling latest code..."
git pull origin main
echo "Pulling pre-built images from GHCR..."
docker compose pull goldenhour-backend goldenhour-frontend
echo "Restarting containers..."
docker compose up -d --no-deps goldenhour-backend goldenhour-frontend
echo "Waiting for backend health check..."
sleep 30
docker compose ps
echo "Deployment complete."
