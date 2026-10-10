#!/usr/bin/env python3
"""Run four opt-in suites against a new owned synthetic MySQL container."""
import argparse
import datetime
import json
import os
import secrets
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SUITES = [('StoredValueMySqlIntegrationTest', 'stored.value'),
          ('StoredValueOrderPaymentMySqlIntegrationTest', 'stored.value.order'),
          ('StoredValueApprovalMySqlIntegrationTest', 'stored.value.approval'),
          ('StoredValueReconciliationMySqlIntegrationTest', 'stored.value.reconciliation')]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', default='stored-value-ci-' + uuid.uuid4().hex[:10])
    args = parser.parse_args()
    if not all(c.isalnum() or c == '-' for c in args.run): raise ValueError('Invalid run name')
    output = ROOT / '.run' / args.run
    output.mkdir(parents=True, exist_ok=False)
    password = secrets.token_hex(24)
    environment = {**os.environ, 'MYSQL_ROOT_PASSWORD': password}
    cid = subprocess.check_output(['docker', 'run', '-d', '--label', 'shiguang.verify=' + args.run,
                                   '-p', '127.0.0.1::3306', '-e', 'MYSQL_ROOT_PASSWORD', '-e', 'TZ=Asia/Shanghai',
                                   'mysql:8.4', '--log-bin-trust-function-creators=1'], env=environment, text=True).strip()
    results = []
    try:
        def sql(statement):
            result = subprocess.run(['docker', 'exec', '-i', cid, 'sh', '-c',
                                     'read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -uroot -N -B'],
                                    input=password + '\n' + statement + '\n', text=True, capture_output=True)
            if result.returncode: raise RuntimeError(result.stderr.replace(password, '<secret>'))
            return result.stdout.strip()
        for _ in range(90):
            try: sql('select 1'); break
            except RuntimeError: time.sleep(1)
        else: raise RuntimeError('Owned MySQL did not become ready')
        port = subprocess.check_output(['docker', 'port', cid, '3306'], text=True).strip().split(':')[-1]
        for index, (suite, prefix) in enumerate(SUITES):
            database = f'check_{index}_{uuid.uuid4().hex[:12]}'
            sql(f'CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
            properties = output / (suite + '.properties')
            properties.write_text(f'{prefix}.jdbc-url=jdbc:mysql://127.0.0.1:{port}/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\n'
                                  f'{prefix}.jdbc-user=root\n{prefix}.jdbc-password={password}\n')
            properties.chmod(0o600)
            started = time.time()
            # MySQL, JDBC and the DEV accounting fixtures use Asia/Shanghai.
            # Pin the test JVM too: hosted runners otherwise default to UTC.
            result = subprocess.run(['mvn', '-B', '-ntp', '-Duser.timezone=Asia/Shanghai',
                                     f'-Dtest={suite}', f'-D{prefix}.integration=true',
                                     '-Dsurefire.systemPropertiesFile=' + str(properties), 'test'],
                                    cwd=ROOT / 'backend', capture_output=True, text=True)
            log = (result.stdout + result.stderr).replace(password, '<secret>').replace(str(ROOT), '<repo>')
            (output / (suite + '.log')).write_text(log)
            xml = ROOT / 'backend/target/surefire-reports' / ('TEST-com.example.venue.storedvalue.' + suite + '.xml')
            if not xml.exists() or xml.stat().st_mtime < started: raise RuntimeError(f'{suite}: missing or stale XML')
            report = ET.parse(xml).getroot()
            counts = {key: int(report.attrib[key]) for key in ['tests', 'failures', 'errors', 'skipped']}
            passed = result.returncode == 0 and counts['tests'] > 0 and not any(counts[k] for k in ['failures', 'errors', 'skipped'])
            results.append({'suite': suite, 'passed': passed, **counts})
            print(json.dumps(results[-1]), flush=True)
            if not passed: raise RuntimeError(f'{suite}: integration failed; see local report')
        (output / 'summary.json').write_text(json.dumps({'checkedAtUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                                       'passed': True, 'suites': results}, indent=2) + '\n')
        print(f'All opt-in suites passed. Report: .run/{args.run}/summary.json')
        return 0
    finally:
        labels = json.loads(subprocess.check_output(['docker', 'inspect', cid], text=True))[0]['Config']['Labels']
        if labels.get('shiguang.verify') == args.run:
            subprocess.run(['docker', 'rm', '-fv', cid], check=True, stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    sys.exit(main())
