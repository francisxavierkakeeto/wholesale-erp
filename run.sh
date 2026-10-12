#!/usr/bin/env bash
# Run the CLI. Optional arg: database name (default from erp.properties) or --selftest.
exec java -cp "out:lib/mariadb-java-client.jar" erp.Main "$@"
