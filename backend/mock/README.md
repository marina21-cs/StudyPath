# Synthetic computing curriculum

This directory contains three original mock lessons and complete companion PDFs for one local demonstration context, `mock_computing_v1` / `mock_college`. All titles, policy references and reviewer values identify themselves as mock data. Required `APPROVED` flags are fabricated schema fixtures; they establish no institutional, curriculum, rights, participant or family consent approval. The exported catalog remains `approved:false` / `DEMONSTRATION_ONLY`.

| Module | Original objective | Checks | Companion PDF |
| --- | --- | --- | --- |
| `mock_programming` | Trace sequential assignments through input, processing and output | Processing choice, operation order, numeric trace | One complete page |
| `mock_data_structures` | Apply FIFO/LIFO and trace a small item count | Stack choice, FIFO order, numeric count | One complete page |
| `mock_databases` | Identify a primary key and count filtered rows | Key choice, table-operation order, numeric count | One complete page |

Each module includes an explanation, worked example, corrective feedback, independent application, glossary, summary and two authored later-review checks. All three supported interaction formats are present. Course plans define a mock 40% pooled-quiz / 60% equal-exam calculation, explicit GWA inclusion and a three-course prerequisite chain. These fixture plans are not real syllabi. Family eligibility and consent remain in the separate family fixture builder.

`createMockCurriculum({assetOrigin})` in `curriculum.mjs` returns candidates, publication inputs/evidence, deterministic PDF buffers, a mock policy/preferences/catalog fixture, and `binding(courseId, capability)`. Candidate entries expose both `.candidate` and flat candidate fields. `catalog.sharingLessonKeys` derives only actual authored payload lesson IDs and exact validated package hashes. Generated review identities are excluded.

`writeMockCurriculum(directory, options)` writes local artifacts. The checked-in `packages/` export uses `https://mock-assets.example.org`, a declared fixture origin with no public hosting claim. Changing the origin changes the immutable package hash; use a fresh isolated namespace and never replace a previously published version.

```sh
node --test --test-isolation=none --test-reporter=tap backend/mock/*.test.mjs
node backend/mock/curriculum.mjs --output backend/mock/packages
```

Local setup accepts only `127.0.0.1:9000` / `127.0.0.1:19000` and exact `demo-studypath-mock-[publisher-]<8hex>` namespaces. Seeding requires an empty namespace and writes academic policy, options and courses. Publication uses the existing `preparePublication` / whole-catalog ETag compare-and-set transport, then writes the derived trusted lesson inventory after publication readback. It performs no admin module import. These commands do not start Firebase services:

```sh
node backend/mock/seed-emulator.mjs --seed demo-studypath-mock-a1b2c3d4
node backend/mock/seed-emulator.mjs --publish demo-studypath-mock-a1b2c3d4
node backend/mock/serve-assets.mjs --port 8765
```

The PDF server is an explicit loopback HTTP mirror. It serves only the three authored PDF paths from memory, with their immutable lengths/hashes, and reads no arbitrary filesystem paths. Production HTTPS/public-DNS controls remain in the production downloader. The named native test repository uses the guarded debug `MockPackageAssets` adapter and independently validates asset length, hash, native PDF parsing and retained files.

After the app/test APKs have been built with the guarded mirror adapter and local services are running, the coordinator can run `android/test-mock-curriculum.py demo-studypath-mock-a1b2c3d4 emulator-5554` on a fresh read-only `StudyPath_Lite_Test`. It publishes the modules, downloads them through the repository using real HTTP, partitions transport, kills/restarts the process, renders all retained PDFs natively, and confirms exact lesson results after reconnecting. It preserves the normal workspace binding and shared database/Auth controls, and deletes only its own synthetic account and namespace. The runner records actual results; its presence alone is not a device pass.

Evidence: [`mock-curriculum-20261031`](../../implementation/phases/04-ppt-spec-alignment/00-source-policy-gate/test-results/mock-curriculum-20261031/README.md).
