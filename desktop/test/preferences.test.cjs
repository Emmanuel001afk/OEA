const test = require('node:test');
const assert = require('node:assert/strict');
const {
  DEFAULT_PREFERENCES,
  MAX_RECENTS,
  recordLaunch,
  sanitizePreferences,
  setAccent,
  toggleFavorite,
} = require('../src/shared/preferences.cjs');

test('corrupt and oversized state is normalized to a bounded safe shape', () => {
  const state = sanitizePreferences({
    favorites: ['one', 'one', '', null, 'two'],
    recent: Array.from({ length: 40 }, (_, index) => `app-${index}`),
    accent: 'remote-css-value',
  });
  assert.deepEqual(state.favorites, ['one', 'two']);
  assert.equal(state.recent.length, MAX_RECENTS);
  assert.equal(state.accent, DEFAULT_PREFERENCES.accent);
});

test('favorite toggles are idempotent and reject invalid ids', () => {
  const first = toggleFavorite(DEFAULT_PREFERENCES, 'app:notes');
  assert.deepEqual(first.favorites, ['app:notes']);
  assert.deepEqual(toggleFavorite(first, 'app:notes').favorites, []);
  assert.deepEqual(toggleFavorite(first, ' '.repeat(4)).favorites, ['app:notes']);
});

test('recent apps move to the front and are capped', () => {
  let state = DEFAULT_PREFERENCES;
  for (let index = 0; index < MAX_RECENTS + 3; index += 1) {
    state = recordLaunch(state, `app-${index}`);
  }
  state = recordLaunch(state, 'app-4');
  assert.equal(state.recent.length, MAX_RECENTS);
  assert.equal(state.recent[0], 'app-4');
  assert.equal(state.recent.filter((id) => id === 'app-4').length, 1);
});

test('accent changes accept only known presets', () => {
  assert.equal(setAccent(DEFAULT_PREFERENCES, 'violet').accent, 'violet');
  assert.equal(setAccent(DEFAULT_PREFERENCES, 'javascript:alert(1)').accent, 'teal');
});
