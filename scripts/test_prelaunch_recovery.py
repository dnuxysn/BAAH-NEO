"""Prelaunch must use the target display's resumed game and recover only that game."""
import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/assets/baah-aos/runner.py'
spec = importlib.util.spec_from_file_location('neo_runner_test', SOURCE)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

GAME = 'com.YostarJP.BlueArchive'
PREFIX = ['adb', '-s', '127.0.0.1:23321']


class PrelaunchRecoveryTest(unittest.TestCase):
    def test_owned_display_accepts_real720p_and1080p_without_using_another_display(self):
        for size in ('1280x720', '1920x1080'):
            dump = f'Display: mDisplayId=42\n  init=1280x720 240dpi\nDisplay: mDisplayId=41\n  init={size} 360dpi'
            with self.subTest(size=size), patch.object(runner, '_run', return_value=dump):
                self.assertTrue(runner._display_present(PREFIX, 41))
                self.assertFalse(runner._display_present(PREFIX, 43))
        dump = 'Display: mDisplayId=42\n  init=1920x1080 360dpi\nDisplay: mDisplayId=41\n  init=1440x2560 560dpi'
        with patch.object(runner, '_run', return_value=dump):
            self.assertFalse(runner._display_present(PREFIX, 41))

    def test_old_activity_record_does_not_count_as_running(self):
        dump = f'Display #41\n  ActivityRecord{{old {GAME}/.Game}}\n  mResumedActivity=ActivityRecord{{new other.app/.Game}}'
        with patch.object(runner, '_run', return_value=dump):
            self.assertFalse(runner._game_on_display(PREFIX, GAME, 41))

    def test_target_display_resumed_game_is_valid(self):
        for marker in ('topResumedActivity=', 'mResumedActivity='):
            dump = f'Display #0\n {marker}ActivityRecord{{other.app/.Game}}\nDisplay #41\n {marker}ActivityRecord{{{GAME}/.Game}}'
            with self.subTest(marker=marker), patch.object(runner, '_run', return_value=dump):
                self.assertTrue(runner._game_on_display(PREFIX, GAME, 41))

    def test_other_display_or_summary_resumed_game_is_not_valid(self):
        for dump in (
            f'Display #41\n ActivityRecord{{old {GAME}/.Game}}\nDisplay #42\n topResumedActivity=ActivityRecord{{{GAME}/.Game}}',
            f'Display #41\n ActivityRecord{{old {GAME}/.Game}}\nDisplay: mDisplayId=42\n mResumedActivity=ActivityRecord{{{GAME}/.Game}}',
        ):
            with self.subTest(dump=dump), patch.object(runner, '_run', return_value=dump):
                self.assertFalse(runner._game_on_display(PREFIX, GAME, 41))

    def prelaunch(self, readiness):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(runner, 'GAME_ACTIVITY_FILE', str(Path(directory) / 'activity')), \
                 patch.object(runner, '_load_user_config', return_value={'ACTIVITY_PATH': GAME + '/.Game'}), \
                 patch.object(runner, '_adb_prefix', return_value=PREFIX), \
                 patch.object(runner, 'find_virtual_display', return_value=41), \
                 patch.object(runner, '_write_vd_id'), \
                 patch.object(runner, '_game_on_display', return_value=True), \
                 patch.object(runner, '_wait_for_frame', side_effect=readiness), \
                 patch.object(runner, '_set_game_on') as game_on, \
                 patch.object(runner, '_run', return_value='') as commands, \
                 patch.object(runner.time, 'sleep'):
                result = runner.prelaunch_on_virtual_display('jp')
                actual = [call.args[1] for call in commands.call_args_list]
                self.assertEqual([
                    ['shell', 'am', 'force-stop', GAME],
                    ['shell', 'am', 'start', '--display', '41',
                     '--activity-exclude-from-recents', '-n', GAME + '/.Game'],
                ], actual)
                return result, game_on.call_count

    def test_not_ready_resumed_game_gets_one_targeted_restart(self):
        self.assertEqual((True, 1), self.prelaunch([False, True]))

    def test_persistent_not_ready_fails_after_one_restart(self):
        self.assertEqual((False, 0), self.prelaunch([False, False]))

    def test_timeout_reports_frame_size_and_last_input_reply(self):
        messages = []
        with patch.object(runner.time, 'time', side_effect=[0, 0, 91]), \
             patch.object(runner.time, 'sleep'), \
             patch.object(runner, '_display_frame_bytes', return_value=24000), \
             patch.object(runner.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=b'waiting\n')), \
             patch.object(runner, 'log', side_effect=messages.append):
            self.assertFalse(runner._wait_for_frame(PREFIX))
        self.assertIn('screen_bytes=24000', messages[-1])
        self.assertIn("response=b'waiting'", messages[-1])


if __name__ == '__main__':
    unittest.main()
