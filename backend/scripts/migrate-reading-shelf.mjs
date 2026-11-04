import { migrateReadingShelf } from '../materials/reading-shelf-migration.mjs';

const args = process.argv.slice(2);
if (args.length < 2 || args.length > 3 || (args[2] !== undefined && args[2] !== '--apply')) {
  throw new Error('Usage: node scripts/migrate-reading-shelf.mjs DEMO_NAMESPACE UID [--apply] (local emulator only; default is dry-run)');
}
console.log(JSON.stringify(await migrateReadingShelf({ namespace: args[0], uid: args[1], apply: args[2] === '--apply' }), null, 2));
