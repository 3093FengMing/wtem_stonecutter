<!-- 中文或英文均可 / Chinese or English are both fine. -->

## What this changes / 改动内容

<!-- Link the issue this closes, if there is one / 如果有对应的 issue 请附上链接 -->

## Versions built / 已构建的版本

<!--
Shared sources are preprocessed per version, so a change that compiles on one node can fail on
another. List the nodes you built, or say "all" if you ran `./gradlew buildAll`.
共享源码按版本预处理，在一个节点能编译的改动可能在另一个节点失败。
请列出已构建的版本节点；若运行过 `./gradlew buildAll` 可写 "all"。
-->

- [ ] `1.21.1`
- [ ] `1.21.3`
- [ ] `1.21.4`
- [ ] `1.21.5`
- [ ] `1.21.8`
- [ ] `1.21.10`
- [ ] `1.21.11`
- [ ] `26.1.x`
- [ ] `26.2.x`

## Checklist / 检查项

- [ ] Tests pass (`:<version>:test`) / 测试通过
- [ ] Extraction changes come with a test covering the new data / 提取相关改动附带覆盖新数据的测试
- [ ] `./gradlew "Reset active project"` was run, so `stonecutter.gradle.kts` is back on the
      repository's default node / 已运行重置任务，`stonecutter.gradle.kts` 回到仓库默认节点
- [ ] README updated if the extraction range or the config changed / 若提取范围或配置有变已更新 README
