# MyFile (我的文件)

一款现代、轻量、高效的 Android 本地与 WebDAV 云端文件管理器，采用 **Jetpack Compose** 与 **Material Design 3** 构建。

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-blue.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-blueviolet.svg)](https://developer.android.com/jetpack/compose)
[![Release](https://img.shields.io/badge/Release-v1.2.8-orange.svg)](https://github.com/almus2zhang/myfile/releases)

---

## 🌟 核心特性

### 📁 1. 本地与 WebDAV 双模管理
- **本地存储管理**：全盘文件/文件夹浏览、重命名、移动、删除、复制、属性查看、分享。
- **WebDAV 云存储管理**：无缝连接群晖/威联通 NAS、Nextcloud、Alist、坚果云及自建 WebDAV 服务，支持多账号快速切换。
- **动态域名解析 (DoH)**：支持无协议设定地址并发解析阿里/腾讯/Cloudflare DNS TXT 记录，自动提取真实打洞 IP 与端口。
- **极速创建副本（服务端原生克隆）**：
  - 远程 WebDAV 文件创建副本直接调用 HTTP `COPY` 协议，**由云端/NAS 本地秒级完成克隆，手机完全零流量消耗**。
  - 智能递增副本命名规则（`abc.a` $\rightarrow$ `abc copy.a` $\rightarrow$ `abc copy 2.a`）。
- **本地缓存对比与软盘标记**：
  - 本地存在同名、同大小且同修改时间的文件时，文件图标左下角显示软盘标识。
  - 属性面板可直观对比远程与本地缓存状态，支持快速双向同步。

### 🔍 2. 双模搜索体系（全盘秒级索引搜索 + 目录递归普通搜索）
- **⚡ 全盘索引搜索**：
  - **原理机制**：基于服务端预生成的 `webdav_index.json`（紧凑三元组格式 `[路径, 字节大小, 时间戳]`）。
  - **秒级响应**：本地持久化轻量缓存，启动时毫秒级预热。即使百万级文件体量，多关键词空格分词过滤依然近乎瞬时出结果。
  - **智能增量校验**：自动检查 HTTP ETag / Last-Modified / Content-Length，仅在云端索引变更时增量/按需同步，节省流量。
  - **配置与使用**：在 WebDAV 账户设置中填写「索引文件路径」（例如 `/webdav_index.json`），勾选自动同步；进入搜索后点击输入框右侧按钮可一键在“索引搜索”与“普通搜索”间无缝切换。
- **🔍 目录递归普通搜索**：
  - 无需额外配置或前置生成索引文件，即开即用。
  - 自动在当前所在目录及所有子目录下进行广度优先深度遍历扫描，边扫描边实时流式呈现匹配结果。

### 🧭 3. 路径面包屑与手动路径直达（支持隐藏路径）
- **层级穿梭**：支持点击面包屑任意层级回跳；
- **空白区域路径直达**：点击面包屑最右侧空白区域（或编辑图标），弹出手动路径跳转对话框，可直接输入相对路径或绝对路径（支持直达被隐藏的隐蔽文件夹）；
- **极速隐蔽隐藏**：在文件夹属性的「完整路径」或文件属性的「远程路径」上**快速连击 5 次**，即可一键隐藏该文件夹或文件；
- **面包屑批量解除**：在顶部面包屑路径末位（当前目录）**快速连击 5 次**，一键取消本目录及子目录下的所有隐藏状态。

### 📦 4. 全格式压缩包内置浏览与解压
无需安装第三方解压软件，直接点击压缩包即可入内浏览：
- **广泛格式支持**：
  - **ZIP**（`.zip`，自适应 UTF-8 / GBK 编码，杜绝 Windows 压缩包乱码）
  - **RAR**（`.rar`，纯 Java 引擎，完美兼容各类 CPU 架构）
  - **7-Zip**（`.7z`）
  - **TAR 归档与复合压缩**（`.tar`, `.tar.gz`, `.tgz`, `.tar.bz2`, `.tbz2`, `.tar.xz`, `.txz`）
  - **单文件压缩**（`.gz`, `.bz2`, `.xz`）
- **功能全面**：
  - 目录树层级浏览、面包屑导航、快速回跳上级。
  - 单文件即时按需提取预览（文本、图片、视频直接调用内置/系统程序打开）。
  - 一键全部解压至本地 Downloads 目录，实时进度展示与严格的 Zip Slip 路径穿越安全防护。

### ⚡ 5. 视频流式播放与多线程下载加速
- **视频边下边播**：内置轻量流式代理服务器，远程视频即点即播，支持记忆历史播放进度。
- **并发分片下载**：支持多连接并发与断点续传。
- **绕过限速**：支持临时扩展名伪装（如 `.avi` 伪装）规避部分网盘对于特定格式的限速策略。

### 📝 6. 内置现代化文本阅读与编辑器
- 支持大文件流式渐进式加载，防卡死防内存溢出。
- 自动检测并支持 UTF-8、GBK、GB2312 等编码无缝切换。
- 支持代码语法查看、行号同步滚动、自动换行与保存回写。

### 🔄 7. 灵活的 OTA 在线更新
- 支持启动时静默检查与设置页手动检查。
- **自主掌控更新**：支持「稍后再说」与「忽略此版本」，拒绝强制更新打扰；设置页可随时查看已忽略版本并一键恢复提醒。

---

## 🛠️ 技术架构

- **UI 框架**：Jetpack Compose、Material Design 3、Accompanist
- **架构模式**：MVVM、Kotlin Coroutines & Flow
- **本地存储**：AndroidX Room (SQLite)、Preferences DataStore
- **网络与媒体**：OkHttp 4、Coil 2 (图片与视频缩略图)
- **解压缩引擎**：Apache Commons Compress、Tukaani XZ、Junrar (全纯 Java 实现，零 NDK 负担)

---

## 📥 下载与安装

前往 [Releases 页面](https://github.com/almus2zhang/myfile/releases) 下载最新发布的 APK 安装包即可体验。

```bash
# 源码克隆与本地编译
git clone https://github.com/almus2zhang/myfile.git
cd myfile
./gradlew assembleRelease
```

---

## 📄 开源许可

本项目遵循开源规范，欢迎提交 Issue 与 Pull Request。
