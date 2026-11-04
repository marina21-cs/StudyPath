import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { READING_STARTER_LIMIT_BYTES } from './reading-starters.mjs';

/** Read a small regular file, including a hard cap if it grows after the size check. */
export async function readReadingStarterBytes(path) {
  let handle;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const stat = await handle.stat();
    if (!stat.isFile()) throw new Error('READING_STARTER_FILE_INVALID');
    if (stat.size >= READING_STARTER_LIMIT_BYTES) throw new Error('READING_STARTER_INPUT_LIMIT');
    const bytes = Buffer.alloc(READING_STARTER_LIMIT_BYTES);
    let total = 0;
    while (total < bytes.length) {
      const { bytesRead } = await handle.read(bytes, total, bytes.length - total, null);
      if (bytesRead === 0) return bytes.subarray(0, total);
      total += bytesRead;
    }
    throw new Error('READING_STARTER_INPUT_LIMIT');
  } catch (error) {
    if (error.message === 'READING_STARTER_INPUT_LIMIT') throw error;
    throw new Error('READING_STARTER_FILE_INVALID', { cause: error });
  } finally { await handle?.close(); }
}
