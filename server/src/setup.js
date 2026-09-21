import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const template = readFileSync(new URL('../.env.example', import.meta.url), 'utf8');
const contents = template
  .replace(/^CHILD_DEVICE_TOKEN=$/m, `CHILD_DEVICE_TOKEN=${randomBytes(32).toString('hex')}`)
  .replace(/^GUARDIAN_DEVICE_TOKEN=$/m, `GUARDIAN_DEVICE_TOKEN=${randomBytes(32).toString('hex')}`);
const target = resolve('.env');
try {
  writeFileSync(target, contents, { flag: 'wx', mode: 0o600 });
  console.info('Created .env with two distinct device tokens. Open the file locally to finish Telegram and Firebase setup. Tokens were not printed.');
} catch (error) {
  if (error.code === 'EEXIST') console.error('.env already exists and was preserved. Edit it locally to complete setup.');
  else console.error('Unable to create .env. Check directory permissions.');
  process.exitCode = 1;
}
