# Sky's Structure Map

> **Early alpha.** This is an unfinished version for testing. Expect rough edges, and expect things to change
> between versions.

A client-side Fabric mod for Minecraft 26.2 that remembers the structures you come across, so you never
have to write down coordinates again.

As you explore, it recognises structures from the blocks your game has already loaded. It doesn't need the
seed, server plugins, or any help from the server, and nothing in it works out or uses the world seed. When
you come near one, it's marked as discovered and appears on Xaero's World Map and Minimap with its own icon.

## Structures

- **Overworld:** villages, pillager outposts, woodland mansions, strongholds, witch huts, jungle temples,
  desert temples, trail ruins, ancient cities, trial chambers, ocean monuments and shipwrecks
- **Nether:** nether fortresses and bastion remnants
- **End:** end cities and end gateways

## Features

- An icon for every structure you've discovered, on the world map and the minimap.
- A legend in the top-right of the world map. It lists the structures that can exist in the dimension you're
  looking at: click one to show or hide it, scroll with the mouse wheel, or fold the legend away. The header
  has three switches:
  - **Box:** box outlines for every structure.
  - **Near:** structures seen nearby but not discovered yet, faded.
  - **Set:** the settings.
- Right-click an icon to:
  - make a Xaero's Minimap waypoint,
  - copy its coordinates,
  - show or hide its outline,
  - share it in chat,
  - delete it.
- **Sharing** works like Xaero's waypoints. The chat line has the name and coordinates for everyone, plus a
  short code. Anyone with this mod gets an **[Add to my map]** button that puts the structure on their map.
  Nothing is sent until you press Enter, so you can share privately with `/msg <player>` too.
- **Settings** (the legend's Set switch, or Mod Menu):
  - icon size on the world map and minimap,
  - how close you must come to a structure to discover it (32 blocks by default, or only when inside),
  - a chat line when you discover one.
- Discoveries are saved per server, in `config/skysstructuremap/`.

## Requirements

- Fabric Loader, Fabric API and Fabric Language Kotlin
- Xaero's World Map (and Xaero's Minimap for minimap icons and waypoints)
- Mod Menu is optional

## How it works

Each kind of structure is recognised from blocks that only it generates, chosen by counting the blocks in
the game's own structure designs:

- **Ocean monuments** are always the same building on the same grid, so their exact box is known.
- **Shipwrecks** are matched against the game's own shipwreck designs.
- **Everything else** is mapped out from its blocks as you see more of it. Where to look (biome, height)
  rules out look-alikes such as dungeons and ocean ruins.

## Known limits

- A structure has to be within your render distance to be recognised.
- Boxes for most structures are built from what you've seen, so they grow as you explore.
- A large player base with paths, beds, a bell and job-site blocks could be taken for a village; right-click
  its icon and choose Delete.

## Licence

MIT. The icons are original pixel art, drawn from the grids in `tools/icons.ps1`.
