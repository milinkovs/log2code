# Fixture datasets

Recorded log snapshots (with `manifest.yml`) from the first PetClinic commit (`3858f9c`). They are kept
**only as fixed inputs for tests** of the log parser, the event assembler and the manifest loader
(`smoke-oracle-01` also documents the oracle format). They are not ingested into OpenSearch and are not
real datasets of the current code version; new datasets are recorded into `datasets/` (see `docs/dataset-guide.md`).
