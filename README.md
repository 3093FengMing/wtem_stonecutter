# WTEM（World Translation Extractor Mod）

中文 | [English](README.en.md)

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
- `<世界目录>/en_us.csv`：提取报告，记录每段文本是在哪里找到的，详见下一节。
- `<世界目录>/datapacks/<原包名>_<短哈希>_wtem/`：与原世界数据包配套的覆盖数据包。
- 修改后的方块实体、实体、记分板、`bossbar` 和结构：硬编码文本组件会被替换为翻译组件。

WTEM 不会修改原始世界数据包。生成的新数据包只保存实际发生变化的资源和有效的 `pack.mcmeta`。
重新进入世界前，请在数据包配置中确认覆盖包已启用，并位于原包之上。

遇到相同文本时，翻译键默认复用同一个键，可以在下方的配置中设置复用策略。

部分内容无法替换为翻译组件时，会记录警告。包括：书与笔、成书的作者名。

### 提取报告

提取时也会导出提取报告，文件名与语言文件同名、后缀为 `.csv`。

内容形如：

| 列         | 内容                                                                                                                                                    |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `key`      | 生成的翻译键。                                                                                                                                          |
| `text`     | 被替换掉的原文。                                                                                                                                        |
| `source`   | 提取阶段，即配置中 `stages` 的名字，例如 `region`、`datapacks`。                                                                                        |
| `location` | 数据存放的位置，维度与区块、数据包与资源 ID，或结构文件。                                                                                               |
| `subject`  | 数据的父子对象，由外到内用 ` > ` 连接，例如 `minecraft:chest (12, 64, -30) > minecraft:shulker_box`。方块实体和实体带坐标，物品与嵌套数据接在容器之后。 |
| `reused`   | 该处文本是否复用了已有的键。`true` 表示它没有新增语言文件条目，改动这个键会同时影响多处。                                                               |

同一个键出现在多处时，语言文件只有一条，报告则每处一行，因此报告的行数通常多于语言文件的条目数。取消提取或中途失败时，已写入的部分会连同语言文件一起写出。

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
  "language_file": "en_us.json"
}
```

- `stages`：控制是否执行该提取阶段。`region` 对应方块实体，`entities` 对应实体，其余对应记分板、bossbar、数据包和生成结构。默认为启用。
- `resources`：控制是否提取该数据包资源类型，键为注册目录名。生成的文件会列出当前版本实际支持的全部类型。默认为启用。
- `key_reuse`：相同文本是否复用已有的翻译键。`default` 是全局策略，`overrides` 按键前缀覆盖，命中的最长前缀生效。需要同一段文本在不同位置分别翻译时把对应前缀设为 `false`，例如 `{"datapack.": false}` 让数据包中的文本各自成键。
- `key_naming`：翻译键的命名方式，详见下一节。
- `nbt_max_depth`：NBT 递归提取的层数上限。超出上限的数据保持原样。小于 1 的值按默认值 32 处理。
- `rebuild_nested_keys`：物品上的 `block_entity_data` 是否用自己的键从头命名。默认 `true`，例如不考虑复用的情况下，一把在潜影盒中的木剑在无论在哪里都得到形如 `item.wooden_sword.1.name`。若为 `false` ，则形如 `item.shulker_box.1.container.wooden_sword.1.name`。
- `skipped`：是否要跳过一些特殊文本，详见下一节。
- `skipped_paths`：跳过数据包 `data/` 下指定目录中的资源，被跳过的资源不会写入伴生数据包。新规则采用资源位置格式 `<命名空间>:<资源路径>`；例如 `animated_java:function` 匹配 `data/animated_java/function/**`。资源路径写为 `*` 可跳过整个命名空间。
- `language_file`：写入世界目录的语言文件名。只接受纯文件名，包含路径分隔符或非 `.json` 后缀时会被忽略。
- `builtin_entries`：预置在语言文件中的条目。提取到的文本若与某条预置文本相同，就直接复用它的键，不再单独占一个键。默认预置空字符串、空格和 0~9。整段留空对象 `{}` 表示不预置任何条目；整段删掉则保持默认。

### 跳过的文本

两项都默认 `true`。改为 `true` 时对应文本不进入语言文件，存档中的原文也保持不变。

- `command_block_output`：命令方块和命令方块矿车缓存的上一次执行结果（`LastOutput`）。
- `filtered_text`：告示牌行和成书页的聊天过滤副本（`filtered_messages` / `filtered`）。

### 键名命名方式

- `scheme`：
  - `structured`（默认）：按提取位置生成可读的键，例如 `entity.zombie.1.name`。由于提取的无序性，重复提取不保证一致
  - `hashed`：用提取位置的 `hash` 生成键，形如 `wtem.<hash>`。同一份存档重复提取得到相同的键。
  - `random`：随机字母，形如 `wtem.<random>`。键与提取位置无关，重复提取不保证一致。
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

## 持续集成与发布

`.github/workflows/build.yml` 在推送到 `main` 和提交 PR 时对九个版本节点分别构建、跑测试并校验 access widener。每个节点是矩阵中独立的一份 checkout，因此 Stonecutter 切换 active project 时不会互相干扰。

`.github/workflows/release.yml` 在推送 `v*` 标签时触发，先校验标签与 `mod.version` 一致，再运行 `buildAll` 和 `publishAll`。发布说明取自标签的注释信息（`git tag -a`），所以变更日志跟着标签走，不写在构建脚本里。也可以手动触发，勾选 `dry_run` 只做一次演练。

发布目标写在 `stonecutter.properties.toml` 中：

```toml
publish.github_repo = "3093FengMing/wtem_stonecutter"
publish.modrinth_id = ""
publish.curseforge_id = ""
publish.curseforge_slug = ""
```

留空表示跳过该平台，所以在填入真实 ID 之前发布配置是惰性的。需要的仓库 Secrets：

| Secret              | 用途                                          |
|---------------------|-----------------------------------------------|
| `MODRINTH_TOKEN`    | Modrinth PAT，需要 `Create versions` 权限     |
| `CURSEFORGE_TOKEN`  | CurseForge API Token                          |
| `GITHUB_TOKEN`      | 自动提供，无需配置                            |

对应的 token 缺失时该平台自动退化为 dry run，因此未配置的平台会被跳过而不是让整个发布失败。本地运行 `.\gradlew.bat publishAll` 在没有 token 的情况下同样只做演练。

版本号后缀决定发布类型：带 `-alpha` 发为 alpha，带 `-beta` 或 `-rc` 发为 beta，其余为正式版。

发布一个版本：

```powershell
# 1. 更新 stonecutter.properties.toml 中的 mod.version
# 2. 提交
git tag -a v0.1.0 -m "变更日志正文写在这里"
git push origin v0.1.0
```

## 许可证与致谢

本项目使用 [CC BY 4.0](LICENSE)。可以自由复制、修改、再分发，包括商业用途，条件是保留署名。

署名时需要给出作者（FengMing）、指明本项目地址、声明所用许可证（CC BY 4.0，附文本或链接），并说明是否修改过。分发本模组或其修改版时，随附 `LICENSE` 与 [`NOTICE`](NOTICE) 即可。

分发本工具的成果也要署名。CC BY 管的是模组本身，提取输出不是模组的改编作品，因此这一条写在 `NOTICE` 中作为使用条件：把语言文件、提取报告或生成的数据包单独发布，或连同世界一起发布时，需注明这些文件由 WTEM 生成并给出上述署名，写在发布说明、世界描述或存档内的文本文件里都可以。这一条只涉及生成的文件，不涉及被提取的世界、你自己写的译文，或与之一同分发的其他内容。

参考了 [WorldTranslationExtractor](https://github.com/5uso/WorldTranslationExtractor)。
