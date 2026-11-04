import { isDeepStrictEqual } from 'node:util';

export const READING_STARTER_LIMIT_BYTES = 100_000;
const fields = ['schemaVersion', 'kind', 'provider', 'pageId', 'pageKey', 'title', 'description', 'topicQuery',
  'revisionId', 'revisionTimestamp', 'sourceUrl', 'revisionUrl', 'licenseTitle', 'licenseUrl', 'attribution', 'retrievedAt'];
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
const positiveId = value => Number.isSafeInteger(value) && value > 0;
const requireThat = (condition, code) => { if (!condition) throw new Error(code); };

/** Parse only small supplemental source metadata; this establishes no curriculum or lesson approval. */
export function validateReadingStarters(bytes) {
  requireThat(Buffer.isBuffer(bytes) && bytes.length < READING_STARTER_LIMIT_BYTES, 'READING_STARTER_INPUT_LIMIT');
  let records;
  try { records = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { throw new Error('READING_STARTER_JSON_INVALID'); }
  requireThat(object(records) && Object.keys(records).length >= 1 && Object.keys(records).length <= 20,
    'READING_STARTER_BATCH_INVALID');
  for (const [key, value] of Object.entries(records)) {
    requireThat(object(value) && Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field)),
      'READING_STARTER_FIELDS_INVALID');
    requireThat(value.schemaVersion === 1 && value.kind === 'SUPPLEMENTAL' && value.provider === 'ENGLISH_WIKIPEDIA',
      'READING_STARTER_KIND_INVALID');
    requireThat(positiveId(value.pageId) && positiveId(value.revisionId) && key === `wiki_${value.pageId}_${value.revisionId}`,
      'READING_STARTER_ID_INVALID');
    for (const [field, max] of Object.entries({ pageKey: 240, title: 240, description: 500, topicQuery: 120, licenseTitle: 80, attribution: 500 })) {
      requireThat(text(value[field], max), 'READING_STARTER_TEXT_INVALID');
    }
    requireThat(typeof value.revisionTimestamp === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(value.revisionTimestamp),
      'READING_STARTER_REVISION_DATE_INVALID');
    const date = new Date(value.revisionTimestamp);
    requireThat(!Number.isNaN(date.valueOf()) && date.toISOString() === value.revisionTimestamp.replace('Z', '.000Z'),
      'READING_STARTER_REVISION_DATE_INVALID');
    let encodedKey;
    try {
      encodedKey = encodeURIComponent(value.pageKey)
        .replace(/[!'()~]/g, character => '%' + character.charCodeAt(0).toString(16).toUpperCase());
    }
    catch { throw new Error('READING_STARTER_SOURCE_URL_INVALID'); }
    requireThat(value.sourceUrl === `https://en.wikipedia.org/wiki/${encodedKey}`, 'READING_STARTER_SOURCE_URL_INVALID');
    requireThat(value.revisionUrl === `https://en.wikipedia.org/w/index.php?oldid=${value.revisionId}`,
      'READING_STARTER_REVISION_URL_INVALID');
    requireThat(typeof value.licenseUrl === 'string' && /^https:\/\/creativecommons\.org\/licenses\/by-sa\/\d\.\d\/(deed\.en)?$/.test(value.licenseUrl),
      'READING_STARTER_LICENSE_INVALID');
    requireThat(Number.isSafeInteger(value.retrievedAt) && value.retrievedAt >= 0 && value.retrievedAt <= 4_102_444_800_000,
      'READING_STARTER_RETRIEVED_AT_INVALID');
  }
  return records;
}

/** Plan a granular insert or exact retry using every requested child's preflight value. No IO or mutations. */
export function planReadingStarterSeed(records, existingById) {
  const incoming = validateReadingStarters(Buffer.from(JSON.stringify(records)));
  requireThat(object(existingById), 'READING_STARTER_PREFLIGHT_INCOMPLETE');
  const newEntries = {}, reusedIds = [];
  for (const [id, value] of Object.entries(incoming)) {
    requireThat(Object.hasOwn(existingById, id), 'READING_STARTER_PREFLIGHT_INCOMPLETE');
    const existing = existingById[id];
    if (existing === null) newEntries[id] = value;
    else {
      requireThat(isDeepStrictEqual(existing, value), `READING_STARTER_CONFLICT:${id}`);
      reusedIds.push(id);
    }
  }
  return { newEntries, reusedIds, total: Object.keys(incoming).length };
}

export function assertLocalEmulatorHost(host) {
  requireThat(host === '127.0.0.1:9000', 'LOCAL_EMULATOR_REQUIRED: set FIREBASE_DATABASE_EMULATOR_HOST=127.0.0.1:9000');
  return 'http://127.0.0.1:9000';
}
