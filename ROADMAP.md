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
- Tracking what holds items without a container screen: lecterns, chiseled bookshelves, shelves,
  decorated pots and jukeboxes; item frames at their exact spot, and armor stands with the 3x3
  home-area rule
- Book tracking: written books and books and quills searchable by title, author and page text, a
  reader in the detail view, and large books (100 full pages) saved once per distinct book
- One memory per server shared by all your accounts, server address aliases, safe saving with
  backups
- Search: names, lore, enchantments, player heads and textures (with aliases), items nested in
  shulkers and bundles, your own inventory ("On <your name>")
- Tracker screen, detail view with contents grid, coordinates converted between dimensions
- Detection: missing, likely destroyed, went through a portal, chest removed, container changed,
  hints for containers whose contents change on their own, "last checked" in play days, and an
  explanation for every warning
- Trash with restore and undo, area and multi-select actions; broken block containers go to the
  trash by themselves (restorable, and back if the container returns); one trash with entity, trade
  and block views
- Safe binding on laggy servers; touch-friendly (PojavLauncher)
- Builds for Minecraft 1.21.11 and 26.2, with one memory file that works in both (older items are
  upgraded; newer ones are kept untouched by an older version, with a backup and a warning)
- Click-to-screen wait that follows the server's lag
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
- Settings screen (via Mod Menu)
- Testing on servers that use ViaVersion, and on a real modpack instance
- **Servers with anti-xray.** Many servers hide buried blocks from the client, chests included
  (Paper's anti-xray does when it's turned on): until you come close, a buried chest arrives as
  stone. Today the mod takes that as the chest being gone, moves it to the trash, and when the
  chest shows up again restores it as never opened, so its remembered contents are lost until you
  open it again. The plan: only trash a block you saw break while it was loaded; a container
  already missing when its area loads gets a warning instead, and keeps its contents

## Ideas (not decided)

- Show remembered ender chest contents in ShulkerBoxTooltip previews
- A simple top-down map of remembered containers without JourneyMap
- Support for older and newer Minecraft versions
