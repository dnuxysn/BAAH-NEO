"""A completed poll from an older run must not terminate a newly accepted run."""
import importlib.util
from pathlib import Path

source = Path(__file__).resolve().parents[1] / 'app/src/main/assets/baah-aos/wrapper.py'
spec = importlib.util.spec_from_file_location('neo_wrapper_test', source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
supervisor = module.RunnerSupervisor()
supervisor._ensure_thread = lambda: None
old_poll = supervisor.status()
supervisor.start('test-jp')
accepted = supervisor.status()
assert accepted['runner_wanted']
assert accepted['runner_generation'] > old_poll['runner_generation']
assert not old_poll['runner_wanted']
supervisor.stop()
stopped = supervisor.status()
assert not stopped['runner_wanted']
assert stopped['runner_generation'] > accepted['runner_generation']
print('Adapter run-generation regression passed')
