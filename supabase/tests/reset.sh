#!/bin/bash
# Recreates the local test database and applies bootstrap + migrations.
set -e
cd "$(dirname "$0")/.."
P="psql -h /tmp/mwpg -p ${MW_PORT:-5545} -U postgres -X -q -v ON_ERROR_STOP=1"
$P -c "drop database if exists messwise" -c "create database messwise" >/dev/null
for f in tests/bootstrap.sql migrations/*.sql; do $P -d messwise -f "$f" >/dev/null 2>&1 || { echo "FAILED: $f"; $P -d messwise -f "$f" 2>&1 | grep -E "ERROR" | head -5; exit 1; }; done
echo "database ready"
