import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { extractToken, extractTpMapData } from '../lib/session.mjs';

const html = readFileSync(new URL('./fixtures/homepage.html', import.meta.url), 'utf8');

test('extractToken pulls the 40-char CSRF token', () => {
  const tok = extractToken(html);
  assert.match(tok, /^[A-Za-z0-9]{40}$/);
});

test('extractTpMapData returns TP schemes keyed by district id', () => {
  const data = extractTpMapData(html);
  const keys = Object.keys(data);
  assert.ok(keys.length > 0, 'expected at least one district key');
  const first = data[keys[0]][0];
  assert.ok(typeof first.tp_title === 'string');
  assert.match(first.link, /^https:\/\/drive\.google\.com\//);
});
