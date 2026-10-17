# Repository separation

This repository is an explicit source extraction from the billing implementation developed inside the older `ebusiness-platform` working tree. It has its own Git directory, Maven reactor, frontend dependency lock, Docker build context, monitoring and AWS configuration. It is not a Git worktree, submodule, symlink or wrapper around the older checkout.

The original checkout and its uncommitted work are preserved. They are not the source required to build or run this repository. The original `payment-service`, general `backend`, discovery server, checkout screens, product/order controllers, notification polling, Razorpay SDK, Redis and DynamoDB dependencies are excluded. The new services start directly; there is no `APP_MODE` switch. Java packages use `com.invoicepayments`. The identity repository scans only its own user entity and repository.

The frontend contains login, invoices, finance and reconciliation. Authentication uses its own `IP_AUTH` cookie. Local databases, Compose project names, volumes and default ports are separate from the original stack. Secrets are freshly generated locally and are not committed. Generated artifacts, installed dependencies, backups and test databases are not source deliverables.

Nine existing database migrations were preserved byte for byte. `migration-checksums.json` records their SHA-256 values. `extraction-provenance.json` records the allowlisted source inputs and original hashes for auditability; these historical paths are not build dependencies. New local databases are created for this app; no original application database is silently reused or migrated.

The split deliberately preserves the existing framework versions rather than combining source separation with a framework migration. This is not a security certification or a claim that the application is ready to handle production money. AWS files are reference infrastructure; local testing does not deploy or validate AWS availability.

`scripts/check-repository.ps1` checks the source and Git boundary. With `-CheckArtifacts`, it also checks packaged runtime classes and libraries. A clean local Git commit provides a reviewable baseline; publishing a remote repository is a separate action.
