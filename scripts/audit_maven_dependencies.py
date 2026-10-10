#!/usr/bin/env python3
"""Resolve Maven's actual transitive dependency tree and query OSV."""
import argparse
import datetime
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default='.reports/maven-osv.json')
    args = parser.parse_args()
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    tree = output.with_suffix('.tree.txt')
    subprocess.run(['mvn', '-B', '-ntp', 'dependency:tree', f'-DoutputFile={tree}'], cwd=ROOT / 'backend', check=True)
    packages = {}
    for line in tree.read_text().splitlines():
        parts = re.sub(r'^[|+\\\- ]+', '', line).split(':')
        if len(parts) >= 5:
            packages[parts[0] + ':' + parts[1]] = parts[-2]
    if not packages: raise RuntimeError('No resolved dependencies; audit cannot claim success')
    queries = [{'package': {'ecosystem': 'Maven', 'name': name}, 'version': version} for name, version in sorted(packages.items())]
    results = []
    for start in range(0, len(queries), 100):
        batch = queries[start:start + 100]
        request = urllib.request.Request('https://api.osv.dev/v1/querybatch', data=json.dumps({'queries': batch}).encode(),
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=60) as response:
            answer = json.load(response)['results']
        if len(answer) != len(batch): raise RuntimeError('Incomplete OSV response')
        results.extend(answer)
    findings = [{'package': query['package']['name'], 'version': query['version'],
                 'ids': [item['id'] for item in result.get('vulns', [])]}
                for query, result in zip(queries, results) if result.get('vulns')]
    accepted = []
    unresolved = []
    exception_file = ROOT / 'docs/security-advisories.json'
    exceptions = json.loads(exception_file.read_text())['exceptions'] if exception_file.exists() else []
    source = '\n'.join(p.read_text(errors='replace') for directory in ['backend/src/main/java', 'backend/src/main/resources']
                       for p in (ROOT / directory).rglob('*') if p.is_file() and p.suffix in {'.java', '.yml', '.yaml', '.xml', '.properties'})
    today = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    for finding in findings:
        for advisory in finding['ids']:
            exception = next((e for e in exceptions if e['id'] == advisory and e['package'] == finding['package']
                              and e['version'] == finding['version'] and e['expiresOn'] >= today
                              and not any(marker in source for marker in e['guardAbsent'])), None)
            if exception:
                accepted.append({'package': finding['package'], 'version': finding['version'], 'id': advisory,
                                 'reason': exception['reason'], 'expiresOn': exception['expiresOn']})
            else:
                unresolved.append({'package': finding['package'], 'version': finding['version'], 'id': advisory})
    report = {'checkedAtUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'source': 'https://api.osv.dev',
              'scope': 'Maven resolved dependencies including test scope; application reachability not assessed',
              'queried': len(queries), 'findings': findings, 'reviewedNotApplicable': accepted, 'unresolved': unresolved}
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(f'OSV checked {len(queries)} Maven packages; {len(findings)} affected packages. Report: {args.output}')
    for item in findings: print(item['package'], item['version'], ', '.join(item['ids']))
    print(f'{len(accepted)} reviewed as not applicable; {len(unresolved)} unresolved advisories.')
    return 1 if unresolved else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(f'Audit failed: {error}', file=sys.stderr)
        sys.exit(2)
