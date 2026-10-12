#!/usr/bin/env python3
"""
MyFile WebDAV 索引单次生成脚本 (Zero-Dependency Standalone Indexer)

该脚本无需安装任何第三方库（仅使用 Python 3 标准库），可直接在 NAS、Linux、Windows 或 Mac 上运行，
扫描指定目录并生成供 MyFile Android 客户端使用的全量搜索索引文件 (webdav_index.json)。

使用示例：
  # 最简单用法：扫描当前目录，在当前目录生成 webdav_index.json
  python3 generate_index.py

  # 扫描指定目录，指定 WebDAV 访问前缀与输出文件路径
  python3 generate_index.py /data/nas/files -p /files/ -o /data/nas/files/webdav_index.json

  # 排除额外目录（如临时目录或缩略图缓存）
  python3 generate_index.py /data/webdav -e .git -e @eaDir -e #recycle -e node_modules
"""

import os
import sys
import json
import time
import argparse

DEFAULT_EXCLUDES = {
    '.git', '.svn', '.hg', 'node_modules', '__pycache__',
    '.DS_Store', 'Thumbs.db', '@eaDir', '#recycle', '$RECYCLE.BIN'
}

def generate_index(scan_dir, webdav_prefix='/', output_file='webdav_index.json', excludes=None, compact=True):
    start_time = time.time()
    scan_dir = os.path.abspath(scan_dir)
    
    if not os.path.exists(scan_dir):
        print(f"[错误] 目标扫描目录不存在: {scan_dir}")
        sys.exit(1)

    # 规范化 WebDAV 前缀，必须以 / 开头且以 / 结尾
    prefix = webdav_prefix.strip()
    if not prefix.startswith('/'):
        prefix = '/' + prefix
    if not prefix.endswith('/'):
        prefix = prefix + '/'

    exclude_set = set(DEFAULT_EXCLUDES)
    if excludes:
        exclude_set.update(excludes)

    output_abs = os.path.abspath(output_file)
    output_filename = os.path.basename(output_abs)

    print(f"==================================================")
    print(f" MyFile WebDAV 索引生成工具")
    print(f"==================================================")
    print(f"本地根目录 : {scan_dir}")
    print(f"WebDAV前缀 : {prefix}")
    print(f"输出目标   : {output_abs}")
    print(f"排除名单   : {sorted(list(exclude_set))}")
    print(f"==================================================")
    print(f"正在扫描文件系统，请稍候...")

    items = []
    file_count = 0
    dir_count = 0

    for dirpath, dirnames, filenames in os.walk(scan_dir):
        # 实时原地过滤排除的目录，避免深入无效遍历
        dirnames[:] = [d for d in dirnames if d not in exclude_set and not d.startswith('.')]

        rel_path = os.path.relpath(dirpath, scan_dir).replace('\\', '/')

        if rel_path == '.':
            current_prefix = prefix
        else:
            current_prefix = f"{prefix}{rel_path}/"
            try:
                d_stat = os.stat(dirpath)
                d_mtime = int(d_stat.st_mtime)
            except Exception:
                d_mtime = int(time.time())
            # 目录记录：以 / 结尾，大小为 0，带修改时间戳
            items.append([current_prefix, 0, d_mtime])
            dir_count += 1

        for f in filenames:
            # 排除自身生成的索引文件及黑名单文件
            if f == output_filename or f in exclude_set or f.startswith('.'):
                continue
            
            f_path = os.path.join(dirpath, f)
            try:
                f_stat = os.stat(f_path)
                size = f_stat.st_size
                mtime = int(f_stat.st_mtime)
            except Exception:
                size = 0
                mtime = 0
            
            # 文件记录：完整远程路径，文件字节大小，Unix时间戳(秒)
            items.append([f"{current_prefix}{f}", size, mtime])
            file_count += 1

    # 原子写入：先写入临时文件，再原子重命名，防止客户端在写入中途下载到残缺数据
    tmp_output = output_abs + '.tmp'
    os.makedirs(os.path.dirname(output_abs), exist_ok=True)
    
    with open(tmp_output, 'w', encoding='utf-8') as fp:
        if compact:
            json.dump(items, fp, separators=(',', ':'), ensure_ascii=False)
        else:
            json.dump(items, fp, indent=2, ensure_ascii=False)
            
    os.replace(tmp_output, output_abs)

    elapsed = time.time() - start_time
    file_size_mb = os.path.getsize(output_abs) / (1024 * 1024)

    print(f"--------------------------------------------------")
    print(f"✅ 索引生成完成！")
    print(f"总计条目: {len(items)} 项（{file_count} 个文件，{dir_count} 个文件夹）")
    print(f"索引体积: {file_size_mb:.2f} MB")
    print(f"消耗时间: {elapsed:.2f} 秒")
    print(f"输出文件: {output_abs}")
    print(f"--------------------------------------------------")

def main():
    parser = argparse.ArgumentParser(description="MyFile WebDAV 全量索引生成脚本 (Zero-Dependency)")
    parser.add_argument("dir", nargs="?", default=".", help="要扫描的本地目录路径（默认当前目录）")
    parser.add_argument("-p", "--prefix", default="/", help="WebDAV 远程访问 URL 前缀，默认为 '/'")
    parser.add_argument("-o", "--output", default="webdav_index.json", help="索引文件输出路径，默认为 'webdav_index.json'")
    parser.add_argument("-e", "--exclude", action="append", help="额外需要排除的目录或文件名（可多次指定）")
    parser.add_argument("--pretty", action="store_true", help="输出带缩进的易读 JSON（默认压缩体积）")

    args = parser.parse_args()
    generate_index(
        scan_dir=args.dir,
        webdav_prefix=args.prefix,
        output_file=args.output,
        excludes=args.exclude,
        compact=not args.pretty
    )

if __name__ == "__main__":
    main()
