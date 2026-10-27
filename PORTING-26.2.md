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
  then check the tracker shows them in the right slots. Repeat on 26.2.

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
  before. `:26.2:compileJava` fails as expected (step 3); `./gradlew build` builds both versions,
  so it fails until step 3 is done. Use `./gradlew :1.21.11:build` until then.
- Version differences go in `//? if >=26.2` blocks.

## Step 3: code changes for 26.2

The first `:26.2:compileJava` stopped at javac's 100-error limit, so there are more than that.
Most are the screen-rendering and `setScreen` rows below; the busiest files are `TrackerScreen`,
`DebugCommand`, `TradesScreen`, `TradeDetailScreen`, `DetailScreen` and `OpenTriggers`.

| Area | 1.21.11 (Mojang names) | 26.2 | Where |
|---|---|---|---|
| Entity interaction | `interact(Player, Entity, Hand)` **and** `interactAt(...)` | **Merged** into one `interact(Player, Entity, EntityHitResult, Hand)` | `ClientPlayerInteractionManagerMixin`: two injections become one, or `defaultRequire: 1` crashes at startup |
| Screen rendering | `render(GuiGraphics, ...)` | `extractRenderState(GuiGraphicsExtractor, ...)`; methods renamed: `item()`, `text()`, `blitSprite()`, `setTooltipForNextFrame()` | All of `gui/*` (about 25 call sites) |
| Current screen | `minecraft.setScreen()`, `minecraft.screen` | `minecraft.gui.setScreen()`, `minecraft.gui.screen()` | About 20 call sites, `OpenTriggers` |
| Selection lists | `Entry.renderContent(GuiGraphics, ...)` | `Entry.extractContent(GuiGraphicsExtractor, int, int, boolean, float)` | The `Entry` classes in `TrackerScreen`, `TradesScreen`, `TradeDetailScreen`, `AreaConfirmScreen` |
| Item stacks | Many methods return `ItemStack` | Some now return the immutable `ItemStackTemplate` (about 10 places) | Mostly `ItemIndex`, `TrackerScreen` |
| Fabric API | `KeyBindingHelper` (`client.keybinding.v1`), `ClientCommandManager.literal/argument` | Renamed to official names; packages moved | `OpenTriggers`, `DebugCommand` |
| Chat messages | `player.displayClientMessage(Component, boolean)` | Gone from `LocalPlayer`; find the 26.2 replacement | `AreaConfirmScreen`, `TrackerScreen` |
| Item names | `Item.getName(ItemStack)` | Signature changed | `ItemIndex` |
| Book reader | `BookViewScreen.BookAccess.fromItem` | Unchanged | `DetailScreen.Reader` |
| Packets | `handleContainerContent`, `handleMerchantOffers` | Unchanged | `ClientPlayNetworkHandlerMixin` |
| Riding inventory | `LocalPlayer.sendOpenInventory` | Unchanged | `ClientPlayerEntityMixin` |
| Mount menu | `AbstractMountInventoryMenu.mount` | Unchanged | `MountScreenHandlerAccessor` |
| Villagers | `VillagerData.profession()` (a `Holder`) | Package moved to `entity.npc.villager` | `TradeBinder`, `TradeView` |
| Trades | `MerchantOffer` getters | Unchanged on the client (trades are data-driven server-side since 26.1) | `TradeData` |
| Fabric API | Yarn-style names | Renamed to official names (see the Fabric Docs migration map) | `OpenTriggers`, `ScreenBinder`, `TradeBinder`, `EntityTrackerClient` |
| Others | `NbtIo.readCompressed(Path, NbtAccounter)`, `DimensionType.coordinateScale()`, `KeyMapping.Category`, `ItemStack.CODEC` | Unchanged (only some entity packages moved, e.g. `AbstractChestedHorse`, `WanderingTrader`) | |

Also watch for `ItemStack`s created before a world is loaded (static fields): not allowed since
26.1, make them lazy or use `ItemStackTemplate`.

## Step 4: save format

Banks store items as raw `ItemStack.CODEC` JSON **without a data version**. Item components
changed between 1.21.11 and 26.2, so old items may fail to parse and show as empty slots.

- Write `"dataVersion"` into the bank root next to `formatVersion`.
- On load, upgrade older stacks with the game's DataFixer (`References.ITEM_STACK` through
  `Dynamic<JsonElement>`). A missing `dataVersion` means 1.21.11.
- Going back to 1.21.11: raw JSON keeps unopened records intact, but the 1.21.11 build should
  back up and warn when it sees a newer `dataVersion`.
- `/etracker import`: run imported stacks through the same upgrade (`ChestTrackerImport`
  already reads the data version).

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

15. Fuzzy search ("dimond"), enchantments, lore, head names, book text, "On me",
    Nether/Overworld coordinate conversion.
16. Kill a donkey, take one through a portal, remove a donkey's chest, break a chest; hopper
    hints; last-checked days.

**Commands**

17. `/etracker import` with a Chest Tracker 2.x file from 1.21.11: data-version warning shown,
    items survive.

**With other mods and servers**

18. With ShulkerBoxTooltip and Mod Menu: no crashes, no clash on the inventory button.
19. On a laggy server: screens still bind to the right container.

## Order of work

1. Mojang names on 1.21.11 (done)
2. Gradle setup for 26.2 (done)
3. Code changes until it compiles
4. Save-format data version and upgrade
5. Run the test plan, update README and ROADMAP

26.3 and 26.4 snapshots are out too; once 26.2 builds, 26.3 should be a few more `//? if` blocks.
