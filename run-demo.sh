#!/usr/bin/env sh
set -eu
classes="${TMPDIR:-/tmp}/marketplace-report-classes"
mkdir -p "$classes"
javac -d "$classes" $(find src/main/java -name '*.java')
java -cp "$classes" dev.marketplace.report.MarketplaceReportService "$@"
