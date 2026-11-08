# 贡献指南

感谢你愿意改进 Filee for Android。

## 开发环境

- JDK 17 或 21
- Android SDK 35
- Android Studio（推荐）或命令行 `./gradlew`

## 提 PR 之前

1. 先开 Issue 讨论，避免大改被拒。
2. 保持提交信息清晰（`fix:`, `feat:`, `docs:` 前缀）。
3. 不要提交 `keystore`、`local.properties`、`build/` 目录。
4. UI 变更请附截图。

## 代码风格

- Kotlin 官方风格，4 空格缩进。
- 逻辑层（`core/`、`engines/`）不依赖 Android UI，便于写单测。
- 新增转换器请实现 `IConverter` 并在 `EngineRegistry` 注册。

## 报告问题

见 [SECURITY.md](SECURITY.md)（安全问题）或 Issue 模板（普通问题）。
