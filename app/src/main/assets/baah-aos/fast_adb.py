"""Run BAAH's frequent local ADB requests without spawning another proot Python."""
import os
import logging
import runpy
import socket
import subprocess
import threading
import time


class BridgeUnavailableError(BaseException):
    """Abort an active run even when upstream catches ordinary task errors."""


_fail_fast = False


def enable_fail_fast():
    global _fail_fast
    _fail_fast = True


def install():
    if getattr(subprocess.run, '_baah_fast_adb', False):
        return
    path = os.path.join(os.environ.get('BAAH_AOS_ROOT', '/opt/baah-aos'), 'adb-wrap')
    api = runpy.run_path(path, run_name='baah_fast_adb_client')
    original = subprocess.run
    clients = threading.local()
    target = '%s:%d' % (api['BRIDGE_HOST'], api['BRIDGE_PORT'])
    allowed = {'screencap', 'input', 'wm', 'dumpsys', 'getprop', 'baah-screencap-bgr'}

    def run(args, *positional, **kwargs):
        # Popen/async commands and requests to other executables/devices stay unchanged.
        if (positional or not isinstance(args, (list, tuple)) or not args or
                os.fspath(args[0]) != path or kwargs.get('shell') or
                any(kwargs.get(k) is not None for k in ('env', 'cwd', 'executable', 'input')) or
                set(kwargs) - {'stdout', 'stderr', 'stdin', 'encoding', 'errors', 'text',
                               'universal_newlines', 'timeout', 'check', 'capture_output',
                               'shell', 'env', 'cwd', 'executable', 'input'}):
            return original(args, *positional, **kwargs)
        serial, rest = api['split_argv'](list(args[1:]))
        if (serial != target or len(rest) < 2 or rest[0] not in ('shell', 'exec-out') or
                not rest[1].split() or rest[1].split()[0] not in allowed):
            return original(args, **kwargs)
        capture = kwargs.get('capture_output', False)
        if capture and (kwargs.get('stdout') is not None or kwargs.get('stderr') is not None):
            raise ValueError('stdout and stderr arguments may not be used with capture_output.')
        if not capture and kwargs.get('stdout') != subprocess.PIPE:
            return original(args, **kwargs)
        if not capture and kwargs.get('stderr') not in (None, subprocess.PIPE, subprocess.STDOUT, subprocess.DEVNULL):
            return original(args, **kwargs)

        client = getattr(clients, 'client', None)
        if client is not None and (clients.pid != os.getpid() or time.monotonic() - clients.last_used > 25):
            # The bridge closes idle sockets after 30 seconds (e.g. a long battle).
            client.close()
            client = None
        if client is None:
            client = clients.client = api['MiniAdb']()
            clients.pid = os.getpid()
        timeout = kwargs.get('timeout') or 30.0
        client.timeout = timeout
        if client.sock is not None:
            client.sock.settimeout(timeout)
        error = b''
        code = 0
        try:
            api['note_taps'](rest[1:])
            service = ('exec:' if rest[0] == 'exec-out' else 'shell:') + ' '.join(rest[1:])
            data = api['_maybe_clean_screencap'](rest[1:], client.service(service))
            clients.last_used = time.monotonic()
        except (TimeoutError, socket.timeout) as failure:
            client.close()
            clients.client = None
            raise subprocess.TimeoutExpired(args, timeout) from failure
        except (OSError, EOFError, RuntimeError) as failure:
            # Do not retry a tap/swipe: it may already have reached the game.
            client.close()
            clients.client = None
            data = b''
            code = 1
            error = ('adb-wrap: in-process request failed: %r\n' % failure).encode()
            if str(failure).startswith('BAAH bridge error: '):
                # Upstream click_on_screen discards CompletedProcess. Keep the
                # explicit bridge rejection visible in the runner's task log.
                logging.getLogger(__name__).error('[BAAH-AOS-ADB] %s', failure)
            if _fail_fast:
                raise BridgeUnavailableError(
                    '虚拟屏截图或触控链路已中断，本轮停止：%s' % failure
                ) from failure
        stderr = error if capture or kwargs.get('stderr') == subprocess.PIPE else None
        if kwargs.get('stderr') == subprocess.STDOUT:
            data += error
        elif stderr is None and error and kwargs.get('stderr') != subprocess.DEVNULL:
            os.write(2, error)
        if kwargs.get('encoding') or kwargs.get('text') or kwargs.get('universal_newlines'):
            encoding = kwargs.get('encoding') or 'utf-8'
            errors = kwargs.get('errors') or 'strict'
            data = data.decode(encoding, errors).replace('\r\n', '\n').replace('\r', '\n')
            if stderr is not None:
                stderr = stderr.decode(encoding, errors).replace('\r\n', '\n').replace('\r', '\n')
        result = subprocess.CompletedProcess(args, code, data, stderr)
        if kwargs.get('check'):
            result.check_returncode()
        return result

    run._baah_fast_adb = True
    subprocess.run = run
    print('[runner] ADB fast path enabled: local screenshot/input/read requests reuse connections', flush=True)
