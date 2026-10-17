# Verification of the extracted repository

Executed on 17 October 2026. These results belong to the independent source extraction. Raw transcripts, local secrets and temporary data are excluded from Git. The sanitized [result manifest](verification-results-2026-10-17.json) records individual Java suites.

| Check | Result |
| --- | --- |
| Fresh local clone: package all three Java modules | PASS |
| Independent Git/source boundary and packaged JAR audit | PASS; no legacy runtime classes or libraries |
| Preserved migration hashes | PASS; nine unchanged SQL files |
| Complete Docker Java suite | 181 passed; zero failures, errors or skipped tests, across 15 classes |
| Financial database invariants | 53 of the 181 tests |
| Embedded three-broker Kafka transport | Four of the 181 tests |
| Synthetic PSP/bank/ERP network and bounded-load scenarios | Seven of the 181 tests |
| Angular tests and optimized build | 39 passed; build passed |
| Extracted Terraform configuration | Validation and both mocked plan scenarios passed; no AWS deployment |
| AWS backup helper and SPA function | Four mocked backup boundary checks and five route checks passed |
| Extracted application stack integration smoke | INCOMPLETE; billing exited with code 137 during startup, then the Docker engine became unavailable |

The Java suite ran inside a disposable Maven verification container sharing a loopback network with its own PostgreSQL 17.11 test container. It did not use the application databases or the host Windows PostgreSQL installation. Initial Windows-native runs stalled during connection setup; interrupted/incomplete runs are not counted as passing. Native verification now requires a fresh, passing JUnit report for every requested class. A clean-clone packaging check reused downloaded public Maven dependencies; no old application source or compiled JARs were supplied.

The frontend tests/build ran on the extracted frontend with a fresh `npm ci`. Its runtime Docker image was also built independently from the new repository. The original app's earlier results are not being relabeled as results of this repository.

The complete seven-check failure/recovery runner is retained in `scripts/verify-billing-stack.ps1`. Its full mode exercises abrupt application restart, broker interruption, actual alert firing and resolution, database interruption, idempotent recovery, and logical backup/restoration with table comparisons and a restored application boot. That longer full mode was not rerun after extraction. `-Quick` is separately labeled integration smoke coverage and cannot establish those recovery results.

No live PSP, bank, ERP, AWS runtime, production traffic, multi-host failover or PITR was tested. Local tests do not certify production readiness, latency, capacity or recovery objectives.

The final runtime attempt is recorded under ignored .tmp-notes/billing-stack/20261017T124640005Z/report.json. It failed before any integration smoke check ran. The billing container exited with code 137; subsequent Docker commands could not reach the Docker Desktop Linux engine. The cause was not established, and an interrupted restart did not restore the engine. No passing runtime, outage, alert-firing or backup-restoration result is claimed for the extracted repository. The independently built images and 181 passing disposable-container Java tests are separate evidence.
