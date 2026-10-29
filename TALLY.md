# The tally

How many of an item your memory holds: "totems", "enchanted golden apples", "gapples". In game it's
`/etracker tally <item>` or the line under the tracker's search field. Outside the game, a script
or [Zymbot](https://github.com/liangforstudy/minecraftplayer-fabric-yuliang) reads the tally file
described here.

## What counts

- Every remembered container on the server (one memory per server, shared by your accounts),
  with what's inside shulker boxes and bundles: a shulker of 27 totems is 27 totems and 1 shulker
  box. Item frames, armor stands, shelves, pots and the like count too.
- **Ender chests** count, and are also given on their own (they're per account).
- **On you**: what the player was carrying when the file was written. Not part of the total.
- **May be gone**: containers flagged missing, likely destroyed, or with their chest removed.
  Their items are given separately and are not part of the total.
- Not counted: containers in the trash, and villagers' trades (offers aren't storage).

Counts are as last seen: a chest someone emptied since you last opened it still counts.

## The file

`<game dir>/entity-tracker/tally/<server>.json`, next to `banks/<server>.json`. `<server>` is the
memory's name (e.g. `9b9t` for 9b9t.com, 9b9t.org and 2b2t.com, see `server-aliases.json`).
Written after each save of the memory, while in game. Writes are atomic (a temp file, then a
rename), so a reader never sees half a file.

```json
{
  "format": 1,
  "server": "9b9t",
  "savedAt": "2026-10-29T14:03:11.204Z",
  "savedAtMillis": 1793282591204,
  "minecraft": "26.2",
  "language": "en_us",
  "player": "Bluetails",
  "containers": 812,
  "items": {
    "minecraft:totem_of_undying": {
      "name": "Totem of Undying",
      "total": 214,
      "containers": 12,
      "enderChests": 64,
      "onPlayer": 3,
      "maybeGone": 27
    }
  },
  "aliases": {
    "totem": ["minecraft:totem_of_undying"],
    "gapple": ["minecraft:enchanted_golden_apple", "minecraft:golden_apple"]
  }
}
```

- `format`: 1. A reader should refuse a format it doesn't know; new fields may be added
  without changing it.
- `items`: every item id the memory or the player has, most plentiful first. `total` includes
  `enderChests`; `onPlayer` and `maybeGone` are extra. `containers` is how many containers
  hold it (a shulker inside a chest is part of that chest). `name` is in the game's `language`.
- `aliases`: the player's words for items, from `entity-tracker/item-aliases.json`, with `*`
  patterns already expanded to the ids that exist. Keys are lowercase and singular.

## Matching a typed name (what a script should do)

The game does this, and a script reading the file should do the same so both give the same
answer:

1. **Normalise**: lowercase; drop a leading `minecraft:`; `_` and punctuation become spaces;
   collapse spaces.
2. **Singular**, word by word: `-ies` → `-y`; `-xes`, `-ches`, `-shes`, `-sses` → drop `es`; else a
   final `s` is dropped unless the word ends in `ss` or `us`. Words of 3 letters or fewer stay.
   Applied to both the typed words and the names, so "totems" = "totem", "bricks" = "brick".
3. **Alias**: if the result is a key of `aliases`, those items.
4. **Exact**: else the items whose `name`, or id in words (`totem of undying`), equals it.
   Exact means exact: "golden apples" is golden apples only, not the enchanted ones.
5. **Words**: else the items whose name contains every typed word ("shulker box" gives every
   colour).
6. Nothing: "No item called ...".

For several items, give each one and their sum. The game words it like this:

```
Totem of Undying: 214 in 12 containers · 64 in ender chests · 3 on you · 27 more in containers that may be gone
```

The file only lists items the memory has, so a script can't tell "none" from a misspelling for a
plain name; an alias that matches gives "none remembered".

## Aliases

`entity-tracker/item-aliases.json` is written with defaults the first time (totem, egap, god apple,
notch apple, gapple/gap (both apples), crapple, pearl, xp, crystal, obby, anchor, rocket, shulker
and shulker box (every colour),
bed). Edit it to add your own words; the game picks changes up without a restart. A value is a
list of item ids; `*` matches any part of an id (`minecraft:*shulker_box`).
