# MyFile WebDAV 服务端索引生成脚本套件

本目录提供了为 **MyFile (我的文件)** Android 客户端生成全量秒级搜索索引文件（`webdav_index.json`）的服务端自动化工具。

---

## 📖 目录

- [1. 为什么需要索引文件？](#1-为什么需要索引文件)
- [2. 索引文件格式规范](#2-索引文件格式规范)
- [3. 工具一：单次生成脚本 (generate_index.py)](#3-工具一单次生成脚本-generate_indexpy)
- [4. 工具二：实时监听守护进程 (webdav_indexer_daemon.py)](#4-工具二实时监听守护进程-webdav_indexer_daemonpy)
- [5. 生产环境部署方案](#5-生产环境部署方案)
  - [方案 A：Linux / 群晖 / 威联通 Crontab 定时任务](#方案-alinux--群晖--威联通-crontab-定时任务)
  - [方案 B：Systemd 系统服务后台常驻](#方案-bsystemd-系统服务后台常驻)
  - [方案 C：Docker 容器化运行](#方案-cdocker-容器化运行)
- [6. 在 MyFile Android App 中配置使用](#6-在-myfile-android-app-中配置使用)

---

## 1. 为什么需要索引文件？

WebDAV 原生协议在文件搜索方面存在局限：
- 传统的目录遍历（递归 PROPFIND）随目录层级加深而产生大量 HTTP 请求，速度慢、耗费电量与流量。
- 服务端生成一个极小体积的紧凑索引文件（10 万文件仅约 5~8 MB），客户端启动时秒级预热至本地缓存：
  - **毫秒级极速响应**：全盘百万文件秒级搜索与过滤。
  - **多关键词多维度匹配**：支持空格分词，不区分大小写，支持 AND 联合匹配。
  - **极省流量与电量**：客户端通过 HTTP ETag / Last-Modified 智能增量校验，服务器未更新时不重复下载。

---

## 2. 索引文件格式规范

索引文件是一个纯 JSON 二维数组，每个元素为一个紧凑的三元组：

```json
[
  ["/projects/notes/", 0, 1726148400],
  ["/projects/notes/todo.txt", 1024, 1726148520],
  ["/photos/2026/travel.jpg", 3456789, 1726149000]
]
```

- **第一项（路径 String）**：WebDAV 远程绝对路径（文件夹以 `/` 结尾，文件不以 `/` 结尾）。
- **第二项（大小 Long）**：文件字节数（文件夹固定为 `0`）。
- **第三项（时间戳 Long）**：Unix 时间戳（秒级）。

---

## 3. 工具一：单次生成脚本 (generate_index.py)

**零依赖（Zero-Dependency）**，仅依赖 Python 3 标准库，开箱即用。适合手动生成、定时任务（Crontab）或自动化构建流程。

### 命令行参数

```bash
python3 generate_index.py [扫描目录] [参数选项]
```

| 参数 | 说明 | 默认值 |
| :--- | :--- | :--- |
| `dir` | 要扫描的本地文件系统根目录 | 当前目录 `.` |
| `-p`, `--prefix` | 该目录在 WebDAV 中的远程 URL 访问前缀 | `/` |
| `-o`, `--output` | 索引文件输出绝对或相对路径 | `webdav_index.json` |
| `-e`, `--exclude` | 额外排除的文件夹或文件名（可多次指定） | 内置常见排除项 |
| `--pretty` | 输出格式化可读 JSON（默认压缩体积） | 默认关闭（紧凑压缩） |

### 使用示例

```bash
# 1. 最基础：扫描当前目录，在当前目录生成 webdav_index.json
python3 generate_index.py

# 2. 扫描指定目录，并配置对应的 WebDAV 前缀
python3 generate_index.py /data/nas/files -p /files/ -o /data/nas/files/webdav_index.json

# 3. 排除特定临时目录或群晖系统生成目录
python3 generate_index.py /data/webdav -e @eaDir -e #recycle -e node_modules -e .git
```

---

## 4. 工具二：实时监听守护进程 (webdav_indexer_daemon.py)

基于 Python `watchdog` 机制，在服务端后台常驻运行：
- **实时事件监听**：监听文件新增、修改、重命名、删除。
- **智能防抖合并（Debounce）**：批量拷贝或上传大量文件时，自动防抖并在操作平息后（默认 2 秒）触发增量刷新，避免高频重复写盘。
- **全量保底刷新**：支持配置周期性全量兜底扫描（默认每 24 小时一次）。
- **原子写入保护**：先写临时文件 `.tmp` 再重命名，避免客户端在生成中途读取到不完整文件。

### 安装依赖

```bash
pip install -r requirements.txt
```

### 配置文件 `config.json`

从示例文件复制并修改：

```bash
cp config.json.example config.json
```

```json
{
  "output_file": "/var/www/webdav/webdav_index.json",
  "mappings": [
    {
      "local_dir": "/data/webdav_root/projects",
      "webdav_prefix": "/projects/"
    },
    {
      "local_dir": "/data/webdav_root/media",
      "webdav_prefix": "/media/"
    }
  ],
  "excludes": [
    ".git",
    "node_modules",
    "__pycache__",
    ".DS_Store",
    "@eaDir",
    "#recycle"
  ],
  "debounce_seconds": 2,
  "interval_minutes": 1440
}
```

### 启动运行

```bash
python3 webdav_indexer_daemon.py config.json
```

---

## 5. 生产环境部署方案

### 方案 A：Linux / 群晖 / 威联通 Crontab 定时任务

最轻量省心的方案，无需安装常驻后台服务。

在群晖「控制面板 -> 任务计划」或 Linux `crontab -e` 中添加：

```bash
# 每小时执行一次索引生成
0 * * * * /usr/bin/python3 /path/to/scripts/generate_index.py /volume1/webdav -p / -o /volume1/webdav/webdav_index.json > /dev/null 2>&1
```

---

### 方案 B：Systemd 系统服务后台常驻

适用于 Ubuntu / Debian / CentOS / Arch 独立主机。

创建服务文件 `/etc/systemd/system/webdav-indexer.service`：

```ini
[Unit]
Description=MyFile WebDAV Indexer Daemon
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/path/to/scripts
ExecStart=/usr/bin/python3 /path/to/scripts/webdav_indexer_daemon.py /path/to/scripts/config.json
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

启动并设置开机自启：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now webdav-indexer.service
sudo systemctl status webdav-indexer.service
```

---

### 方案 C：Docker 容器化运行

创建 `Dockerfile`：

```dockerfile
FROM python:3.11-alpine
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY webdav_indexer_daemon.py .
CMD ["python3", "webdav_indexer_daemon.py", "/etc/indexer/config.json"]
```

`docker-compose.yml` 示例：

```yaml
version: '3.8'
services:
  webdav-indexer:
    build: .
    restart: always
    volumes:
      - /data/webdav:/data/webdav
      - ./config.json:/etc/indexer/config.json:ro
```

---

## 6. 在 MyFile Android App 中配置使用

1. 打开 **MyFile** App；
2. 切换到目标 WebDAV 账户，点击顶部账户卡片的 **编辑账户**（铅笔图标）；
3. 找到 **「全盘索引文件」** 配置项：
   - 填写远程访问路径，例如 `/webdav_index.json`；
   - 勾选 **「自动后台同步索引」** 开关；
4. 保存配置后，App 会在连接时自动检测并下载云端索引；
5. 进入搜索界面后，点击输入框右侧的搜索模式切换按钮，即可在 **⚡ 索引搜索** 与 **🔍 普通递归搜索** 之间随时无缝切换！
