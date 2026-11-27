const MAX_FAVORITES = 80;
const MAX_RECENTS = 16;
const ACCENTS = new Set(['teal', 'blue', 'violet', 'amber']);

const DEFAULT_PREFERENCES = Object.freeze({
  version: 1,
  favorites: [],
  recent: [],
  accent: 'teal',
});

function cleanIds(value, limit) {
  if (!Array.isArray(value)) return [];
  const seen = new Set();
  const result = [];
  for (const candidate of value) {
    if (typeof candidate !== 'string') continue;
    const id = candidate.trim();
    if (!id || id.length > 240 || seen.has(id)) continue;
    seen.add(id);
    result.push(id);
    if (result.length >= limit) break;
  }
  return result;
}

function sanitizePreferences(value) {
  const source = value && typeof value === 'object' ? value : {};
  return {
    version: 1,
    favorites: cleanIds(source.favorites, MAX_FAVORITES),
    recent: cleanIds(source.recent, MAX_RECENTS),
    accent: ACCENTS.has(source.accent) ? source.accent : DEFAULT_PREFERENCES.accent,
  };
}

function toggleFavorite(preferences, id) {
  const state = sanitizePreferences(preferences);
  if (typeof id !== 'string' || !id.trim() || id.length > 240) return state;
  const favorites = new Set(state.favorites);
  if (favorites.has(id)) favorites.delete(id);
  else if (favorites.size < MAX_FAVORITES) favorites.add(id);
  return { ...state, favorites: [...favorites] };
}

function recordLaunch(preferences, id) {
  const state = sanitizePreferences(preferences);
  if (typeof id !== 'string' || !id.trim() || id.length > 240) return state;
  return {
    ...state,
    recent: [id, ...state.recent.filter((item) => item !== id)].slice(0, MAX_RECENTS),
  };
}

function setAccent(preferences, accent) {
  const state = sanitizePreferences(preferences);
  return { ...state, accent: ACCENTS.has(accent) ? accent : state.accent };
}

module.exports = {
  ACCENTS: [...ACCENTS],
  DEFAULT_PREFERENCES,
  MAX_FAVORITES,
  MAX_RECENTS,
  recordLaunch,
  sanitizePreferences,
  setAccent,
  toggleFavorite,
};
