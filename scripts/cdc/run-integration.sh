#!/usr/bin/env bash
set -euo pipefail
collect() {
  result=$?
  trap - EXIT
  mkdir -p /evidence/junit
  cp -a billing-service/target/surefire-reports/. /evidence/junit/ || result=1
  exit "$result"
}
trap collect EXIT
mvn -B -ntp -Dmaven.repo.local=/opt/maven-cache/repository -pl billing-service -am clean test -Dtest=BillingCdcIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
