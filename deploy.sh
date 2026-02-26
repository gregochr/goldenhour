#!/bin/bash
set -e

echo "🏗️  Building Golden Hour..."
echo ""

# Build frontend
echo "📦 Building frontend..."
cd frontend
npm run build
cd ..

# Build backend
echo "📦 Building backend..."
cd backend
./mvnw clean package -DskipTests -q
cd ..

# Deploy with Docker
echo ""
echo "🐳 Deploying with Docker..."
docker-compose down
docker-compose up -d

# Wait for containers to start
sleep 15

# Verify health
echo ""
echo "✅ Deployment complete!"
echo ""
echo "Access the app:"
echo "  Frontend:      http://localhost"
echo "  Backend API:   http://localhost/api"
echo "  Backend direct: http://localhost:8082 (for debugging)"
echo ""
echo "View logs:"
echo "  Frontend: docker logs -f goldenhour-frontend"
echo "  Backend:  docker logs -f goldenhour-backend"
echo ""
echo "Stop containers:"
echo "  docker-compose down"
