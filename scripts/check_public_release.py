#!/usr/bin/env python3
"""Check publication contents, relative document links and frozen evidence."""
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
EXCLUDED = {'.git', '.run', 'node_modules', 'target', 'dist', '__pycache__', '.idea', '.reports'}


def publication_files():
    if (ROOT / '.git').exists():
        result = subprocess.run(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
                                cwd=ROOT, capture_output=True, check=True)
        return [ROOT / p for p in result.stdout.decode().split('\0') if p]
    files = []
    for directory, dirs, names in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in EXCLUDED and not (Path(directory).name == 'engineering' and d == 'runs')]
        for name in names:
            if name == '.env.local' or name.endswith(('.log', '.pyc')):
                continue
            files.append(Path(directory) / name)
    return files


def main():
    errors = []
    files = publication_files()
    required = ['LICENSE', 'THIRD_PARTY_NOTICES.md', 'README.md', 'CONTRIBUTING.md', 'SECURITY.md',
                '.github/workflows/ci.yml', 'docs/limitations.md', 'docs/release-verification.md']
    for name in required:
        if not (ROOT / name).is_file(): errors.append(f'Missing release file: {name}')
    for p in files:
        relative = p.relative_to(ROOT)
        if any(part in EXCLUDED for part in relative.parts) or p.name in {'.env.local', '.env'}:
            errors.append(f'Runtime/private artifact included: {relative}')
        if p.suffix.lower() in {'.pem', '.key', '.p12', '.jks', '.har', '.log', '.pyc'}:
            errors.append(f'Credential/log artifact included: {relative}')
        if not p.is_file():
            errors.append(f'Missing indexed file: {relative}'); continue
        if p.suffix == '.md' and relative.parts[0] != 'licenses':
            text = p.read_text()
            if re.search(r'/Users/|/Volumes/', text): errors.append(f'Local absolute path in public documentation: {relative}')
            # Fenced code examples are not Markdown link declarations.
            text = re.sub(r'```.*?```', '', text, flags=re.S)
            for match in re.finditer(r'!?\[[^\]]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)', text):
                target = match.group(1).strip('<>')
                if urlsplit(target).scheme or target.startswith('//'): continue
                path, _, anchor = unquote(target).partition('#')
                destination = (p.parent / path).resolve() if path else p
                if not destination.exists(): errors.append(f'Broken link in {relative}: {target}'); continue
                if anchor and destination.suffix == '.md':
                    headings = re.findall(r'^#{1,6}\s+(.+)$', destination.read_text(), flags=re.M)
                    anchors = {re.sub(r'[^\w\s-]', '', h.lower()).strip().replace(' ', '-') for h in headings}
                    if anchor not in anchors: errors.append(f'Broken heading in {relative}: {target}')
    baseline = ROOT / 'docs/migrations.sha256.json'
    if baseline.exists():
        for name, expected in json.loads(baseline.read_text()).items():
            p = ROOT / name
            if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest() != expected:
                errors.append(f'Frozen migration changed: {name}')
    manifest = ROOT / 'frontend/public/engineering/manifest.json'
    if manifest.exists():
        for item in json.loads(manifest.read_text()):
            source = ROOT / item['source']; public = ROOT / 'frontend/public' / item['url'].lstrip('/')
            if not source.is_file() or not public.is_file(): errors.append(f'Missing engineering report: {item["case"]}'); continue
            if source.read_bytes() != public.read_bytes() or hashlib.sha256(source.read_bytes()).hexdigest() != item['sha256']:
                errors.append(f'Historical report checksum mismatch: {item["case"]}')
    if errors:
        print('\n'.join(errors)); return 1
    print(f'Public release checks passed: {len(files)} files; links, artifacts, migrations and report hashes verified.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
