const test = require('node:test');
const assert = require('node:assert/strict');
const { classify, getSearchRoots, launchQuickTool } = require('../src/host/windows.cjs');

test('host app categories recognize common desktop launchers', () => {
  assert.equal(classify('Steam', 'Programs/Games'), 'Games');
  assert.equal(classify('Visual Studio Code'), 'Work');
  assert.equal(classify('Windows Terminal'), 'System');
  assert.equal(classify('Spotify'), 'Media');
  assert.equal(classify('Acme Tool'), 'Utilities');
});

test('quick-tool dispatch rejects inherited and unknown keys', async () => {
  assert.deepEqual(await launchQuickTool('constructor', {}), {
    ok: false,
    error: 'Unknown desktop tool.',
  });
  assert.deepEqual(await launchQuickTool('__proto__', {}), {
    ok: false,
    error: 'Unknown desktop tool.',
  });
  assert.deepEqual(await launchQuickTool('missing', {}), {
    ok: false,
    error: 'Unknown desktop tool.',
  });
});

test('Windows catalog roots are derived only from the supplied user environment', () => {
  const roots = getSearchRoots({
    APPDATA: 'C:\\Users\\A\\AppData\\Roaming',
    ProgramData: 'C:\\ProgramData',
    USERPROFILE: 'C:\\Users\\A',
    PUBLIC: 'C:\\Users\\Public',
  });
  assert.equal(roots.length, 4);
  assert.match(roots[0][0].replace(/\\/gu, '/'), /Start Menu\/Programs$/u);
  assert.equal(roots[2][1], 'Desktop');

  assert.deepEqual(getSearchRoots({}), []);
});
