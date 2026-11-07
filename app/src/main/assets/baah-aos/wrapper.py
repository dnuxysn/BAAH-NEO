#!/usr/bin/env python3
"""
BAAH-AOS · wrapper —— 跑在 proot guest 里的"管家"
=================================================

职责（**只做进程与日志，不 import BAAH**）：

  * 监听 127.0.0.1:23400，给 App 的挂机页/悬浮窗提供启停与日志的 HTTP 接口；
  * 拉起并监管 `runner.py`（真正的 BAAH 调度器）：崩了按退避重拉，停止时杀整个进程组；
  * 按 mtime 找最新日志并吐尾巴；
  * 单实例锁 + stdin EOF 自杀（App/proot 一死，这里不能留孤儿）。

为什么是 stdlib-only：guest 里多一个依赖就多一个 aarch64 装不上的风险；
而这个文件要能在任何 Python 3.10 上直接跑。

与上游的关系：本文件属于 **我方适配层**（`/opt/baah-aos`），
与 `/opt/baah` 的上游源码严格分开——上游更新只替换后者。

路径与端口都可用环境变量覆盖（默认值是 guest 里的真实路径），
这样在开发机上也能把它跑起来做测试。
"""

from __future__ import annotations

import json
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# ── 路径与端口（guest 里的默认值）────────────────────────────────────────────
AOS_ROOT = os.environ.get("BAAH_AOS_ROOT", "/opt/baah-aos")
BAAH_ROOT = os.environ.get("BAAH_AOS_BAAH_ROOT", "/opt/baah")
RUNNER_PATH = os.path.join(AOS_ROOT, "runner.py")
SEED_DIR = os.path.join(AOS_ROOT, "seeds")
CONFIG_DIR = os.path.join(BAAH_ROOT, "BAAH_CONFIGS")
LOG_DIR = os.path.join(BAAH_ROOT, "DATA", "LOGS")
LOCK_PATH = os.path.join(AOS_ROOT, "log", "wrapper.lock")
# [DSH 2026-09-25] runner 的输出原来进 /dev/null，崩了完全没线索。改落盘。
RUNNER_LOG = os.path.join(AOS_ROOT, "log", "runner-out.log")

# 桥监听在特权进程里；BAAH 用 rootfs 里的 aarch64 adb 客户端连它
BRIDGE_HOST = os.environ.get("BAAH_AOS_BRIDGE_HOST", "127.0.0.1")
BRIDGE_PORT = int(os.environ.get("BAAH_AOS_BRIDGE_PORT", "23300"))
ADB_BINARY = os.environ.get("BAAH_AOS_ADB_BINARY", "/usr/bin/adb")


def adb_client() -> str:
    """现在用哪个当 adb 客户端。

    ⚠️ 2026-09-28 查明：APK 里那份 rootfs.tar.xz **根本没有 adb 二进制**（`find / -name adb` 空），
    所以不能假设 `/usr/bin/adb` 存在。改成优先用我们自己的 `adb-wrap`：它在真 adb 不存在时
    会退回**内置的迷你 adb 客户端**（纯 stdlib，只实现 BAAH 用到的几条命令，直连桥 23300）。
    """
    wrap = os.path.join(AOS_ROOT, "adb-wrap")
    try:
        if os.path.isfile(wrap) and os.access(wrap, os.X_OK):
            return wrap
    except OSError:
        pass
    return ADB_BINARY

HOST = os.environ.get("BAAH_AOS_WRAPPER_HOST", "127.0.0.1")
PORT = int(os.environ.get("BAAH_AOS_WRAPPER_PORT", "23400"))

# 重拉退避：5s 起步翻倍，60s 封顶；活过 300s 就把退避复位
BACKOFF_START_SEC = 5.0
BACKOFF_MAX_SEC = 60.0
BACKOFF_RESET_AFTER_SEC = 300.0
STOP_GRACE_SEC = 3.0

CONFIG_NAME_RE = __import__("re").compile(r"^[A-Za-z0-9_\-]+$")


def log(message: str) -> None:
    print(f"[wrapper] {message}", flush=True)


# [DSH 2026-09-25] 传给 runner 的环境必须是「guest 视角」的。
# App 从 Android 继承下来的变量（尤其 TMPDIR=/data/user/0/<pkg>/files/proot-tmp）
# 在 proot 里是不存在的路径，会被解析成 <rootfs>/data/user/0/... ，导致：
#   F adb: cannot open .../adb.<uid>.log: No such file or directory
#   ADB server didn't ACK -> failed to start daemon -> adb: cannot connect to daemon
# 表现就是 BAAH 一直「未检测到设备连接」。这里统一洗成 guest 内的路径。
_ANDROID_ENV_KEYS = (
    "ANDROID_ROOT",
    "ANDROID_DATA",
    "ANDROID_STORAGE",
    "ANDROID_TMP",
    "ANDROID_ASSETS",
    "ANDROID_PROPERTY_WORKSPACE",
    "BOOTCLASSPATH",
    "EXTERNAL_STORAGE",
    "DOWNLOAD_CACHE",
)


def build_child_env() -> dict:
    env = dict(os.environ)
    for key in _ANDROID_ENV_KEYS:
        env.pop(key, None)
    env["TMPDIR"] = "/tmp"
    env["TMP"] = "/tmp"
    env["TEMP"] = "/tmp"
    env["HOME"] = env.get("HOME") or "/root"
    env.setdefault("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
    return env


# ── runner 监管 ──────────────────────────────────────────────────────────────
class RunnerSupervisor:
    """按 wanted 状态拉起/停掉 runner，崩了自动重拉。"""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._process: subprocess.Popen | None = None
        self._wanted = False
        self._config: str | None = None
        self._task: str | None = None
        self._respawns = 0
        self._last_exit_code = None
        self._generation = 0
        self._started_at = 0.0
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None

    # -- 对外 ---------------------------------------------------------------
    def start(self, config: str, task: str | None = None) -> None:
        task = task or None
        with self._lock:
            process = self._process
            running_config = self._config
            running_task = self._task
        # 换配置/换任务时先把旧的收掉，否则会同时跑两个 BAAH 抢同一个"设备"
        if process is not None and process.poll() is None and (running_config != config or running_task != task):
            log(f"切换 -> {config}/{task}，先停掉当前 runner")
            self._terminate_group(process)
        with self._lock:
            self._wanted = True
            self._generation += 1
            self._config = config
            self._task = task
            self._respawns = 0
            self._last_exit_code = None
        self._ensure_thread()
        log(f"start 请求：config={config}" + (f" task={task}" if task else ""))

    def stop(self) -> None:
        with self._lock:
            self._wanted = False
            self._generation += 1
            process = self._process
        if process is not None:
            self._terminate_group(process)
        log("stop 请求：runner 已停止")

    def status(self) -> dict:
        with self._lock:
            process = self._process
            return {
                "runner_alive": process is not None and process.poll() is None,
                "runner_generation": self._generation,
                "runner_pid": process.pid if process is not None else None,
                "runner_wanted": self._wanted,
                "runner_config": self._config,
                "runner_task": self._task,
                "runner_respawns": self._respawns,
                "runner_exit_code": self._last_exit_code,
            }

    def shutdown(self) -> None:
        self._stop_event.set()
        self.stop()

    # -- 内部 ---------------------------------------------------------------
    def _ensure_thread(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._thread = threading.Thread(target=self._supervise, name="runner-supervisor", daemon=True)
        self._thread.start()

    def _spawn(self, config: str, task: str | None = None) -> subprocess.Popen:
        os.makedirs(os.path.dirname(LOCK_PATH), exist_ok=True)
        # [DSH 2026-09-25] 原来 stdout/stderr 都进 DEVNULL，runner 一崩就什么线索都没有
        # （实测：连崩 3 次、日志目录全空、只看到 runner_alive=false）。改成落盘留证。
        os.makedirs(os.path.dirname(RUNNER_LOG), exist_ok=True)
        if os.path.isfile(RUNNER_LOG) and os.path.getsize(RUNNER_LOG):
            archive = os.path.join(os.path.dirname(RUNNER_LOG),
                "runner-out-%s-%s.log" % (time.strftime("%Y%m%d-%H%M%S"), time.time_ns()))
            os.replace(RUNNER_LOG, archive)
        out = open(RUNNER_LOG, "wb", buffering=0)
        out.write(
            ("\n===== runner 启动 %s config=%s =====\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), config)).encode()
        )
        kwargs = dict(
            cwd=AOS_ROOT,
            stdin=subprocess.DEVNULL,   # runner 里也把 input() 换掉了，双保险
            stdout=out,
            stderr=subprocess.STDOUT,
            env=build_child_env(),
        )
        # os.setsid 是 Unix 专有；这样整个 runner 及其子进程构成一个进程组，
        # 停止时一次 killpg 就能收干净（BAAH 里会再往下起 adb/python 子进程）
        if hasattr(os, "setsid"):
            kwargs["preexec_fn"] = os.setsid
        argv = [sys.executable, RUNNER_PATH, config]
        if task:
            argv.append(task)
        return subprocess.Popen(argv, **kwargs)

    def _terminate_group(self, process: subprocess.Popen) -> None:
        if process.poll() is not None:
            return
        try:
            if hasattr(os, "killpg"):
                os.killpg(os.getpgid(process.pid), signal.SIGTERM)
            else:
                process.terminate()
        except (ProcessLookupError, PermissionError, OSError):
            return
        try:
            process.wait(timeout=STOP_GRACE_SEC)
            return
        except subprocess.TimeoutExpired:
            pass
        log("SIGTERM 后仍未退出，升级为 SIGKILL")
        try:
            if hasattr(os, "killpg"):
                os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            else:
                process.kill()
        except (ProcessLookupError, PermissionError, OSError):
            pass

    def _supervise(self) -> None:
        backoff = BACKOFF_START_SEC
        while not self._stop_event.is_set():
            with self._lock:
                wanted = self._wanted
                config = self._config
                task = self._task
                generation = self._generation
            if not wanted or not config:
                time.sleep(0.5)
                backoff = BACKOFF_START_SEC
                continue

            try:
                process = self._spawn(config, task)
            except Exception as exc:  # noqa: BLE001
                log(f"拉起 runner 失败：{exc!r}")
                time.sleep(backoff)
                backoff = min(backoff * 2, BACKOFF_MAX_SEC)
                continue

            with self._lock:
                stale = generation != self._generation or not self._wanted
                if not stale:
                    self._process = process
                    self._started_at = time.monotonic()
            if stale:
                self._terminate_group(process)
                continue
            log(f"runner 已拉起 pid={process.pid} config={config}")

            exit_code = process.wait()
            with self._lock:
                self._process = None
                if generation != self._generation:
                    continue
                self._last_exit_code = exit_code
                alive_for = time.monotonic() - self._started_at
                still_wanted = self._wanted

            log(f"runner 退出 code={exit_code}（存活 {alive_for:.0f}s）" + (f" task={task}" if task else ""))
            if task or exit_code != 3:
                # 单任务是一次性的：跑完就结束，别让监督循环再把它拉起来（否则任务会被刷爆）
                with self._lock:
                    if generation != self._generation:
                        continue
                    self._wanted = False
                    self._task = None
                log("本轮任务结束，不再重拉；仅虚拟屏尚未就绪时重试")
                continue
            if not still_wanted:
                continue

            # 活够久说明是正常运行结束/偶发崩溃，退避复位；秒退则是配置性错误，要退避
            if alive_for >= BACKOFF_RESET_AFTER_SEC:
                backoff = BACKOFF_START_SEC
            with self._lock:
                self._respawns += 1
            log(f"{backoff:.0f}s 后重拉（第 {self._respawns_dict()} 次）")
            time.sleep(backoff)
            backoff = min(backoff * 2, BACKOFF_MAX_SEC)

    def _respawns_dict(self) -> int:
        with self._lock:
            return self._respawns


# ── 日志与配置 ───────────────────────────────────────────────────────────────
def newest_log_file() -> str | None:
    """按 mtime 取最新日志——不猜文件名（BAAH 的日志名带日期，整天不换名）。"""
    if not os.path.isdir(LOG_DIR):
        return None
    candidates = [
        os.path.join(LOG_DIR, name)
        for name in os.listdir(LOG_DIR)
        if name.endswith(".txt") or name.endswith(".log")
    ]
    if not candidates:
        return None
    return max(candidates, key=os.path.getmtime)


def tail_log(lines: int, path: str | None = None) -> tuple[str | None, list[str]]:
    path = path or newest_log_file()
    if path is None:
        return None, []
    try:
        with open(path, "rb") as fp:
            # 先 seek 到末尾 256KB 再切行，避免整天的大日志把内存吃光
            fp.seek(0, os.SEEK_END)
            size = fp.tell()
            fp.seek(max(0, size - TAIL_SCAN_BYTES))
            data = fp.read().decode("utf-8", "replace")
        return path, data.splitlines()[-lines:]
    except OSError as exc:
        return path, [f"<读日志失败: {exc}>"]


TAIL_SCAN_BYTES = 256 * 1024


def list_configs() -> list[str]:
    if not os.path.isdir(CONFIG_DIR):
        return []
    names = [n[:-5] for n in os.listdir(CONFIG_DIR) if n.endswith(".json")]
    # 按名字排序，保证 UI 上顺序稳定
    return sorted(names)


# ── adb 包装器：修「截图文件损坏」─────────────────────────────────────────────
# 我们的假 adbd 对 `screencap` 的实现是**把 PNG 吐到 stdout**（不写文件），而设备侧
# screencap 在多屏环境下还会往 stderr 打 `[Warning] Multiple displays ...`；BAAH 用
# pipe 方式吃 PNG 时，一旦 stderr 被合并进 stdout（或桥走了 Passthrough 转发真 screencap），
# 拿到的字节流就不是合法 PNG → BAAH 报「截图文件损坏」并重试。这里在 adb 外面套一层：
# 从 PNG 魔数开始截取、丢掉前面任何警告文本；带 `-p <路径>` 就把干净 PNG 落盘。
ADB_WRAPPER_PATH = os.path.join(AOS_ROOT, "adb-wrap")

ADB_WRAPPER_SRC = '''#!/usr/bin/env python3
"""DSH: adb 包装器 —— ①抹平假 adbd 的 screencap 差异；②给 BAAH 的 `am start` 补 --display；
③**真 adb 不存在时，自己顶上当 adb 客户端**（2026-09-28 新增）。

用户实测过的现象：点「推短篇剧情」这种单任务，游戏先在虚拟屏上打开，随后**又跳到手机
主屏开了一遍**。原因是 BAAH 自己的 Loginin/任务里那句 `am start -n <游戏>` 不带 --display，
Android 就按默认屏（主屏）来；本 App 的 runner 只负责"预启动到虚拟屏"，管不到 BAAH 内部
那句。这里在 adb 外面套一层统一补上 --display <我们的虚拟屏>（id 从 /opt/baah-aos/vd_id_app 读，
和 runner 用的是同一个来源），其它命令原样放行。

为什么要有第 ③ 条：APK 里的 rootfs.tar.xz **根本没有 adb 二进制**（2026-09-28 用
`find / -name adb` 查空），而 BAAH 全靠外部 adb 干活 —— 于是 `/usr/bin/adb` 这条 execv
必然是 FileNotFoundError。与其往 rootfs 里塞一个几 MB 的 aarch64 二进制（还得挑 glibc 版本），
不如用 BAAH 真正用到的那几条命令反向实现一个**迷你 adb 客户端**：直连本机桥（假 adbd）
23300，说 modern adb 协议（CNXN / OPEN / OKAY / WRTE / CLSE），只支持
`connect / devices / shell / exec-out`。
"""

import os
import re
import socket
import struct
import subprocess
import sys

REAL = os.environ.get("BAAH_AOS_REAL_ADB", "/usr/bin/adb")
BRIDGE_HOST = os.environ.get("BAAH_AOS_BRIDGE_HOST", "127.0.0.1")
BRIDGE_PORT = int(os.environ.get("BAAH_AOS_BRIDGE_PORT", "23300"))
PNG_MAGIC = b"\\x89PNG\\r\\n\\x1a\\n"
VD_ID_PATH = "/opt/baah-aos/vd_id_app"
TAP_LOG = "/opt/baah-aos/log/taps.log"
# BAAH（打过 DSH 补丁后的 click_on_screen）打的是 `input -d <虚拟屏> tap x y`，
# 而这条命令**有时是拆成多个 argv、有时是一整个字符串**，所以这里在拼起来的整行上正则找。
TAP_RE = re.compile(r"input\\s+(?:-d\\s+\\d+\\s+)?(tap|swipe)\\s+(-?\\d+)\\s+(-?\\d+)")


def note_taps(argv):
    """把 tap/swipe 记一行 —— 给 App 的「显示触摸预览」用。"""
    m = TAP_RE.search(" ".join(argv))
    if not m:
        return
    try:
        os.makedirs(os.path.dirname(TAP_LOG), exist_ok=True)
        with open(TAP_LOG, "a") as fp:
            fp.write("%s %s %s\\n" % (m.group(1), m.group(2), m.group(3)))
    except OSError:
        pass


def vd_id() -> int:
    try:
        with open(VD_ID_PATH) as fp:
            return int((fp.read() or "-1").strip() or "-1")
    except Exception:
        return -1


def fix_display(argv):
    """虚拟屏启动统一使用 MaaMeow 的 NEW_TASK / EXCLUDE_FROM_RECENTS 标记。"""
    # Upstream open_app calls monkey after am start. Monkey's cleanup can
    # unfreeze the system rotation lock. Resolve the launcher with am instead.
    if "monkey" in argv:
        index = argv.index("monkey")
        tail = argv[index + 1:]
        if len(tail) == 3 and tail[0] == "-p" and tail[2] == "1":
            try:
                with open("/opt/baah-aos/game_activity", encoding="utf-8") as fp:
                    activity = fp.read().strip()
            except OSError:
                activity = ""
            if activity.startswith(tail[1] + "/"):
                launch = ["-n", activity]
            else:
                launch = ["-a", "android.intent.action.MAIN", "-c",
                          "android.intent.category.LAUNCHER", "-p", tail[1]]
            argv = argv[:index] + ["am", "start"] + launch
    try:
        i = argv.index("am")
    except ValueError:
        return argv
    if i + 1 >= len(argv) or argv[i + 1] != "start":
        return argv
    vd = vd_id()
    if vd <= 0:
        return argv
    options = []
    if "--display" not in argv:
        options += ["--display", str(vd)]
    # am start 已自动带 NEW_TASK；Intent CLI 没有 --activity-new-task 选项。
    for flag in ("--activity-exclude-from-recents",):
        if flag not in argv:
            options.append(flag)
    sys.stderr.write("adb-wrap: am start -> hidden virtual task display %d\\n" % vd)
    return argv[: i + 2] + options + argv[i + 2 :]


# ── 最小 adb 客户端（modern adb 协议）────────────────────────────────────────
A_CNXN = 0x4E584E43
A_AUTH = 0x48545541
A_OPEN = 0x4E45504F
A_OKAY = 0x59414B4F
A_CLSE = 0x45534C43
A_WRTE = 0x45545257


def _checksum(data: bytes) -> int:
    total = 0
    for byte in data:
        total = (total + byte) & 0xFFFFFFFF
    return total


def _packet(cmd: int, arg0: int, arg1: int, payload: bytes = b"") -> bytes:
    payload = payload or b""
    header = struct.pack("<6I", cmd, arg0, arg1, len(payload), _checksum(payload),
                         cmd ^ 0xFFFFFFFF)
    return header + payload


class MiniAdb:
    """够 BAAH 用的最小 adb 客户端：握手 → OPEN("shell:…") → 收 WRTE 直到 CLSE。"""

    def __init__(self, host=None, port=None, timeout=30.0):
        self.host = host or BRIDGE_HOST
        self.port = int(port or BRIDGE_PORT)
        self.timeout = timeout
        self.sock = None
        self.next_id = 1
        self.connected = False

    # -- 底层 --
    def _recv_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("桥把连接关了")
            buf.extend(chunk)
        return bytes(buf)

    def _read_msg(self):
        header = self._recv_exact(24)
        cmd, arg0, arg1, length, _crc, _magic = struct.unpack("<6I", header)
        data = self._recv_exact(length) if length else b""
        return cmd, arg0, arg1, data

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.settimeout(self.timeout)
        self.sock.sendall(_packet(A_CNXN, 0x01000001, 65536, b"host::\\x00"))
        while True:
            cmd, _a0, _a1, data = self._read_msg()
            if cmd in (A_CNXN, A_OKAY):
                break
            if cmd == A_WRTE:            # 极少数实现会先塞点 banner
                continue
            if cmd == A_CLSE:
                raise RuntimeError("桥上不支持这条连接（CLSE）")
        self.connected = True
        return True

    def service(self, name: str) -> bytes:
        """跑一个 service（`shell:…` / `exec:…`），返回它吐出来的字节。"""
        if not self.connected:
            self.connect()
        local = self.next_id
        self.next_id += 1
        self.sock.sendall(_packet(A_OPEN, local, 0, name.encode("utf-8") + b"\\x00"))
        out = bytearray()
        while True:
            cmd, arg0, arg1, data = self._read_msg()
            if cmd == A_WRTE:
                out += data
                try:
                    self.sock.sendall(_packet(A_OKAY, local, arg0 or arg1))
                except OSError:
                    pass
            elif cmd == A_CLSE:
                break
            elif cmd == A_OKAY:
                continue
        result = bytes(out)
        if result.startswith(b"BAAH bridge error: "):
            raise RuntimeError(result.decode("utf-8", errors="replace").strip())
        return result

    def close(self):
        try:
            if self.sock:
                self.sock.close()
        except OSError:
            pass


# ── CLI ─────────────────────────────────────────────────────────────────────
GLOBAL_FLAGS_WITH_VALUE = ("-s", "-H", "-P", "-L", "-t")


def split_argv(argv):
    """剥掉全局 flag，返回 (serial, 其余 argv)。"""
    serial = None
    i = 0
    while i < len(argv):
        tok = argv[i]
        if tok in GLOBAL_FLAGS_WITH_VALUE and i + 1 < len(argv):
            if tok == "-s":
                serial = argv[i + 1]
            i += 2
            continue
        if tok.startswith("-"):
            i += 1
            continue
        break
    return serial, argv[i:]


def _probe_bridge(timeout=5.0) -> bool:
    try:
        with socket.create_connection((BRIDGE_HOST, BRIDGE_PORT), timeout=timeout):
            return True
    except OSError:
        return False


def _maybe_clean_screencap(argv, data: bytes) -> bytes:
    """把 PNG 魔数之前的杂音去掉；带 `-p <路径>` 就落盘、stdout 什么都不吐。"""
    if "screencap" not in argv:
        return data
    idx = data.find(PNG_MAGIC)
    if idx < 0:
        return data
    png = data[idx:]
    if "-p" in argv:
        j = argv.index("-p")
        if j + 1 < len(argv) and not argv[j + 1].startswith("-"):
            try:
                with open(argv[j + 1], "wb") as fp:
                    fp.write(png)
            except OSError as exc:
                sys.stderr.write("adb-wrap: write %s failed: %r\\n" % (argv[j + 1], exc))
                return b""
            return b""
    return png


def local_main(argv) -> int:
    """REAL 不存在时：自己当 adb 客户端（只实现 BAAH 用到的几条）。"""
    serial, rest = split_argv(list(argv))
    if not rest:
        sys.stderr.write("adb-wrap: 缺子命令\\n")
        return 1
    cmd, args = rest[0], rest[1:]
    target = "%s:%d" % (BRIDGE_HOST, BRIDGE_PORT)

    if cmd == "connect":
        ok = _probe_bridge()
        sys.stdout.write(("connected to %s\\n" if ok else "failed to connect to %s\\n")
                         % (args[0] if args else target))
        return 0 if ok else 1
    if cmd in ("disconnect", "reconnect", "kill-server", "start-server", "root",
               "unroot", "wait-for-device", "usb", "tcpip"):
        return 0
    if cmd == "devices":
        sys.stdout.write("List of devices attached\\n%s\\tdevice\\n\\n" % (serial or target))
        return 0
    if cmd == "version":
        sys.stdout.write("Android Debug Bridge version 1.0.41 (baah-aos mini)\\n")
        return 0
    if cmd in ("shell", "exec-out", "exec-in", "exec"):
        line = " ".join(args)
        if not line:
            return 0
        service = ("exec:" if cmd.startswith("exec") else "shell:") + line
        client = MiniAdb()
        try:
            data = client.service(service)
        except Exception as exc:            # noqa: BLE001  桥没起/协议不对都要说清楚
            sys.stderr.write("adb-wrap: %s 失败：%r\\n" % (service, exc))
            return 1
        finally:
            client.close()
        out = _maybe_clean_screencap(args, data)
        if out:
            sys.stdout.buffer.write(out)
            sys.stdout.buffer.flush()
        return 0

    sys.stderr.write("adb-wrap: 迷你客户端暂不支持 `%s`（只实现 BAAH 用到的 "
                     "connect/devices/shell/exec-out）\\n" % cmd)
    return 1


def main() -> int:
    argv = fix_display(sys.argv[1:])
    note_taps(argv)
    real_ok = os.path.isfile(REAL) and os.access(REAL, os.X_OK)
    if not real_ok:
        return local_main(argv)
    if "screencap" not in argv:
        os.execv(REAL, [REAL] + argv)
        return 0
    proc = subprocess.run([REAL] + argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out = _maybe_clean_screencap(argv, proc.stdout or b"")
    if out:
        sys.stdout.buffer.write(out)
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
'''


def ensure_adb_wrapper() -> str:
    """
    把 adb 包装器写出来（幂等），返回它的路径；失败就退回真 adb。

    只在内容变化时重写，避免每次都动 mtime。
    """
    try:
        need = True
        if os.path.isfile(ADB_WRAPPER_PATH):
            with open(ADB_WRAPPER_PATH, "r", encoding="utf-8") as fp:
                need = fp.read() != ADB_WRAPPER_SRC
        if need:
            with open(ADB_WRAPPER_PATH, "w", encoding="utf-8") as fp:
                fp.write(ADB_WRAPPER_SRC)
            log(f"adb 包装器已写出：{ADB_WRAPPER_PATH}")
        os.chmod(ADB_WRAPPER_PATH, 0o755)
        return ADB_WRAPPER_PATH
    except OSError as exc:
        log(f"写 adb 包装器失败（继续用真 adb）：{exc!r}")
        return ADB_BINARY


def apply_bridge_to_config(config_name: str) -> bool:
    """
    把 BAAH 配置里的设备指向桥（ADB_PATH / TARGET_IP_PATH / TARGET_PORT …）。

    ⚠️ 必须**每次 start 都执行**，不能只在"首次生成配置"时执行：桥端口是 App 定的
    （AppConstants.BRIDGE_PORT），曾经因为端口从 22300 换到 23300 而旧配置还指着 22300，
    runner 于是跑去问**另一个 App（ALAS-AOS）的桥**要虚拟屏列表 → "没找到 1280x720 的虚拟屏"
    → 跳过预启动 → 游戏根本不在屏上。配置是适配层的一部分，必须跟着 App 走。
    """
    target = os.path.join(CONFIG_DIR, f"{config_name}.json")
    if not os.path.isfile(target):
        return False
    try:
        with open(target, "r", encoding="utf-8") as fp:
            cfg = json.load(fp)
        wanted = {
            "ADB_PATH": ensure_adb_wrapper(),
            "TARGET_IP_PATH": BRIDGE_HOST,
            "TARGET_PORT": BRIDGE_PORT,
            # 手机上没有"模拟器"可启动；把游戏拉到虚拟屏是 App 的事
            "TARGET_EMULATOR_PATH": "",
            # pipe 截图：BAAH 直接吃桥喂的 PNG 字节流，不落盘
            "SCREENSHOT_METHOD": "pipe",
            # 端口占用检查走的是 Windows 的 netstat，Linux 上没意义
            "KILL_PORT_IF_EXIST": False,
            "ADB_DIRECT_USE_SERIAL_NUMBER": False,
        }
        changed = [k for k, v in wanted.items() if cfg.get(k) != v]
        cfg.update(wanted)
        if changed:
            with open(target, "w", encoding="utf-8") as fp:
                json.dump(cfg, fp, ensure_ascii=False, indent=4)
            log(f"配置已指向桥: {ADB_BINARY} -> {BRIDGE_HOST}:{BRIDGE_PORT}（修正 {','.join(changed)}）")
        return True
    except (OSError, ValueError) as exc:
        log(f"改写配置里的桥字段失败（保留原样）：{exc!r}")
        return False


def seed_config_if_missing(config_name: str) -> bool:
    """
    首次运行时从种子生成一份 BAAH 配置，并**把设备指向桥**。

    这一段必须在 wrapper（而不是 runner）里：`/start` 要先验证配置存在才肯拉起 runner，
    而 runner 只有被拉起来才有机会生成配置——写反了就是鸡生蛋死锁。
    （这个 bug 是被 spike/s2/test_wrapper.py 抓出来的。）

    改的都是 BAAH 配置里的普通字段，不算改 BAAH 代码。
    """
    target = os.path.join(CONFIG_DIR, f"{config_name}.json")
    if not os.path.isfile(target):
        for candidate in (
            os.path.join(SEED_DIR, f"{config_name}.json"),
            os.path.join(SEED_DIR, "default.json"),
            os.path.join(CONFIG_DIR, "example.json"),
        ):
            if os.path.isfile(candidate):
                seed = candidate
                break
        else:
            return False

        os.makedirs(CONFIG_DIR, exist_ok=True)
        shutil.copyfile(seed, target)
        log(f"从种子生成配置 {target} <- {seed}")

    # 已存在也要对齐（见 apply_bridge_to_config 的说明）
    apply_bridge_to_config(config_name)
    return True


# ── HTTP 服务 ────────────────────────────────────────────────────────────────
class Handler(BaseHTTPRequestHandler):
    supervisor: RunnerSupervisor  # 由 create_server 注入

    server_version = "BAAH-AOS-wrapper/0.1"

    def log_message(self, fmt: str, *args) -> None:  # 默认会写到 stderr，太吵
        return

    # -- 工具 ---------------------------------------------------------------
    def _send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _query(self) -> dict:
        return {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}

    # -- 路由 ---------------------------------------------------------------
    def do_GET(self) -> None:  # noqa: N802
        route = urlparse(self.path).path
        if route == "/healthz":
            self._send_json({"ok": True})
        elif route == "/status":
            # Runner stdout is always captured, even when BAAH file logging is disabled.
            path, lines = tail_log(TAIL_LINES_FOR_STATUS, RUNNER_LOG)
            payload = self.supervisor.status()
            payload.update({"log_file": path, "log_lines": lines})
            self._send_json(payload)
        elif route == "/configs":
            self._send_json({"configs": list_configs()})
        elif route == "/logs":
            try:
                lines = int(self._query().get("tail", "200"))
            except ValueError:
                lines = 200
            lines = max(1, min(lines, MAX_TAIL_LINES))
            path, tail = tail_log(lines)
            self._send_json({"log_file": path, "lines": tail})
        else:
            self._send_json({"error": "not found", "path": route}, status=404)

    def do_POST(self) -> None:  # noqa: N802
        route = urlparse(self.path).path
        query = self._query()

        if route == "/start":
            config = query.get("config", "")
            if not CONFIG_NAME_RE.match(config):
                self._send_json({"error": "配置名非法（只允许字母数字下划线连字符）"}, status=400)
                return
            # 首次运行：先从种子补一份配置（并把设备指向桥），再拉起 runner
            if config not in list_configs() and not seed_config_if_missing(config):
                self._send_json({"error": f"配置不存在且无可用种子: {config}"}, status=404)
                return
            # 已存在的配置也要**每次**对齐：桥端口、ADB 包装器都是 App 侧决定的，
            # 之前只在 seed_config_if_missing（配置不存在时）里对齐 → 现有配置永远指着旧值
            # （实测就吃过这个亏：ADB_PATH 一直是 /usr/bin/adb，新加的包装器根本没被用上）。
            apply_bridge_to_config(config)
            # task 可选：给了就是"只跑这一个任务"（「小工具」页的快速调用任务按钮）
            task = query.get("task") or None
            self.supervisor.start(config, task)
            self._send_json({"ok": True, "config": config, "task": task,
                             "runner_generation": self.supervisor.status()["runner_generation"]})

        elif route == "/stop":
            self.supervisor.stop()
            self._send_json({"ok": True})

        elif route == "/shell":
            # 「快捷选项」用：关闭游戏 / 静音 / 熄屏 —— 在 guest 里跑一条 shell，
            # 由 guest 的 adb 客户端连到桥，最终以 shell 身份执行。
            cmd = query.get("cmd", "")
            out, code = run_shell(cmd)
            self._send_json({"ok": code == 0, "code": code, "out": out})

        else:
            self._send_json({"error": "not found", "path": route}, status=404)


def run_shell(cmd: str) -> tuple[str, int]:
    """在 guest 里执行一条 shell（走桥 → 特权进程）。返回 (输出, 退出码)。"""
    if not cmd or len(cmd) > 500:
        return "命令为空或过长", 1
    target = f"{BRIDGE_HOST}:{BRIDGE_PORT}"
    client = adb_client()
    try:
        subprocess.run([client, "connect", target],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        proc = subprocess.run([client, "-s", target, "shell", cmd],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=25)
        out = (proc.stdout or b"").decode("utf-8", "replace").strip()
        log(f"shell[{cmd}] -> code={proc.returncode} out={out[:200]}")
        return out, proc.returncode
    except subprocess.TimeoutExpired:
        log(f"shell[{cmd}] 超时")
        return "命令超时", 1
    except OSError as exc:
        log(f"shell[{cmd}] 失败：{exc!r}")
        return f"执行失败: {exc!r}", 1


TAIL_LINES_FOR_STATUS = 200
MAX_TAIL_LINES = 2000


def create_server(supervisor: RunnerSupervisor) -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {"supervisor": supervisor})
    return ThreadingHTTPServer((HOST, PORT), handler)


# ── 单实例锁与生命周期 ───────────────────────────────────────────────────────
def kill_stale_wrappers() -> int:
    """
    收掉**上一个 App 会话留下的 wrapper**。

    为什么必须做：App 每次重装/被杀，proot 里的 wrapper 有可能没跟着退（stdin 没 EOF 就
    会赖着活），于是它继续占着 flock 锁和 23400 端口。新会话的 wrapper 一看锁被占就
    `exit(2)`，App 那边表现为「运行环境已启动」但 runner 永远起不来 —— 这个坑实测踩过。

    只杀真正跑 `wrapper.py` 的 python3 进程（同 uid），并且排除：
      * 自己；
      * 自己的父进程：proot 的 tracer 命令行里也带 wrapper.py 字样，杀了它=自杀。
    """
    me = os.getpid()
    parent = os.getppid()
    killed = 0
    try:
        pids = [p for p in os.listdir("/proc") if p.isdigit()]
    except OSError:
        return 0
    for pid_s in pids:
        pid = int(pid_s)
        if pid in (me, parent):
            continue
        try:
            with open("/proc/%d/cmdline" % pid, "rb") as fp:
                cmd = fp.read().replace(b"\x00", b" ").decode("utf-8", "replace")
        except OSError:
            continue
        if "wrapper.py" not in cmd or "libproot" in cmd:
            continue
        try:
            if os.stat("/proc/%d" % pid).st_uid != os.getuid():
                continue
        except OSError:
            continue
        log("发现残留 wrapper（pid=%d），先收掉：%s" % (pid, cmd[:120]))
        try:
            os.kill(pid, signal.SIGKILL)
            killed += 1
        except OSError as exc:
            log("杀残留 wrapper 失败：%r" % (exc,))
    if killed:
        time.sleep(0.6)
    return killed


def acquire_single_instance_lock():
    """
    flock 单实例锁。抢不到时先清掉残留 wrapper 再抢；仍然抢不到才 exit(2)。

    Windows 上没有 fcntl：开发机测试时降级为"不锁"，并明确写日志，
    免得让人误以为锁生效了。
    """
    try:
        import fcntl  # noqa: PLC0415
    except ImportError:
        log("没有 fcntl（非 Unix），单实例锁降级为关闭")
        return None

    os.makedirs(os.path.dirname(LOCK_PATH), exist_ok=True)
    kill_stale_wrappers()
    fp = None
    for attempt in range(3):
        if fp is not None:
            fp.close()
        fp = open(LOCK_PATH, "w")
        try:
            fcntl.flock(fp, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return fp
        except OSError:
            if attempt < 2:
                log("锁被占，清理残留 wrapper 后重试（第 %d 次）" % (attempt + 1))
                kill_stale_wrappers()
                time.sleep(0.8)
    log("已有另一个 wrapper 在跑（锁被占），退出")
    sys.exit(2)


def watch_parent_stdin(supervisor: RunnerSupervisor, server: ThreadingHTTPServer) -> None:
    """
    父进程（App 起的 proot）一死，我们的 stdin 就是 EOF。
    这时必须把 runner 一起带走——否则 shell uid 的残留进程 `am force-stop` 是收不掉的
    （ALAS-AOS 的教训）。
    """

    def worker() -> None:
        try:
            while True:
                chunk = sys.stdin.buffer.read(1)
                if not chunk:
                    break
        except Exception:  # noqa: BLE001
            pass
        log("stdin EOF：父进程已消失，清理并退出")
        supervisor.shutdown()
        try:
            server.shutdown()
        except Exception:  # noqa: BLE001
            pass

    threading.Thread(target=worker, name="stdin-watchdog", daemon=True).start()


# ── BAAH 自带的 nicegui 控制台（App「控制台」标签内嵌的 WebView 就是它）───────────
GUI_SCRIPT = os.path.join(BAAH_ROOT, "jsoneditor.py")
GUI_PORT = int(os.environ.get("BAAH_AOS_GUI_PORT", "8000"))
GUI_LOG = os.path.join(os.path.dirname(RUNNER_LOG), "gui.log")
_gui_process = None


def _port_listening(port: int, host: str = "127.0.0.1") -> bool:
    import socket

    try:
        with socket.create_connection((host, port), timeout=0.5):
            return True
    except OSError:
        return False


GUI_CERT = os.path.join(AOS_ROOT, "gui.crt")
GUI_KEY = os.path.join(AOS_ROOT, "gui.key")
GUI_SSL_MARKER = "# DSH: 控制台走 HTTPS"


def ensure_gui_cert() -> bool:
    """
    自签一张证书。App 的 targetSdk=36 默认禁止明文 HTTP，WebView / HttpURLConnection 连
    http://127.0.0.1:8000 都会被 NetworkSecurityPolicy 拦掉（表现为控制台一直"正在等待"），
    所以控制台直接上 HTTPS；App 侧对这张本地自签证书放行。
    """
    if os.path.isfile(GUI_CERT) and os.path.isfile(GUI_KEY):
        return True
    try:
        subprocess.run(
            ["/usr/bin/openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
             "-keyout", GUI_KEY, "-out", GUI_CERT, "-days", "3650",
             "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        log(f"已生成 GUI 自签证书 {GUI_CERT}")
        return True
    except Exception as exc:  # noqa: BLE001
        log(f"生成 GUI 证书失败：{exc!r}")
        return False


def ensure_gui_ssl(config_name: str = "default") -> None:
    """给 BAAH 的 jsoneditor.py 打幂等补丁：ui.run(...) 带上 ssl_certfile/ssl_keyfile。"""
    if not os.path.isfile(GUI_SCRIPT):
        return
    try:
        with open(GUI_SCRIPT, "r", encoding="utf-8") as fp:
            src = fp.read()
        if GUI_SSL_MARKER in src:
            return
        old = "reload=False, host=args.host"
        if src.count(old) != 1:
            log("GUI SSL 补丁跳过：源码结构与预期不符（上游改版了？）")
            return
        new = (
            "reload=False, " + GUI_SSL_MARKER + "\n       "
            'ssl_certfile=r"%s", ssl_keyfile=r"%s", host=args.host' % (GUI_CERT, GUI_KEY)
        )
        src = src.replace(old, new, 1)
        compile(src, GUI_SCRIPT, "exec")
        with open(GUI_SCRIPT, "w", encoding="utf-8") as fp:
            fp.write(src)
        log("GUI SSL 补丁已应用（控制台改用 HTTPS）")
    except Exception as exc:  # noqa: BLE001
        log(f"GUI SSL 补丁失败：{exc!r}")


def ensure_gui() -> None:
    """
    把 BAAH 自带的家居界面（nicegui）拉起来，App 的控制台标签内嵌它。

    幂等：端口已在监听就什么都不做；进程没了会自动重拉。
    两个坑：① `--port` 必须显式给 —— BAAH 默认用的是 native.find_open_port()（随机端口）；
    ② 必须由**本 wrapper 这个长命 proot** 来拉：proot 退出时会杀掉它跟踪的进程，
    一次性 `guest.sh` 起的 GUI 会立刻消失（实测）。
    """
    global _gui_process
    if _gui_process is not None and _gui_process.poll() is None:
        return
    if not os.path.isfile(GUI_SCRIPT):
        log(f"找不到 GUI 脚本: {GUI_SCRIPT}")
        return
    if not ensure_gui_cert():
        return
    ensure_gui_ssl()
    if _port_listening(GUI_PORT):
        return
    try:
        out = open(GUI_LOG, "ab", buffering=0)
        _gui_process = subprocess.Popen(
            [sys.executable, GUI_SCRIPT,
             "--host", "127.0.0.1", "--port", str(GUI_PORT), "--no-show"],
            cwd=BAAH_ROOT,
            stdin=subprocess.DEVNULL,
            stdout=out,
            stderr=subprocess.STDOUT,
        )
        log(f"GUI 已拉起 pid={_gui_process.pid} -> https://127.0.0.1:{GUI_PORT}")
    except Exception as exc:  # noqa: BLE001
        log(f"GUI 拉起失败：{exc!r}")


def gui_worker() -> None:
    while True:
        try:
            ensure_gui()
        except Exception as exc:  # noqa: BLE001
            log(f"GUI 看门人异常：{exc!r}")
        time.sleep(5.0)


def main() -> int:
    if not os.path.isfile(RUNNER_PATH):
        log(f"找不到 runner.py: {RUNNER_PATH}")
        return 1

    lock = acquire_single_instance_lock()

    supervisor = RunnerSupervisor()
    server = create_server(supervisor)

    # The GUI imports psutil before any task runner has started.
    from runner import install_psutil_shim
    install_psutil_shim()

    # Android uses the native BAAH editor; only start NiceGUI when explicitly requested.
    if os.environ.get("BAAH_AOS_ENABLE_GUI", "0") == "1":
        threading.Thread(target=gui_worker, name="gui-keeper", daemon=True).start()

    def cleanup(*_args) -> None:
        log("收到退出信号，清理中")
        supervisor.shutdown()
        sys.exit(0)

    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            signal.signal(sig, cleanup)
        except (ValueError, OSError):
            pass

    watch_parent_stdin(supervisor, server)

    log(f"wrapper 监听 {HOST}:{PORT}｜AOS_ROOT={AOS_ROOT}｜BAAH_ROOT={BAAH_ROOT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        cleanup()
    finally:
        supervisor.shutdown()
        if lock is not None:
            lock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
