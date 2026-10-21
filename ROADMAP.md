# Roadmap

What's done, what's next, and what's planned. This file changes with the project: items move up
as they get built, and get added or removed as plans change. For what works today, see the
[README](README.md).

## Done

- Tracking entity containers (donkeys, mules, llamas, chest and hopper minecarts, chest boats) by
  UUID, recognised on sight, followed as they move
- Tracking block containers (chests, double chests, barrels, hoppers, furnaces, smokers, blast
  furnaces, dispensers, droppers, brewing stands, shulker boxes) and per-account ender chests
- One memory per server shared by all your accounts, server address aliases, safe saving with
  backups
- Search: names, lore, enchantments, player heads and textures (with aliases), items nested in
  shulkers and bundles, your own inventory ("On me")
- Tracker screen, detail view with contents grid, coordinates converted between dimensions
- Detection: missing, likely destroyed, went through a portal, chest removed, container changed,
  hints for containers whose contents change on their own, "last checked" in play days, and an
  explanation for every warning
- Trash with restore and undo, area and multi-select actions
- Safe binding on laggy servers; touch-friendly (PojavLauncher)

## Next

- **Chest Tracker importer:** bring your existing Chest Tracker memories over (read-only, the
  original files are never changed)
- **JourneyMap integration:** results as map markers and waypoints, readable across dimensions

## Planned

- In-world area selection box that works with freecam, and Litematica selections
- Search filters (`@ench:mending`, `@in:donkey`, `@here`, ...)
- Tracer lines from the crosshair to selected results
- Named groups of containers ("Base 1", "Base 2") with per-group totals
- Villager and wandering trader offers, searchable in their own Trades screen (prices, stock and
  new offers change over time, so each shows when it was last seen)
- Settings screen (via Mod Menu)
- Testing on servers that use ViaVersion, and on a real modpack instance

## Ideas (not decided)

- Show remembered ender chest contents in ShulkerBoxTooltip previews
- A simple top-down map of remembered containers without JourneyMap
- Support for older Minecraft versions
