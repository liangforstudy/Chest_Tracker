# Roadmap

What's done, what's next, and what's planned. This file changes with the project: items move up
as they get built, and get added or removed as plans change. For what works today, see the
[README](README.md).

## Done

- Tracking entity containers (donkeys, mules, llamas, chest and hopper minecarts, chest boats) by
  UUID, recognised on sight, followed as they move
- Tracking block containers (chests, double chests, trapped chests, copper chests, barrels,
  hoppers, furnaces, smokers, blast furnaces, dispensers, droppers, crafters, brewing stands,
  shulker boxes) and per-account ender chests
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
- Villager and wandering trader offers in their own Trades screen: prices and stock as last seen,
  changes since your previous look, per-account prices, level-up and profession warnings
- Chest Tracker importer: `/etracker import` brings Chest Tracker 2.x memory banks over (read-only,
  the original file is never changed; newer memories win). `/etracker import all` merges the files of
  every address of a server, e.g. 9b9t.com, 9b9t.org and 2b2t.com

## Next

- **JourneyMap integration:** results as map markers and waypoints, readable across dimensions

## Planned

- In-world area selection box that works with freecam, and Litematica selections
- Search filters (`@ench:mending`, `@in:donkey`, `@here`, ...)
- Tracer lines from the crosshair to selected results
- Named groups of containers ("Base 1", "Base 2") with per-group totals
- Tracking the last block containers added up to 26.2, which hold items without a normal container
  screen: lecterns (the book on them), chiseled bookshelves (up to six books), shelves (three items),
  decorated pots and jukeboxes (the disc in them)
- Tracking item frames and glow item frames (the item in them). They never move, so they are judged
  at their exact spot like a block, not with the 3x3 home-area rule for donkeys and villagers
- Tracking armor stands (their armor and held items), with the same 3x3 home-area rule as donkeys
  and villagers, since they can be pushed around
- Book tracking: search written books and books and quills by title, author and page text, and
  read them in the detail view, including books inside shulkers, bundles, lecterns and chiseled
  bookshelves. Built for large books (100 full pages): page text is stored once per distinct book
  rather than once per copy, and searched without slowing the game down
- Settings screen (via Mod Menu)
- Testing on servers that use ViaVersion, and on a real modpack instance

## Ideas (not decided)

- Show remembered ender chest contents in ShulkerBoxTooltip previews
- A simple top-down map of remembered containers without JourneyMap
- Support for older and newer Minecraft versions
