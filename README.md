# Sky's Structure Map

**[⬇ Download the latest version](https://github.com/SkyStormer1/SkysStructureMap/releases/latest)**
&nbsp;·&nbsp; Minecraft 26.2 · Fabric · client-side

A client-side Fabric mod that remembers the structures you come across, so you never have to write down coordinates
again. It puts every structure you find on **Xaero's World Map and Minimap**, with its own icon.

![The structures it finds](docs/structures.png)

> **Alpha.** This is an early version for testing. It works, but expect rough edges and changes between versions.

## How it works

As you explore, the mod recognises structures from the blocks your game has already loaded. It doesn't need the
seed, a server plugin or any help from the server, and nothing in it works out or uses the world seed. When you
come within 32 blocks of a structure, it's saved as discovered and shows up on your map.

Each kind of structure is recognised from blocks that only it generates, chosen by counting the blocks in the
game's own structure designs:

- **Ocean monuments** are always the same building on the same grid, so their exact box is known.
- **Shipwrecks** are matched against the game's own shipwreck designs.
- **Everything else** is mapped out from its blocks as you see more of it. Where it looks (biome, height) rules out
  look-alikes such as dungeons and ocean ruins.

## Features

- **Icons on the world map and minimap** for every structure you've discovered. Hover one on the world map for
  its coordinates and size.
- **A legend** on the world map, listing the structures that can exist in the dimension you're looking at, with
  how many you've found.
  - Click a line to show or hide that kind.
  - Drag the header to move the legend, click the header to fold it away, and drag the bottom edge to show more
    or fewer lines. It scrolls with the mouse wheel.
  - **Box** draws every structure's outline, **Near** shows ones you've seen but not discovered yet, and **Set**
    opens the settings.
- **Right-click an icon** to make a Xaero's Minimap waypoint, copy its coordinates, show or hide its outline, share
  it, or delete it.
- **Share** a structure with everyone in chat, or privately with the players you pick. Anyone with this mod gets
  an **[Add to my map]** button that puts it on their map. Anyone without it still sees the name and coordinates.
- **Settings** (the legend's Set button, or Mod Menu):
  - icon size on the world map and minimap,
  - how close counts as discovering a structure (or only when you're inside it),
  - a chat line when you discover one,
  - the command used for private shares (`tell` by default).
- Discoveries are saved per server, in `config/skysstructuremap/`.

## Structures

| Overworld | Nether | End |
|---|---|---|
| Villages, pillager outposts, woodland mansions, strongholds, witch huts, jungle temples, desert temples, trail ruins, ancient cities, trial chambers, ocean monuments, shipwrecks | Nether fortresses, bastion remnants | End cities, end gateways |

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2.
2. Put these in your `mods` folder:
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
   - [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map)
   - [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap), for minimap icons and waypoints
   - [Mod Menu](https://modrinth.com/mod/modmenu), optional
3. Add the `skysstructuremap` jar from the [latest release](https://github.com/SkyStormer1/SkysStructureMap/releases/latest).

## Known limits

- A structure has to be within your render distance to be recognised.
- Boxes for most structures are built from what you've seen, so they grow as you explore more of them.
- A large player base with paths, beds, a bell and job-site blocks could be taken for a village. Right-click its
  icon and choose Delete.

## Licence

MIT. The icons are original pixel art, drawn from the grids in `tools/icons.ps1`.
