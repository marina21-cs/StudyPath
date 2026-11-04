import { createHash } from 'node:crypto';
import { CandidateError } from './validate-candidate.mjs';

const check = value => { if (!value) throw new CandidateError('LESSON_RUNTIME_INVALID'); };
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const trim = value => value.replace(/^[\s\u001c-\u001f]+|[\s\u001c-\u001f]+$/g, '');
const unique = values => check(new Set(values).size === values.length);
const fields = (value, names) => check(record(value) && Object.keys(value).length === names.length &&
  names.every(name => Object.hasOwn(value, name)));
const text = (value, limit) => {
  check(typeof value === 'string' && trim(value).length > 0 && value.length <= limit); return value;
};
const key = value => { const result = text(value.id, 64); check(/^[A-Za-z0-9_-]{1,64}$/.test(result)); return result; };
const rows = (value, minimum, maximum) => {
  check(Array.isArray(value) && value.length >= minimum && value.length <= maximum && value.every(record)); return value;
};
const strings = (value, minimum, maximum, limit = 64) => {
  check(Array.isArray(value) && value.length >= minimum && value.length <= maximum);
  value.forEach(item => text(item, limit)); unique(value); return value;
};

function unicode(value) {
  for (let i = 0; i < value.length; i++) {
    const unit = value.charCodeAt(i);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const following = value.charCodeAt(++i); check(following >= 0xdc00 && following <= 0xdfff);
    } else check(unit < 0xdc00 || unit > 0xdfff);
  }
}

/** Preserve reviewed bytes; reject the ambiguity JSON.parse silently accepts, as native TextPackageJson does. */
function strictObject(raw) {
  check(typeof raw === 'string'); unicode(raw);
  let index = 0; const numbers = new Map();
  const space = () => { while (/[\x20\t\r\n]/.test(raw[index] ?? '') && index < raw.length) index++; };
  const token = () => {
    const start = index; check(raw[index++] === '"');
    while (index < raw.length) {
      const char = raw[index++];
      if (char === '"') { const result = JSON.parse(raw.slice(start, index)); unicode(result); return result; }
      check(char.charCodeAt(0) >= 32);
      if (char === '\\') {
        const escaped = raw[index++];
        if (escaped === 'u') { check(/^[a-fA-F0-9]{4}$/.test(raw.slice(index, index + 4))); index += 4; }
        else check(typeof escaped === 'string' && '"\\/bfnrt'.includes(escaped));
      }
    }
    check(false);
  };
  const value = (depth, path) => {
    check(depth < 64); space();
    const first = raw[index];
    if (first === '{') {
      index++; space(); const names = new Set();
      if (raw[index] !== '}') while (true) {
        space(); const name = token(); check(!names.has(name)); names.add(name);
        space(); check(raw[index++] === ':'); value(depth + 1, [...path, name]); space();
        if (raw[index] !== ',') break;
        index++;
      }
      check(raw[index++] === '}');
    } else if (first === '[') {
      index++; space(); let item = 0;
      if (raw[index] !== ']') while (true) {
        value(depth + 1, [...path, item++]); space(); if (raw[index] !== ',') break; index++;
      }
      check(raw[index++] === ']');
    } else if (first === '"') token();
    else {
      const literal = /^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(raw.slice(index));
      check(literal);
      if (/^-?[0-9]/.test(literal[0])) numbers.set(JSON.stringify(path), literal[0]);
      index += literal[0].length;
    }
  };
  value(0, []); space(); check(index === raw.length);
  const result = JSON.parse(raw); check(record(result)); return { result, numbers };
}

// Native integer fields use BigDecimal.longValueExact(), while JSON.parse rounds decimals.
// Compare their original decimal tokens without constructing unbounded powers of ten.
function exactInteger(numbers, path, expected) {
  const raw = numbers.get(JSON.stringify(path)); check(typeof raw === 'string' && Number.isSafeInteger(expected));
  const [coefficient, exponent = '0'] = raw.toLowerCase().split('e');
  const negative = coefficient.startsWith('-');
  const [whole, fraction = ''] = coefficient.replace(/^-/, '').split('.');
  const digits = (whole + fraction).replace(/^0+/, '');
  if (!digits) { check(expected === 0); return; }
  check(!negative && Number.isSafeInteger(Number(exponent)));
  const shift = Number(exponent) - fraction.length;
  let integer;
  if (shift >= 0) {
    check(digits.length + shift <= 16); integer = BigInt(digits + '0'.repeat(shift));
  } else {
    check(-shift < digits.length && /^0*$/.test(digits.slice(digits.length + shift)));
    integer = BigInt(digits.slice(0, digits.length + shift));
  }
  check(integer === BigInt(expected));
}

function question(q) {
  const common = ['id', 'prompt', 'format', 'hint', 'explanation'];
  key(q); text(q.prompt, 1000); text(q.format, 40); text(q.hint, 1000); text(q.explanation, 2000);
  if (q.format === 'selected_response' || q.format === 'ordering') {
    fields(q, [...common, 'options', 'answer']);
    const options = rows(q.options, 2, 12).map(option => {
      fields(option, ['id', 'text', 'feedback']); text(option.text, 500); text(option.feedback, 1000); return key(option);
    });
    unique(options); const answer = strings(q.answer, 1, 12);
    check(answer.every(id => options.includes(id)));
    check(q.format === 'selected_response' ? answer.length === 1 : answer.length === options.length);
  } else {
    check(q.format === 'bounded_response'); text(q.responseType, 20);
    check(typeof q.unit === 'string' && q.unit.length <= 40);
    if (q.responseType === 'numeric') {
      fields(q, [...common, 'responseType', 'unit', 'target', 'tolerance']);
      check(typeof q.target === 'number' && Number.isFinite(q.target) && q.target >= -1e9 && q.target <= 1e9 &&
        typeof q.tolerance === 'number' && Number.isFinite(q.tolerance) && q.tolerance >= 0 && q.tolerance <= 1e6);
    } else {
      check(q.responseType === 'short'); fields(q, [...common, 'responseType', 'unit', 'acceptedAnswers', 'caseSensitive']);
      check(typeof q.caseSensitive === 'boolean');
      unique(strings(q.acceptedAnswers, 1, 20, 80).map(answer => q.caseSensitive ? trim(answer) : trim(answer).toLowerCase()));
    }
  }
}

/** Publication gate matching Android InteractiveLessonParser; the shared corpus runs in both runtimes. */
export function validateRuntimeLesson(manifestJson, textJson) {
  try {
    const parsedManifest = strictObject(manifestJson), parsedPayload = strictObject(textJson);
    const manifest = parsedManifest.result, payload = parsedPayload.result;
    for (const name of ['schemaVersion', 'version']) {
      exactInteger(parsedManifest.numbers, [name], manifest[name]);
      exactInteger(parsedPayload.numbers, [name], payload[name]);
    }
    exactInteger(parsedManifest.numbers, ['text', 'bytes'], manifest.text.bytes);
    manifest.assets.forEach((asset, index) => exactInteger(parsedManifest.numbers, ['assets', index, 'bytes'], asset.bytes));
    if (manifest.assetPolicy) for (const name of ['maxCount', 'maxFileBytes', 'maxTotalBytes']) {
      exactInteger(parsedManifest.numbers, ['assetPolicy', name], manifest.assetPolicy[name]);
    }
    fields(payload, ['schemaVersion', 'moduleId', 'version', 'lessons', 'questions', 'glossary']);
    check(payload.schemaVersion === 1 && payload.moduleId === manifest.moduleId && payload.version === manifest.version);
    const objectives = rows(manifest.objectives, 1, 100).map(entry => {
      fields(entry, ['id', 'description']); text(entry.description, 500); return key(entry);
    });
    unique(objectives);
    const concepts = rows(manifest.concepts, 1, 100).map(key); unique(concepts);
    const questions = rows(payload.questions, 1, 500); questions.forEach(question);
    const questionIds = questions.map(key); unique(questionIds);
    const metadata = rows(manifest.questions, 1, 500);
    metadata.forEach(entry => {
      fields(entry, ['id', 'objectiveId', 'conceptIds', 'format']);
      check(objectives.includes(text(entry.objectiveId, 64)));
      check(strings(entry.conceptIds, 1, 100).every(id => concepts.includes(id)));
      text(entry.format, 40);
    });
    const metadataIds = metadata.map(key); unique(metadataIds);
    check(metadataIds.length === questionIds.length && metadataIds.every(id => questionIds.includes(id)));
    questions.forEach(q => check(metadata.find(entry => entry.id === q.id).format === q.format));
    const lessons = rows(payload.lessons, 1, 50);
    const allSteps = [];
    lessons.forEach(lesson => {
      fields(lesson, ['id', 'title', 'steps']); key(lesson); text(lesson.title, 160);
      rows(lesson.steps, 1, 1000).forEach(step => {
        check(['READING', 'EXAMPLE', 'CHECK'].includes(step.kind));
        fields(step, ['id', 'kind', 'title', 'body', ...(step.kind === 'CHECK' ? ['questionId'] : [])]);
        allSteps.push(key(step)); text(step.title, 160); text(step.body, 8000);
        if (step.kind === 'CHECK') check(questionIds.includes(text(step.questionId, 64)));
      });
      check(lesson.steps.some(step => step.kind === 'READING') && lesson.steps.some(step => step.kind === 'EXAMPLE') &&
        lesson.steps.filter(step => step.kind === 'CHECK').length >= 2);
    });
    unique(lessons.map(key)); unique(allSteps);
    const inventories = rows(manifest.lessons, 1, 50), used = [], reviewSteps = [], reviewIds = [];
    check(inventories.length === lessons.length);
    inventories.forEach((inventory, index) => {
      const lesson = lessons[index];
      fields(inventory, ['id', 'objectiveIds', 'stepIds', 'questionIds', 'reviewQuestionIds', 'conceptIds']);
      check(key(inventory) === lesson.id);
      check(JSON.stringify(strings(inventory.stepIds, 1, 1000)) === JSON.stringify(lesson.steps.map(step => step.id)));
      check(strings(inventory.objectiveIds, 1, 100).every(id => objectives.includes(id)));
      check(strings(inventory.conceptIds, 1, 100).every(id => concepts.includes(id)));
      const checks = strings(inventory.questionIds, 1, 500), reviews = strings(inventory.reviewQuestionIds, 1, 500);
      check(JSON.stringify(checks) === JSON.stringify(lesson.steps.filter(step => step.kind === 'CHECK').map(step => step.questionId)));
      used.push(...checks, ...reviews);
      const review = 'review_' + createHash('sha256').update(lesson.id).digest('hex').slice(0, 24);
      reviewIds.push(review); reviewSteps.push(`${review}_goal`, `${review}_example`, ...reviews.map((_id, i) => `${review}_q${i + 1}`));
    });
    unique(used); check(used.length === questionIds.length && used.every(id => questionIds.includes(id)));
    unique([...lessons.map(key), ...reviewIds]); unique([...allSteps, ...reviewSteps]);
    const terms = rows(payload.glossary, 1, 100).map(entry => {
      fields(entry, ['term', 'definition']); text(entry.definition, 1000); return text(entry.term, 80);
    });
    unique(terms);
  } catch (error) {
    if (error instanceof CandidateError) throw error;
    throw new CandidateError('LESSON_RUNTIME_INVALID');
  }
}
