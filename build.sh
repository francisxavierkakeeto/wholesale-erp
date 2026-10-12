#!/usr/bin/env bash
# Compile the project (Java 17+). Run from the project root.
set -e
rm -rf out && mkdir -p out
javac -d out -cp lib/mariadb-java-client.jar src/erp/*.java
cp sql/schema.sql out/schema.sql
echo "Build OK -> run with ./run.sh   (or ./run.sh --selftest)"
