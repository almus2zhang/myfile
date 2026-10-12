#!/usr/bin/env python3
import os
import json
import time
import threading
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler

class IndexerDaemon:
    def __init__(self, config_path):
        self.config_path = config_path
        self.load_config()
        self.debounce_timer = None
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.periodic_thread = None
        
    def load_config(self):
        with open(self.config_path, 'r', encoding='utf-8') as f:
            self.config = json.load(f)
        self.output_file = self.config.get('output_file', 'webdav_index.json')
        self.mappings = self.config.get('mappings', [])
        self.excludes = set(self.config.get('excludes', ['.git', 'node_modules', '__pycache__']))
        self.debounce_seconds = self.config.get('debounce_seconds', 2.0)
        # Default: 1440 minutes (24 hours / 1 day)
        self.interval_minutes = self.config.get('interval_minutes', 1440)
        
    def generate_index(self):
        with self.lock:
            print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] Generating index...")
            items = []
            
            for mapping in self.mappings:
                local_dir = os.path.abspath(mapping['local_dir'])
                webdav_prefix = mapping['webdav_prefix']
                if not webdav_prefix.endswith('/'):
                    webdav_prefix += '/'
                    
                if not os.path.exists(local_dir):
                    print(f"Warning: Local directory not found: {local_dir}")
                    continue
                    
                for dirpath, dirnames, filenames in os.walk(local_dir):
                    # Filter out excluded directories
                    dirnames[:] = [d for d in dirnames if d not in self.excludes]
                    
                    rel_path = os.path.relpath(dirpath, local_dir)
                    rel_path = rel_path.replace('\\', '/')
                    
                    if rel_path == '.':
                        current_prefix = webdav_prefix
                    else:
                        current_prefix = f"{webdav_prefix}{rel_path}/"
                        try:
                            d_stat = os.stat(dirpath)
                            d_mtime = int(d_stat.st_mtime)
                        except Exception:
                            d_mtime = 0
                        items.append([current_prefix, 0, d_mtime])
                        
                    for f in filenames:
                        if f == os.path.basename(self.output_file) or f in self.excludes:
                            continue
                        file_path = os.path.join(dirpath, f)
                        try:
                            f_stat = os.stat(file_path)
                            size = f_stat.st_size
                            mtime = int(f_stat.st_mtime)
                        except Exception:
                            size = 0
                            mtime = 0
                        items.append([f"{current_prefix}{f}", size, mtime])
            
            # Write atomically to prevent partial reads by the frontend
            tmp_output = self.output_file + '.tmp'
            with open(tmp_output, 'w', encoding='utf-8') as f:
                json.dump(items, f, separators=(',', ':'))
            os.replace(tmp_output, self.output_file)
            
            print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] Index generated: {len(items)} items saved to {self.output_file}")

    def on_event(self, event):
        # Ignore events on the output file or its temp file
        if event.src_path.endswith(self.output_file) or event.src_path.endswith('.tmp'):
            return
            
        with self.lock:
            if self.debounce_timer is not None:
                self.debounce_timer.cancel()
            self.debounce_timer = threading.Timer(self.debounce_seconds, self.generate_index)
            self.debounce_timer.start()

    def start_periodic_refresh(self):
        if not self.interval_minutes or self.interval_minutes <= 0:
            print("[Periodic Refresh] Disabled (interval_minutes <= 0)")
            return

        interval_seconds = self.interval_minutes * 60
        hours = self.interval_minutes / 60
        print(f"[Periodic Refresh] Scheduled every {self.interval_minutes} minutes ({hours:.1f} hours)")

        def periodic_worker():
            while not self.stop_event.is_set():
                # Wait until timeout or stop signal is received
                if self.stop_event.wait(interval_seconds):
                    break
                print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] [Periodic Refresh] Triggering scheduled full index refresh...")
                try:
                    self.generate_index()
                except Exception as e:
                    print(f"Error during scheduled refresh: {e}")

        self.periodic_thread = threading.Thread(target=periodic_worker, daemon=True)
        self.periodic_thread.start()

    def stop(self):
        self.stop_event.set()
        with self.lock:
            if self.debounce_timer is not None:
                self.debounce_timer.cancel()

class ChangeHandler(FileSystemEventHandler):
    def __init__(self, daemon):
        self.daemon = daemon
        
    def on_any_event(self, event):
        self.daemon.on_event(event)

def main():
    import sys
    config_path = sys.argv[1] if len(sys.argv) > 1 else 'config.json'
    if not os.path.exists(config_path):
        print(f"Config file not found: {config_path}")
        print("Please copy config.json.example to config.json and configure it.")
        sys.exit(1)
        
    daemon = IndexerDaemon(config_path)
    
    # Generate initial index
    daemon.generate_index()
    
    # Start periodic full refresh timer
    daemon.start_periodic_refresh()
    
    # Setup filesystem observer
    observer = Observer()
        
    handler = ChangeHandler(daemon)
    
    for mapping in daemon.mappings:
        local_dir = os.path.abspath(mapping['local_dir'])
        if os.path.exists(local_dir):
            observer.schedule(handler, local_dir, recursive=True)
            print(f"Watching: {local_dir} -> {mapping['webdav_prefix']}")
            
    observer.start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping daemon...")
        daemon.stop()
        observer.stop()
    observer.join()

if __name__ == '__main__':
    main()
