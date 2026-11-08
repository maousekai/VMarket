// Minimal .xlsx (zip) editing without dependencies: read every entry, replace a few
// XML parts, write the archive back. Untouched entries keep their original
// compressed bytes, so styles, formulas and the other sheets stay exactly as they were.
import { deflateRawSync, inflateRawSync } from 'node:zlib';

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

export function readZip(buffer) {
  let eocd = buffer.length - 22;
  while (eocd >= 0 && buffer.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  if (eocd < 0) throw new Error('not a zip file');
  const count = buffer.readUInt16LE(eocd + 10);
  let p = buffer.readUInt32LE(eocd + 16);
  const entries = [];
  for (let i = 0; i < count; i++) {
    if (buffer.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central directory');
    const entry = {
      flags: buffer.readUInt16LE(p + 8),
      method: buffer.readUInt16LE(p + 10),
      time: buffer.readUInt16LE(p + 12),
      date: buffer.readUInt16LE(p + 14),
      crc: buffer.readUInt32LE(p + 16),
      compSize: buffer.readUInt32LE(p + 20),
      size: buffer.readUInt32LE(p + 24),
      name: buffer.toString('utf8', p + 46, p + 46 + buffer.readUInt16LE(p + 28)),
    };
    const local = buffer.readUInt32LE(p + 42);
    const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28);
    entry.raw = buffer.subarray(start, start + entry.compSize);
    entries.push(entry);
    p += 46 + buffer.readUInt16LE(p + 28) + buffer.readUInt16LE(p + 30) + buffer.readUInt16LE(p + 32);
  }
  return entries;
}

export function entryText(entry) {
  return (entry.method === 0 ? entry.raw : inflateRawSync(entry.raw)).toString('utf8');
}

export function setEntryText(entry, text) {
  const data = Buffer.from(text, 'utf8');
  entry.raw = deflateRawSync(data);
  entry.method = 8;
  entry.crc = crc32(data);
  entry.size = data.length;
  entry.compSize = entry.raw.length;
}

export function writeZip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const e of entries) {
    const name = Buffer.from(e.name, 'utf8');
    const flags = e.flags & 0x0800; // keep UTF-8 bit; sizes are known, no data descriptor
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(flags, 6);
    local.writeUInt16LE(e.method, 8);
    local.writeUInt16LE(e.time, 10);
    local.writeUInt16LE(e.date, 12);
    local.writeUInt32LE(e.crc, 14);
    local.writeUInt32LE(e.raw.length, 18);
    local.writeUInt32LE(e.size, 22);
    local.writeUInt16LE(name.length, 26);
    locals.push(local, name, e.raw);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(flags, 8);
    central.writeUInt16LE(e.method, 10);
    central.writeUInt16LE(e.time, 12);
    central.writeUInt16LE(e.date, 14);
    central.writeUInt32LE(e.crc, 16);
    central.writeUInt32LE(e.raw.length, 20);
    central.writeUInt32LE(e.size, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, name);
    offset += 30 + name.length + e.raw.length;
  }
  const centralSize = centrals.reduce((n, b) => n + b.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, ...centrals, end]);
}

// ---------- sheet helpers ----------
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const colIndex = (ref) => ref.match(/^[A-Z]+/)[0].split('').reduce((n, c) => n * 26 + c.charCodeAt(0) - 64, 0);

export function sharedStrings(entries) {
  const e = entries.find((x) => x.name === 'xl/sharedStrings.xml');
  if (!e) return [];
  return [...entryText(e).matchAll(/<si>([\s\S]*?)<\/si>/g)].map((m) =>
    [...m[1].matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map((t) => t[1]).join(''),
  );
}

// Path of the worksheet XML for a sheet name.
export function sheetPath(entries, sheetName) {
  const workbook = entryText(entries.find((e) => e.name === 'xl/workbook.xml'));
  const sheet = [...workbook.matchAll(/<sheet [^>]*name="([^"]+)"[^>]*r:id="([^"]+)"/g)].find((m) => m[1] === esc(sheetName));
  if (!sheet) throw new Error(`sheet "${sheetName}" not found`);
  const rels = entryText(entries.find((e) => e.name === 'xl/_rels/workbook.xml.rels'));
  const target = rels.match(new RegExp(`<Relationship [^>]*Id="${sheet[2]}"[^>]*Target="([^"]+)"`)) ||
    rels.match(new RegExp(`<Relationship [^>]*Target="([^"]+)"[^>]*Id="${sheet[2]}"`));
  return `xl/${target[1].replace(/^\/?xl\//, '')}`;
}

// Text value of every cell in a column: { rowNumber: text }.
export function columnValues(xml, column, strings) {
  const out = {};
  for (const m of xml.matchAll(new RegExp(`<c r="${column}(\\d+)"([^>]*?)(?:/>|>([\\s\\S]*?)</c>)`, 'g'))) {
    const [, row, attrs, inner = ''] = m;
    const v = (inner.match(/<v>([\s\S]*?)<\/v>/) || [])[1];
    const t = (attrs.match(/t="(\w+)"/) || [])[1];
    out[row] = t === 's' ? strings[Number(v)] : t === 'inlineStr' ? (inner.match(/<t[^>]*>([\s\S]*?)<\/t>/) || [])[1] : v;
  }
  return out;
}

// Set a cell to an inline string, keeping its style (or styleFrom's style if the cell is new).
export function setCell(xml, ref, text, styleFrom) {
  const row = ref.match(/\d+$/)[0];
  const cellRe = new RegExp(`<c r="${ref}"([^>]*?)(?:/>|>[\\s\\S]*?</c>)`);
  const existing = xml.match(cellRe);
  let style = existing ? (existing[1].match(/\ss="(\d+)"/) || [])[1] : undefined;
  if (style === undefined && styleFrom) {
    const from = xml.match(new RegExp(`<c r="${styleFrom}"([^>]*?)(?:/>|>)`));
    style = from ? (from[1].match(/\ss="(\d+)"/) || [])[1] : undefined;
  }
  const cell = `<c r="${ref}"${style !== undefined ? ` s="${style}"` : ''} t="inlineStr"><is><t>${esc(text)}</t></is></c>`;
  if (existing) return xml.replace(cellRe, cell);

  const rowRe = new RegExp(`(<row r="${row}"[^>]*?)(/>|>([\\s\\S]*?)</row>)`);
  const rowMatch = xml.match(rowRe);
  if (!rowMatch) throw new Error(`row ${row} not found`);
  const cells = [...(rowMatch[3] || '').matchAll(/<c r="([A-Z]+)\d+"[\s\S]*?(?:\/>|<\/c>)/g)];
  const after = cells.find((c) => colIndex(c[1]) > colIndex(ref));
  const inner = rowMatch[3] || '';
  const newInner = after ? inner.replace(after[0], cell + after[0]) : inner + cell;
  return xml.replace(rowRe, `${rowMatch[1]}>${newInner}</row>`);
}
