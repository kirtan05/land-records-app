// The absolute path of this repository, resolved from this file's own location.
// Every script imports REPO instead of hardcoding a home directory, so the repo
// works from any clone path. Override with IRMSC_ROOT if you relocate the data.
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

export const REPO = process.env.IRMSC_ROOT
  || resolve(dirname(fileURLToPath(import.meta.url)), '..');

export default REPO;
