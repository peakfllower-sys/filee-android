# Filee for Android v2.3.0

首个公开的 Android 版本：Windows 版 Filee 的非官方移植，Kotlin + Jetpack Compose 重写。

## 亮点

- **环形格式菜单**：选中文件后从圆环上直接点目标格式
- **预设 + 多步路由**：`docx → pdf → png` 这类链路自动选代价最低的路径
- **批量任务队列**：排队、进度、取消、历史
- **完全离线**：不申请 INTERNET 权限，文件不出本机
- **Material 3**：深色模式、动态取色、多语言

## 安装

1. 下载 `Filee-Android-2.3.0.apk`
2. 允许「安装未知来源应用」
3. 安装后可直接从系统分享菜单把文件发给 Filee

## 校验

```
SHA-256: 2BFF837C83919235DF448BB63E13FB14958F69F81BD88BCA8BE685FC77E00A4A
签名证书 SHA-256: 42:78:CA:8B:AF:9B:30:81:DE:7B:30:BB:F9:B4:57:B0:16:6D:C6:FE:1E:03:35:40:B5:F6:E6:B3:F6:B0:A1:65
```

## 系统要求

- Android 8.0（API 26）及以上
- arm64-v8a / armeabi-v7a / x86_64

## 已知限制

- 不含音视频转码与 CAD（桌面版依赖外部引擎）
- 支持格式是桌面版的子集
- 超大 PDF 可能因内存不足失败

## 致谢

基于 [KnifeLemon/Filee](https://github.com/KnifeLemon/Filee)（MIT）的产品设计与交互。
本项目为非官方移植，与上游作者无隶属关系。
