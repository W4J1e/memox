# memoX

一款简约的 Android 笔记应用。

Fork 自 [NotallyX](https://github.com/PhilKas/NotallyX)，进行了大量 UI 重构和品牌重塑。

## 功能特性

- **笔记与清单** — 创建文本笔记，支持内联可交互复选框
- **图片与附件** — 在笔记中插入图片、录音和文件附件
- **标签** — 使用彩色标签分类管理笔记
- **提醒** — 为笔记设置提醒
- **搜索** — 可展开的搜索栏，支持关键词过滤
- **WebDAV 同步** — 通过 WebDAV 服务器双向同步笔记和附件
  - 笔记修改后自动上传
  - 启动应用时自动拉取远端更新
  - 每 30 分钟定时双向同步
  - 支持手动上传/下载
- **自动备份** — 定时自动备份到本地存储
- **导入/导出** — 支持 JSON、HTML、纯文本和 Evernote 格式
- **生物识别锁** — 使用指纹或系统锁屏 PIN 保护笔记
- **动态配色** — Android 12+ 支持 Material You 主题
- **数据管理** — 在 设置 > 数据 中查看已删除笔记和提醒

## 截图

> TODO: 添加截图

## 构建

环境要求：
- Android Studio
- JDK 17+
- Android SDK 35

```bash
./gradlew assembleDebug
```

调试版 APK 输出路径：`app/build/outputs/apk/debug/`

## 开源许可

[GPL-3.0](LICENSE)
