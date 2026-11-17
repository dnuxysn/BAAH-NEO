"""The private screenshot transport exposes logical pixels, including to BAAH 2.5.12."""
import importlib.util
from pathlib import Path
import struct
import sys
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/assets/baah-aos/screenshot_runtime.py'
spec = importlib.util.spec_from_file_location('neo_screenshot_test', SOURCE)
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


class Pixels:
    def reshape(self, height, width, channels):
        self.shape = (height, width, channels)
        return self

    def copy(self):
        return self


class ScreenshotCoordinatesTest(unittest.TestCase):
    def test_native1080_session_is_replaced_by_logical720_transport_dimensions(self):
        config = SimpleNamespace(userconfigdict={
            'SCREENSHOT_METHOD': 'pipe', 'SERVER_TYPE': 'JP', 'SCREENSHOT_NAME': 'test.png',
        }, sessiondict={'NATIVE_SCREENSHOT_WIDTH': 1920, 'NATIVE_SCREENSHOT_HEIGHT': 1080})
        adb = ModuleType('modules.utils.adb_utils')
        adb.config = config
        adb.screen_shot_to_global = lambda **kwargs: None
        adb._is_PC_app = lambda server: False
        adb.getNewestSeialNumber = lambda cfg: '127.0.0.1:23300'
        adb.get_config_adb_path = lambda cfg: 'adb'
        utils = ModuleType('modules.utils')
        utils.adb_utils = adb
        packages = {
            'modules': ModuleType('modules'), 'modules.utils': utils,
            'modules.utils.adb_utils': adb,
            'cv2': SimpleNamespace(imwrite=lambda *_args: True),
            'numpy': SimpleNamespace(uint8='uint8', frombuffer=lambda *_args, **_kwargs: Pixels()),
        }
        payload = b'BAAHBGR1' + struct.pack('<II', 1280, 720) + bytes(1280 * 720 * 3)
        with patch.dict(sys.modules, packages), \
                patch.dict(transport.os.environ, {'BAAH_AOS_BRIDGE_HOST': '127.0.0.1', 'BAAH_AOS_BRIDGE_PORT': '23300'}), \
                patch.object(transport.subprocess, 'run', return_value=SimpleNamespace(stdout=payload)):
            transport.install()
            adb.screen_shot_to_global()
        self.assertEqual((720, 1280, 3), config.sessiondict['SCREENSHOT_DATA'].shape)
        self.assertEqual(1280, config.sessiondict['NATIVE_SCREENSHOT_WIDTH'])
        self.assertEqual(720, config.sessiondict['NATIVE_SCREENSHOT_HEIGHT'])
        # 2.5.12 to_native_point uses exactly these ratios. No extra 1.5x may enter the bridge.
        self.assertEqual((640, 360), (
            640 * config.sessiondict['NATIVE_SCREENSHOT_WIDTH'] / 1280,
            360 * config.sessiondict['NATIVE_SCREENSHOT_HEIGHT'] / 720,
        ))


if __name__ == '__main__':
    unittest.main()
