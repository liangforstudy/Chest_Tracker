# Testing

How the mod is tested, and the test plan with the latest results. There are no automated tests
yet: every test below is run in a real game, by hand or by an agent driving the game through
[MCPFabric](https://github.com/denfry/mcpfabric). Re-run the tests touching the code you changed
before claiming something works (the README only claims what is tested).

## Setup

- **Build check without a local build:** every push and pull request is built on GitHub (Actions
  tab, workflow `Build`), both Minecraft versions, with the jars attached to the run. Useful when
  your workspace can't reach Gradle or the Maven servers. It only compiles; the tests below need a
  real game.
- **Dev client:** `./gradlew :1.21.11:runClient` or `./gradlew :26.2:runClient`. The player is
  `Bluetails_dev` (offline, fixed name so the UUID stays the same); `-PdevUser=<name>` starts a
  second account, for the per-account tests (ender chests, villager prices).
- **Test server:** a local [Paper](https://papermc.io) server on the same Minecraft version as the
  client, `online-mode=false`, the dev player opped, peaceful (mobs killed test villagers at night).
  The mod is client-side only; the server needs nothing installed. Always `stop` the server from
  its console so entities are saved.
- **Test datapack:** copy `testing/datapack/etracker_tests` into the server's
  `world/datapacks/`, then `/reload` and run a setup, e.g. `/function etracker_tests:nested`. Each
  function removes its previous setup first, so it can be re-run.

  | Function | Builds | Tests |
  |---|---|---|
  | `nested` | a chest, donkey, chest minecart and chest boat holding shulkers and bundles 4 levels deep, named items, heads, enchanted books | 12-16b |
  | `detect` | containers near hoppers, furnaces, minecarts on rails, a nether portal | 33-39 |
  | `trades` | a librarian, an armorer and a wandering trader | 40-41 |
  | `anim` | villagers with 3, 4 and 5 offers (item count = offer number), for the trade preview | 40 |
  | `hall` | 2 librarians and 2 toolsmiths, Master level, 10 offers each | 40-41 |
  | `displays` (+ `displays_frames`, called by it) | a shelf, decorated pot, jukebox, chiseled bookshelf, lecterns, item frames and an armor stand; stand at 320 100 228 looking south. Frames and armor stand have fixed UUIDs `00000005-0000-0000-0000-000000000037/38/39` | 50-58 |

  Chat commands over 256 characters get the client kicked, which is why the long setups are
  functions. Tags are unique per function.
- **Lag:** `python testing/lagproxy.py --listen 25570 --target 25565 --delay 1000 --jitter 600`,
  then join `localhost:25570`, aliased to the `localhost` memory in
  `entity-tracker/server-aliases.json` so it uses the same memory (test 28).
- **Stress memory (no freezes):** `testing/stress/stress-200chests.json.gz` is a memory of 200
  double chests full of unique shulkers (~300k items, the old format 1, which the mod converts on
  load). With the game closed, unzip it over the dev run's `entity-tracker/banks/localhost.json`
  (back that file up first, and restore it afterwards). Join and check: the screens show
  "Loading memory..." instead of freezing, searching never stalls a frame, and the log warns of
  any refresh slower than 16 ms.
- **Server commands from a script:** `python testing/rcon.py <server folder> <command>` (needs
  `enable-rcon` in `server.properties`; the password is read from there, never printed).
- **Driving the game (agents):** MCPFabric 0.8.0 is loaded in the dev client as a runtime-only
  dependency. If its MCP server isn't connected, call the bridge directly:
  `MCPFABRIC_CONFIG=<run dir>/config/mcpfabric.config.json python testing/mcprpc.py <method> '<json>'`
  (`vision.screenshot '{}' out.png` saves a screenshot). The config holds a token: never commit it.

## Tips for driving the game

- Wait for `Sound engine started` in the new `logs/latest.log` before driving a freshly started
  client (right after a launch, `latest.log` is still the previous run's).
- Wait ~2 s after sneaking before using a donkey, or the server sees an unsneaked click and mounts
  it. Blocks must be opened unsneaked.
- To right-click a block: look at its face, then use the item. Force-load chunks before building
  in another dimension.
- Server commands go through chat (`chat.send`). From Git Bash, set `MSYS_NO_PATHCONV=1` first, or
  a leading `/item` turns into a Windows path.
- The game opens its pause menu when the window loses focus: close the screen before each action
  (or set `pauseOnLostFocus:false` in the dev `options.txt`).
- MCPFabric can click widgets by label but can't tap list rows, grid cells or scroll; those steps
  need a person.
- Changes "while away" (grief tests) need no second player: from the server console,
  `forceload add` the area, change it (`kill`, `fill ... air`, `item replace ...`), then
  `forceload remove all`.

## Result codes

| Code | Meaning |
|---|---|
| OK | Correct entity, correct contents |
| STALE | Remembered, but old position or old contents |
| MISS | Not remembered at all |
| DUP | Same entity appears twice |
| WRONG | Contents attributed to the wrong entity |
| GHOST | Entity is gone but still shown |

Basic setup for tests 1-11: donkeys `A` and `B` with chests, chest minecart `M`, hopper minecart
`H`, chest boat `CB`, each with one unique marker item (A: diamond, B: emerald, M: lapis, H:
amethyst shard, CB: echo shard). Record their UUIDs (`/data get entity @e[...] UUID`); after each
test, search the marker and record a code.

## Tests

| # | Test | Steps | Last result | Notes |
|---|---|---|---|---|
| 1 | Baseline | Open and close each container, search markers | not tested yet |  |
| 2 | Movement | Lead A 30 blocks away, push M along rails, search | not tested yet | Does highlight follow or stay? |
| 3 | Unload | Walk 300+ blocks away, return, re-check UUIDs | not tested yet | UUID should be unchanged |
| 4 | Relog | Disconnect and rejoin | not tested yet |  |
| 5 | Server restart | Restart server, rejoin | not tested yet |  |
| 6 | Swap | While away: `/tp` A to B's spot and B to A's | not tested yet | Tests UUID vs position keying |
| 7 | Hidden change | While away: clear A's chest slot via `/item replace entity ...` (slot names vary by version), or hopper under M | not tested yet | Does it detect on return? |
| 8 | Portal | Take donkey and minecart through a Nether portal and back, re-check UUIDs | not tested yet |  |
| 9 | Death / break | Kill B, break M | OK (killed donkey -> died, broken minecart -> likely destroyed, both kept) | Entry cleared or GHOST? |
| 10 | Open method | Open A while mounted (inventory key) vs sneak-right-click | not tested yet |  |
| 11 | Minecart attribution | Open two chest minecarts side by side quickly | not tested yet | Generic screen problem |
| 12 | Nested shulker | Put a named shulker with a marker item inside A, search the marker | OK (2026-10-20, datapack `etracker_tests:nested`): in chest, donkey, chest minecart and chest boat, items 4 levels deep (Kit > Pouch > Inner > DeepCake) found with full path | Shows full path? |
| 13 | Bundle | Bundle with a marker item inside M, search it | OK (same setup: Bag > Inner > DeepCake; heads, Mending, TestBlade and texture alias all found inside nested shulkers/bundles in every container type) |  |
| 14 | Custom name | Anvil-rename a sword "TestBlade", store in B, search "TestBlade" | OK |  |
| 15 | Enchant search | Store a Mending book and a Sharpness V sword, search by enchantment | OK (vanilla; plain text, no @ench filter yet) | Modded/datapack enchant too if available |
| 16 | Player head | Store a player head (`/give @s player_head[profile={name:"Notch"}]` on 1.20.5+), search "Notch" | OK |  |
| 16b | Named head | Rename a Notch head to "Trophy" in an anvil; get a texture-only decorative head and rename it "Cake"; store both in B. Search "Notch", "Trophy", "Cake", then alias the cake texture and search the alias | OK (alias via /etracker alias; @headtex works as text) | Also check that two same-texture heads with different names stay separate |
| 17 | Manual remove | Mark M as destroyed, then mark an area around A and B as destroyed, then undo | OK via /etracker area + confirm list + undo; per-row Mark destroyed/Restore/Delete in the screen not yet tested by hand | Native feature or missing? |
| 18 | Minecart drift | Push M one chunk over (still within 3x3), then two chunks over | OK (1 chunk: stays home; 3 chunks: re-homed, same record) | Own mod: first stays "home", second flags moved |
| 19 | Freecam select | Enter freecam, fly 50+ blocks up, select a box over a test storage area, mark destroyed | not tested yet | Own mod only; also test with Litematica selection |
| 20 | Many entities | Spawn 10 chested donkeys and 10 chest minecarts in one area, each with a unique marker, open all, relog, search each marker | not tested yet |  |
| 21 | Identical twins | Summon two donkeys with the same name, same variant, same contents; open both; change one's contents; search | OK (separate records, changing one leaves the other) | Must stay two separate records |
| 21b | Twins, same chunk | Put the two identical donkeys from test 21 in the same chunk (also try the same block), open both, relog, search | OK on the same block and after relog; name-tag step not done | Then name-tag one and repeat |
| 22 | Recognise before open | Leave and return to test 20 area, check the UI identifies each entity on sight before opening any | not tested yet |  |
| 24 | Tracers | Search a marker that's in a chest, in a loaded donkey (walking), and in an unloaded minecart; select all; check lines start at the crosshair, follow the donkey smoothly, are dashed for the unloaded cart; test with view bobbing on, in freecam, through walls, then open one container and check its line clears. Bind the toggle key and check hide/show keeps the selection. With and without a highlight mod installed, check blocks are highlighted exactly once | not tested yet | Own mod only |
| 25 | Groups | Build two storage areas 1000+ blocks apart with the same item in both. With no groups: check one flat list. Create groups "Base 1" and "Base 2" (one box, one lasso); check results split with correct totals; ride a donkey from one to the other and check it changes group; `@here` only shows the current group | not tested yet | Own mod only |
| 26 | JourneyMap | With JourneyMap installed: select results, check map/minimap markers and group outlines; draw a lasso group (freehand and click-by-click), edit and delete it; create a waypoint from a result; then remove JourneyMap and check the mod still loads | not tested yet | Own mod only |
| 28 | Lag | Run `testing/lagproxy.py --delay 1000 --jitter 600`, join the "Lag test" entry (`localhost:25570`, aliased to the `localhost` bank). Change contents server-side first so fresh snapshots are recognisable. (A) right-click a donkey then a minecart straight away, before the first screen arrives; (B) open a container and close it the moment the screen appears; (C) move items in a container and close at once. Repeat with `--delay 2500 --jitter 1500` | A OK, B OK (2026-10-20, 1000+/-600). 26.2, 2026-10-28: A, B, C, 32, 47 OK at 1000+/-600; at 2500+/-1500 only after the click wait was made adaptive (3-20 s, `InteractionTracker.windowTicks`) | Never WRONG and never an empty snapshot replacing real contents. Closing before contents arrive must keep the previous snapshot |
| 29 | Mounted | While riding a horse, right-click a chest minecart; while sitting in a chest boat, open a block chest next to it; right-click your own chest boat while in it; press the inventory key in a chest boat (manual: MCPFabric has no key tool) | all OK (2026-10-20; inventory key tested by hand: CB snapshot updated with the new marker, M2 opened afterwards went to M2, CB untouched) | The block chest must not end up in the boat's record |
| 31 | Grief | Remember a base, tp 3000 blocks away, from there `forceload` the base and kill a donkey, break a minecart, remove two block containers, `forceload remove all`; return. Then `/etracker area here 14`, confirm, check trash, undo, trash again, leave and return | OK (2026-10-20): removed blocks -> likely destroyed, minecart -> missing, killed donkey -> died; area trash hides them, undo restores; trashed entities still in view stay trashed, ones that load in again are restored as "reappeared" | Unticking rows in the confirm list and Restore / Delete forever in the trash view still need a hand test (MCPFabric can't tap list rows) |
| 30 | Block containers | Open each with a unique item: chest, double chest, renamed chest, barrel, hopper, furnace, dispenser, dropper, brewing stand, shulker box, ender chest; plus a hopper minecart. Then break one container and remove one half of the double chest and reopen the other half | all OK (2026-10-20) | Double chest = one record at its lower-corner position, other half in `otherPositions` (same shape as Chest Tracker's data, for the importer). Ender chest = one global record. Broken container -> `likely destroyed`, kept. Double -> single replaces the record |
| 32 | Lag: two minecarts (A) | Through the lag proxy (1000 +/- 600 ms), right-click two unnamed chest minecarts back to back; fresh markers in each first | OK (each cart got its own contents) | Before 4.1d A, cart 1's screen bound to cart 2 |
| 33 | No click, no record (B) | Look at a hopper minecart and press E (own inventory has 5 crafting slots, like a hopper minecart) | OK (hand test 2026-10-21: last opened unchanged, contents still wheat) | Must not record your inventory as the minecart |
| 34 | Missing only in range (C) | Record a donkey, stand 110 blocks away (beyond 96 tracking, within 160 view) | OK (stays unloaded, not missing) |  |
| 35 | Vanish judgement (D) | Near (<16): tp a donkey away; tp another away while unrelated items land there; break a minecart; roll a minecart into a nether portal on rails | OK (missing / missing / likely destroyed / went through a portal) | Portal check needs ~4 blocks: moving entities are drawn behind their real position. A donkey placed inside the portal frame suffocated (test setup), correctly "died" |
| 36 | Container changed (E) | Replace a remembered chest with a barrel | OK (flag, contents kept) |  |
| 37 | Chest removed (F) | `data merge entity <donkey> {ChestedHorse:0b}` | OK |  |
| 38 | Hints (G) | Chest over hopper, chest fed by hopper, furnace, hopper minecart, minecart on rail over hopper | OK (all five; the hoppers really drained two of them) |  |
| 39 | Stale + On me | `/etracker debug age 9` near containers; carry a renamed sword and search it | OK ("last checked 9 play days ago"; sword found "On <player>") |  |
| 40 | Trades: baseline (4.11) | Open a librarian, an armorer and a wandering trader; check the Trades screen lists every offer (buy items, sell item, uses left), profession and level, and that nothing appears in the storage search | OK (2026-10-21, datapack `etracker_tests:trades`): profession, level, every offer incl. second price, uses, out of stock; not in storage | Own mod only |
| 41 | Trades: search | Search the Trades screen for "mending", an item a villager sells ("emerald"-priced bread) and an item it buys; results show villager, position and last seen age | OK ("mending", "emerald": one row per offer with price -> goods icons) | Enchanted books by enchantment, registry-driven |
| 42 | Trades: out of stock and restock | Use one trade until it's locked, close, check "out of stock"; wait for a restock (villager at its workstation) or `time add`, reopen, check it's back | out of stock shown (from the offer's uses); real trading and restock not tested yet |  |
| 43 | Trades: price changes | Trade one offer many times (demand raises the price next restock); cure a zombie villager (discount); give Hero of the Village; reopen each time, check the stored price and the change are shown. Repeat the cured villager on the second account (`-PdevUser`) and check both prices are kept per account | price up (was 12) after a server-side price change; demand/discount/cure and second account not tested yet | Reputation is per player |
| 44 | Trades: villager wanders | Record a villager, lead or push it 2 chunks away, reopen; then walk away and back while it roams | OK (moved 3 chunks: same record, re-homed) | Same record (UUID), re-homed like donkeys |
| 45 | Trades: new offers by themselves | Level a villager up (new offers appear); break the workstation of an untraded novice and give it another (trades replaced) | level-up flag OK (level raised server-side); profession change not tested yet | Old offers must not linger as current |
| 46 | Trades: gone | Kill a villager, let a wandering trader despawn, `kill` one far away; check died / likely gone / missing, never auto-removed | killed farmer -> likely destroyed, kept; despawn not tested yet. Trashing a wandering trader hides it, and it stays trashed when it loads in again | Wandering traders despawn on their own |
| 47 | Trades: binding | Under the lag proxy, right-click two villagers back to back; right-click a villager then a chest minecart straight away | not tested yet | Each villager gets its own offers; no offers land on the wrong entity |
| 48 | Chest Tracker import | Copy real Chest Tracker files into `entity-tracker/import/`. `/etracker import` lists them; import one named after another server (refused without `confirm`, then imported), an empty one, the same one twice; a fresh account (`-PdevUser`) for the ender chest; a file named after this server with a newer copy of a real nearby chest (no `confirm` needed), then open that chest | OK (2026-10-23, real Chest Tracker files from a 9b9t instance: 2b2t_com 121 containers / 2748 stacks, 9b9t_org, empty play_trialsmp_org): wrong-server warning, counts, re-import = all kept, newer local ender chest and the newer copy of a chest found in both files kept, names ("Terracotta Colours" (Shulker Box)), nested search, "from Chest Tracker" note; opening the chest replaced the imported contents and cleared the note. Bug found and fixed: Chest Tracker writes empty ender chest slots as `{}`, which were imported as 4 "unknown items"; now skipped, and slots stay where they were |  |
| 49 | Import: aliased servers | With 9b9t.org and 2b2t.com aliased to the current server: `/etracker import` marks those files `[this server]` (not the trialsmp one), `/etracker import all` merges both; a single aliased file needs no `confirm`; an old alias file gets 2b2t.com added to the 9b9t group once | OK (2026-10-23): 120 new, 1 updated (the chest in both files: the 9b9t_org copy from 2026-09-14 won over the 2b2t_com one from 2026-07-21), 2 kept (newer local ender chests) | Test used temporary localhost aliases; the dev server list has no 9b9t entry |
| 50 | Shelf and pot | Right-click (empty hand) a shelf holding 3 items and a decorated pot holding 1; then change both server-side (`data modify block`) while standing near; search each item | not tested yet | Own (2026-10-25, `etracker_tests:displays`): shelf OK (remembered on click, slot taken out and a server-side `item replace` both followed while loaded, no reopen). Pot: first FAIL (always empty). Cause: Paper's pot `getUpdateTag` sends only the sherds, never the item; vanilla sends the item only in chunk data, never on change (insertion only calls `markDirty`). Fixed: pots are hand-tracked like jukeboxes, confirmed by the wobble block event (`lastWobbleType` POSITIVE = taken, NEGATIVE = refused); chunk data with an item is believed, empty chunk data proves nothing. Retest OK: 2 emeralds by hand -> x2; amethyst refused by a known emerald pot -> no hint; a pot with hidden diamonds refused an amethyst -> "not seen yet", then took a diamond -> diamond x1 + hint kept (count unknown); relog kept both. Vanilla chunk-data path untested (no vanilla server) |
| 51 | Shelf is not a furnace | Right-click a shelf, then straight away open a furnace next to it | not tested yet | OK (furnace got its own record with raw iron; shelf kept its items) |
| 52 | Jukebox | Put a disc in an empty jukebox by hand; separately, fill a jukebox server-side and right-click it; take the first disc out | not tested yet | OK (pigstep remembered; ejected -> dropped; refilled server-side -> "holds something not seen yet") |
| 53 | Chiseled bookshelf | Put two different books in two slots by hand; fill a third slot server-side; take one hand-placed book out | not tested yet | OK (mending book slot 3 and book slot 5, matching the server's slots; server-filled slot 2 -> hint; taking slot 5 out dropped it) |
| 54 | Lectern | Put a written book on an empty lectern by hand; on a second lectern placed with a book server-side, check the "has a book" hint, then open it | not tested yet | OK (hand-placed book remembered; emptied server-side -> dropped; refilled -> "has a book, open it to remember"; opening -> book remembered, hint gone; second lectern remembered on opening) |
| 55 | Item frame | Right-click an item frame and a glow item frame holding items; change one item server-side; kill the other frame while near (<24 blocks) | not tested yet | OK (both remembered on click; glow frame item changed server-side -> followed (count shown 1, frames hold 1); killed frame within 24 blocks -> missing) |
| 56 | Armor stand | Right-click an armor stand wearing armour and holding an item (empty hand, on the base); change its helmet server-side; push / tp it 1 chunk then 3 chunks | not tested yet | OK (5 items with slot labels Head..Off hand in the detail view; helmet change followed; 1 chunk = same home, 3 chunks = re-homed once, same record) |
| 57 | Books | Store a written book (title, author, a distinctive word on a late page) and a book and quill in a chest, and 3 copies of a 100-page book in a shulker; search title, author and the page word; open the detail view and tap the book; check the bank file | not tested yet | OK for search: page word on page 87 -> all 3 copies, title, author, book and quill text; `books` table holds the 100-page book once (marker appears once in the file). Detail view shows "Tap a book to read it"; tapping the book (reader) needs a hand test (MCPFabric can't tap grid slots) |
| 58 | Display grief | After 50-56: break the shelf, pot, jukebox and lectern while near | not tested yet | OK (shelf, pot, jukebox, chiseled bookshelf, lectern -> likely destroyed, kept). Minor: hints like "not seen yet" stay on a destroyed record |
| 59 | Multi-item search (#9) | Search `obby, echest`, `obby, ender chest`, `obby, zzzz`; `/search obby, echest`; with an `item-aliases.json` from an older version | OK (2026-11-18): both kinds listed with a tally line each; `/search` says which term found nothing. First found `echest` missing on existing installs (alias file written once); fixed: newer default words are added to an old file once (`_defaultsVersion`) | Both kinds listed; a term with no hits says so in /search |
| 60 | Name matches first (#8) | Near chest: Fire Aspect sword and Fire Protection chestplate; farther chest: Firework Rocket. Search `fire` | OK in the tracker screen (Fire Charge and Firework Rocket above the sword). `/search` in chat still lists in container order | Fire Charge rows above the armour, also when found after it |
| 61 | Sort by age (#10) | Press Sort: Nearest, Last opened, Oldest seen, Newest seen | OK (2026-11-18): Last opened puts a chest seen 1 s ago but opened 16 play days ago near the top; oldest / newest seen order by last seen | Never opened / never seen last |
| 62 | Highlight in container (#7) | Search an item, pick a result, open that container | OK (hand test 2026-11-18, furnace: the searched coal outlined) | Diamonds and shulkers holding them outlined; inventory slots not |
| 63 | Streamer mode (#4) | `/etracker streamer`, check list, detail view, trades, Copy, `/etracker list` show no coordinates; Copy with the clipboard blocked (default, and a bare `/etracker streamer allowclipboard`) and allowed (`... allowclipboard true`); the Mod Menu settings screen; toggle back; relog keeps the setting | OK (2026-11-18): no coordinates anywhere (`/etracker list` leaves an empty ", ,"); blocked: clipboard untouched; allowed: "x z" copied, message still hidden; settings screen toggles both and saves them | |
| 64 | Second open key (#5) | Press Numpad 5 in game | Bound to Numpad 5 by default; press not tested yet (test tools have no key presses) | Opens the tracker |
| 65 | Alias merge (#11) | Join with an old separate bank of an address that is now an alias (here `localhost_25570.json` beside `localhost.json`); also a broken one | OK (2026-11-18): merged on join with a chat note, its record searchable, old file renamed `.merged-into-localhost-...`; a broken file is logged and left alone | Merged once with a chat note; old file renamed, not deleted |
| 66 | Grid hides destroyed | Open the Grid with records that may be gone; press Destroyed; go back to the list | OK (2026-11-18): grid opens with "Destroyed: hidden" (46 kinds), 70 kinds when shown; the list keeps its own setting | Grid starts hidden, list starts shown |
| 67 | Tracers off from chat (#12) | Trace two containers; `/etracker tracers` (hide), again (show), `hide`, `show`, `clear`; bind and press the Toggle tracers key | OK from chat (2026-11-18): two traced chests; `/etracker tracers` hid them ("2 kept"), `show` brought both back, `clear` then "Nothing is traced". Key press not tested (test tools have no key presses) | Lines hide and come back with the same containers; clear forgets them; each says so |
| 68 | Settings screen | Mod Menu > Entity Container Tracker > configure: Keys..., Streamer mode..., Done on each | OK (2026-11-18): Keys... opens Controls > Key Binds scrolled to the Entity Container Tracker section (K, Keypad 5, three unbound); the streamer page opens and toggles both settings | Keys in the game's own Controls screen |
| 69 | Grid first, big counts | Open the tracker; have items with 4+ digit totals (two chests of 27 x 64) | OK (2026-11-18): opens on the grid; 1736 and 1728 drawn smaller inside their own slot, no overlap with the next one | Counts never run into the next slot |
| 23 | UUID clash (optional) | `/summon donkey ~ ~ ~ {UUID:[I;1,2,3,4]}` twice, check the server log for a duplicate UUID message | not tested yet | Paper: record the duplicate-UUID mode first (0.1); result depends on it |
