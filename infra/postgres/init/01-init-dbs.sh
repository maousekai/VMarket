#!/bin/bash
# Tao database rieng cho tung service (database-per-service theo SRS)
# vmarket_auth da tao qua bien POSTGRES_DB
set -e

for db in vmarket_user vmarket_shop vmarket_order vmarket_payment vmarket_delivery vmarket_review vmarket_recommendation; do
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "CREATE DATABASE $db;"
  echo "Created database: $db"
done

echo "All service databases created."
