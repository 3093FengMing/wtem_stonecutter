# WTEM（World Translation Extractor Mod）

中文 | [English](README.md)

WTEM 是一个纯客户端的 Fabric 模组。
用于把 Minecraft 世界、世界数据包和世界生成结构中的硬编码文本转换为翻译键，并生成对应的 i18n 条目。
同时支持多个 Minecraft 版本。

本模组的部分内容借助 AI 创作，使用的 AI 工具包括 gpt-5.6-sol 和 claude-opus-5。

## 支持版本

| 发布版本  | 对应游戏版本     | Java |
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

## 使用方法

1. 把对应 Minecraft 版本的模组文件放入客户端 `mods` 目录。
2. 启动游戏，在单人游戏世界列表中选中目标世界并进入“编辑”页面。
3. 点击“提取”按钮。
4. 在备份确认页面创建备份。强烈建议备份；提取会直接修改世界数据。
5. 等待提取结束。界面会显示区块进度和警告数量，详情查看游戏日志。

提取开始后可以取消。但取消或异常发生前已经写入的区块、SavedData 或结构不会自动回滚，一定要先备份。

## 目前提取范围

存档数据：

- 区块中的方块实体文本，例如容器名称、告示牌、命令方块输出等。
- 实体及乘客、装备、物品栏、村民交易等嵌套数据中的文本。
- 记分板队伍、目标和分数显示名称。
- 自定义 Bossbar 名称。
- 世界 `generated/<namespace>/structure(s)` 中实际存在的 NBT 结构。
- 世界 `data/` 目录下全部（包括子目录中的） `*.dat` SavedData 文件；使用保守的文本字段识别，并可按文件和内部路径过滤。选定范围内的每个 NBT 字符串都会记录。

数据包：

- 进度、附魔、唱片曲目、盔甲纹饰、画、乐器、对话框和维度类型中的已知文本字段（`attributes.minecraft:gameplay/bed_rule.error_message`）。
- 物品修饰器、战利品表和谓词中的可翻译文本组件。
- NBT 结构中的实体、方块实体与物品文本。
- 函数中的文本组件参数、`summon` 命令 `nbt` 参数中的实体文本，以及解析为物品或方块实体的参数，如 `give` 的物品组件、`setblock` 和 `fill` 的方块实体 NBT。

## 输出内容

提取完成后可能产生以下内容：

- `<世界目录>/en_us.json`：翻译键与原始文本的映射。
- `<世界目录>/en_us.csv`：提取报告，记录每段文本是在哪里找到的，详见下一节。
- `<世界目录>/datapacks/<原包名>_<短哈希>_wtem/`：与原世界数据包配套的覆盖数据包。
- 默认客户端资源包 `resources.zip`：26.1 前位于 `<世界目录>/`，26.1 及以后位于 `<世界目录>/resourcepacks/`。
- 修改后的方块实体、实体、记分板、`bossbar` 和结构：硬编码文本组件会被替换为翻译组件。

WTEM 不会修改原始世界数据包。生成的新数据包只保存实际发生变化的资源和 `pack.mcmeta`。
重新进入世界前，请在数据包配置中确认覆盖包已启用，并位于原包之上。

### 提取报告

提取时也会导出提取报告，文件名与语言文件同名、后缀为 `.csv`。

内容形如：

| 列         | 内容                                                                                                  |
|------------|-------------------------------------------------------------------------------------------------------|
| `key`      | 生成的翻译键。                                                                                        |
| `text`     | 被替换掉的原文。                                                                                      |
| `source`   | 提取阶段，即配置中 `stages` 的名字，例如 `region`、`datapacks`。                                      |
| `location` | 数据存放的位置，维度与区块、数据包与资源 ID，或结构文件。                                             |
| `subject`  | 数据的父子对象，由外到内用 ` > ` 连接，例如 `minecraft:chest (12, 64, -30) > minecraft:shulker_box`。 |
| `reused`   | 该处文本是否复用了已有的键。`true` 表示它没有新增语言文件条目，改动这个键会同时影响多处。             |
| `replaced` | 原始世界/数据包值是否已经替换为翻译组件。`false` 表示只加入目录，而不被提取替换。                     |

### 警告类型

警告不会中止提取。每条警告都会包含类型，以及触发它的源文件、资源 ID、命令或 NBT 路径。
部分警告意味着需要人工检查，或无法安全改写的情况。

| 类型                         | 含义                                                                                           |
|------------------------------|------------------------------------------------------------------------------------------------|
| `region_snbt`                | 无法写出按区块导出的 SNBT。                                                                    |
| `saved_data`                 | SavedData 文件无法读取、解压、解析或应用选择过滤。                                             |
| `saved_data_string`          | 选定 SavedData 范围内发现裸字符串；除非它位于已配置文本字段，否则保持不变。                    |
| `datapack`                   | 数据包资源无法读取或处理。                                                                     |
| `datapack_staging`           | 无法暂存或发布生成的覆盖数据包。                                                               |
| `function_index`             | 无法为数据包建立函数调用索引。                                                                 |
| `function_parse`             | Minecraft 命令解析器无法解析函数命令。                                                         |
| `function_command`           | 已解析的命令无法处理。                                                                         |
| `function_reparse`           | 改写后的命令未通过重新解析校验。                                                               |
| `function_component_rewrite` | 函数中的文本组件或结构化参数无法安全改写。                                                     |
| `function_selector_name`     | 无法解析看起来包含文本参数的选择器名称。                                                       |
| `function_storage_string`    | `data modify storage ... set value` 直接向命令存储写入了裸字符串。                             |
| `pattern_json_string`        | 自定义 JSON pattern 明确选中了裸字符串；它会进入目录但保持原样，需要人工复核。                 |
| `pattern_command_string`     | 自定义命令 pattern 明确选中了裸字符串；参数会进入目录但保持原样，需要人工复核。                |
| `function_macro_binding`     | 宏调用者或 storage 参数缺失、含义不明确，或无法静态解析。                                      |
| `function_macro_restore`     | 无法为调用者恢复并校验实例化后的宏命令。                                                       |
| `writable_book`              | 书与笔的 `writable_book_content.pages[*].raw` 被编目，但它不能替换为组件（`replaced=false`）。 |
| `written_book_string`        | 成书中的 `author` 等普通字符串保持原样。                                                       |
| `generated_structure`        | 无法读取或改写世界生成结构。                                                                   |
| `language`                   | 无法写出主要语言文件。                                                                         |
| `manifest`                   | 无法写出 CSV 提取报告。                                                                        |
| `schema`                     | 无法写出可选的提取 schema。                                                                    |
| `resource_pack`              | 无法写出可选的客户端资源包。                                                                   |
| `ai_key_naming`              | AI 语义键命名失败或返回无效键；会回退为结构化命名。                                            |
| `ai_translation`             | AI 翻译请求或响应失败；普通语言文件会保留。                                                    |
| `close`                      | 提取结束后某个资源无法正常关闭。                                                               |

## 配置

首次启动会在 `config/wtem.json` 生成默认配置。
文件中的每一项都是可选的，删掉或不填写时即保持默认行为。
你也可以在提取界面中的“配置”按钮进入 YACL 配置界面。

提取页面的“选择来源”按钮会打开只包含数据包、SavedData 文件和实体/方块实体类型选择的 YACL 界面。
在外部编辑 `wtem.json` 也会自动热重载。正在运行的提取使用启动时快照，修改只影响下一次运行。

YACL 并不是必选的，但我们强烈推荐你安装。

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
  "patterns": {
    "files": [],
    "json": [],
    "saved_data": [],
    "commands": []
  },
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

- `stages`：控制是否执行该提取阶段。`region` 对应方块实体，`entities` 对应实体，`storage` 递归读取 `data/` 下全部 `*.dat`，其余对应记分板、bossbar、数据包和生成结构。默认为启用。
- `resources`：控制是否提取该数据包资源类型，键为注册目录名。生成的文件会列出当前版本实际支持的全部类型。默认为启用。
- `key_reuse`：相同文本是否复用已有的翻译键。`default` 是全局策略，`overrides` 按键前缀覆盖，命中的最长前缀生效。需要同一段文本在不同位置分别翻译时把对应前缀设为 `false`，例如 `{"datapack.": false}` 。
- `key_naming`：翻译键的命名方式，详见下一节。
- `nbt_max_depth`：NBT 递归提取的层数上限。超出上限的数据保持原样。小于 1 的值按默认值 32 处理。
- `rebuild_nested_keys`：物品上的 `block_entity_data` 是否用自己的键从头命名。默认 `false`，例如不考虑复用的情况下，一把在潜影盒中的木剑会得到形如 `item.shulker_box.1.container.wooden_sword.1.name`。若为 `true`，则从嵌套物品重新开始命名，形如 `item.wooden_sword.1.name`。
- `skipped`：是否要跳过一些特殊文本，详见下一节。
- `skipped_paths`：跳过数据包 `data/` 下指定目录中的资源，被跳过的资源不会写入伴生数据包。资源位置格式形如 `<命名空间>:<资源路径>`；例如 `animated_java:function` 匹配 `data/animated_java/function/**`。资源路径写为 `*` 可跳过整个命名空间。
- `language_file`：写入世界目录的语言文件名。只接受纯文件名，包含路径分隔符或非 `.json` 后缀时会被忽略。
- `saved_data_text_fields`：扫描 SavedData 时用于识别可能是文本组件的字段名。默认列表包含 `back_text`；选定 SavedData 范围内的其他字符串会保持原样，并记录 `saved_data_string` 警告，而不会猜测为文本。
- `patterns`：带明确类型的自定义提取选择器。JSON 规则选择数据包资源目录和 `body[*].contents` 一类结构化路径；SavedData 规则选择 `.dat` 文件与 NBT 路径；命令规则选择 Brigadier 命令节点和已解析参数。规则默认追加到内建规则之后，`kind` 默认为 `component`；显式使用 `plain_string` 时只编目、不改写，并给出人工复核警告。`files` 可引用 WTEM 配置目录下的相对 JSON 文件，外部文件同样使用 `json`、`saved_data` 和 `commands` 数组。路径支持键、`[*]`、数字列表下标和转义点号；JSON、NBT、命令仍由 Gson/Minecraft codec/Brigadier 结构化解析，不使用正则解析这些语法。
- `builtin_entries`：预置在语言文件中的条目。提取到的文本若与某条预置文本相同，就直接复用它的键，不再单独占一个键。默认预置空字符串、空格和 0~9。整段留空对象 `{}` 表示不预置任何条目；整段删掉则保持默认。

- `filters`：统一的来源过滤。普通规则表示包含，`!` 开头表示排除，`*`/`?` 是通配符，排除优先。区域位置为 `dimension/chunk/x_z`，数据包位置为 `pack/namespace:path`，SavedData 位置为 `<data 下相对文件>.dat/<NBT 路径>`；`entity` 和 `block_entity` 匹配完整命名空间类型 ID。
- `filters.selection`：提取界面的精确选择结果。数据包和 SavedData 文件来自当前世界；实体/方块实体选项来自当前游戏版本注册表。空列表表示全部。在界面中全部取消会保存为 `["!none"]`，表示不选择任何项。
- `outputs`：可开启按区块导出 SNBT，以及在世界根目录写入 schema JSON。
- `ai_translation`：启用后会把完整语言目录一次性发送到 OpenAI 兼容的 Responses 或 chat-completions 接口。可以填写的 BaseURL，也可以填写完整的 `/responses` 或 `/chat/completions` URL；使用 BaseURL 时由 `protocol` 选择协议。`translation_prompt` 支持 `{target_language}` 占位符，`key_naming_prompt` 用于可选的 AI 语义键命名。
- `resource_pack`：启用后会使用配置的资源包 `name` 导出资源包。资源包内容包括 `pack.mcmeta` 和 `assets/wtem/lang/` 下的语言文件。

### 自定义提取 pattern

`patterns` 是为 WTEM 尚未内置 schema 的数据包提供的追加式选择器。与内置规则并存。
同一路径重复出现时优先使用内置规则，再使用自定义规则补充其他路径。
pattern 只描述树结构，不负责解析文本或命令。解析均通过原版方法。

`path` 使用点分隔的对象键、数字列表下标和列表通配符，例如：

```text
title
body[*].contents
entries[0].display.label
payload.a\.b[1]       # 对象键实际为 a.b
```

`kind`：`plain_string` 或 `component`（默认）。`component` 使用原版的 codec，`plain_string` 则只记录裸字符串、保持原文不变，并记录警告。

JSON 规则必选 `resource`、`path`，可选 `namespace`、`resource_path`、`kind`。
- `resource`：数据包注册目录（如 `dialog`、`advancement`）。
- `namespace` 和 `resource_path`：分别根据命名空间和资源路径匹配，支持 `*` 和 `?` 通配符。

SavedData 规则必选 `file`、`path`，可选 `kind`。
- `file`：匹配存档 `data/` 下的文件名，支持 `*` 和 `?` 通配符。
其它普通 SavedData 字符串仍遵循原有的保守处理策略。

命令规则选择 Brigadier 已解析的命令参数：

```json
{
  "patterns": {
    "json": [
      {"resource":"dialog", "path":"body[*].contents"},
      {"resource":"my_registry", "path":"display.label", "kind":"plain_string"}
    ],
    "saved_data": [
      {"file":"custom.dat", "path":"entry.display_label"}
    ],
    "commands": [
      {"command":"data", "literals":["merge","storage"],
       "argument":"nbt", "data_path":"custom.label"},
      {"command":"say", "argument_index":1, "kind":"plain_string"}
    ]
  }
}
```

可以用 `argument` 匹配命令参数名，也可以用 `argument_index` 匹配参数索引。
`literals` 可以进一步限制命令路径。`data_path` 会在已解析的 NBT 参数中继续向下选择，不对原始命令文本做匹配。
宏命令会先根据调用链绑定实例化，再由原版解析并执行 pattern 匹配。

规则较多时，可在 `patterns.files` 中引用配置目录下的相对文件：

```json
{"patterns":{"files":["patterns/my-pack.json"]}}
```

外部文件应只包含上面所示的 `json`、`saved_data` 和 `commands` 数组。

### 跳过的文本

两项都默认 `true`。改为 `true` 时对应文本不进入语言文件，存档中的原文也保持不变。

- `command_block_output`：命令方块和命令方块矿车缓存的上一次执行结果（`LastOutput`）。
- `filtered_text`：告示牌行和成书页的聊天过滤副本（`filtered_messages` / `filtered`）。

### 键名命名方式

- `scheme`：
  - `structured`（默认）：按提取位置生成可读的键，例如 `entity.zombie.1.name`。由于提取的无序性，重复提取不保证一致
  - `hashed`：用提取位置的 `hash` 生成键，形如 `wtem.<hash>`。同一份存档重复提取得到相同的键。
  - `random`：随机字母，形如 `wtem.<random>`。键与提取位置无关，重复提取不保证一致。
  - `ai`：把原文和建议路径发送给配置的 OpenAI 兼容接口，生成语义键。例如木剑名 `The Best Sword` 可得到 `item.the_best_sword.name`。响应必须是合法的点分键。
- `random_length`：`random` 方式下随机字母的位数，默认 8。

## 开发与构建

仓库默认 active project 是 `26.2.x`。修改共享源码前后应通过 Stonecutter 的切换任务生成对应版本源码。

PowerShell 示例：

```powershell
.\gradlew.bat "Set active project to 1.21.11"
.\gradlew.bat :1.21.11:compileJava :1.21.11:compileTestJava :1.21.11:test :1.21.11:validateAccessWidener
```

构建并获取某个版本（26.2.x）的发布 JAR：

```powershell
.\gradlew.bat "Set active project to 26.2.x"
.\gradlew.bat :26.2.x:buildAndCollect
```

输出位于 `build/libs/<mod version>/`。提交代码前应运行：

```powershell
.\gradlew.bat "Reset active project"
```

这会把共享源码恢复为仓库配置的 `26.2.x` 状态。

一次构建全部版本：

```powershell
.\gradlew.bat buildAll
```

## 许可证与致谢

本项目使用 [CC BY 4.0](LICENSE)。可以自由复制、修改、再分发，包括商业用途，条件是保留署名。

署名时需要给出作者、指明本项目地址、声明所用许可证（CC BY 4.0，附文本或链接），并说明是否修改过。
分发本模组或其修改版时，随附 `LICENSE` 与 [`NOTICE`](NOTICE) 即可。

分发本工具的成果也要署名。
把语言文件、提取报告或生成的数据包单独发布，或连同世界一起发布时，需注明这些文件由 WTEM 生成并给出上述署名。
这一条只涉及生成的文件，不涉及被提取的世界、译文，或与之一同分发的其他内容。

参考了 [WorldTranslationExtractor](https://github.com/5uso/WorldTranslationExtractor)。
