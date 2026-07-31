# WTEM（World Translation Extractor Mod）

WTEM 是一个纯客户端的 Fabric 模组，用于把 Minecraft 世界、世界数据包和世界生成结构中的硬编码文本转换为翻译键，并生成对应的 i18n 条目。同时支持多个 Minecraft 版本。

## 支持版本

| 发布版本  | 对应游戏版本  | Java |
|-----------|---------------|------|
| `1.21.1`  | [1.21,1.21.4) | 21   |
| `1.21.4`  | 1.21.4        | 21   |
| `1.21.11` | 1.21.11       | 21   |
| `26.1.x`  | [26.1,26.2)   | 25   |
| `26.2.x`  | 26.2          | 25   |

## 使用方法

1. 把对应 Minecraft 版本的模组文件放入客户端 `mods` 目录。
2. 启动游戏，在单人游戏世界列表中选中目标世界并进入“编辑”页面。
3. 点击“提取”按钮。
4. 在备份确认页面创建备份。强烈建议始终备份；提取会直接修改世界数据。
5. 等待提取结束。界面会显示区块进度和警告数量，详情查看游戏日志。

提取开始后可以取消。但取消或异常发生前已经写入的区块、SavedData 或结构不会自动回滚，一定要先备份。

## 输出内容

提取完成后可能产生以下内容：

- `<世界目录>/en_us.json`：翻译键与原始文本的映射。
- `<世界目录>/datapacks/<原包名>_<短哈希>_wtem/`：与原世界数据包配套的覆盖数据包。
- 修改后的方块实体、实体、记分板、`bossbar` 和结构：硬编码文本组件会被替换为翻译组件。

WTEM 不会修改原始世界数据包。生成的新数据包只保存实际发生变化的资源和有效的 `pack.mcmeta`。
重新进入世界前，请在数据包配置中确认覆盖包已启用，并位于原包之上。

遇到相同文本时，翻译键默认复用同一个键。

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

## 开发与构建

仓库默认 active project 是 `26.2.x`。修改共享源码前后应通过 Stonecutter 的切换任务生成对应版本源码，不要直接编辑 Gradle 生成目录。

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

参考了 [WorldTranslationExtractor](https://github.com/5uso/WorldTranslationExtractor)。Minecraft 版本兼容由 [Stonecutter](https://stonecutter.kikugie.dev/) 管理。
