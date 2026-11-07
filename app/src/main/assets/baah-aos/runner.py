#!/usr/bin/env python3
"""
BAAH-AOS · runner —— 在 guest 里把 BAAH 调度器跑起来
====================================================

职责：

  1. **预启动游戏到虚拟屏**（见 `prelaunch_on_virtual_display`）—— 这是本机
     （BaahAosVirtualDisplay 方案）能跑通的前提；
  2. 把 **上游 BAAH 原样** 跑起来（`run_baah_script`），cwd 指到 `/opt/baah`；
  3. 首次运行时用我们的种子配置补一份 BAAH 配置——**把 adb 指向桥**
     （`ADB_PATH=/usr/bin/adb`、`TARGET_IP_PATH=127.0.0.1`、`TARGET_PORT=23300`）；
  4. 把 `input()` 换掉——`main.py` 的异常分支里有个阻塞的 `input("回车退出")`；
  5. 上游 BAAH 自举：rootfs 里没有 `/opt/baah` 时，从适配层带的副本拷一份。

**不改 BAAH 一行代码**：上面全是"环境层"动作。

本文件属于我方适配层（`/opt/baah-aos`），与上游 `/opt/baah` 严格分开。
"""

from __future__ import annotations

import builtins
import json
import os
import re
import shutil
import subprocess
import sys
import time

AOS_ROOT = os.environ.get("BAAH_AOS_ROOT", "/opt/baah-aos")
BAAH_ROOT = os.environ.get("BAAH_AOS_BAAH_ROOT", "/opt/baah")
CONFIG_DIR = os.path.join(BAAH_ROOT, "BAAH_CONFIGS")
# 补 Python 依赖用的镜像：**GitHub 在手机上连不上，但国内 PyPI 镜像通**
# （vivo 实测：清华 48ms / 阿里 21ms，2026-09-28），所以缺包时走它而不是塞进 APK。
PIP_MIRROR = os.environ.get("BAAH_AOS_PIP_MIRROR", "https://pypi.tuna.tsinghua.edu.cn/simple")
SEED_DIR = os.path.join(AOS_ROOT, "seeds")
# 随适配层一起刷进来的上游源码副本（可能不存在）
BUNDLED_BAAH = os.path.join(AOS_ROOT, "baah")
# 打包进 APK 的 BAAH 源码包（约 1.3MB）：`/opt/baah` 缺失时由 install_upstream_from_pkg() 解压安装
UPSTREAM_PKG = os.path.join(AOS_ROOT, "baah-src.tgz")

# 当前虚拟屏 id 落盘位置（给下面给 adb_utils 打的 input 补丁读）
VD_ID_FILE = os.path.join(AOS_ROOT, "vd_id")

# App（Android 侧）把「它自己那块屏」的 displayId 写在这里（-1 = 还没建屏）。
#
# ⚠️ 为什么必须由 App 给、而不是 runner 自己去 dumpsys 里扫：
#   ALAS-AOS 的 MaaFwVirtualDisplay **同样是 1280x720**，靠分辨率找「第一块 1280x720
#   的屏」会拿到**别人的屏**——于是 `am start --display <别人的屏>` 把游戏搬到别人屏上、
#   甚至把 input 打进别人的游戏里（2026-09-26 在 dumpsys 里实测确认这个隐患）。
APP_VD_ID_FILE = os.path.join(AOS_ROOT, "vd_id_app")

# 反过来：runner 把「游戏确实已经在这块虚拟屏上打开了」告诉 App。
# 预览卡右上角的「运行中」和帧率显示都读它 —— 用户要求「虚拟屏要在游戏打开的时候才显示
# 已运行，FPS 也只在进入游戏界面才显示」，而 App 内部的 HangarState 在屏刚建好时就已经
# 是 running 了，判不出来。
GAME_ON_FILE = os.path.join(AOS_ROOT, "game_on")
GAME_ACTIVITY_FILE = os.path.join(AOS_ROOT, "game_activity")

# 等 App 把虚拟屏建出来：App 的「开始挂机」是异步的，runner 常常先被拉起来
VD_WAIT_SEC = 90.0

# 交给 BAAH 之前先确认虚拟屏真的出画：最长等待秒数 / 认为"出画"的最小 PNG 字节数
FRAME_WAIT_SEC = 90.0
FRAME_MIN_BYTES = 20000

# 上游 adb_utils.py 里点击/滑动两条命令要改造成「定向虚拟屏」
ADB_UTILS_PATH = os.path.join(BAAH_ROOT, "modules", "utils", "adb_utils.py")
INPUT_PATCH_MARKER = "[DSH PATCH 2026-09-25b] input 定向虚拟屏"

INPUT_HELPER = '''
def _dsh_vd_args():
    """''' + INPUT_PATCH_MARKER + ''' —— 返回把 input 定向到虚拟屏的 -d 参数。

    不指定 -d 时 Android 的 input 默认打 display 0（主屏），而游戏跑在
    BaahAosVirtualDisplay 上，结果就是「点击完全没反应」。读不到 id 就返回空列表，
    退回原行为，对其他机型无影响。
    """
    try:
        with open("/opt/baah-aos/vd_id") as fp:
            vd = fp.read().strip()
    except OSError:
        vd = ""
    return ["-d", vd] if vd.isdigit() else []


def click_on_screen(x, y):'''

INPUT_OLD_CLICK = '    subprocess_run([get_config_adb_path(), "-s", getNewestSeialNumber(), "shell", "input", "tap", str(int(x)), str(int(y))])'
INPUT_NEW_CLICK = '    subprocess_run([get_config_adb_path(), "-s", getNewestSeialNumber(), "shell", "input"] + _dsh_vd_args() + ["tap", str(int(x)), str(int(y))])'
INPUT_OLD_SWIPE = '    subprocess_run([get_config_adb_path(), "-s", getNewestSeialNumber(), "shell", "input", "swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), str(int(ms))])'
INPUT_NEW_SWIPE = '    subprocess_run([get_config_adb_path(), "-s", getNewestSeialNumber(), "shell", "input"] + _dsh_vd_args() + ["swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), str(int(ms))])'

# 虚拟屏在 dumpsys 里的特征：分辨率固定 1280x720（BAAH 硬校验），且都是 240dpi
VD_SIZE_RE = re.compile(r"init=(\d+)x(\d+)\s", re.IGNORECASE)
VD_ID_RE = re.compile(r"mDisplayId=(\d+)")


def log(message: str) -> None:
    print(f"[runner] {message}", flush=True)


# ── 上游 BAAH 自举 ────────────────────────────────────────────────────────────
def upstream_present() -> bool:
    return os.path.isfile(os.path.join(BAAH_ROOT, "main.py"))


def install_upstream_from_pkg() -> bool:
    """从适配层里的**源码包** `baah-src.tgz` 解压安装 BAAH 本体。

    <p>用户要求（换手机时暴露）：「第一次启动的时候要检测一下目录里面有没有 baah，
    没有的话自动安装」。APK 不背整个 BAAH 目录，只带一个约 1.3MB 的源码包，
    随适配层铺到 {@code AOS_ROOT}，这里解压到 {@code /opt/baah}。
    排除项（DATA / BAAH_CONFIGS / __pycache__）在打包时就已剔除，所以不会覆盖用户数据。
    """
    if not os.path.isfile(UPSTREAM_PKG):
        return False
    log(f"检测到 BAAH 本体缺失 → 自动安装: {UPSTREAM_PKG} -> {BAAH_ROOT}")
    try:
        import tarfile
        if os.path.exists(BAAH_ROOT):
            shutil.rmtree(BAAH_ROOT)
        os.makedirs(BAAH_ROOT, exist_ok=True)
        with tarfile.open(UPSTREAM_PKG, "r:gz") as tf:
            try:
                tf.extractall(BAAH_ROOT, filter="data")   # py3.12+ 推荐；老版本会 TypeError
            except TypeError:
                tf.extractall(BAAH_ROOT)
        os.makedirs(CONFIG_DIR, exist_ok=True)            # 配置目录也一并备好（导入/导出要用）
    except Exception as exc:
        log(f"BAAH 本体自动安装失败: {exc!r}")
        return False
    ok = upstream_present()
    log("BAAH 本体自动安装完成 ✓" if ok else "BAAH 本体安装后仍缺 main.py ✗")
    return ok


def bootstrap_upstream() -> bool:
    """把 BAAH 本体自举到 BAAH_ROOT（已存在则 no-op）。三级来源：
    ① 已在 → 直接用；② 适配层带的目录 `baah/` → 拷贝；③ 适配层带的源码包 `baah-src.tgz`
    → 解压安装（**换手机后第一次启动走这条**）。
    """
    if upstream_present():
        log(f"上游 BAAH 已存在: {BAAH_ROOT}")
        return True
    if not os.path.isfile(os.path.join(BUNDLED_BAAH, "main.py")):
        if install_upstream_from_pkg():
            return True
        log(
            f"上游 BAAH 不存在: {BAAH_ROOT}，适配层里也没有源码副本 "
            f"({BUNDLED_BAAH}/main.py) 或源码包（{UPSTREAM_PKG}）"
        )
        log("→ 回 App 首页把「正在准备 BAAH 运行环境」跑完（首次安装会从 GitHub 下载本体）")
        return False
    log(f"自举上游 BAAH: {BUNDLED_BAAH} -> {BAAH_ROOT}")
    try:
        if os.path.exists(BAAH_ROOT):
            shutil.rmtree(BAAH_ROOT)
        shutil.copytree(BUNDLED_BAAH, BAAH_ROOT, symlinks=False)
    except OSError as exc:
        log(f"自举失败: {exc!r}")
        return False
    return upstream_present()


# ── 预启动游戏到虚拟屏 ────────────────────────────────────────────────────────
def _load_user_config(config_name: str) -> dict:
    try:
        with open(os.path.join(CONFIG_DIR, config_name), "r", encoding="utf-8") as fp:
            return json.load(fp)
    except (OSError, ValueError) as exc:
        log(f"读取配置失败（跳过预启动）: {exc!r}")
        return {}


def _adb_prefix(cfg: dict) -> list:
    adb = cfg.get("ADB_PATH") or "/usr/bin/adb"
    ip = cfg.get("TARGET_IP_PATH") or "127.0.0.1"
    port = cfg.get("TARGET_PORT") or 23300
    return [adb, "-s", f"{ip}:{port}"]


def _run(prefix: list, args: list, timeout: int = 30) -> str:
    try:
        p = subprocess.run(
            prefix + args,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
        return (p.stdout or b"").decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        log(f"adb 执行失败 {args}: {exc!r}")
        return ""


def _connect_bridge(prefix: list, target: str) -> None:
    """
    先让 guest 里的 adb server 连上桥（假 adbd）。

    上游 BAAH 自己会在 `check_connect()` 里 `adb connect`，但**预启动跑在 BAAH
    之前** —— 少了这一步，预启动里所有 `adb -s 127.0.0.1:23300 ...` 都报
    `device not found`，于是「等 App 建虚拟屏」永远等不到（2026-09-26 实测：
    屏都建好了 id 也写对了，runner 还在干等）。
    幂等：重复 connect 同一个地址是安全的。
    """
    out = _run([prefix[0]], ["connect", target], timeout=25)
    for line in out.splitlines():
        if line.strip():
            log(f"  adb connect {target}: {line.strip()}")


def _read_app_vd_id() -> int:
    """读 App 写下来的权威虚拟屏 id（读不到或 <0 = 还没有屏）。"""
    try:
        with open(APP_VD_ID_FILE) as fp:
            txt = fp.read().strip()
    except OSError:
        return -1
    return int(txt) if txt.lstrip("-").isdigit() else -1


def _display_present(prefix: list, vd: int) -> bool:
    """这块屏现在真的存在、且规格仍是 1280x720 吗（App 关掉屏后 id 会失效）。"""
    out = _run(prefix, ["shell", "dumpsys", "window", "displays"], timeout=25)
    current = None
    for line in out.splitlines():
        m = VD_ID_RE.search(line)
        if m:
            current = int(m.group(1))
            continue
        m = VD_SIZE_RE.search(line)
        if m and current == vd:
            return (int(m.group(1)), int(m.group(2))) == (1280, 720)
    return False


def find_virtual_display(prefix: list, target: str, wait_sec: float = VD_WAIT_SEC) -> int | None:
    """
    找**我们自己的**虚拟屏 displayId，最多等 wait_sec 秒（等不到返回 None）。

    id 只认 App 写下的那一个：App 是建屏的人，它给的 id 权威。
    **不要**退回"扫 dumpsys 里第一块 1280x720 的屏"——ALAS-AOS 的 MaaFwVirtualDisplay
    也是 1280x720，那样会拿到别人的屏（见 APP_VD_ID_FILE 处的说明）。

    为什么要等：App 的「开始挂机」先起 wrapper、异步建虚拟屏，而 wrapper 一起就把
    runner 拉起来了 —— 不等就会「预启动跳过：没找到虚拟屏」，然后 BAAH 对着空屏
    一直报 `pipe截图失败`（2026-09-26 实测）。
    """
    import time as _t

    deadline = _t.time() + wait_sec
    next_log = 0.0
    next_connect = 0.0
    while _t.time() < deadline:
        if _t.time() >= next_connect:
            _connect_bridge(prefix, target)
            next_connect = _t.time() + 15.0
        vd = _read_app_vd_id()
        if vd > 0 and _display_present(prefix, vd):
            log(f"虚拟屏就绪：displayId={vd}（App 指定）")
            return vd
        if _t.time() >= next_log:
            log(f"等 App 建虚拟屏…（{APP_VD_ID_FILE} = {vd}）")
            next_log = _t.time() + 10.0
        _t.sleep(2)
    log(f"等 App 建虚拟屏超时（{wait_sec:.0f}s）")
    return None


def _game_on_display(prefix: list, pkg: str, vd: int) -> bool:
    """
    游戏是否**已经**在这块虚拟屏上（用于让预启动幂等）。

    ⚠️ 切段必须同时认两种头：`Display #N (activities ...)` 和
    `Display: mDisplayId=N (organized)`。只认前一种的话，**最后一块屏的"段"会一直
    延续到后面所有内容**，于是把无关行里出现的包名当成"在虚拟屏上"（这个假阳性
    踩过，导致预启动被跳过、虚拟屏全空、桥截图恒 0 字节）。
    """
    out = _run(prefix, ["shell", "dumpsys", "activity", "activities"], timeout=25)
    in_target = False
    for line in out.splitlines():
        m = re.match(r"\s*Display #(\d+)", line)
        if m:
            in_target = int(m.group(1)) == vd
            continue
        if re.match(r"\s*Display: mDisplayId=", line):
            in_target = False
            continue
        if in_target and pkg in line:
            return True
    return False


def _display_frame_bytes(prefix: list) -> int:
    """
    虚拟屏现在能不能截到**真实画面**（返回 PNG 字节数；0 = 截不到）。

    ⚠️ 别用 `screencap -p <路径>` + `stat` 那一套：**我们的假 adbd 不写文件**，
    它把 PNG 直接吐到 stdout（实测 `ls`/`stat` 都说文件不存在，害我白等 180 秒）。
    所以这里只看 stdout 有多少字节：真画面 ≈ 几百 KB，空屏/无帧只有几十~几百字节。
    """
    try:
        p = subprocess.run(
            prefix + ["shell", "screencap", "-p"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
        )
        return len(p.stdout or b"")
    except Exception as exc:  # noqa: BLE001
        log(f"screencap 探测失败：{exc!r}")
        return 0


def _wait_for_frame(prefix: list) -> bool:
    """
    等虚拟屏**真的出画**再交给 BAAH（返回是否等到）。

    冷启动（App 刚建好虚拟屏 + 游戏刚 am start）时屏上什么都没有，桥的 screencap
    会返回空文件 → BAAH 启动后第一次截图就报「截图文件损坏，请尝试清理电脑内存后
    重启程序」并直接退出（2026-09-26 实测，日志里看得一清二楚）。
    """
    import time as _t

    deadline = _t.time() + FRAME_WAIT_SEC
    while _t.time() < deadline:
        n = _display_frame_bytes(prefix)
        if n > FRAME_MIN_BYTES:
            try:
                result = subprocess.run(prefix + ["shell", "baah-input-ready"],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=8)
                ready = result.returncode == 0 and result.stdout.strip() == b"ready"
            except (OSError, subprocess.TimeoutExpired):
                ready = False
            if ready:
                log(f"虚拟屏已出画且游戏输入窗口就绪（截图 {n} 字节），可以交给 BAAH 了")
                return True
        _t.sleep(3)
    log("等待虚拟屏画面与游戏输入窗口超时，本轮不启动 BAAH")
    return False


def prelaunch_on_virtual_display(config_name: str) -> bool:
    """
    把游戏预启动到**虚拟屏**上。返回"能不能把屏交给 BAAH"。

    为什么需要这一步（实测结论，2026-09-25）：
      * 桥（假 adbd）的 `screencap` 只拍虚拟屏；游戏在主屏时截图恒为 0 字节，
        BAAH 会一直「未检测到设备连接」；就算拍到，比例也是错的（主屏 1080x2640
        vs 虚拟屏 1280x720），模板匹配必然失败。
      * 上游 BAAH 的 `open_app()` 走的是 `am start <activity>` / `monkey`，
        **不带 `--display`**，所以游戏永远落在主屏。
      * 而 `am start --display <vd> -n <activity>` 在本机是有效的（shell 有权限），
        前提是**先 force-stop**：任务已存在时 Android 会复用主屏上的那个任务，
        `--display` 会被忽略（这一条踩过坑）。

    ⚠️ 必须幂等：runner 崩了会被 wrapper 反复重拉，如果每次都 force-stop 重启游戏，
    BAAH 就永远走不完登录流程（实测 respawns=5 时卡在这里）。所以先准确地查游戏
    是否已经在这块虚拟屏上，在就直接返回。

    ⚠️ 返回 False 时调用方**不要启动 BAAH**：屏没出画就交给 BAAH，它会在
    「截图文件为空 / 截图文件损坏」上无限重试，白白烧 CPU（2026-09-26 实测）。
    """
    cfg = _load_user_config(config_name)
    activity = cfg.get("ACTIVITY_PATH") or ""
    if "/" not in activity:
        log(f"预启动跳过：ACTIVITY_PATH 无效 ({activity!r})")
        return False
    pkg = activity.split("/")[0]
    with open(GAME_ACTIVITY_FILE, "w", encoding="utf-8") as fp:
        fp.write(activity)
    prefix = _adb_prefix(cfg)
    target = prefix[2]

    vd = find_virtual_display(prefix, target)
    if vd is None:
        log("预启动失败：等不到 App 的虚拟屏（App 还没把屏建好？）")
        return False

    # 先落盘，给 input 补丁用（幂等跳过也要写，否则补丁拿不到 id）
    _write_vd_id(vd)

    if _game_on_display(prefix, pkg, vd):
        log(f"预启动跳过：{pkg} 确实已在 display {vd} 上（幂等）")
        frames = _wait_for_frame(prefix)
        if frames:
            _set_game_on(True)
        return frames

    log(f"虚拟屏 displayId={vd}，准备把 {pkg} 预启动上去")
    _run(prefix, ["shell", "am", "force-stop", pkg], timeout=25)
    import time as _time

    _time.sleep(3)
    out = _run(
        prefix,
        ["shell", "am", "start", "--display", str(vd),
         "--activity-exclude-from-recents", "-n", activity],
        timeout=40,
    )
    for line in out.splitlines():
        if line.strip():
            log(f"  am start: {line.strip()}")
    _time.sleep(3)

    if _game_on_display(prefix, pkg, vd):
        log(f"预启动确认：{pkg} 已在 display {vd} 上 ✓")
    else:
        log(f"预启动结果未能确认（display {vd} 上没看到 {pkg}）")

    # 等屏上真的出画（见 _wait_for_frame 的说明）；出画了才在 App 侧显示「运行中」+帧率
    frames = _wait_for_frame(prefix)
    if frames:
        _set_game_on(True)
    return frames


# ── 配置校验 ──────────────────────────────────────────────────────────────────
def require_config(config_name: str) -> str:
    target = os.path.join(CONFIG_DIR, config_name)
    if not os.path.isfile(target):
        raise SystemExit(
            f"配置不存在: {target}\n"
            f"  正常路径是 App 调 wrapper 的 /start?config={config_name}，由 wrapper 从 {SEED_DIR} 生成。"
        )
    log(f"使用配置 {target}")
    return config_name


# ── 把 input 定向到虚拟屏 ─────────────────────────────────────────────────────
def _write_vd_id(vd: int) -> None:
    try:
        with open(VD_ID_FILE, "w") as fp:
            fp.write(str(vd))
        log(f"已记录虚拟屏 id -> {VD_ID_FILE} = {vd}")
    except OSError as exc:
        log(f"写 vd_id 失败: {exc!r}")


def _set_game_on(on: bool) -> None:
    """告诉 App「游戏已经在虚拟屏上打开了/已经关了」——预览卡的状态徽标与帧率显示读它。"""
    try:
        if on:
            with open(GAME_ON_FILE, "w") as fp:
                fp.write("1")
            log(f"游戏已在虚拟屏上 -> {GAME_ON_FILE}=1（App 侧显示「运行中」+帧率）")
        else:
            if os.path.isfile(GAME_ON_FILE):
                os.remove(GAME_ON_FILE)
                log(f"清除 {GAME_ON_FILE}")
    except OSError as exc:
        log(f"写 game_on 失败: {exc!r}")


def install_input_patch() -> None:
    """
    把上游 `click_on_screen` / `swipe_on_screen` 改造成 `input -d <虚拟屏> ...`。

    **为什么必须**（实测，2026-09-25）：Android 的 `input` 不指定 `-d` 时打的是
    display 0（主屏），而游戏跑在 BaahAosVirtualDisplay 上 —— 结果 BAAH 每 5 秒
    「点击空白处」点了几个小时，游戏一点反应都没有（画面变化只是标题背景在轮播）。
    带上 `-d <vd>` 后立刻生效：点到「切换账号」会弹出「您确定要切换账号吗?」。

    幂等：靠 INPUT_PATCH_MARKER。改的是上游文件（`/opt/baah`），但**由本文件每次
    启动时施加**，所以自包含、不需要重打 rootfs。
    """
    if not os.path.isfile(ADB_UTILS_PATH):
        log(f"input 补丁跳过：找不到 {ADB_UTILS_PATH}")
        return
    try:
        with open(ADB_UTILS_PATH, "r", encoding="utf-8") as fp:
            src = fp.read()
    except OSError as exc:
        log(f"input 补丁跳过：读文件失败 {exc!r}")
        return

    if INPUT_PATCH_MARKER in src:
        log("input 补丁：已打过（幂等）")
        return

    # 只用「目标函数唯一」做结构校验；幂等已由上面的 marker 判定。
    # （曾经错写成 `INPUT_HELPER not in src` —— 拿「要插入的代码」去查「是否已存在」，
    #  必然不成立，于是补丁永远被跳过。）
    if src.count("def click_on_screen(x, y):") != 1:
        log("input 补丁跳过：源码结构与预期不符（上游改版了？）")
        return

    for name, old, new in (
        ("click", INPUT_OLD_CLICK, INPUT_NEW_CLICK),
        ("swipe", INPUT_OLD_SWIPE, INPUT_NEW_SWIPE),
    ):
        n = src.count(old)
        if n != 1:
            log(f"input 补丁中止：{name} 命中 {n} 次（期望 1）")
            return
        src = src.replace(old, new, 1)

    src = src.replace("def click_on_screen(x, y):", INPUT_HELPER.lstrip("\n"), 1)

    try:
        compile(src, ADB_UTILS_PATH, "exec")
    except SyntaxError as exc:
        log(f"input 补丁中止：改完语法错误 {exc!r}")
        return

    try:
        with open(ADB_UTILS_PATH, "w", encoding="utf-8") as fp:
            fp.write(src)
    except OSError as exc:
        log(f"input 补丁写入失败: {exc!r}")
        return
    log("input 补丁已应用：点击/滑动将带 -d 定向到虚拟屏 ✓")


# ── 上游 adb_utils.py 的日志降噪（多屏手机特有的两条噪音）──────────────────────
LOG_PATCH_MARKER = "[DSH PATCH 2026-09-28a] 日志降噪"

# ① MuMu 保活告警：多屏（主屏+副屏+我们的虚拟屏）下 dumpsys 里天然有 `mCurrentFocus=null`
#    的行，上游把它当成"模拟器没关后台保活"告警 → 日志刷满误导性的 MUMU 报错。
MUMU_WARN_RE = re.compile(
    r'logging\.warn\(\{"zh_CN":\s*">>> MUMU[^"]*",\s*"en_US":\s*"[^"]*"\}\)',
    re.S,
)
# ② 「运行中...当前运行的app是：X」：check_app_running() 每次调用都打一条，而它在等游戏
#    启动的循环里被反复调用 → 刷屏。
RUNNING_INFO_RE = re.compile(
    r'logging\.info\(\{"zh_CN":\s*"运行中\.\.\.当前运行的app是：\{\}"\.format\(output\),\s*'
    r'"en_US":\s*"Running\.\.\.The currently running app is: \{\}"\.format\(output\)\}\)',
    re.S,
)

LOG_HELPER = '''

# ═══ ''' + LOG_PATCH_MARKER + ''' ═══
# 只在 app 真的变了的时候打一条（否则等游戏启动的轮询里会刷屏）。
_dsh_last_running_app = None


def _dsh_log_running_once(value):
    global _dsh_last_running_app
    if value == _dsh_last_running_app:
        return
    _dsh_last_running_app = value
    logging.info({"zh_CN": "运行中...当前运行的app是：{}".format(value),
                  "en_US": "Running...The currently running app is: {}".format(value)})
'''


def install_log_noise_patch() -> None:
    """
    把上游 adb_utils.py 里两条刷屏/误导的日志按掉（用户 2026-09-27 点名的两条）：
    「baah 有 mumu 模拟器需要关闭保活的报错」与「动不动就跳出运行中...当前运行的app是」。

    幂等：标记 + 把 info 调用换成 `_dsh_log_running_once(output)`；改完先 compile 再落盘。
    """
    if not os.path.isfile(ADB_UTILS_PATH):
        log(f"日志补丁跳过：找不到 {ADB_UTILS_PATH}")
        return
    try:
        with open(ADB_UTILS_PATH, "r", encoding="utf-8") as fp:
            src = fp.read()
    except OSError as exc:
        log(f"日志补丁跳过：读文件失败 {exc!r}")
        return

    if LOG_PATCH_MARKER in src:
        log("日志补丁：已打过（幂等）")
        return

    src, n_mumu = MUMU_WARN_RE.subn(
        "pass  # " + LOG_PATCH_MARKER + "：多屏手机的 mCurrentFocus=null 不是 MuMu 保活\n", src)
    src, n_run = RUNNING_INFO_RE.subn("_dsh_log_running_once(output)", src)
    if (n_mumu, n_run) != (1, 1):
        log(f"日志补丁跳过：命中数不对（mumu={n_mumu}, running={n_run}，上游改版了？）")
        return

    src = src.rstrip("\n") + "\n" + LOG_HELPER

    try:
        compile(src, ADB_UTILS_PATH, "exec")
    except SyntaxError as exc:
        log(f"日志补丁中止：改完语法错误 {exc!r}")
        return

    try:
        with open(ADB_UTILS_PATH, "w", encoding="utf-8") as fp:
            fp.write(src)
    except OSError as exc:
        log(f"日志补丁写入失败: {exc!r}")
        return
    log("日志补丁已应用：MuMu 保活告警静音 + 「当前运行的app」只在变化时打 ✓")


# ── 上游 adb_utils.py 的**多屏前台判定**补丁 ──────────────────────────────────
FG_PATCH_MARKER = "[DSH PATCH 2026-09-28b] 多屏前台判定"
FG_VD_MARKER = "[DSH PATCH 2026-10-07a] 虚拟屏游戏状态优先"
FG_OLD_CHECK = '                if "ResumedActivity" not in _dsh_act_line or _dsh_want_pkg not in _dsh_act_line:'
FG_NEW_CHECK = '                if ("ResumedActivity:" not in _dsh_act_line and "Resumed:" not in _dsh_act_line) or _dsh_want_pkg not in _dsh_act_line:'

# 锚点：`get_now_running_app()` 的函数头 + 它第一行（取 target_config）。
# 补丁**插在它后面**：命中目标包就直接 return，不命中才走上游那套 mCurrentFocus 解析。
FG_ANCHOR_RE = re.compile(
    r"(def get_now_running_app\(use_config=None\):\n(?:.*\n)*?"
    r"    target_config = config if not use_config else use_config\n)",
)

FG_HELPER = '''    # ═══ ''' + FG_PATCH_MARKER + ''' ═══
    # 多屏手机：`dumpsys window` 的 mCurrentFocus **只会来回报一条** —— 主屏是我们的 App
    # （用户在看界面）时，虚拟屏上正在跑的游戏那一行就漏了 → 上游判「游戏未在前台」→
    # 反复 `am force-stop` + `am start` 重启游戏 ← 用户报的「运行日服配置时，日服 APP 自动退出了」。
    # 这里用 `dumpsys activity activities` 的 Resumed/ResumedActivity 当权威来源：
    # 不同 Android 版本/区段的写法不同，vivo 的副屏是 `Resumed:`。
    # 目标包名匹配就直接返回，不再靠猜。
    try:
        _dsh_want_pkg = target_config.userconfigdict.get("ACTIVITY_PATH", "").split("/")[0]
        if _dsh_want_pkg:
            _dsh_act_out = subprocess_run([get_config_adb_path(target_config), "-s",
                                           getNewestSeialNumber(target_config),
                                           "shell", "dumpsys", "activity", "activities"]).stdout
            for _dsh_act_line in _dsh_act_out.splitlines():
                if ("ResumedActivity:" not in _dsh_act_line and "Resumed:" not in _dsh_act_line) or _dsh_want_pkg not in _dsh_act_line:
                    continue
                for _dsh_tok in _dsh_act_line.replace("{", " ").replace("}", " ").split():
                    if "/" in _dsh_tok and _dsh_want_pkg in _dsh_tok:
                        return _dsh_tok
    except Exception:
        pass
'''

FG_VD_HELPER = '''    # ═══ ''' + FG_VD_MARKER + ''' ═══
    # 主屏允许 ALAS 等其它应用继续显示；游戏状态只看 BAAH 自己的虚拟屏。
    # mCurrentFocus/topResumedActivity 是主屏全局状态，不能判断隐藏虚拟屏上的游戏。
    try:
        _dsh_want_pkg = target_config.userconfigdict.get("ACTIVITY_PATH", "").split("/")[0]
        with open("/opt/baah-aos/vd_id", "r", encoding="utf-8") as _dsh_vd_fp:
            _dsh_our_vd = int(_dsh_vd_fp.read().strip())
        if _dsh_want_pkg and _dsh_our_vd > 0:
            _dsh_act_out = subprocess_run([get_config_adb_path(target_config), "-s",
                                           getNewestSeialNumber(target_config),
                                           "shell", "dumpsys", "activity", "activities"]).stdout
            _dsh_current_display = None
            for _dsh_act_line in _dsh_act_out.splitlines():
                _dsh_stripped = _dsh_act_line.strip()
                if _dsh_stripped.startswith("Display #"):
                    try:
                        _dsh_current_display = int(_dsh_stripped.split("#", 1)[1].split()[0].split("(", 1)[0])
                    except (ValueError, IndexError):
                        _dsh_current_display = None
                    continue
                if _dsh_stripped.startswith("Display: mDisplayId="):
                    try:
                        _dsh_current_display = int(_dsh_stripped.split("mDisplayId=", 1)[1].split()[0].split("(", 1)[0])
                    except (ValueError, IndexError):
                        _dsh_current_display = None
                    continue
                if _dsh_current_display != _dsh_our_vd or _dsh_want_pkg not in _dsh_act_line:
                    continue
                for _dsh_tok in _dsh_act_line.replace("{", " ").replace("}", " ").split():
                    if "/" in _dsh_tok and _dsh_want_pkg in _dsh_tok:
                        return _dsh_tok
                return _dsh_want_pkg
    except Exception:
        pass
'''


def install_foreground_patch() -> None:
    """
    修「游戏到底在不在前台」这个判定（用户报的「运行日服配置时日服 APP 自动退出」的直接原因）。

    上游 `get_now_running_app()` 只读 `dumpsys window` 的 `mCurrentFocus`。多屏设备（主屏 +
    我们的 1280x720 虚拟屏）上这一行**来回报一个屏**：主屏是我们的 App 时，虚拟屏上正在跑的
    游戏就漏了 → 上游判"游戏未在前台" → `am force-stop <游戏包>` 再 `am start` 重启游戏
    （实测 logcat：`BaahBridge: OPEN shell: 'am force-stop com.YostarJP.BlueArchive'`）→
    玩家看到的就是"游戏自己退了"。补丁在函数体开头插一段兜底：用
    `dumpsys activity activities` 的 `Resumed:` / `ResumedActivity:` 做权威判定。

    幂等：标记 + 先 compile 再落盘。
    """
    if not os.path.isfile(ADB_UTILS_PATH):
        log(f"前台判定补丁跳过：找不到 {ADB_UTILS_PATH}")
        return
    try:
        with open(ADB_UTILS_PATH, "r", encoding="utf-8") as fp:
            src = fp.read()
    except OSError as exc:
        log(f"前台判定补丁跳过：读文件失败 {exc!r}")
        return

    changed = False
    # Remove the older global-resumed check. It can report ALAS as the game
    # because Android exposes the primary display's resumed activity globally.
    old_marker_at = src.find(FG_PATCH_MARKER)
    if old_marker_at >= 0:
        block_start = src.rfind("    # ═══", 0, old_marker_at)
        block_end = src.find("    except Exception:\n        pass\n", old_marker_at)
        if block_start < 0 or block_end < 0:
            log("旧前台判定补丁结构异常，保留原文件")
            return
        block_end += len("    except Exception:\n        pass\n")
        src = src[:block_start] + src[block_end:]
        changed = True

    # Prefer the task belonging to BAAH's own hidden virtual display. The global
    # resumed activity can legitimately be ALAS while this game remains active.
    if FG_VD_MARKER not in src:
        src, count = FG_ANCHOR_RE.subn(lambda m: m.group(1) + FG_VD_HELPER, src, count=1)
        if count != 1:
            log(f"虚拟屏前台判定补丁跳过：锚点命中数不对（{count}）")
            return
        changed = True
    if not changed:
        log("虚拟屏前台判定补丁：已打过（幂等）")
        return
    try:
        compile(src, ADB_UTILS_PATH, "exec")
        with open(ADB_UTILS_PATH, "w", encoding="utf-8") as fp:
            fp.write(src)
    except (SyntaxError, OSError) as exc:
        log(f"虚拟屏前台判定补丁写入失败: {exc!r}")
        return
    log("虚拟屏前台判定补丁已应用：按 BAAH 自有 displayId 识别游戏，允许 ALAS 留在主屏")


TASK_FAILURES = []


def install_progress_events(perf=None) -> None:
    """Report top-level pipeline steps; nested task logs do not advance Android progress."""
    from modules.AllTask import myAllTask as tasks
    if perf is not None:
        perf.install()

    if getattr(tasks.AllTask, "_aos_progress_installed", False):
        return
    original_parse = tasks.AllTask.parse_task

    def emit(payload):
        print("[BAAH-AOS-PROGRESS] " + json.dumps(payload, ensure_ascii=False), flush=True)

    def parse_with_progress(self):
        original_parse(self)
        order, onoff, *_ = tasks.return_now_activate_pipeline(tasks.config)
        enabled = [name for name, active in zip(order or [], onoff or []) if active]
        table = tasks.task_instances_map.taskmap
        labels = [tasks.config.get_text(table[name].i18n_key_name) if name in table else str(name) for name in enabled]
        emit({"event": "plan", "tasks": labels})
        offset = 1 if tasks.config.userconfigdict.get("OPEN_GAME_APP_TASK") else 0
        for index, task in enumerate(self.taskpool):
            user_index = index - offset
            done = max(0, min(user_index, len(labels)))
            title = labels[user_index] if 0 <= user_index < len(labels) else (
                "进入游戏" if user_index < 0 else "任务收尾")
            original_run = task.run

            def report_run(original=original_run, title=title, done=done, user_index=user_index, task=task):
                emit({"event": "task", "title": title, "done": done, "total": len(labels)})
                if perf is not None:
                    started = time.perf_counter()
                    before = perf.snapshot()
                try:
                    result = original()
                except BaseException as failure:
                    # Upstream catches task exceptions and returns normally. Keep
                    # their real failure so the scheduler cannot report success.
                    TASK_FAILURES.append(f"{title}: {type(failure).__name__}: {failure}")
                    raise
                finally:
                    if perf is not None:
                        perf.report(title, before, started)
                task_error = getattr(task, '_aos_task_error', None)
                if task_error:
                    TASK_FAILURES.append(f'{title}: {task_error}')
                completed = done + 1 if 0 <= user_index < len(labels) else done
                emit({"event": "task_done", "title": title, "done": completed, "total": len(labels)})
                return result

            task.run = report_run

    tasks.AllTask.parse_task = parse_with_progress
    tasks.AllTask._aos_progress_installed = True


def _resolve_task_name(display_name: str) -> str | None:
    """把 App 传来的**显示名**（跟网页「小工具」按钮上的字一样）换成 BAAH 的真实配置名。

    为什么这么绕：App 侧只有 i18n 显示名（schema 里的 task_names），而 BAAH 的
    `task_instances_map.taskmap` 用的是真实名（如 MAIL）。映射表就在 guest 里，就地查最稳。
    传进来的要是本来就叫真实名，也认。
    """
    try:
        from modules.AllTask.myAllTask import task_instances_map as tim

        table = getattr(tim, "task_config_name_2_i18n_name", {}) or {}
        if display_name in tim.taskmap:
            return display_name
        for real, shown in table.items():
            if str(shown) == display_name:
                return real
    except BaseException as exc:  # noqa: BLE001
        log(f"任务名映射失败: {exc!r}")
    return None


def run_single_task(config_name: str, task_display: str, edition=None) -> int:
    """只跑一个任务（对应「小工具」页的快速调用任务按钮）。"""
    baah_config = config_name if config_name.endswith(".json") else config_name + ".json"
    try:
        require_config(baah_config)
    except SystemExit as exc:
        log(str(exc))
        return 1
    if not prelaunch_on_virtual_display(baah_config):
        log("虚拟屏没准备好，本轮不启动 BAAH（等 wrapper 重拉）")
        return 3
    from fast_adb import enable_fail_fast
    enable_fail_fast()
    install_input_patch()
    install_log_noise_patch()
    install_foreground_patch()
    os.chdir(BAAH_ROOT)
    sys.path.insert(0, BAAH_ROOT)
    builtins.input = lambda *args, **kwargs: ""

    real = _resolve_task_name(task_display)
    if not real:
        log(f"找不到任务：{task_display}")
        return 1
    log(f"单任务启动（cwd={os.getcwd()}, config={baah_config}, task={real}）")
    if edition is not None:
        edition.install_gameplay()
    import queue as _queue

    from BAAH import BAAH_single_func_process  # noqa: PLC0415
    from screenshot_runtime import install as install_raw_screenshot
    install_raw_screenshot()

    def emit_progress(payload):
        print("[BAAH-AOS-PROGRESS] " + json.dumps(payload, ensure_ascii=False), flush=True)

    emit_progress({"event": "plan", "tasks": [task_display]})
    emit_progress({"event": "task", "title": task_display, "done": 0, "total": 1})
    try:
        BAAH_single_func_process(reread_config_name=baah_config, msg_queue=_queue.Queue(),
                                to_run_func_config_name=real)
    except SystemExit as exc:
        log(f"BAAH SystemExit: {exc}")
        return int(exc.code or (1 if TASK_FAILURES else 0))
    except BaseException as exc:  # noqa: BLE001
        import traceback

        log(f"BAAH 异常退出: {type(exc).__name__}: {exc}")
        traceback.print_exc()
        return 1
    from modules.configs.MyConfig import config
    claim_error = config.sessiondict.get('_AOS_FREE_AWARD_ERROR')
    if claim_error:
        log('BAAH 单任务未完成: ' + str(claim_error))
        return 1
    emit_progress({"event": "task_done", "title": task_display, "done": 1, "total": 1})
    log("BAAH 正常结束（单任务）")
    return 0


# ── psutil 垫片：guest 里没有 psutil，而 BAAH 一 import 就要它 ────────────────
PSUTIL_SHIM_NOTE = "[DSH PATCH 2026-09-28c] psutil 垫片"
PSUTIL_SHIM_SRC = '''"""DSH psutil 垫片（[DSH PATCH 2026-09-28c]）—— 只实现 BAAH 真正用到的那几个 API。

背景：APK 里的 rootfs.tar.xz **没有 psutil**（`find / -name 'psutil*'` 空），
而 `modules/utils/utils/__init__.py` → `adb_utils` → `subprocess_helper` 一上来就
`import psutil` —— 缺了它，BAAH 的任何模块都进不来。真 psutil 是 C 扩展，得挑
aarch64/cp312 的轮子；本机又下不动包。宁可写个纯 stdlib 的垫片：
BAAH 实际只用到 process_iter(['pid','name']) / cpu_count / virtual_memory / swap_memory。
"""

import os


def _read(path, default=""):
    try:
        with open(path, "rb") as fp:
            return fp.read().decode("utf-8", "replace")
    except OSError:
        return default


class _Proc(object):
    def __init__(self, pid, name):
        self.pid = pid
        self.info = {"pid": pid, "name": name}

    def __repr__(self):
        return "psutil.Process(pid=%d, name=%r)" % (self.pid, self.info["name"])


class _Mem(object):
    def __init__(self, total, available):
        self.total = total
        self.available = available
        self.used = max(0, total - available)
        self.percent = 0.0

    def __repr__(self):
        return "psutil memory(total=%d)" % self.total


def _proc_name(pid):
    name = _read("/proc/%d/comm" % pid).strip()
    if name:
        return name
    cmdline = _read("/proc/%d/cmdline" % pid)
    return cmdline.split("\\x00")[0] if cmdline else ""


def process_iter(attrs=None):
    try:
        entries = os.listdir("/proc")
    except OSError:
        return
    for entry in entries:
        if not entry.isdigit():
            continue
        pid = int(entry)
        yield _Proc(pid, _proc_name(pid))


def pid_exists(pid):
    return os.path.isdir("/proc/%d" % int(pid))


def cpu_count(logical=True):
    if logical:
        return os.cpu_count() or 1
    cores = set()
    for line in _read("/proc/cpuinfo").splitlines():
        if line.startswith("physical id"):
            cores.add(line.split(":", 1)[-1].strip())
    return len(cores) or (os.cpu_count() or 1)


def _meminfo(key):
    for line in _read("/proc/meminfo").splitlines():
        if line.startswith(key):
            parts = line.split()
            if len(parts) >= 2 and parts[1].isdigit():
                return int(parts[1]) * 1024
    return 0


def virtual_memory():
    total = _meminfo("MemTotal:")
    if not total:
        try:
            total = os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
        except (AttributeError, ValueError, OSError):
            total = 0
    return _Mem(total, total)


def swap_memory():
    total = _meminfo("SwapTotal:")
    return _Mem(total, total)
'''


def install_psutil_shim() -> None:
    """没有 psutil 就在当前解释器的 purelib 里放一个纯 stdlib 垫片（幂等）。

    必须在**任何 BAAH import 之前**调用 —— BAAH 的 `modules/utils/__init__.py` 会
    顺着 `adb_utils → subprocess_helper` 立刻 `import psutil`。
    """
    try:
        import importlib.util

        if importlib.util.find_spec("psutil") is not None:
            return
    except Exception:  # noqa: BLE001
        pass
    try:
        import sysconfig

        target_dir = sysconfig.get_paths().get("purelib") or "/usr/local/lib/python3.12/dist-packages"
        os.makedirs(target_dir, exist_ok=True)
        path = os.path.join(target_dir, "psutil.py")
        with open(path, "w", encoding="utf-8") as fp:
            fp.write(PSUTIL_SHIM_SRC)
        log(f"psutil 垫片已写入：{path}")
    except OSError as exc:
        log(f"psutil 垫片写入失败（BAAH 可能起不来）：{exc!r}")


def ensure_dns() -> None:
    """guest 的 `/etc/resolv.conf` 是空的 → 里面任何域名都解析不了（pip 报
    `Temporary failure in name resolution`）。Android 自己走 netd，rootfs 里没有它的配置，
    所以这里直接写两个国内公共 DNS（阿里 / DNSPod，实测手机侧通）。幂等。
    """
    path = "/etc/resolv.conf"
    try:
        cur = ""
        if os.path.isfile(path):
            with open(path, "r", encoding="utf-8", errors="replace") as fp:
                cur = fp.read()
        if "nameserver" in cur:
            return
        with open(path, "w", encoding="utf-8") as fp:
            fp.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\n")
        log("已写入 /etc/resolv.conf（223.5.5.5 / 119.29.29.29）")
    except OSError as exc:
        log(f"写 /etc/resolv.conf 失败：{exc!r}")



def main() -> int:
    from fast_adb import install as install_fast_adb
    install_fast_adb()
    install_psutil_shim()
    from python_setup import missing
    missing_packages = missing()
    if missing_packages:
        raise RuntimeError("请先完成首次初始化中的 Python 依赖安装：" + ", ".join(missing_packages))
    edition = None
    if os.environ.get("BAAH_NEO_ONLY") == "1":
        import neo_private as edition
        edition.install_ocr()
    if len(sys.argv) < 2 or not sys.argv[1]:
        log("用法: runner.py <配置名> [任务显示名]")
        return 2
    config_name = sys.argv[1]
    task_display = sys.argv[2] if len(sys.argv) > 2 else ""
    if task_display:
        # 单任务模式：只跑这一个任务，跑完就退（wrapper 侧不会重拉）
        _set_game_on(False)
        if not bootstrap_upstream():
            return 1
        return run_single_task(config_name, task_display, edition)

    # 新一轮开始：先把上一轮的「游戏已打开」标记清掉（App 侧据此显示启动中/未运行）
    _set_game_on(False)

    if not bootstrap_upstream():
        return 1

    # [DSH 2026-09-25] BAAH 的 run_baah_script 要的是「带 .json 的完整文件名」——
    # 上游用法就是 `BAAH.exe 配置名.json`。而适配层对外（wrapper 的 /start?config=）
    # 用的是不带扩展名的名字，这里补上，否则 BAAH 报
    # "输入的配置文件名不在可用配置文件列表中" / FileNotFoundError。
    baah_config = config_name if config_name.endswith(".json") else config_name + ".json"

    try:
        require_config(baah_config)
    except SystemExit as exc:
        log(str(exc))
        return 1

    # 先把游戏搬到虚拟屏（详见函数 docstring 里的实测结论）
    # 屏没出画就**不要**启动 BAAH：它只会在「截图文件为空/损坏」上无限重试。
    # 直接退出，让 wrapper 的监督循环过一会儿重拉（那时 App 的屏多半已经建好）。
    if not prelaunch_on_virtual_display(baah_config):
        log("虚拟屏没准备好，本轮不启动 BAAH（等 wrapper 重拉）")
        return 3

    from fast_adb import enable_fail_fast
    enable_fail_fast()

    # 再把上游的 input 定向到虚拟屏（否则点击全打在主屏上，游戏收不到）
    install_input_patch()
    # 顺手把两条刷屏的日志按掉（MuMu 保活告警 / 「当前运行的app」）
    install_log_noise_patch()
    install_foreground_patch()

    # BAAH 依赖相对路径（./DATA/...、./BAAH_CONFIGS/...），必须 chdir 到上游根
    os.chdir(BAAH_ROOT)
    sys.path.insert(0, BAAH_ROOT)

    # main.py 的异常分支里有 input("错误，回车退出"）；无人应答会挂死
    builtins.input = lambda *args, **kwargs: ""

    log(f"启动 BAAH（cwd={os.getcwd()}, config={baah_config}）")
    import main  # noqa: PLC0415  —— 必须在 chdir 之后才能 import
    from screenshot_runtime import install as install_raw_screenshot
    install_raw_screenshot()
    if edition is not None:
        edition.install_gameplay()
    install_progress_events(edition.perf if edition is not None else None)

    try:
        main.run_baah_script(baah_config)
    except SystemExit as exc:
        log(f"BAAH SystemExit: {exc}")
        return int(exc.code or 0)
    except BaseException as exc:  # noqa: BLE001
        import traceback

        log(f"BAAH 异常退出: {type(exc).__name__}: {exc}")
        traceback.print_exc()
        return 1

    if TASK_FAILURES:
        log("BAAH 任务失败结束: " + " | ".join(TASK_FAILURES))
        return 1
    log("BAAH 正常结束")
    return 0


if __name__ == "__main__":
    exit_code = main()
    if exit_code:
        # Upstream OCR workers can keep non-daemon threads alive after failure.
        # Flush diagnostics and let the supervisor observe the real exit code.
        sys.stdout.flush()
        sys.stderr.flush()
        os._exit(exit_code)
    raise SystemExit(exit_code)
