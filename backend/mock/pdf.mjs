// Small deterministic, original text PDFs for the isolated fixture. Courier uses stable fixed-width metrics.
const literal = value => value.replace(/[^\x20-\x7e]/g, '-').replace(/[\\()]/g, char => '\\' + char);
const wrap = (value, width = 84) => {
  const lines = []; let line = '';
  for (const word of value.split(/\s+/)) {
    if (line && line.length + word.length + 1 > width) { lines.push(line); line = ''; }
    line += (line ? ' ' : '') + word;
  }
  if (line) lines.push(line);
  return lines;
};
export function modulePdf(module) {
  const lines = [module.title, 'SYNTHETIC LOCAL DEMO - NOT INSTITUTIONAL CONTENT OR APPROVAL', '',
    ...wrap('Objective: ' + module.objective), '', ...wrap(module.introduction), '', 'WORKED EXAMPLE',
    ...wrap(module.example), '', 'CHECK AND CORRECTION', ...wrap(module.guided.prompt),
    ...wrap(module.guided.explanation), '', ...wrap(module.bridge), '', 'INDEPENDENT APPLICATION',
    ...wrap(module.independent.prompt), ...wrap(module.independent.explanation), '', 'GLOSSARY',
    ...module.glossary.flatMap(item => wrap(item.term + ': ' + item.definition)), '', ...wrap(module.summary), '',
    ...wrap('Original mock fixture. No real learner data, school policy, signatures or redistribution review.'),
    ...wrap('Version 1. Accessible lesson text and authored checks are included separately in this package.')];
  const pages = [];
  for (let i = 0; i < lines.length; i += 50) pages.push(lines.slice(i, i + 50));
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '', '<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>'];
  const pageIds = [];
  pages.forEach((page, index) => {
    const pageId = objects.length + 1, contentId = pageId + 1; pageIds.push(pageId);
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents ${contentId} 0 R >>`);
    const stream = 'BT /F1 9.5 Tf 14 TL 44 796 Td\n' + page.map(line => `(${literal(line)}) Tj T*`).join('\n') +
      `\nET\nBT /F1 8 Tf 44 28 Td (MOCK ONLY - ${module.id} v1 - page ${index + 1}/${pages.length}) Tj ET\n`;
    objects.push(`<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}endstream`);
  });
  objects[1] = `<< /Type /Pages /Kids [${pageIds.map(id => `${id} 0 R`).join(' ')}] /Count ${pageIds.length} >>`;
  let pdf = '%PDF-1.4\n'; const offsets = [0];
  objects.forEach((object, index) => { offsets.push(Buffer.byteLength(pdf)); pdf += `${index + 1} 0 obj\n${object}\nendobj\n`; });
  const xref = Buffer.byteLength(pdf);
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n` + offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(pdf);
}
