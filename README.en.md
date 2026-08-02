# WTEM (World Translation Extractor Mod)

[中文](README.md) | English

WTEM is a client-side Fabric mod that turns hardcoded text in Minecraft worlds, world data packs and world-generated structures into translation keys, and writes the matching i18n entries. It supports several Minecraft versions.

## Supported versions

| Release   | Game versions    | Java |
|-----------|------------------|------|
| `1.21.1`  | 1.21 ~ 1.21.1    | 21   |
| `1.21.3`  | 1.21.2 ~ 1.21.3  | 21   |
| `1.21.4`  | 1.21.4           | 21   |
| `1.21.5`  | 1.21.5           | 21   |
| `1.21.8`  | 1.21.6 ~ 1.21.8  | 21   |
| `1.21.10` | 1.21.9 ~ 1.21.10 | 21   |
| `1.21.11` | 1.21.11          | 21   |
| `26.1.x`  | 26.1 ~ 26.1.2    | 25   |
| `26.2.x`  | 26.2             | 25   |

## Usage

1. Put the mod file for your Minecraft version into the client's `mods` directory.
2. Start the game, select the target world in the singleplayer world list and open its "Edit" page.
3. Click the "Extract" button.
4. Create a backup on the backup confirmation page. Always backing up is strongly recommended; extraction modifies world data in place.
5. Wait for extraction to finish. The screen shows chunk progress and a warning count; the game log has the details.

Extraction can be cancelled once it has started. Chunks, SavedData and structures already written before the cancellation or before an error are not rolled back, so back up first.

## What gets extracted

Save data:

- Block entity text in chunks, such as container names, signs and command block output.
- Text in entities and their nested data: passengers, equipment, inventories, villager trades.
- Scoreboard team, objective and score display names.
- Custom boss bar names.
- NBT structures actually present in the world's `generated/<namespace>/structure(s)`.

Data packs:

- Known text fields in advancements, enchantments, jukebox songs, armor trims, paintings, instruments, dialogs and dimension types (for example `attributes.minecraft:gameplay/bed_rule.error_message`).
- Translatable text components in item modifiers, loot tables and predicates.
- Entity, block entity and item text in NBT structures.
- Text component arguments in functions, entity text in the `nbt` argument of `summon`, and arguments that parse as an item or a block entity, such as `give`'s item components and the block entity NBT of `setblock` and `fill`.

When a replacement in a function file fails, the whole command is left as it was and a warning is logged. Item components in commands like `give` are written back sorted by component ID, so repeated extraction of the same data pack produces the same output.

## Output

A finished extraction may produce:

- `<world>/en_us.json`: the mapping from translation keys to the original text.
- `<world>/en_us.csv`: the extraction report, recording where each piece of text was found. See the next section.
- `<world>/datapacks/<original pack>_<short hash>_wtem/`: an override data pack paired with the original world data pack.
- Modified block entities, entities, scoreboard, `bossbar` and structures, with hardcoded text components replaced by translate components.

WTEM does not modify the original world data packs. A generated pack holds only the resources that actually changed, plus a valid `pack.mcmeta`. Before entering the world again, check in the data pack settings that the override pack is enabled and ordered above the original.

Identical text reuses the same translation key by default. The reuse policy is configurable, see below.

A warning is logged where text cannot be replaced by a translate component. This includes the author name of books and quills and of written books.

### Extraction report

Extraction also exports a report, named after the language file with a `.csv` suffix.

Its columns:

| Column     | Contents                                                                                                                                                                                                                                   |
|------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `key`      | The generated translation key.                                                                                                                                                                                                             |
| `text`     | The original text that was replaced.                                                                                                                                                                                                       |
| `source`   | The extraction stage, i.e. the name used in the config's `stages`, for example `region` or `datapacks`.                                                                                                                                    |
| `location` | Where the data lives: dimension and chunk, data pack and resource ID, or structure file.                                                                                                                                                   |
| `subject`  | The chain of containing objects, outermost first, joined by ` > `, for example `minecraft:chest (12, 64, -30) > minecraft:shulker_box`. Block entities and entities carry their coordinates; items and nested data follow their container. |
| `reused`   | Whether this occurrence reused an existing key. `true` means it added no language file entry, and that editing the key affects more than one place.                                                                                        |

A key that appears in several places has one language file entry but one report row per occurrence, so the report usually has more rows than the language file has entries. If extraction is cancelled or fails partway, the part already written is exported along with the language file.

## Configuration

The first launch writes a default config to `config/wtem.json`, with every option spelled out at its default value so it can be edited directly. Every option is optional: deleting one or leaving it out keeps the default behaviour.

```json
{
  "stages": {
    "region": true,
    "entities": true,
    "scoreboard": true,
    "boss_bar": true,
    "datapacks": true,
    "generated_structures": true
  },
  "resources": {
    "advancement": true,
    "enchantment": true,
    "jukebox_song": true,
    "trim_material": true,
    "trim_pattern": true,
    "painting_variant": true,
    "instrument": true,
    "dialog": true,
    "dimension_type": true,
    "item_modifier": true,
    "loot_table": true,
    "predicate": true,
    "function": true,
    "structure": true
  },
  "key_reuse": {
    "default": true,
    "overrides": {}
  },
  "key_naming": {
    "scheme": "structured",
    "random_length": 8
  },
  "nbt_max_depth": 32,
  "rebuild_nested_keys": true,
  "skipped": {
    "command_block_output": true,
    "filtered_text": true
  },
  "skipped_paths": [
    "function/animated_java/"
  ],
  "builtin_entries": {
    "wtem.blank": "",
    "wtem.space": " ",
    "wtem.0": "0",
    "wtem.1": "1",
    "wtem.2": "2",
    "wtem.3": "3",
    "wtem.4": "4",
    "wtem.5": "5",
    "wtem.6": "6",
    "wtem.7": "7",
    "wtem.8": "8",
    "wtem.9": "9"
  },
  "language_file": "en_us.json"
}
```

- `stages`: switches per extraction stage. `region` is block entities in chunks, `entities` is entity chunks, and the rest are the scoreboard, boss bars, world data packs and world-generated structures.
- `resources`: switches per data pack resource type, keyed by registry directory name. The generated file lists every type the current version actually supports; a type that is not listed counts as enabled.
- `key_reuse`: whether identical text reuses an existing translation key. `default` is the global policy and `overrides` overrides it by key prefix, with the longest matching prefix winning. To translate the same text separately in different places, set the matching prefix to `false`, for example `{"datapack.": false}` gives data pack text its own keys.
- `key_naming`: how translation keys are named. See below.
- `nbt_max_depth`: the recursion limit for NBT extraction, which keeps mutually nested items and the like from exhausting the stack. Each nested item, entity or block entity costs one level, and data past the limit is left as it is. A value below 1 is treated as the default.
- `rebuild_nested_keys`: whether `block_entity_data` on an item names itself from scratch. `true` by default, so, reuse aside, the same wooden sword in different shulker boxes gets the same key wherever it is. With `false` the key looks like `item.shulker_box.1.container.wooden_sword.name`.
- `skipped`: text that can be translated but usually does not need to be. See below.
- `skipped_paths`: text that will not be extracted in specific paths of datapacks.
- `builtin_entries`: entries seeded into the language file. Extracted text that matches one of them reuses its key instead of taking a key of its own. The defaults seed the empty string, a space and 0~9: text with no translation value that nonetheless shows up constantly (blank sign lines, numbers on a scoreboard), which after seeding takes one line each. An empty object `{}` seeds nothing; deleting the whole option keeps the defaults.
- `language_file`: the name of the language file written into the world directory. Only a plain file name is accepted; a name containing a path separator or not ending in `.json` is ignored.


### Skipped text

Both options default to `true`. Set to `true`, the matching text stays out of the language file and the original text in the save is left alone.

- `command_block_output`: the cached result of the last execution of a command block or command block minecart (`LastOutput`).
- `filtered_text`: the chat-filtered copy of sign lines and written book pages (`filtered_messages` / `filtered`).

### Key naming schemes

- `scheme`:
  - `structured` (default): readable keys derived from the extraction site, for example `entity.zombie.1.name`. Because extraction is unordered, repeated extraction is not guaranteed to be consistent.
  - `hashed`: keys derived from a `hash` of the extraction site, of the form `wtem.<hash>`. Repeated extraction of the same save produces the same keys.
  - `random`: random letters, of the form `wtem.<random>`. The key is unrelated to the extraction site, and repeated extraction is not guaranteed to be consistent.
- `random_length`: how many random letters the `random` scheme uses. 8 by default.

## Development and building

The repository's default active project is `26.2.x`. Before and after editing shared sources, use Stonecutter's switch tasks to generate the sources for the version you want.

PowerShell example:

```powershell
.\gradlew.bat "Set active project to 1.21.11"
.\gradlew.bat :1.21.11:compileJava :1.21.11:compileTestJava :1.21.11:test :1.21.11:validateAccessWidener
```

Build and collect the release JAR for one version (26.2.x):

```powershell
.\gradlew.bat "Set active project to 26.2.x"
.\gradlew.bat :26.2.x:buildAndCollect
```

The output lands in `build/libs/<mod version>/`. Before committing, run:

```powershell
.\gradlew.bat "Reset active project"
```

which restores the shared sources to the `26.2.x` state the repository is configured for.

To build every version in one go:

```powershell
.\gradlew.bat buildAll
```

## CI and releasing

`.github/workflows/build.yml` builds, tests and validates the access widener for each of the nine
version nodes on pushes to `main` and on pull requests. Each node is its own checkout in the matrix,
so Stonecutter switching the active project cannot make the jobs interfere with each other.

`.github/workflows/release.yml` runs on a `v*` tag. It first checks that the tag matches
`mod.version`, then runs `buildAll` and `publishAll`. The release notes come from the tag's
annotation message (`git tag -a`), so the changelog travels with the tag instead of living in the
build script. It can also be triggered manually with `dry_run` checked for a rehearsal.

The publishing targets live in `stonecutter.properties.toml`:

```toml
publish.github_repo = "3093FengMing/wtem_stonecutter"
publish.modrinth_id = ""
publish.curseforge_id = ""
publish.curseforge_slug = ""
```

A blank value skips that platform, so the publishing config is inert until the real IDs are filled
in. The repository secrets it needs:

| Secret             | Purpose                                            |
|--------------------|----------------------------------------------------|
| `MODRINTH_TOKEN`   | Modrinth PAT with the `Create versions` scope      |
| `CURSEFORGE_TOKEN` | CurseForge API token                               |
| `GITHUB_TOKEN`     | Provided automatically, nothing to configure       |

A platform whose token is absent falls back to a dry run, so an unconfigured platform is skipped
rather than failing the whole release. Running `.\gradlew.bat publishAll` locally is likewise a
rehearsal as long as no token is set.

The version suffix decides the release type: `-alpha` publishes as alpha, `-beta` or `-rc` as beta,
anything else as a stable release.

To cut a release:

```powershell
# 1. Update mod.version in stonecutter.properties.toml
# 2. Commit
git tag -a v0.1.0 -m "The changelog body goes here"
git push origin v0.1.0
```

## License and credits

This project uses [CC BY 4.0](LICENSE). You may copy, modify and redistribute it, including commercially, as long as you keep the attribution.

Attribution means naming the author (FengMing), pointing at this project, stating the license (CC BY 4.0, with its text or a link), and saying whether you changed anything. When redistributing the mod or a modified version, shipping `LICENSE` and [`NOTICE`](NOTICE) alongside it is enough.

Distributing the tool's output requires attribution too. CC BY governs the mod itself, and extraction output is not an adaptation of the mod, so this requirement lives in `NOTICE` as a condition of use: when you publish the language file, the extraction report or the generated data packs, on their own or inside a world, state that those files were produced by WTEM and give the attribution above. Release notes, the world description or a text file inside the save all work. This covers the generated files only, not the world you extracted from, your own translations, or anything else you distribute alongside them.

Inspired by [WorldTranslationExtractor](https://github.com/5uso/WorldTranslationExtractor).
