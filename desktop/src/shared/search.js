(function attachOeaSearch(root, factory) {
  const search = factory();
  if (typeof module === 'object' && module.exports) module.exports = search;
  if (root) root.OeaSearch = search;
})(typeof globalThis !== 'undefined' ? globalThis : this, function createOeaSearch() {
  const combiningMarks = /\p{Mn}+/gu;
  const collator = new Intl.Collator(undefined, { sensitivity: 'base', numeric: true });

  function normalize(value) {
    return String(value ?? '')
      .normalize('NFD')
      .replace(combiningMarks, '')
      .toLocaleLowerCase();
  }

  class SearchIndex {
    constructor(limit = 4096) {
      this.limit = Math.max(1, Number(limit) || 4096);
      this.entries = new Map();
    }

    entry(key, label, packageName = '') {
      const cacheKey = `${key}\u0000${label}`;
      const cached = this.entries.get(cacheKey);
      if (cached) return cached;

      const normalized = normalize(label);
      const words = normalized.split(/[\s._-]+/u).filter(Boolean);
      const first = normalize(String(label).trim()).charAt(0).toLocaleUpperCase();
      const entry = Object.freeze({
        normalized,
        words,
        initials: words.map((word) => word.slice(0, 1)).join(''),
        packageLower: String(packageName).toLocaleLowerCase(),
        initialChar: !first ? null : /^[A-Z]$/u.test(first) ? first : '#',
      });

      if (this.entries.size >= this.limit) this.entries.clear();
      this.entries.set(cacheKey, entry);
      return entry;
    }

    score(entry, query) {
      if (entry.normalized.startsWith(query)) return 0;
      if (entry.words.some((word) => word.startsWith(query))) return 1;
      if (query.length >= 2 && entry.initials.startsWith(query)) return 2;
      if (entry.normalized.includes(query)) return 3;
      if (entry.packageLower.includes(query)) return 4;
      return null;
    }

    clear() {
      this.entries.clear();
    }

    size() {
      return this.entries.size;
    }
  }

  function searchApps(apps, rawQuery = '', options = {}) {
    const query = normalize(String(rawQuery).trim());
    const index = options.index || new SearchIndex();
    const recentOrder = new Map((options.recentIds || []).map((id, position) => [id, position]));

    if (!query) {
      return [...apps].sort((a, b) => collator.compare(a.name || '', b.name || ''));
    }

    return apps
      .map((app) => {
        const entry = index.entry(
          app.id,
          app.name || '',
          app.searchKey || app.appId || app.path || app.id || ''
        );
        const score = index.score(entry, query);
        return score === null ? null : { app, score };
      })
      .filter(Boolean)
      .sort((left, right) => {
        if (left.score !== right.score) return left.score - right.score;
        const leftRecent = recentOrder.has(left.app.id) ? recentOrder.get(left.app.id) : Number.MAX_SAFE_INTEGER;
        const rightRecent = recentOrder.has(right.app.id) ? recentOrder.get(right.app.id) : Number.MAX_SAFE_INTEGER;
        if (leftRecent !== rightRecent) return leftRecent - rightRecent;
        return collator.compare(left.app.name || '', right.app.name || '');
      })
      .map((result) => result.app);
  }

  return Object.freeze({ SearchIndex, normalize, searchApps });
});
