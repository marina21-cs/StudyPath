# Supplemental reading starters

`reading-starters.mjs` validates small English Wikipedia discovery records. These records help learners choose a reading that the Android client explicitly retrieves using the existing public API. They contain an original discovery description and source/revision/license/attribution metadata. They contain no saved HTML, reading excerpt, quiz, curriculum mapping or approval decision. Validation cannot establish that prose is original or that a curriculum reviewer approved a source.

The input is one UTF-8 JSON object keyed by `wiki_<pageId>_<revisionId>`, containing 1–20 exact-schema records and fewer than 100,000 raw bytes. Positive IDs must be safe integers. UTC revision timestamps must round-trip without date normalization. Source and immutable revision URLs must match the supplied identity. Unknown fields and malformed types fail validation. Retrieval timestamps are integer milliseconds from zero through 4,102,444,800,000.

The exported interfaces are:

- `validateReadingStarters(Buffer)` returns the validated keyed object or throws.
- `planReadingStarterSeed(records, existingById)` returns `{newEntries, reusedIds, total}` without mutating inputs. `existingById` must contain a fetched value or explicit `null` for every requested ID. Unrelated existing entries are ignored. A conflicting same-ID record rejects the whole plan.
- `assertLocalEmulatorHost(host)` accepts only `127.0.0.1:9000`.

Run focused tests from the repository root with the project's Node 24 runtime:

```sh
node --test --test-isolation=none --test-reporter=tap backend/materials/tests/*.test.mjs
```

The local seeder is a separate command. Start the Firebase emulator first, and pass an explicitly reviewed supplemental reading-starter JSON file:

```sh
FIREBASE_DATABASE_EMULATOR_HOST=127.0.0.1:9000 node backend/scripts/seed-reading-starters.mjs PATH_TO_READING_STARTERS_JSON
```

The destination namespace is fixed to `demo-studypath-default-rtdb`. The script checks each requested existing child before writing, accepts exact same-record retries, patches only new children beneath `catalog/readingStarters`, and reads every requested child back. It never clears users or replaces the shared catalog. HTTP redirects are rejected, requests time out after 12 seconds, and no cloud credentials are used. The emulator's local `Bearer owner` authorization is used only at the fixed loopback endpoint.

The [input reader](read-reading-starters.mjs) accepts regular files and rejects symbolic links. Both the initial file-size check and the actual capped read enforce raw input strictly below 100,000 bytes, including files that grow after opening. The validator then rejects malformed UTF-8 and invalid metadata before the first request.

Preflight GETs and the later granular PATCH are not an atomic compare-and-set publication transaction. Do not run concurrent admin writers for the same IDs. Readback detects changed final values but cannot undo another admin writer's mutation. This command is local demonstration setup, not a cloud publication or curriculum approval workflow.

Failing-first and passing unit output, command identities and direct CLI checks are recorded in `test-results/`. All unit fixtures use synthetic IDs and metadata. Actual API retrieval and Android behavior need their own integration evidence.
