# Filee for Android

> [Filee](https://github.com/KnifeLemon/Filee) 的**非官方 Android 移植** —— 用 Kotlin + Jetpack Compose 重写的离线文件格式转换器。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)

Windows 版 Filee 的核心体验是「把文件拖到格式圆环上松手」。这个 Android 版本把同一套
「预设 + 格式路由 + 环形菜单」的思路搬到了触摸屏上，并且**全部转换在本机完成，不联网**。

---

## 功能

- **环形格式菜单**：选中文件后弹出环形菜单，按目标格式一键转换（自绘 Canvas + 角度命中测试）。
- **预设系统**：图片、PDF、文档、表格、字幕、压缩包等预设，参数可调、可保存。
- **多步转换路由**：不是硬编码的 if-else，而是把「docx → pdf → png」这类链路建成带权图，按代价选最优路径。
- **任务队列**：批量任务的排队、进度、取消与结果汇总。
- **工具栏配置**：自定义常用预设的排列与显示。
- **历史记录**：最近转换的任务可回溯。
- **深色模式 / 多语言 / Material 3**。
- **完全离线**：没有 `INTERNET` 权限。

### 支持的引擎类别

| 类别 | 说明 |
|---|---|
| 图片 / 矢量 | 位图与 SVG 类格式互转、缩放、裁剪 |
| PDF | 基于 PDFBox-Android 的页面处理、合并/拆分/压缩 |
| 文档 / 电子书 | DOCX / PPTX / HWPX / EPUB 等解析与导出 |
| 表格 / 数据 | XLSX / CSV 等表格与结构化数据互转 |
| 字幕 | SRT / VTT / LRC / ASS 互转 |
| 压缩包 | ZIP / 7Z / TAR 等打包与解包 |

> 具体支持的格式数量与矩阵以 App 内预设为准；桌面版宣称的 263 种格式依赖外部引擎
> （ffmpeg、LibreOffice 等），Android 版不包含这些引擎，因此**音视频转码与 CAD 不支持**。

---

## 下载

前往 [Releases](https://github.com/peakfllower-sys/filee.apk/releases) 下载最新 APK。

| 文件 | 说明 |
|---|---|
| `Filee-Android-x.y.z.apk` | 正式签名安装包 |

安装前请核对 Release 页面给出的 SHA-256；首次安装需要在系统里允许「安装未知来源应用」。

---

## 系统要求

- Android 8.0（API 26）及以上
- targetSdk 35，compileSdk 35
- ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`
- 建议预留 200 MB 以上空间（转换过程会产生临时文件）

---

## 隐私

- 应用**不申请** `android.permission.INTERNET`，没有任何网络请求。
- 文件只在本机读写，不会上传。
- 应用数据仅保存设置、历史记录与临时文件，可随时在系统设置中清除。

---

## 从源码构建

见 [docs/BUILDING.md](docs/BUILDING.md)。简要步骤：

```bash
# 需要 JDK 17+ 与 Android SDK 35
./gradlew assembleRelease
```

签名密钥不入库：本地放在 `keystore.properties`（已被 `.gitignore` 忽略），CI 用 GitHub Secrets。

---

## 与上游的关系

- 上游 [KnifeLemon/Filee](https://github.com/KnifeLemon/Filee) 使用 **MIT** 许可证，版权归
  Myeonggu Kim (KnifeLemon) 与 Filee contributors 所有。
- 本仓库是**独立、非官方**的 Android 移植，与上游作者无隶属关系，未获得其背书。
- 差异：UI 全部用 Compose 重写；转换引擎换成纯 JVM 实现（PDFBox-Android、Commons Compress 等）；
  格式覆盖是桌面版的子集；交互从「拖拽」改成「点选 + 环形菜单」。
- 上游的 MIT 声明完整保留，见 [LICENSE](LICENSE) 与 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

---

## 已知限制

- 不支持音视频转码与 CAD（缺少相应引擎）。
- 支持格式少于桌面版。
- 大文件转换受设备内存限制，超大 PDF 可能失败。
- 尚未在大量机型上做兼容性验证。
- 后台长时间转换可能被系统回收，建议保持前台。

---

## 许可证

本项目采用 **MIT License**，见 [LICENSE](LICENSE)。
第三方组件及其许可证见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

---

## 致谢

- [KnifeLemon/Filee](https://github.com/KnifeLemon/Filee) —— 原始创意、产品设计与格式圆环交互。
- [Apache PDFBox](https://pdfbox.apache.org/) / [PDFBox-Android](https://github.com/TomRoush/PdfBox-Android)
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) 与 AndroidX
- [Bouncy Castle](https://www.bouncycastle.org/)
