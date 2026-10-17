# Verification of the extracted repository

The extracted services must be tested again; results recorded in the original project do not prove this repository works. The final verification record is maintained here after execution.

Supported checks include native Java packaging, Angular tests/build, disposable PostgreSQL tests, embedded Kafka transport tests, a synthetic PSP/bank/ERP network lab, and a complete Docker stack with real local Kafka, recovery workers, Prometheus rules and Grafana queries.

The full-stack script exercises application restart, broker interruption, database interruption and idempotent recovery, then creates and restores a logical database backup. It compares all billing and identity table fingerprints and boots the application against the restored database. This is not point-in-time recovery, multi-host failover, live payment processing or a production load test.
