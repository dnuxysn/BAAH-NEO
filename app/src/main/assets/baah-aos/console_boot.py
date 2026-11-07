"""BAAH 控制台启动引导（App 侧 ConsoleLauncher 用 proot 拉起它）。

为什么要有这一层：控制台要**进 App 就能用**，不能等悬停挂机（guest 里的 wrapper）起来。
这里做三件事：
  1) 确保自签证书在位（WebView 走 https://127.0.0.1:8000，明文被 targetSdk36 拦掉）；
  2) 给 /opt/baah/jsoneditor.py 打幂等的 SSL 补丁（nicegui ui.run 加 ssl_certfile/ssl_keyfile）；
  3) runpy 以 __main__ 方式跑 jsoneditor.py（ui.run 会阻塞，进程常驻）。

注意：proot 退出会带走所有被跟踪的进程，所以本进程必须由 App 侧的长命 proot 持有。
"""

import io
import os
import runpy
import subprocess
import sys

AOS = "/opt/baah-aos"
CERT = os.path.join(AOS, "gui.crt")
KEY = os.path.join(AOS, "gui.key")
LOG_DIR = os.path.join(AOS, "log")
GUI = "/opt/baah/jsoneditor.py"
MARK = "# DSH: \u63a7\u5236\u53f0\u8d70 HTTPS"
PORT = os.environ.get("BAAH_AOS_GUI_PORT", "8000")


def log(msg):
    print("[console] " + msg, flush=True)


def ensure_cert():
    if os.path.isfile(CERT) and os.path.isfile(KEY):
        return True
    try:
        subprocess.run(
            ["/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
             "-keyout", KEY, "-out", CERT, "-days", "3650",
             "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        log("cert generated")
        return True
    except Exception as exc:  # noqa: BLE001
        log("cert failed: %r" % (exc,))
        return False


def ensure_ssl_patch():
    try:
        src = io.open(GUI, encoding="utf-8").read()
    except OSError as exc:
        log("cannot read gui: %r" % (exc,))
        return
    if MARK in src:
        return
    old = "reload=False, host=args.host"
    if src.count(old) != 1:
        log("ssl patch skipped (upstream ui.run call changed)")
        return
    new = ("reload=False, " + MARK + "\n       ssl_certfile=r\"%s\", ssl_keyfile=r\"%s\", host=args.host"
           % (CERT, KEY))
    patched = src.replace(old, new, 1)
    try:
        compile(patched, GUI, "exec")
    except SyntaxError as exc:
        log("ssl patch rejected (syntax): %r" % (exc,))
        return
    io.open(GUI, "w", encoding="utf-8").write(patched)
    log("ssl patch applied")


def main():
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
    except OSError:
        pass
    os.chdir("/opt/baah")
    log("console boot: port=%s cwd=%s" % (PORT, os.getcwd()))
    ensure_cert()
    ensure_ssl_patch()
    if not os.path.isfile(GUI):
        log("gui script missing: %s" % GUI)
        return
    sys.argv = [GUI, "--host", "127.0.0.1", "--port", PORT, "--no-show"]
    runpy.run_path(GUI, run_name="__main__")


if __name__ == "__main__":
    main()
