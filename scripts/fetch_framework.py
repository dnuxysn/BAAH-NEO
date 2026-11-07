"""Fetch an exact official MaaFramework release; never run downloaded code."""
import hashlib, io, json, urllib.request, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'BAAH-NEO-dependency'})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read()
def main():
    lock = json.loads((ROOT/'framework.lock.json').read_text(encoding='utf8'))
    metadata = json.loads(fetch('https://api.github.com/repos/'+lock['repository']+'/releases/tags/'+lock['tag']))
    asset = next(item for item in metadata['assets'] if item['name'] == lock['asset'])
    expected = asset.get('digest', '')
    if not expected.startswith('sha256:'):
        raise RuntimeError('Official release has no SHA256; inspect provenance before adding a trusted digest')
    content = fetch(asset['browser_download_url'])
    digest = 'sha256:'+hashlib.sha256(content).hexdigest()
    if digest != expected:
        raise RuntimeError('Release digest mismatch')
    output = ROOT/'app/src/main/jniLibs/arm64-v8a'
    output.mkdir(parents=True,exist_ok=True)
    names = set()
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        for info in archive.infolist():
            parts = Path(info.filename).parts
            if 'bin' not in parts or 'plugins' in parts or not info.filename.endswith('.so'):
                continue
            name = Path(info.filename).name
            if name in names:
                raise RuntimeError('Duplicate native library: '+name)
            names.add(name)
            (output/name).write_bytes(archive.read(info))
        for info in archive.infolist():
            if Path(info.filename).name.lower().startswith(('license','copying')) and not info.is_dir():
                relative = Path(info.filename)
                if relative.is_absolute() or '..' in relative.parts:
                    raise RuntimeError('Invalid license path')
                target = ROOT/'third_party/MaaFramework'/relative
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(archive.read(info))
    if 'libMaaFramework.so' not in names:
        raise RuntimeError('Framework library missing')
    (ROOT/'framework.resolved.json').write_text(json.dumps({'tag':lock['tag'],'asset':lock['asset'],'digest':digest,'source':asset['browser_download_url'],'libraries':sorted(names)},indent=2)+'\n',encoding='utf8')
    print('Verified official framework',lock['tag'],len(names),'libraries')
if __name__ == '__main__':
    main()
