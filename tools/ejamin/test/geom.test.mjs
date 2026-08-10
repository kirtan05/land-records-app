import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bbox, area, contains, adjacency } from '../lib/geom.mjs';

const sq = (x, y, s = 10) => [[x, y], [x + s, y], [x + s, y + s], [x, y + s]];

test('bbox and area of a unit-ish square', () => {
  assert.deepEqual(bbox(sq(0, 0)), [0, 0, 10, 10]);
  assert.equal(area(sq(0, 0)), 100);
});

test('contains handles inside, outside and a concave notch', () => {
  assert.equal(contains(sq(0, 0), [5, 5]), true);
  assert.equal(contains(sq(0, 0), [15, 5]), false);
  const L = [[0, 0], [10, 0], [10, 4], [4, 4], [4, 10], [0, 10]];
  assert.equal(contains(L, [2, 8]), true);
  assert.equal(contains(L, [8, 8]), false);
});

test('adjacency links only parcels sharing an edge', () => {
  // 0 and 1 share the x=10 edge; 2 sits diagonally, touching only at a corner.
  const polys = [sq(0, 0), sq(10, 0), sq(30, 30)];
  const adj = adjacency(polys);
  assert.deepEqual(adj[0], [1]);
  assert.deepEqual(adj[1], [0]);
  assert.deepEqual(adj[2], []);
});
