import { test } from 'node:test';
import assert from 'node:assert/strict';
import { driveUrls } from '../lib/drive.mjs';

test('parses an escaped file/d/<id>/view link', () => {
  const out = driveUrls('https:\\/\\/drive.google.com\\/file\\/d\\/1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB\\/view');
  assert.equal(out.driveFileId, '1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB');
  assert.equal(out.viewUrl, 'https://drive.google.com/file/d/1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB/view');
  assert.equal(out.downloadUrl, 'https://drive.google.com/uc?export=download&id=1r-hkIUZHke5Ei4bi5hMlroBfLGbEU_cB');
});

test('parses an open?id= link', () => {
  assert.equal(driveUrls('https://drive.google.com/open?id=ABC123').driveFileId, 'ABC123');
});

test('returns null for a missing or non-Drive link', () => {
  assert.equal(driveUrls(null), null);
  assert.equal(driveUrls(''), null);
  assert.equal(driveUrls('https://example.com/x.pdf'), null);
});
