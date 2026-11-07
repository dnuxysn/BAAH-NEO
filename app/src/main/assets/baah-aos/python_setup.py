"""Install and validate BAAH dependencies during Android's first-use dialog."""
import importlib.util
import json
import os
import subprocess
import sys

PACKAGES = (("pponnxcr==2.0", False), ("shapely", False), ("pyclipper", False), ("nicegui", True))
MARKER = "/.baah-python-deps-ready-v1"

def progress(done, title):
    print("[BAAH-AOS-SETUP] " + json.dumps({"done": done, "total": len(PACKAGES), "title": title}), flush=True)

def missing():
    return [spec for spec, _ in PACKAGES if importlib.util.find_spec(spec.split("==")[0]) is None]

def setup():
    if os.path.exists(MARKER):
        os.unlink(MARKER)
    with open("/etc/resolv.conf", "r", encoding="utf8") as stream:
        dns = stream.read()
    if "nameserver" not in dns:
        with open("/etc/resolv.conf", "w", encoding="utf8") as stream:
            stream.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\n")
    for index, (spec, with_deps) in enumerate(PACKAGES):
        name = spec.split("==")[0]
        progress(index, "正在准备 Python 依赖：" + name)
        if importlib.util.find_spec(name) is None:
            command = [sys.executable, "-m", "pip", "install", "--disable-pip-version-check",
                "--no-warn-script-location", "--root-user-action=ignore", "--break-system-packages",
                "--timeout", "30", "--retries", "2", "-i",
                os.environ.get("BAAH_AOS_PIP_MIRROR", "https://pypi.tuna.tsinghua.edu.cn/simple"), spec]
            if not with_deps:
                command.append("--no-deps")
            subprocess.run(command, check=True, timeout=900)
        progress(index + 1, "Python 依赖已就绪：" + name)
    # Install all four before import: pponnxcr itself needs shapely and pyclipper.
    progress(len(PACKAGES), "正在检查 Python 依赖是否可用")
    for spec, _ in PACKAGES:
        subprocess.run([sys.executable, "-c", "import " + spec.split("==")[0]], check=True, timeout=90)
    with open(MARKER, "w", encoding="utf8") as stream:
        stream.write("1")
    progress(len(PACKAGES), "Python 依赖全部安装完成")

if __name__ == "__main__":
    setup()
