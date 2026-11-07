"""Private packed BGR transport for upstream pipe screenshots; PNG remains standard."""
import struct
import os
import subprocess
import sys


def install():
    import cv2
    import numpy as np
    from modules.utils import adb_utils
    original = adb_utils.screen_shot_to_global
    target = '%s:%s' % (os.environ.get('BAAH_AOS_BRIDGE_HOST', '127.0.0.1'),
                        os.environ.get('BAAH_AOS_BRIDGE_PORT', '23300'))
    if getattr(original, '_baah_raw', False):
        return

    def screenshot(use_config=None, output_png=False):
        config = adb_utils.config if use_config is None else use_config
        values = config.userconfigdict
        if (values.get('SCREENSHOT_METHOD') != 'pipe' or
                adb_utils._is_PC_app(values['SERVER_TYPE']) or
                adb_utils.getNewestSeialNumber(config) != target):
            return original(use_config=use_config, output_png=output_png)
        result = subprocess.run([
            adb_utils.get_config_adb_path(config), '-s',
            adb_utils.getNewestSeialNumber(config), 'exec-out', 'baah-screencap-bgr'
        ], stdout=subprocess.PIPE, check=True)
        data = result.stdout
        if len(data) < 16 or data[:8] != b'BAAHBGR1':
            raise RuntimeError('Invalid BAAH raw screenshot header')
        width, height = struct.unpack_from('<II', data, 8)
        if not width or not height or len(data) != 16 + width * height * 3:
            raise RuntimeError('Invalid BAAH raw screenshot dimensions')
        # Writable, independent image matching cv2.imdecode(IMREAD_COLOR).
        image = np.frombuffer(data, np.uint8, offset=16).reshape(height, width, 3).copy()
        config.sessiondict['SCREENSHOT_DATA'] = image
        if output_png:
            cv2.imwrite('./' + values['SCREENSHOT_NAME'], image)

    screenshot._baah_raw = True
    # Upstream imports this function into task modules before runner startup.
    for module in tuple(sys.modules.values()):
        if module is None:
            continue
        for name, value in tuple(vars(module).items()):
            if value is original:
                setattr(module, name, screenshot)
    print('[runner] Screenshot transport: packed BGR (standard PNG unchanged)', flush=True)
