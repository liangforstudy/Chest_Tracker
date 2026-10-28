# Porting to Minecraft 26.2

Plan for adding a 26.2 build next to 1.21.11 (Stonecutter keeps both). Facts below were checked
against the 26.2 client jar and Fabric's maven, not from memory.

## Background

- 26.2 was released on 2026-06-16. It needs **Java 25** and ships **unobfuscated**: there are
  no mappings, so Yarn ends at 1.21.11 and the code must use Mojang's official names.
- Fabric for 26.2: Fabric API `0.161.0+26.2`, Loader 0.19.3 or newer, and the non-remapping
  Loom plugin `net.fabricmc.fabric-loom` (1.19.x). `fabric-loom-remap` and the `mod*`
  configurations (`modImplementation`, `modLocalRuntime`, ...) don't apply to 26.x.
- Dev-run mods with 26.2 builds: MCPFabric `0.8.0+26.2`, ShulkerBoxTooltip `5.4.3+26.2-fabric`,
  Mod Menu `20.0.3`, Placeholder API `3.1.0-beta.1+26.2` (beta only).

## Step 1: Mojang names on 1.21.11 (done)

No behaviour change; isolates the rename from the real 26.2 differences.

- `"mappings"(yarn)` → `loom.officialMojangMappings()`.
- Sources migrated with Loom's `migrateMappings` to `net.minecraft:mappings:1.21.11`.
- Mixin target strings renamed by hand (the migrator doesn't touch strings):
  `onInventory` → `handleContainerContent`, `onSetTradeOffers` → `handleMerchantOffers`,
  `openRidingInventory` → `sendOpenInventory`, `interactEntity` → `interact`,
  `interactEntityAtLocation` → `interactAt`, `interactBlock` → `useItemOn`.
- Fabric API class names (`ScreenEvents`, `Screens`, `KeyBindingHelper`, ...) stay as they are
  on 1.21.11; Fabric API only renames them from 26.1 on, so that belongs to step 2.
- Play-tested on 1.21.11 in a dev client driven through MCPFabric: chest, donkey (mount and
  sneak-open), villager trades, item frame, armor stand, chest boat via the inventory key while
  riding, and the tracker and Trades screens all recorded and rendered correctly.

### Still to check by hand

- **Equipping an armor stand by right-click.** Clicking a stand through MCPFabric while holding
  an iron helmet returned `Pass` and nothing was equipped, most likely because of where the
  bridge aims. The mod's hook still fired, and a stand equipped by command was recorded with the
  right slots. In a normal client: hold armor and a tool, right-click the stand to put each on,
  then check the tracker shows them in the right slots. On 26.2 the same click through MCPFabric
  did equip the helmet and the tracker recorded it, so only 1.21.11 still needs this by hand.

## Step 2: add the 26.2 node (done)

- `settings.gradle.kts`: `versions("1.21.11", "26.2")`.
- `stonecutter.gradle.kts`: both Loom plugins declared with `apply false` at the same version
  (1.19.3). `loom-back-compat` (already the latest, 0.4.3) treats Stonecutter versions from 26 on
  as unobfuscated: it applies the plain `fabric-loom` there and keeps the `mod*` configurations
  working as aliases, so the dependency block didn't need to change.
- `build.gradle.kts`:
  - `requiredJava` follows `loomx.isUnobfuscated`: 21 for 1.21.11, 25 for 26.2 (the mixin
    `JAVA_25` level follows from it).
  - `loomx.applyMojangMappings()` instead of the mappings line: Mojang's mappings on 1.21.11,
    nothing on 26.2.
  - `runs.client`: `programArguments.addAll(...)`, since Loom 1.19 deprecates `programArgs`.
- `stonecutter.properties.toml`: `["26.2"]` block with `mod.mc_compat = "~26.2"` and the
  dependency versions above. Mod Menu still needs Placeholder API, which only has a beta for 26.2
  (dev client only).
- Loom 1.19 is built for Java 25, so Gradle itself must run on JDK 25.
  `gradle/gradle-daemon-jvm.properties` (written by `./gradlew updateDaemonJvm --jvm-version=25`)
  makes Gradle use an installed JDK 25 or download one. Compiling 1.21.11 still targets Java 21
  through toolchains (checked: class files are version 65).
- Checked: both versions configure, all 26.2 dependencies resolve, and the 1.21.11 jar builds as
  before.
- Version differences go in `//? if >=26.2` blocks.

## Step 3: code changes for 26.2 (done)

`./gradlew build` builds both jars: 1.21.11 (Java 21, `JAVA_21` mixins, `~1.21.11`) and 26.2
(Java 25, `JAVA_25` mixins, `~26.2`). There were 137 compile errors, nearly all renames, so most
of the port is a list of Stonecutter replacements in `build.gradle.kts` instead of a `//?` block
at every call site. Each pattern is scoped (to the `context` graphics variable, a receiver or a
whole word) so it can't hit unrelated code, and the 26.2 side never appears in the 1.21.11
sources, so the 1.21.11 build is untouched.

| Area | 1.21.11 (Mojang names) | 26.2 | How it's handled |
|---|---|---|---|
| Screen rendering | `render`, `renderBackground`, `Entry.renderContent` with `GuiGraphics`; `drawString`, `drawCenteredString`, `renderItem`, `renderItemDecorations` | `extractRenderState`, `extractBackground`, `Entry.extractContent` with `GuiGraphicsExtractor`; `text`, `centeredText`, `item`, `itemDecorations` | Replacements |
| Current screen | `minecraft.setScreen()`, `client.screen` | `minecraft.gui.setScreen()`, `client.gui.screen()` | Replacements |
| Fabric API | `KeyBindingHelper.registerKeyBinding`, `ClientCommandManager`, `Screens.getButtons` | `KeyMappingHelper.registerKeyMapping`, `ClientCommands`, `Screens.getWidgets` | Replacements |
| Shulker contents | `ItemContainerContents.nonEmptyStream()` | `nonEmptyItemCopyStream()` (`nonEmptyItems()` now gives `ItemStackTemplate`s) | Replacement; loops use streams |
| Bundle contents | `BundleContents.items()` | `items()` gives `ItemStackTemplate`s | `itemCopyStream()`, which both versions have |
| Item names | `Item.getName()` | Only `getName(ItemStack)` | `getName(stack)` on both |
| Chunk position | `ChunkPos.x` / `.z` fields | `x()` / `z()` accessors | `SectionPos.blockToSectionCoord` on both |
| Chat messages | `player.displayClientMessage(c, overlay)` | `sendSystemMessage(c)` / `sendOverlayMessage(c)` | `gui.Messages` helper, one `//?` switch each |
| Entity interaction | `interact(Player, Entity, Hand)` and `interactAt(..., EntityHitResult, Hand)` | Merged into one `interact(Player, Entity, EntityHitResult, Hand)` | `//?` switch in `ClientPlayerInteractionManagerMixin` (a wrong target crashes at startup) |
| Unchanged | Packet handlers, `LocalPlayer.sendOpenInventory`, `AbstractMountInventoryMenu.mount`, `BookViewScreen.BookAccess`, `MerchantOffer`, `NbtIo`, `DimensionType.coordinateScale()`, `KeyMapping.Category`, `ItemStack.CODEC` | | |

Played on 26.2 in the dev client through MCPFabric, on a copy of the 1.21.11 test world:
- No mixin errors at startup or on joining the world.
- The 1.21.11 bank loaded (the bank is chosen by world name, so the copy used the same one):
  the tracker, chest detail view and trade detail rendered its items, enchantments included.
- Chest (with a named shulker box holding netherite added, found by searching "netherite"),
  donkey (sneak-open), villager trades, a new glow item frame, an armor stand (helmet put on by
  right-click, chestplate and shield by command) and a chest boat opened with the inventory key
  while riding were all recorded with the right slots, and saved in the 26.2 item format.
- `LocalPlayer.sendSystemMessage` / `sendOverlayMessage` go through the same chat listener calls
  as 1.21.11's `displayClientMessage`, so messages behave the same.
- Loading a 1.21.11 world in 26.2 shows "Create a backup before upgrading this world?" first.

## Step 4: save format (done)

`ItemData` holds the logic. Data versions: 1.21.11 = 4671, 26.2 = 4903.

- Every record writes `"dataVersion"` (the version of its items; an item from another version has
  its own `"dataVersion"` in its slot); trade snapshots too; the bank root has the version that
  last saved it. Missing means 1.21.11 (`ItemData.LEGACY`).
- On load (IO thread), older items and trade offers go through the game's DataFixer
  (`References.ITEM_STACK` on a `Dynamic<JsonElement>`) and are stamped with this version.
- Newer items (an older game opening the bank) are never rewritten: they keep their JSON and
  version until that container is opened again in this version. The file is copied to
  `backups/<key>/from-newer-versions/<time>.json` and a chat warning is shown.
- `ItemData.decode` is used wherever a saved item is shown or searched: if the codec can't read
  it, the item still shows as its type and count.
- Trade change marks are skipped when the previous offers are from a newer version.
- `/etracker import`: Chest Tracker items are upgraded from the file's `MinecraftDataVersion`
  before parsing; unreadable ones are kept raw with their version.
- Tested 2026-10-28 with both dev clients on one bank: 26.2 upgraded 126 unversioned items
  (114 container items compared byte for byte: none changed); 1.21.11 showed the warning, made
  the copy, found every search result, and left all 81 records it didn't touch byte-identical;
  a chest reopened there was saved as 4671 with a new item; back on 26.2 its 3 items were
  upgraded and every search matched. (The 1.21.11 leg ran on the older 1.21.11 test world, so
  blocks built later were missing there and went to the trash; back on 26.2 they were restored
  as never opened. That's the two test worlds differing, not the save format.)
- Found while testing step 3: simple items (diamonds, an enchanted book, a named sword) from a
  1.21.11 bank already read fine on 26.2. Both dev clients share `../RnD/run`, so 26.2 rewrites
  the same bank files in its own format; the mod's daily backup kept the 1.21.11 copy
  (`entity-tracker/backups/`). Test the way back by opening such a bank in 1.21.11 again.

## In-game test plan (26.2; singleplayer and a Paper/Fabric server)

**Startup**
1. Launches with no mixin errors in the log; `/etracker` is registered.
2. In a world: tracker key, Trades key, and the button on the inventory screen all open.

**Old saves** (first, on a copy of a 1.21.11 `entity-tracker/` folder)

3. Every record is there; counts and names match 1.21.11, including enchanted items, player
   heads, written books, filled shulkers and bundles, potions.
4. Change something, quit, reload. Open the file in 1.21.11: nothing lost, warning shown.

**Interaction hooks** (the merged `interact`)

5. Donkey, mule, llama: right-click, sneak-right-click while mounted, inventory key while
   riding (horse and chest boat).
6. Chest minecart, hopper minecart, chest boat: open by right-click.
7. Item frame and glow item frame: put in, rotate, take out. Armor stand: put on and take off
   armor and hand items (these use the hit-position path).
8. Villager and wandering trader: offers, prices and changes since last look are recorded;
   level-up warning after trading until it levels.

**Block containers**

9. Single and double chests (trapped, copper), barrel, hopper, the three furnaces, dispenser,
   dropper, crafter, brewing stand, shulker box, ender chest with two accounts.
10. Shelf, decorated pot, jukebox, lectern (place book and open), chiseled bookshelf. Pot and
    jukebox on Paper too.

**Screens** (the rendering change)

11. Tracker list: icons, statuses, hover tooltips, scrolling, multi-select, area confirm,
    trash, restore, undo.
12. Detail view: grid with highlighted matches, drill into shulkers and bundles, book reader
    pages, copy-coordinates buttons.
13. Trades screen and trade detail: arrow sprites and out-of-stock state.
14. GUI scale 1 to 4 and a window resize; then everything once with the **Vulkan backend**.

**Search and detection**

15. Fuzzy search ("dimond"), enchantments, lore, head names, book text, "On <player>",
    Nether/Overworld coordinate conversion.
16. Kill a donkey, take one through a portal, remove a donkey's chest, break a chest; hopper
    hints; last-checked days.

**Commands**

17. `/etracker import` with a Chest Tracker 2.x file from 1.21.11: data-version warning shown,
    items survive.

**With other mods and servers**

18. With ShulkerBoxTooltip and Mod Menu: no crashes, no clash on the inventory button.
19. On a laggy server: screens still bind to the right container.

### Results so far (Paper 26.2 build 135, 26.2 dev client through MCPFabric)

Server: a copy of the 1.21.11 Paper test world, so the existing bank lined up with it.

- 1-2: no mixin errors; `/etracker`, `/offers` and the screens open.
- 3: the 1.21.11 bank (76 records) loaded: nested shulker/bundle items, custom names, player
  heads, enchanted books and written books all found by search.
- 5-6: mule and llama (sneak-click), chest minecart and hopper minecart (click) recorded with
  the right contents.
- 7: item frame rotate (no change), take out (item dropped from the record), put in (recorded);
  armor stand helmet swap by right-click recorded.
- 9: single, double (one record, both halves), trapped and copper chests, barrel, hopper, blast
  furnace, smoker, dispenser, dropper, crafter, brewing stand, shulker box, ender chest. The
  crafter was never recorded on either version (its screen has a 10th slot, the result);
  fixed by not counting result slots.
- 10: shelf, decorated pot (hand insert counted through the wobble), jukebox (hand-placed disc
  remembered, server-filled one gets "holds something not seen yet"), lectern (hand-placed book
  found by page text; server-placed one remembered on opening), chiseled bookshelf.
- 11, 13: tracker and Trades lists render (icons, statuses, hints, trade arrows, out of stock).
- 16: killed mule "likely died", llama chest removed "chest removed", destroyed minecart
  "likely destroyed". A killed animal also got "chest removed" (it drops its chest during the
  death animation); fixed by ignoring dying animals.

- Gone block containers now go to the trash by themselves (user's decision): on joining, the
  pots, shulker box and chest removed in earlier tests were trashed; a pot broken by hand was
  trashed and left search; putting a pot back at that spot restored the record as never opened.
  Gone blocks show "destroyed" ("picked up" for a shulker box). One Trash button opens a trash
  with Entities, Trades and Blocks views; the Trades screen's Trash button opens it on Trades.

- 19 (lag proxy, 1000 +/- 600 ms): two unnamed chest minecarts clicked back to back each got
  their own contents; donkey then a named minecart straight away, both right; two villagers back
  to back, each kept its own offers; villager then a chest minecart, the cart got its own
  contents; a barrel closed the moment its screen appeared kept the fresh contents; a barrel
  emptied and closed at once matched the server (empty).
- 19 at 2500 +/- 1500 ms: at first nothing but the donkey was recorded, because a click waited
  only 3 s for its screen. Fixed: the wait follows the lag (twice the slowest recent
  click-to-screen time or twice the ping, plus a second; 3 s to 20 s, `InteractionTracker`).
  Retest: all of the above passed with screens arriving after ~7 s.

Still to do: 8 (trading and level-up), 12 (detail
view, book reader: needs hand tapping), 14 (GUI scales, resize, Vulkan: by hand), 15 (fuzzy
search in the screen, dimension conversion), 17, 18 (inventory button with Mod Menu), mounted
inventory key (by hand).

## Order of work

1. Mojang names on 1.21.11 (done)
2. Gradle setup for 26.2 (done)
3. Code changes until it compiles (done)
4. Save-format data version and upgrade (done)
5. Run the test plan, update README and ROADMAP

26.3 and 26.4 snapshots are out too; once 26.2 builds, 26.3 should be a few more `//? if` blocks.
