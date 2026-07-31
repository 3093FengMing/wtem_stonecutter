# WTEM（World Translation Extractor Mod）

WTEM 是一个纯客户端的 Fabric 模组，用于把 Minecraft 世界、世界数据包和世界生成结构中的硬编码文本转换为翻译键，并生成对应的 i18n 条目。同时支持多个 Minecraft 版本。

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
4. 在备份确认页面创建备份。强烈建议始终备份；提取会直接修改世界数据。
5. 等待提取结束。界面会显示区块进度和警告数量，详情查看游戏日志。

提取开始后可以取消。但取消或异常发生前已经写入的区块、SavedData 或结构不会自动回滚，一定要先备份。

## 目前提取范围

存档数据：

- 区块中的方块实体文本，例如容器名称、告示牌、命令方块输出等。
- 实体及乘客、装备、物品栏、村民交易等嵌套数据中的文本。
- 记分板队伍、目标和分数显示名称。
- 自定义 Bossbar 名称。
- 世界 `generated/<namespace>/structure(s)` 中实际存在的 NBT 结构。

数据包：

- 进度、附魔、唱片曲目、盔甲纹饰、画、乐器、对话框和维度类型中的已知文本字段（`attributes.minecraft:gameplay/bed_rule.error_message`）。
- 物品修饰器、战利品表和谓词中的可翻译文本组件。
- NBT 结构中的实体、方块实体与物品文本。
- 函数中的文本组件参数、`summon` 命令 `nbt` 参数中的实体文本，以及解析为物品或方块实体的参数，如 `give` 的物品组件、`setblock` 和 `fill` 的方块实体 NBT。

函数文件替换失败时整条命令会保持原样，并记录警告。`give` 等命令的物品组件按组件 ID 排序回写，因此同一份数据包重复提取的输出保持一致。

## 输出内容

提取完成后可能产生以下内容：

- `<世界目录>/en_us.json`：翻译键与原始文本的映射。
- `<世界目录>/datapacks/<原包名>_<短哈希>_wtem/`：与原世界数据包配套的覆盖数据包。
- 修改后的方块实体、实体、记分板、`bossbar` 和结构：硬编码文本组件会被替换为翻译组件。

WTEM 不会修改原始世界数据包。生成的新数据包只保存实际发生变化的资源和有效的 `pack.mcmeta`。
重新进入世界前，请在数据包配置中确认覆盖包已启用，并位于原包之上。

遇到相同文本时，翻译键默认复用同一个键，可以在下方的配置中设置复用策略。

部分内容无法替换为翻译组件时，会记录警告。包括：书与笔、成书的作者名。

## 配置

首次启动会在 `config/wtem.json` 生成默认配置，其中把每一项都按默认值写全，可以直接在上面改。文件中的每一项都是可选的，删掉或不填写时即保持默认行为。

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
  "language_file": "en_us.json",
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
  }
}
```

- `stages`：按提取阶段开关。`region` 是区块中的方块实体，`entities` 是实体区块，其余对应记分板、bossbar、世界数据包和世界生成结构。
- `resources`：按数据包资源类型开关，键为注册目录名。生成的文件会列出当前版本实际支持的全部类型，未列出的按启用处理。
- `key_reuse`：相同文本是否复用已有的翻译键。`default` 是全局策略，`overrides` 按键前缀覆盖，命中的最长前缀生效。需要同一段文本在不同位置分别翻译时把对应前缀设为 `false`，例如 `{"datapack.": false}` 让数据包中的文本各自成键。
- `key_naming`：翻译键的命名方式，详见下一节。
- `nbt_max_depth`：NBT 递归提取的层数上限，防止相互嵌套的物品等把栈耗尽。每一层嵌套的物品、实体或方块实体各占一层，超出上限的数据保持原样。小于 1 的值按默认值处理。
- `rebuild_nested_keys`：物品上的 `block_entity_data` 是否用自己的键从头命名。默认 `true`，例如不考虑复用的情况下，同一把木剑在无论在哪里都得到相同的键。若为 `false` ，则形如 `item.shulker_box.1.container.wooden_sword.name`。
- `language_file`：写入世界目录的语言文件名。只接受纯文件名，包含路径分隔符或非 `.json` 后缀时会被忽略。
- `builtin_entries`：预置在语言文件中的条目。提取到的文本若与某条预置文本相同，就直接复用它的键，不再单独占一个键。默认预置空字符串、空格和 0~9，这类文本没有翻译价值却出现频繁（空的告示牌行、记分板上的数字），预置后它们各只占一行。整段留空对象 `{}` 表示不预置任何条目；整段删掉则保持默认。

### 键名命名方式

- `scheme`：
  - `structured`（默认）：按提取位置生成可读的键，例如 `entity.zombie.1.name`。由于提取的无序性，重复提取不保证一致
  - `hashed`：用提取位置的 `hash` 生成键，形如 `wtem.<hash>`。同一份存档重复提取得到相同的键。
  - `random`：随机字母，形如 `wtem.<random>`。键与提取位置无关，重复提取不保证一致。
- `random_length`：`random` 方式下随机字母的位数，默认 8。

原版对翻译键的长度没有限制，因此键名不会被截断或缩写，`structured` 方式下的键始终保留完整的提取位置。

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

## 许可证与致谢

本项目使用 [CC0-1.0](LICENSE)。

参考了 [WorldTranslationExtractor](https://github.com/5uso/WorldTranslationExtractor)。
