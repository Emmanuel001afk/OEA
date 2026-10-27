# Send Off — controls & inventory intelligence (2026-10-27)

Investigation of the reference mobile shooters (PUBG Mobile, Call of Duty Mobile,
Free Fire) plus OEA's own games (runner, chess), to decide what to build next in
Send Off's controls and inventory.

## 1. What the reference games do

### PUBG Mobile / PUBG PC
- **Throwables (6 kinds):** frag, smoke, stun (flashbang), molotov, decoy, C4/sticky.
  PUBG's throwables wheel (update 8.2) has 6 slots: frag/molotov/smoke/stun permanent,
  sticky+C4 and decoy+spike in dynamic slots.
- **Cook-off:** pull-pin (R) shortens the fuse while held — the universal grenade mechanic.
- **Attachments:** scope, suppressor, grip, stock, magazine, barrel — per-weapon stat changes.
- **Reload-all** from the inventory; **auto-pickup** setting.

### Call of Duty Mobile / Warzone Mobile
- **Tacticals vs lethals:** stun, flash, smoke, decoy, stim, snapshot (tactical);
  frag, semtex, C4, molotov, claymore, proximity mine, thermalite (lethal).
- **Perks/perk packages** (movement, reload speed, explosive resistance); **resupply**
  perk refills equipment over time.
- **Ping system** (marks for squads); **operator skills**.

### Free Fire
- **Prone** (Z / long-press crouch) — standard BR movement trio: walk/crouch/prone.
- **Bag/inventory** (TAB) with looting management; **auto-pick** option.
- **Character skill slots:** 1 active + 3 passive per loadout.
- **Cook grenade** (hold to cook), **revive teammate**, supply crates, loot tier display.

### OEA's own games (consistency)
- **Runner:** swipe gestures with a deliberate threshold — one gesture per input.
- **Chess:** drag-and-drop + pinch/orbit — one gesture per action, no overlap.

### Lessons for Send Off
1. One gesture/button per action; long-press opens a second layer (crouch→prone,
   grenade→cook) — exactly the runner's "deliberate" threshold philosophy.
2. Throwables are a *family*: frag + smoke are done; stun and molotov are the
   next-most-universal additions (stun is PUBG's #3 and CoD's default tactical).
3. The inventory is a *screen*, not a HUD strip: Free Fire's bag and PUBG's
   inventory manage weapons, ammo, consumables and throwables in one panel.
4. Cook-off is expected behavior in every reference title.

## 2. Adopted this pass (implemented)

| Enhancement | Source | Status |
|---|---|---|
| Stun grenade (flash + slow, 3 s) | PUBG #3 throwable, CoD default tactical | ✅ done |
| Cook-off — hold FRAG to shorten the fuse | PUBG pull-pin, Free Fire cook | ✅ done |
| Prone — long-press crouch (button + Z) | Free Fire / PUBG Mobile movement trio | ✅ done |
| Reload-all | PUBG Mobile inventory | ✅ done |
| Pause pack screen — weapons, ammo, consumables, throwables | Free Fire bag / PUBG inventory | ✅ done |

## 3. Deferred (documented, not built)
- Molotov / C4 / decoy (more throwable kinds; same pipeline as stun).
- Attachments (per-weapon stat pipeline — larger).
- Perks / character skills (CoD/Free Fire loadout systems).
- Ping system (needs squad net-sync surface).
