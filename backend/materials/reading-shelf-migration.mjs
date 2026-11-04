import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';

const key = /^[A-Za-z0-9_-]{1,64}$/;
const slotKey = /^s[01][0-9]$/;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const check = (condition, code) => { if (!condition) throw new Error(`READING_SHELF_${code}`); };
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
const text = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max;
const fields = ['pageId', 'pageKey', 'title', 'excerpt', 'sourceUrl', 'revisionId', 'revisionUrl',
  'revisionTimestamp', 'licenseTitle', 'licenseUrl', 'attribution', 'modificationNotice', 'savedAt', 'retrievedAt'];
const canonical = value => object(value) ? Object.fromEntries(Object.keys(value).sort().map(name => [name, canonical(value[name])]))
  : Array.isArray(value) ? value.map(canonical) : value;
const digest = value => createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');

function validateSnapshot(id, value) {
  check(key.test(id) && object(value) && fields.every(field => Object.hasOwn(value, field)) &&
    Object.keys(value).every(field => fields.includes(field) || field === 'courseId'), 'SNAPSHOT_FIELDS_INVALID');
  check(integer(value.pageId, 1, Number.MAX_SAFE_INTEGER) && integer(value.revisionId, 1, Number.MAX_SAFE_INTEGER), 'SNAPSHOT_ID_INVALID');
  for (const [field, max] of Object.entries({ pageKey: 240, title: 240, excerpt: 8000, licenseTitle: 80,
    attribution: 500, modificationNotice: 240 })) check(text(value[field], max), 'SNAPSHOT_TEXT_INVALID');
  check(text(value.sourceUrl, 2000) && /^https:\/\/en[.]wikipedia[.]org\/wiki\/[A-Za-z0-9_%~!$&'()*+,;=:@.\/?#-]+$/.test(value.sourceUrl), 'SNAPSHOT_SOURCE_INVALID');
  check(value.revisionUrl === `https://en.wikipedia.org/w/index.php?oldid=${value.revisionId}`, 'SNAPSHOT_REVISION_INVALID');
  check(typeof value.revisionTimestamp === 'string' && /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$/.test(value.revisionTimestamp), 'SNAPSHOT_TIMESTAMP_INVALID');
  check(text(value.licenseUrl, 240) && /^https:\/\/creativecommons[.]org\/licenses\/by-sa\/[0-9][.][0-9]\/(deed[.]en)?$/.test(value.licenseUrl), 'SNAPSHOT_LICENSE_INVALID');
  for (const field of ['savedAt', 'retrievedAt']) check(integer(value[field], 0, 4102444800000), 'SNAPSHOT_TIMESTAMP_INVALID');
  if (Object.hasOwn(value, 'courseId')) check(typeof value.courseId === 'string' && key.test(value.courseId), 'SNAPSHOT_COURSE_INVALID');
}

/** Pure, lossless plan: never renames, edits or drops a saved source snapshot or unrelated user field. */
export function planReadingShelfMigration(value) {
  check(object(value), 'USER_INVALID');
  const user = structuredClone(value);
  const readings = user.savedReadings ?? {};
  check(object(readings), 'READINGS_INVALID');
  const ids = Object.keys(readings).sort();
  check(ids.length <= 20, 'OVER_LIMIT_REQUIRES_LEARNER_CLEANUP');
  for (const id of ids) validateSnapshot(id, readings[id]);
  const before = digest(user.savedReadings ?? null);
  if (Object.hasOwn(user, 'readingShelfVersion')) {
    check(user.readingShelfVersion === 1, 'VERSION_UNSUPPORTED');
    const slots = user.savedReadingSlots ?? {};
    check(object(slots) && Object.entries(slots).every(([slot, id]) => slotKey.test(slot) &&
      typeof id === 'string' && ids.includes(id)) && ids.every(id => Object.values(slots).includes(id)), 'ACTIVE_INDEX_INVALID');
    return { user, changed: false, readingCount: ids.length, snapshotsSha256Before: before, snapshotsSha256After: before };
  }
  check(!Object.hasOwn(user, 'savedReadingSlots'), 'PARTIAL_INDEX_REQUIRES_REVIEW');
  // These collections did not exist in the legacy schema. Do not activate unvalidated companion data.
  check(!Object.hasOwn(user, 'readingAnnotations') && !Object.hasOwn(user, 'readingContexts'), 'LEGACY_COMPANIONS_REQUIRE_REVIEW');
  user.readingShelfVersion = 1;
  if (ids.length) user.savedReadingSlots = Object.fromEntries(ids.map((id, index) => [`s${String(index).padStart(2, '0')}`, id]));
  const after = digest(user.savedReadings ?? null);
  check(before === after, 'SOURCE_PRESERVATION_FAILED');
  return { user, changed: true, readingCount: ids.length, snapshotsSha256Before: before, snapshotsSha256After: after };
}

/** Trusted local emulator maintenance only; dry run by default, conditional PUT and full readback on apply. */
export async function migrateReadingShelf({ namespace, uid, apply = false, fetchImpl = fetch }) {
  check(typeof namespace === 'string' && /^demo-studypath[A-Za-z0-9_-]{0,64}$/.test(namespace), 'LOCAL_NAMESPACE_REQUIRED');
  check(typeof uid === 'string' && key.test(uid), 'UID_INVALID');
  check(typeof apply === 'boolean', 'APPLY_INVALID');
  const url = `http://127.0.0.1:9000/users/${uid}.json?ns=${namespace}`;
  const request = async (method = 'GET', body, etag) => {
    // Export preserves unrelated priority metadata in the conditional whole-user PUT.
    // Unsupported priorities inside a source snapshot fail snapshot validation instead of being stripped.
    const response = await fetchImpl(method === 'GET' ? `${url}&format=export` : url, { method, redirect: 'error', signal: AbortSignal.timeout(12000),
      headers: { Authorization: 'Bearer owner', 'content-type': 'application/json',
        ...(method === 'GET' ? { 'X-Firebase-ETag': 'true' } : {}), ...(etag ? { 'if-match': etag } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    check(response.status !== 412, 'CONCURRENT_CHANGE_CONFLICT');
    check(response.ok, `LOCAL_${method}_FAILED_HTTP_${response.status}`);
    let value;
    try { value = await response.json(); } catch { throw new Error('READING_SHELF_RESPONSE_JSON_INVALID'); }
    return { value, etag: response.headers.get('etag') };
  };
  const initial = await request();
  const plan = planReadingShelfMigration(initial.value);
  const summary = { scope: 'LOCAL_READING_SHELF_MIGRATION', namespace, readingCount: plan.readingCount,
    changed: plan.changed, applied: false, verified: !plan.changed,
    snapshotsSha256Before: plan.snapshotsSha256Before, snapshotsSha256After: plan.snapshotsSha256After };
  if (!apply || !plan.changed) return summary;
  check(typeof initial.etag === 'string' && initial.etag.length > 0, 'ETAG_REQUIRED');
  await request('PUT', plan.user, initial.etag);
  const final = await request();
  check(isDeepStrictEqual(final.value, plan.user), 'READBACK_FAILED_RECHECK_USER_STATE');
  return { ...summary, applied: true, verified: true };
}
