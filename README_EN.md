# The Magnet Weapons

Throwable, recallable magnetic weapons: swords, pickaxes, axes, shovels, hoes and a mace,
across 7 material tiers.

> 中文版见 [README.md](README.md)。

## Requirements

| Item | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.0 or newer (developed against 21.1.176) |
| Java | 21 |

## Optional Dependencies

- **oremygod** (>= 1.1.0): adds the three extra tiers "End Meteorite / Chang'e Jade / Doomsday Crystal".
  Without it these tiers and their smithing recipes simply do not appear; every other tier and all
  gameplay features are unaffected.

## Contents

- **Tools**: iron, gold, diamond, netherite, end meteorite, Chang'e jade and doomsday crystal —
  7 tiers × 5 tool types, plus one magnetic mace.
- **Throwing**: right-click while holding a tool to charge and throw it. While the magnetic link is
  active you can mine and attack remotely. When the link breaks, an enchantment decides whether the
  tool comes back.
- **Magnets**: meteorite magnets with red / blue polarity, which set the direction of the polarity
  field's attraction and repulsion.
- **Polarity field**: same poles repel at close range, opposite poles attract; at least one side must
  be in the attached state.
- **Unstable zone**: a faster-mining area forms around the landing point of a thrown tool.
- **Fool's gold ore**: overworld and deepslate variants, with matching drops and raw ore.

## Enchantments

| ID | Name | Effect |
| --- | --- | --- |
| `themagnetweapons:loyalty` | Loyalty | Returns the tool instantly when the link breaks; costs 80 / 60 / 35 durability by level |
| `themagnetweapons:wriggler` | Wriggler | Slides along surfaces while sprinting until it reaches the pickaxe |
| `themagnetweapons:stabilizer` | Stabilizer | A thrown pickaxe no longer breaks blocks in stable mode |
| `themagnetweapons:reach` | Reach | +7% throw velocity per level |
| `themagnetweapons:impact` | Impact | +10% shockwave damage per level |
| `themagnetweapons:siphon` | Siphon | 3% chance per level to restore 1 durability when breaking a block |
| `themagnetweapons:precision` | Precision | Lets shovels mine pickaxe-class blocks |
| `themagnetweapons:harvester` | Harvester | Pickaxe / axe / hoe drops go straight into your inventory |

## Compatibility Notes

- This mod's Loyalty is a **separate enchantment** (`themagnetweapons:loyalty`). It **does not
  override** the vanilla `minecraft:loyalty` data, so vanilla trident Loyalty keeps working, and it
  never competes with other mods for the same data file.
- The old approach still works: an anvil-applied vanilla Loyalty book on a magnetic tool is
  recognised by the code, which takes the higher of the two levels.
- The legacy namespaces `themagnetpickaxe` / `magneticweapons` are no longer registered. Items from
  those namespaces in upgraded worlds become invalid — recover or replace them first.

## Installation

Drop `themagnetweapons-1.0.0.jar` into your `mods/` folder. Required on both client and server.

## Acknowledgements

This mod would not exist without the following people.

- **Core framework: implemented by cu6醋 (@cu6醋), a content creator on Bilibili.**
  The mod was originally commissioned from @cu6醋. The current multi-tier expansion, throw/recall
  mechanics, polarity field and enchantment system were built on top of that framework.
  Bilibili homepage: <https://space.bilibili.com/240796707/>
- **Integration, extension and maintenance**: THEDIMS.
- **License**: MIT — see [LICENSE](LICENSE). The copyright notice for the core framework is kept
  intact.
