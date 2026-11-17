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


# Exercise the real supervisor loop without starting BAAH or sleeping.
from unittest.mock import patch


class FinishedProcess:
    def __init__(self, code, before_wait=None):
        self.code = code
        self.before_wait = before_wait
        self.pid = 100

    def poll(self):
        return self.code

    def wait(self):
        if self.before_wait:
            self.before_wait()
        return self.code


def run_supervisor(supervisor, spawn):
    def no_sleep(_seconds):
        if not supervisor.status()['runner_wanted']:
            supervisor._stop_event.set()

    supervisor._stop_event.clear()
    with patch.object(supervisor, '_spawn', side_effect=spawn):
        with patch.object(module.time, 'sleep', side_effect=no_sleep):
            supervisor._supervise()


limited = module.RunnerSupervisor()
limited._ensure_thread = lambda: None
limited.start('missing-display')
attempts = []


def missing_display(config, task):
    attempts.append(config)
    return FinishedProcess(3)


run_supervisor(limited, missing_display)
failed = limited.status()
assert len(attempts) == module.MAX_PRELAUNCH_RETRIES + 1
assert not failed['runner_wanted'] and not failed['runner_alive']
assert failed['runner_exit_code'] == 3
assert failed['runner_respawns'] == module.MAX_PRELAUNCH_RETRIES

# An exhausted attempt must not poison the next accepted run.
limited.start('next-schedule')
new_generation = limited.status()['runner_generation']
run_supervisor(limited, lambda config, task: FinishedProcess(0))
recovered = limited.status()
assert recovered['runner_generation'] == new_generation
assert recovered['runner_exit_code'] == 0 and recovered['runner_respawns'] == 0

# A late code-3 exit from an old generation must not consume the new retry budget.
switched = module.RunnerSupervisor()
switched._ensure_thread = lambda: None
switched.start('old-run')
seen = []


def change_generation(config, task):
    seen.append(config)
    if config == 'old-run':
        return FinishedProcess(3, lambda: switched.start('new-run'))
    return FinishedProcess(0)


run_supervisor(switched, change_generation)
assert seen == ['old-run', 'new-run']
assert switched.status()['runner_exit_code'] == 0
assert switched.status()['runner_respawns'] == 0
print('Prelaunch retries are bounded; later runs recover and stale exits stay isolated')
