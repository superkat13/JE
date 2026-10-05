#!/usr/bin/env python3
"""Run the bounded Sage runtime lab; never claim physical device acceptance."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

CLASSES = [
    'maintenance.SelfCareManagerTest',
    'maintenance.SageSelfCheckResponderTest',
    'runtime.SageRuntimeTest',
    'runtime.GoalCompletionPolicyTest',
    'runtime.RecoveryCompletionPolicyTest',
    'runtime.RecoveredResumeSchedulerTest',
    'continuity.TaskRecoveryManagerTest',
    'core.SageTurnCoordinatorTest',
    'speech.CommandEndpointPolicyTest',
    'speech.CommandRecognizerPolicyTest',
    'speech.CommandSpeechRegressionLabTest',
    'speech.CommandSpeechTurnOwnershipTest',
    'speech.CommandSpeechFailurePolicyTest',
    'speech.RecognitionSessionGateTest',
    'speech.WakeRecoveryBudgetTest',
    'speech.WakeReconnectPolicyTest',
    'localapi.SageLocalApiServerTest',
    'SageSherpaRecognitionServiceLifecycleTest',
]
PREFIX = 'com.pineapple.sageos2.'
# SageSherpaRecognitionServiceLifecycleTest is listed without a package because it is not under
# PREFIX. It lives in com.pineapple.sage, beside the service it drives, which is what lets it call
# RecognitionService's protected lifecycle callbacks on a real service instance. Every other entry
# resolves against PREFIX.
PACKAGE_OVERRIDES = {
    'SageSherpaRecognitionServiceLifecycleTest': 'com.pineapple.sage.',
}


def qualified(name):
    return PACKAGE_OVERRIDES.get(name, PREFIX) + name


def summarize(reports, started):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    problems = []
    for name in CLASSES:
        path = reports / ('TEST-' + qualified(name) + '.xml')
        if not path.is_file() or path.stat().st_mtime < started - 2:
            problems.append('Missing or stale report: ' + name)
            continue
        try:
            suite = ET.parse(path).getroot()
            counts = {key: int(suite.attrib.get(key, '0')) for key in totals}
            if counts['tests'] <= 0:
                problems.append('No tests executed: ' + name)
            for key in totals:
                totals[key] += counts[key]
        except (ET.ParseError, ValueError) as error:
            problems.append('Invalid report: ' + name + ': ' + str(error))
    if totals['failures'] or totals['errors'] or totals['skipped']:
        problems.append('Failures, errors, or skipped cases prevent lab acceptance.')
    return totals, problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', help='Gradle executable; otherwise installed Gradle or repository wrapper')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    project = root / 'sageos2'
    output = project / 'build' / 'self-reliance-lab'
    output.mkdir(parents=True, exist_ok=True)
    gradle = args.gradle or shutil.which('gradle') or str(root / 'gradlew')
    command = [gradle, '-p', str(project), ':app:testDebugUnitTest', '--rerun-tasks', '--no-build-cache']
    for name in CLASSES:
        command += ['--tests', qualified(name)]
    started = time.time()
    result = {'scope': 'Automated JVM/Robolectric runtime lab',
              'device_acceptance': 'NOT RUN', 'command': command,
              'started_at_epoch': started, 'passed': False}
    try:
        result['exit_code'] = subprocess.call(command, cwd=root)
        totals, problems = summarize(project / 'app/build/test-results/testDebugUnitTest', started)
        result.update(totals=totals, problems=problems)
        result['passed'] = result['exit_code'] == 0 and not problems
    except OSError as error:
        result['problems'] = [str(error)]
    report = output / 'result.json'
    report.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    print('Lab report: ' + str(report))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
