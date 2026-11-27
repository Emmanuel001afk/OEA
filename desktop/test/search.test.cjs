const test = require('node:test');
const assert = require('node:assert/strict');
const { SearchIndex, normalize, searchApps } = require('../src/shared/search.js');

const apps = [
  { id: 'vscode', name: 'Visual Studio Code', searchKey: 'Microsoft.Code' },
  { id: 'terminal', name: 'Windows Terminal', searchKey: 'Microsoft.WindowsTerminal' },
  { id: 'calculator', name: 'Calculator', searchKey: 'Microsoft.WindowsCalculator' },
  { id: 'cafe', name: 'Café Notes', searchKey: 'oea.notes' },
];

test('normalization folds accents and case like OEA drawer search', () => {
  assert.equal(normalize('CAFÉ'), 'cafe');
});

test('search ranks exact label prefix before word prefixes and substrings', () => {
  const ranked = searchApps(apps, 'win');
  assert.deepEqual(ranked.map((app) => app.id), ['terminal', 'calculator']);

  const wordPrefix = searchApps(apps, 'studio');
  assert.deepEqual(wordPrefix.map((app) => app.id), ['vscode']);
});

test('search supports initials, accents, and package/app identifiers', () => {
  assert.deepEqual(searchApps(apps, 'vs').map((app) => app.id), ['vscode']);
  assert.deepEqual(searchApps(apps, 'cafe').map((app) => app.id), ['cafe']);
  assert.deepEqual(searchApps(apps, 'windowscalculator').map((app) => app.id), ['calculator']);
});

test('recent launches break equal-score ties and the index stays bounded', () => {
  const twins = [
    { id: 'a', name: 'Reader Alpha' },
    { id: 'b', name: 'Reader Beta' },
  ];
  assert.deepEqual(searchApps(twins, 'reader', { recentIds: ['b'] }).map((app) => app.id), ['b', 'a']);

  const index = new SearchIndex(2);
  index.entry('a', 'One', 'one');
  index.entry('b', 'Two', 'two');
  index.entry('c', 'Three', 'three');
  assert.equal(index.size(), 1);
});

test('blank search returns a stable alphabetical app catalogue', () => {
  assert.deepEqual(searchApps(apps).map((app) => app.id), ['cafe', 'calculator', 'vscode', 'terminal']);
});
