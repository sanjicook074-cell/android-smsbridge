# -*- coding: utf-8 -*-
"""生成 docs/ 里的图。

两张：
  ui-main.png —— App 主界面**还原图**（照着 MainActivity.java 的控件与文案画，
                 不是真机截图；Android 原生界面没法在本机直接抓）
  flow.png    —— 三条读取通道 + 电脑端收码服务的数据流

演示数据全是虚构的：号码用服务号 1069、验证码用顺子 741852、平台名用「某某网」。
"""
import os
import subprocess
import tempfile
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "docs")
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

# (输出名, 源文件, 窗口尺寸)
PAGES = [
    ("ui-main.png", "ui.html", "393,1150"),
    ("flow.png", "flow.html", "980,502"),
]


def shoot(profile, url, out_path, size):
    subprocess.run(
        [CHROME, "--headless=new", "--disable-gpu", "--no-first-run",
         "--no-default-browser-check", "--hide-scrollbars",
         "--window-size=" + size, "--force-device-scale-factor=2",
         "--virtual-time-budget=4000",
         "--user-data-dir=" + profile, "--screenshot=" + out_path, url],
        capture_output=True, timeout=180)


def main():
    os.makedirs(OUT, exist_ok=True)
    tmpdir = tempfile.mkdtemp(prefix="sb_shot_")
    try:
        for name, src, size in PAGES:
            page = os.path.join(HERE, src)
            if not os.path.exists(page):
                print("跳过（源文件不存在）：%s" % src)
                continue
            # 每个页面用独立的空 profile：否则会读到上一次的缓存 / localStorage
            shoot(os.path.join(tmpdir, "p_" + name), "file:///" + page.replace("\\", "/"),
                  os.path.join(OUT, name), size)
            print("%-16s -> docs/%s" % (src, name))
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == "__main__":
    main()
