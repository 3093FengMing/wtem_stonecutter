# WTEM (World Translation Extractor Mod)

English | [中文](README.zh.md)

WTEM is a client-side Fabric mod that turns hardcoded text in Minecraft worlds, world data packs and world-generated structures into translation keys, and writes the matching i18n entries. It supports several Minecraft versions.

Parts of this mod were created with AI assistance. The AI tools used include gpt-5.6-sol and claude-opus-5.

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
- Every non-vanilla compressed `*.dat` SavedData file below the world's `data/` directory, including nested custom files, with conservative field detection and per-file/path filtering. Every NBT string in the selected scope records a `saved_data_string` warning; only strings identified as text components are extracted. The recognized field names are configurable through `saved_data_text_fields` (which includes `back_text` by default).

Datapacks:

- Known text fields in advancements, enchantments, jukebox songs, armor trims, paintings, instruments, dialogs and dimension types (for example `attributes.minecraft:gameplay/bed_rule.error_message`).
- Translatable text components in item modifiers, loot tables and predicates.
- Entity, block entity and item text in NBT structures.
- Text component arguments in functions, entity text in the `nbt` argument of `summon`, and arguments that parse as an item or a block entity, such as `give`'s item components and the block entity NBT of `setblock` and `fill`.

## Output

A finished extraction may produce:

- `<world>/en_us.json`: the mapping from translation keys to the original text.
- `<world>/en_us.csv`: the extraction report, recording where each piece of text was found. See the next section.
- `<world>/datapacks/<original pack>_<short hash>_wtem/`: an override data pack paired with the original world data pack.
- The default client resource pack, `resources.zip`, under `<world>/` before 26.1 and under `<world>/resourcepacks/` in 26.1 and newer.
- Modified block entities, entities, scoreboard, `bossbar` and structures, with hardcoded text components replaced by translate components.

WTEM will not modify the original world datapack.
The generated new data package will only save the resources that have actually changed and `pack.mcmeta`.
Before re-entering the world, confirm in the datapack configuration that the overlay pack is enabled and above the original pack.

### Extraction report

Extraction also exports a report, named after the language file with a `.csv` suffix.

Its columns:

| Column     | Contents                                                                                                                                            |
|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `key`      | The generated translation key.                                                                                                                      |
| `text`     | The original text that was replaced.                                                                                                                |
| `source`   | The extraction stage, i.e. the name used in the config's `stages`, for example `region` or `datapacks`.                                             |
| `location` | Where the data lives: dimension and chunk, data pack and resource ID, or structure file.                                                            |
| `subject`  | The chain of containing objects, outermost first, joined by ` > `, for example `minecraft:chest (12, 64, -30) > minecraft:shulker_box`.             |
| `reused`   | Whether this occurrence reused an existing key. `true` means it added no language file entry, and that editing the key affects more than one place. |
| `replaced` | Whether the original world/data-pack value was replaced with a translate component. `false` identifies catalog-only text instead of a replaced one. |

A key that appears in several places has one language file entry but one report row per occurrence, so the report usually has more rows than the language file has entries. If extraction is cancelled or fails partway, the part already written is exported along with the language file.

### Warning types

Warnings are non-fatal diagnostics. Each warning includes its type and the source file, resource ID, command, or NBT path that caused it.
The warnings below identify cases that need review or could not be rewritten safely.

| Type                         | Meaning                                                                                                                             |
|------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `region_snbt`                | A per-region SNBT export could not be written.                                                                                      |
| `saved_data`                 | A SavedData file could not be read, decompressed, parsed, or selected.                                                              |
| `saved_data_string`          | A plain string was found in the selected SavedData scope. It remains unchanged unless it is in a configured text field.             |
| `datapack`                   | A data-pack resource could not be read or processed.                                                                                |
| `datapack_staging`           | A generated override data pack could not be staged or published.                                                                    |
| `function_index`             | The function call index could not be built for a data pack.                                                                         |
| `function_parse`             | A function command could not be parsed by Minecraft's command parser.                                                               |
| `function_command`           | A parsed command could not be processed.                                                                                            |
| `function_reparse`           | A rewritten command did not pass the validation reparse.                                                                            |
| `function_component_rewrite` | A text component or structured argument in a function could not be rewritten safely.                                                |
| `function_selector_name`     | A selector name that looks like a text-bearing argument could not be resolved.                                                      |
| `function_storage_string`    | A literal string was written directly into command storage by `data modify storage ... set value`.                                  |
| `function_macro_binding`     | A macro caller/storage binding was missing, ambiguous, or not statically resolvable.                                                |
| `function_macro_restore`     | A materialized macro command could not be restored and validated for its callers.                                                   |
| `writable_book`              | A writable-book `writable_book_content.pages[*].raw` string was cataloged but cannot be replaced by a component (`replaced=false`). |
| `written_book_string`        | A written-book plain string such as `author` remains unchanged.                                                                     |
| `generated_structure`        | A world-generated structure could not be read or rewritten.                                                                         |
| `language`                   | The primary language catalog could not be written.                                                                                  |
| `manifest`                   | The CSV extraction report could not be written.                                                                                     |
| `schema`                     | The optional extraction schema could not be written.                                                                                |
| `resource_pack`              | The optional client resource pack could not be written.                                                                             |
| `ai_key_naming`              | AI semantic key naming failed or returned an invalid key; structured naming is used as a fallback.                                  |
| `ai_translation`             | The AI translation request or response failed; the normal catalog is retained.                                                      |
| `close`                      | An extraction resource could not be closed cleanly after the run.                                                                   |

## Configuration

The first launch writes a default config to `config/wtem.json`, with every option spelled out at its default value so it can be edited directly.
Every option is optional: deleting one or leaving it out keeps the default behavior.
You can also access the YACL configuration interface by clicking the Configuration button in the extraction screen.

The Select sources button on the extraction screen opens a YACL screen for datapacks, SavedData files, and entity/block entity types.
The Configuration button is reserved for the other extraction settings.
Editing `wtem.json` externally is hot-reloaded automatically.
A running extraction keeps its startup snapshot, so changes apply to the next run.

YACL is not mandatory, but we strongly recommend that you install it.

```json
{
  "stages": {
    "region": true,
    "entities": true,
    "scoreboard": true,
    "boss_bar": true,
    "datapacks": true,
    "generated_structures": true,
    "storage": true
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
  "rebuild_nested_keys": false,
  "skipped": {
    "command_block_output": true,
    "filtered_text": true
  },
  "skipped_paths": [
    "animated_java:function"
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
  "language_file": "en_us.json",
  "saved_data_text_fields": [
    "name",
    "custom_name",
    "customname",
    "title",
    "subtitle",
    "description",
    "text",
    "message",
    "label",
    "display_name",
    "displayname",
    "lore",
    "messages",
    "pages",
    "lines",
    "raw",
    "front_text",
    "back_text",
    "prompt",
    "tooltip",
    "error_message"
  ],
  "filters": {
    "region": [],
    "datapack": [],
    "storage": [],
    "entity": [],
    "block_entity": [],
    "selection": {
      "datapacks": [],
      "entities": [],
      "block_entities": [],
      "storage_files": []
    }
  },
  "outputs": {
    "export_region_snbt": false,
    "export_schema": false,
    "region_snbt_directory": "wtem/regions",
    "schema_file": "wtem-schema.json"
  },
  "ai_translation": {
    "enabled": false,
    "endpoint": "https://api.deepseek.com",
    "api_key": "",
    "model": "deepseek-v4-flash",
    "target_language": "zh-CN",
    "output_file": "zh_cn.json",
    "timeout_seconds": 60,
    "protocol": "responses",
    "translation_prompt": "Translate the values of the supplied JSON object into {target_language}. Return only one JSON object with exactly the same keys. Preserve Minecraft formatting codes, placeholders such as %s and %1$s, escape sequences, whitespace, and intentional line breaks. Do not translate JSON keys, commands, identifiers, or formatting tokens.",
    "key_naming_prompt": "Create one concise semantic Minecraft translation key for the supplied text and suggested_path. Return only a JSON object in the form {\"key\":\"...\"}. Use lowercase ASCII letters, digits, underscores, and dots only. Prefer the source category (item, entity, block, sign, book, or datapack) as the first segment and preserve the semantic role such as .name, .title, or .description as the last segment. Translate the meaning into concise English key words even when the visible text is in another language. Describe the visible text rather than the material id: for example, The Best Sword on a wooden sword should be item.the_best_sword.name. Never include numeric occurrence indexes."
  },
  "resource_pack": {
    "enabled": true,
    "format": "zip",
    "name": "wtem_resources.zip",
    "description": "WTEM translations",
    "pack_format": 0
  }
}
```

- `stages`: switches per extraction stage. `region` is block entities in chunks, `entities` is entity chunks, `storage` recursively reads every `*.dat` below `data/`, and the rest are the scoreboard, boss bars, world data packs and world-generated structures.
- `resources`: switches per data pack resource type, keyed by registry directory name. The generated file lists every type the current version actually supports; a type that is not listed counts as enabled.
- `key_reuse`: whether identical text reuses an existing translation key. `default` is the global policy and `overrides` overrides it by key prefix, with the longest matching prefix winning. To translate the same text separately in different places, set the matching prefix to `false`, for example `{"datapack.": false}`.
- `key_naming`: how translation keys are named. See below.
- `nbt_max_depth`: the recursion limit for NBT extraction, which keeps mutually nested items and the like from exhausting the stack. Each nested item, entity or block entity costs one level, and data past the limit is left as it is. A value below 1 is treated as the default.
- `rebuild_nested_keys`: whether `block_entity_data` on an item names itself from scratch. It is `false` by default, so, reuse aside, a wooden sword inside a shulker box gets a key such as `item.shulker_box.1.container.wooden_sword.1.name`. With `true`, nested items restart their key and produce a key such as `item.wooden_sword.1.name`.
- `skipped`: text that can be translated but usually does not need to be. See below.
- `skipped_paths`: skips resources below selected directories under `data/`; skipped resources are not written to the companion pack. The resource-location form is like `<namespace>:<resource path>`: `animated_java:function` matches `data/animated_java/function/**`. `*:function/generated` matches that directory in every namespace, and a resource path of `*` skips a whole namespace.
- `builtin_entries`: entries seeded into the language file. Extracted text that matches one of them reuses its key instead of taking a key of its own. The defaults seed the empty string, a space and 0~9: text with no translation value that nonetheless shows up constantly (blank sign lines, numbers on a scoreboard), which after seeding takes one line each. An empty object `{}` seeds nothing; deleting the whole option keeps the defaults.
- `language_file`: the name of the language file written into the world directory. Only a plain file name is accepted; a name containing a path separator or not ending in `.json` is ignored.
- `saved_data_text_fields`: field names used to identify likely text components while scanning SavedData. The default list includes `back_text`; every other string in the selected SavedData scope is retained and reported as `saved_data_string` instead of being guessed as text.
- `filters`: unified source filters. A normal rule includes a location, `!` excludes it, and `*`/`?` are wildcards; exclusions always win. Region locations are `dimension/chunk/x_z`, data-pack locations are `pack/namespace:path`, SavedData locations are `<path below data>.dat/<NBT path>`, and the entity filters match full namespaced type IDs.
- `filters.selection`: exact choices saved by the extraction screen. Data packs and SavedData files are discovered from the current world. Entity and block-entity choices come from the current version's registries. An empty list means all current. Unchecking every item stores the internal sentinel `["!none"]`, which explicitly means none.
- `outputs`: enables per-chunk SNBT files under the world-relative `region_snbt_directory` and a schema JSON file.
- `ai_translation`: when enabled, sends the complete catalog in one request to an OpenAI-compatible Responses or chat-completions endpoint. You may enter either a BaseURL or a complete `/responses` or `/chat/completions` URL; `protocol` selects the protocol when a BaseURL is used. `translation_prompt` supports the `{target_language}` placeholder, and `key_naming_prompt` controls optional AI semantic key naming.
- `resource_pack`: when enabled, writes a folder, ZIP, or both using only the configured pack `name`. The pack contains `pack.mcmeta` and `assets/wtem/lang/` catalogs.


### Skipped text

Both options default to `true`. Set to `true`, the matching text stays out of the language file and the original text in the save is left alone.

- `command_block_output`: the cached result of the last execution of a command block or command block minecart (`LastOutput`).
- `filtered_text`: the chat-filtered copy of sign lines and written book pages (`filtered_messages` / `filtered`).

### Key naming schemes

- `scheme`:
  - `structured` (default): readable keys derived from the extraction site, for example `entity.zombie.1.name`. Because extraction is unordered, repeated extraction is not guaranteed to be consistent.
  - `hashed`: keys derived from a `hash` of the extraction site, of the form `wtem.<hash>`. Repeated extraction of the same save produces the same keys.
  - `random`: random letters, of the form `wtem.<random>`. The key is unrelated to the extraction site, and repeated extraction is not guaranteed to be consistent.
  - `ai`: sends the original text and suggested source path to the configured OpenAI-compatible endpoint. For example, a wooden sword named `The Best Sword` may become `item.the_best_sword.name`. Responses must be valid dotted keys.
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

## License and credits

This project uses [CC BY 4.0](LICENSE). You may copy, modify and redistribute it, including commercially, as long as you keep the attribution.

Attribution means naming the author, pointing at this project, stating the license (CC BY 4.0, with its text or a link), and saying whether you changed anything.
When redistributing the mod or a modified version, shipping `LICENSE` and [`NOTICE`](NOTICE).

Distributing the tool's output requires attribution too.
When you publish the language file, the extraction report or the generated data packs, on their own or inside a world, state that those files were produced by WTEM and give the attribution above.
This covers the generated files only, not the world extracted from, translations, or anything else you distribute alongside them.

Inspired by [WorldTranslationExtractor](https://github.com/5uso/WorldTranslationExtractor).
