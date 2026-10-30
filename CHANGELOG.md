# Changelog

## 1.0.0 — 2026-10-30

First public release.

### Weapons
- Five magnetic tool kinds (sword, pickaxe, axe, shovel, hoe) in seven tiers:
  iron, gold, diamond, netherite, end meteorite, chang'e jade, doomsday crystal.
- Magnetic mace.
- Charge up and release to throw a tool; it stays linked to you and can mine or
  attack at range until the link ends.
- Tools fly back along the magnetic chain after 30 seconds, damaging durability
  only for the blocks they pass through.

### Enchantments
- `themagnetweapons:loyalty` — returns the tool instantly when the link breaks,
  costing 80 / 60 / 35 durability by level. This is a standalone enchantment and
  does **not** overwrite the vanilla `minecraft:loyalty` data.
- `wriggler`, `stabilizer`, `reach`, `impact`, `siphon`, `precision`, `harvester`.

### World and items
- Red and blue meteorite magnets forming a polarity field: like poles repel,
  opposite poles attract.
- Unstable area around a thrown tool that speeds up mining.
- Fool's gold ore in stone and deepslate variants, with drops and raw ore.

### Compatibility
- Vanilla loyalty is untouched; anvil-applied vanilla loyalty books still work
  and the higher of the two levels is used.
- Optional support for **oremygod**: the end meteorite, chang'e jade and doomsday
  crystal tiers and their smithing recipes only appear when that mod is present.
- Legacy `themagnetpickaxe` / `magneticweapons` item IDs are no longer registered.

### Credits
- Core framework by **cu6 (bilibili UP @cu6醋)**.
- Integration, expansion and maintenance by THEDIMS.
