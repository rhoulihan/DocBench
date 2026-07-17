#!/bin/bash
# Upgrade MongoDB from 8.0 to 8.2 with Vector Search support
# This script stops the old container and starts new ones with mongot

set -e

echo "=== MongoDB 8.2 Upgrade with Vector Search ==="

# Check if old container is running
if docker ps --format '{{.Names}}' | grep -q "mongo-translator-mongodb"; then
    echo "Stopping existing MongoDB container..."
    docker stop mongo-translator-mongodb
    docker rm mongo-translator-mongodb
fi

# Create network if not exists
docker network create docbench-network 2>/dev/null || true

# Start MongoDB 8.2
echo "Starting MongoDB 8.2..."
docker run -d \
    --name docbench-mongodb \
    --network docbench-network \
    -p 27017:27017 \
    -v mongoplsql-bridge_mongodb-data:/data/db \
    mongodb/mongodb-community-server:8.2.0-ubi9 \
    mongod --replSet rs0 --bind_ip_all --setParameter mongotHost=docbench-mongot:27027

# Wait for MongoDB to be ready
echo "Waiting for MongoDB to be ready..."
sleep 10

# Initialize replica set if needed
echo "Checking replica set status..."
docker exec docbench-mongodb mongosh --eval "
try {
    rs.status();
    print('Replica set already initialized');
} catch(e) {
    print('Initializing replica set...');
    rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'localhost:27017'}]});
}
" || true

# Start mongot (search service)
echo "Starting mongot (search service)..."
docker run -d \
    --name docbench-mongot \
    --network docbench-network \
    -p 27027:27027 \
    mongodb/mongodb-community-search:latest \
    mongot --mongodHostAndPort docbench-mongodb:27017

# Connect Oracle to the same network
echo "Connecting Oracle to network..."
docker network connect docbench-network mongo-translator-oracle 2>/dev/null || true

# Verify setup
echo ""
echo "=== Verification ==="
sleep 5

echo "MongoDB version:"
docker exec docbench-mongodb mongosh --eval "db.version()" --quiet

echo ""
echo "Checking mongot connection..."
docker logs docbench-mongot 2>&1 | tail -5

echo ""
echo "=== Upgrade Complete ==="
echo "MongoDB 8.2 with Vector Search is now running."
echo ""
echo "To create a vector search index, use:"
echo '  db.collection.createSearchIndex("vector_index", {'
echo '    mappings: {'
echo '      dynamic: true,'
echo '      fields: {'
echo '        embedding: {'
echo '          type: "knnVector",'
echo '          dimensions: 384,'
echo '          similarity: "cosine"'
echo '        }'
echo '      }'
echo '    }'
echo '  })'
