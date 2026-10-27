# Entity Container Tracker

A client-side Fabric mod that remembers what's inside the containers you open, including the ones
most chest trackers somehow refuse to track: **entities**. Donkeys, mules, llamas, chest minecarts,
hopper minecarts and chest boats are all tracked, as well as ordinary chests, barrels and other
block containers. Search everything you've seen, even nested shulker boxes and bundles, from
anywhere.

It also remembers **villager trades**: the offers of every villager and wandering trader you trade
with, searchable in their own screen.

> Early development. It works, but expect rough edges and changes to the save format.
> See the [roadmap](ROADMAP.md) for what's done and what's still to come.

## Features

- **Entity containers that move.** Entities are remembered by UUID, not position, so a donkey you
  lead away or a minecart you push stays the same record. The mod recognises them on sight when
  you come back.
- **Block containers.** Chests (single and double, trapped and copper), barrels, hoppers,
  furnaces, smokers, blast furnaces, dispensers, droppers, crafters, brewing stands and shulker
  boxes.
- **Things that hold items without a container screen.** Shelves, decorated pots, jukebox discs,
  lectern books, chiseled bookshelves, item frames (glow ones too) and armor stands. Like a chest
  once opened, each is remembered once you right-click it, and kept up to date after that:
  shelves, frames and armor stands whenever they're loaded, since the server sends their items.
  Servers only say *whether* a jukebox or chiseled bookshelf slot is filled, not with what, and
  many (Paper among them) never say what's in a decorated pot, so for those the mod knows the
  items you put in yourself, and marks the rest as "not seen yet".
  A lectern's book is remembered when you put it there or open the lectern. Item frames never
  move, so they're expected at their exact spot; armor stands can be pushed, so they get the same
  home area as donkeys and villagers.
- **Books.** Written books and books and quills are searchable by title, author and page text,
  wherever they are (in shulkers, bundles, on lecterns and in chiseled bookshelves). Tap a book in
  the detail view to read it. Built for big ones: a 100-page book is saved once however many
  copies you have, and searching its pages never stalls the game.
- **Ender chests per account.** If you play several accounts on one server, each account's ender
  chest is remembered separately and all of them are searchable, so you know which account to
  switch to.
- **Deep search.** Item names, custom names, lore, enchantments (modded and datapack ones too),
  player heads by profile name or texture, and items inside shulkers and bundles, with the full path
  (`Donkey "Pack" > Shulker "Tools" > Diamond Pickaxe`). Typos are forgiven: when nothing matches
  exactly, close matches are shown and marked as such ("dimond" finds diamonds). Results appear
  as they're found, nearest first, so on a big base the closest ones show up straight away.
- **"On me".** Your own inventory (and the shulkers and bundles in it) is searched too, listed first,
  so the sword you're looking for doesn't hide in your inventory.
- **Detail view.** Click any result to see the container's last known contents as an inventory
  grid, with the matching items outlined. Tap a shulker or bundle to look inside it, or a book to
  read it. Copy the
  coordinates as `x y z`, or as `x z` for Baritone goals.
- **Across dimensions.** A Nether stash seen from the overworld shows where it lines up
  (`Nether (30, 99, 25) ≈ 244, 204 here`), and results are sorted by that distance. The scale comes
  from each dimension's own settings, so modded dimensions work too; the End isn't converted.
- **Honest about what it knows.** The client can't see what happens to a container while you're
  away, so the mod tells you what it noticed and leaves the decision to you. Nothing is hidden or
  deleted automatically:
  - **missing:** you're close to where an entity should be, and it isn't there;
  - **likely died** (animals and villagers) or **likely destroyed** (minecarts, boats, blocks): it
    was seen dying, or vanished next to you and its own items dropped on the spot. A shulker box
    that's gone is **likely picked up**, since it keeps its contents when broken;
  - **went through a portal**, **chest removed** (a donkey seen without its chest), **container
    changed** (another kind of container now stands where it was);
  - hints that contents may change on their own: a hopper below or feeding it, furnaces and
    brewing stands, hopper minecarts, minecarts over a hopper;
  - **last checked N play days ago** once you haven't opened it during your last 7 days on that
    server. Time away from the server doesn't count, and it isn't a warning: it may simply be
    safely stored.
- **Every warning explains itself.** Hover a status or note to see what it means and why the mod
  thinks so; on a touch screen, use **Show warnings** in the row's menu. The detail view always
  shows the explanations. All texts live in `assets/entitytracker/lang/en_us.json`, so a resource
  pack can reword them.
- **Villager trades.** Trade with a villager or wandering trader and its offers are remembered:
  price, second price, what it sells, uses left. Search them in their own **Trades** screen (the
  **Trades** tab at the top of the tracker screen), kept apart from your storage: "mending" finds every librarian selling it.
  Villagers are followed like donkeys when they wander. Prices and stock show as last seen (another
  player may have traded since; opening the villager again gets the real numbers), with
  what changed since your previous look (price up or down, new offers, offers gone), and each
  account keeps its own prices, since reputation and discounts are per player. A villager seen
  with a higher level or another profession is flagged, as its offers are out of date. Wandering
  traders can be hidden all at once, or put in the trash one by one when they've moved on.
- **Trash for the cases it can't judge.** Mark single entries, a selection (Ctrl/Shift-click, or
  the **Select** button on touch screens) or a whole area destroyed (after a grief, say), with
  restore and undo. The **Trash (N)** button shows what's in it.
- **Safe storage.** Memory is saved per server under `<game dir>/entity-tracker/`, outside
  `config/`, with atomic writes and daily backups. Loading, saving and building the search index
  happen in the background, so even a huge base never freezes the game. Several addresses can share
  one memory (`server-aliases.json`); by default `9b9t.com`, `9b9t.org` and `2b2t.com` (which
  redirects to 9b9t) do. `2b2t.org`, the real 2b2t, stays separate.
- **High-ping aware.** Contents are only attributed to a container you actually clicked, matched in
  order and by name, so two minecarts clicked quickly on a laggy server each get their own
  contents. Closing a screen before its contents arrive never overwrites what was remembered.
- **Touch-friendly.** Works on PojavLauncher: every action is reachable without a keyboard or
  right-click.

## Usage

- Press **K** (rebindable in Controls), the **ET** button in your inventory, or type `/etracker` to
  open the tracker. Type to search, click a result for its detail view, right-click a row (or tap
  its **⋮**) for actions. The detail view scrolls (mouse wheel, or drag) when it doesn't fit, for
  example a double chest at a large GUI scale.
- **Storage | Trades** tabs at the top switch between the two screens; `/offers [text]` (or `/etracker trades`), or bind
  **Open trades** in Controls: the villager trades you've seen, e.g. `/offers mending`.
- `/search <text>`: search from chat, nearest first.
- `/search nearby <radius> [text]`: list or search containers around you.
- `/etracker area here <radius>`: mark everything in an area as destroyed (for example after a
  base was griefed), with a confirm list and `/etracker undo`.
- `/etracker trash`, `/etracker trash restoreall`, `/etracker heads`, `/etracker alias <hash> <name>`.
- `/etracker import`: bring over your Chest Tracker memories. Copy the server's `.nbt` file into
  `<game dir>/entity-tracker/import/` (Chest Tracker's own `chesttracker/` folder is searched too), then
  `/etracker import <file>` while on that server. Files named after any of the server's addresses
  are marked `[this server]`, and `/etracker import all` imports all of them at once. The files are
  only read. When the same container is in several files, or the mod already remembers it, the
  newest memory wins. Chest Tracker doesn't save slot positions, so imported items fill the grid in
  order until you open the container again.

It only records containers you open yourself, and only when it can tell for sure which block or
entity the screen belongs to. Server menus and plugin GUIs are ignored.

## Requirements

- Minecraft **1.21.11**, Fabric Loader, [Fabric API](https://modrinth.com/mod/fabric-api)
- Client only. The server needs nothing installed.

The mod sends no extra packets: it only reads what your client already receives. Some servers have
rules about client mods, so check yours.

## Building

Gradle runs on JDK 25 and downloads one if none is installed.

```
./gradlew build
```

The jars end up in `versions/<minecraft version>/build/libs/` (1.21.11 and, in progress, 26.2; see
[PORTING-26.2.md](PORTING-26.2.md)). The project uses
[Stonecutter](https://stonecutter.kikugie.dev/) and Mojang's official mappings.

## Credits

Inspired by [Chest Tracker](https://modrinth.com/mod/chest-tracker-port); this is a separate mod,
not affiliated with it.

## License

[MIT](LICENSE)
