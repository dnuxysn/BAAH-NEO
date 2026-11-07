"""Create NEO's private development identity, separate from Android's global debug key."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
properties = root / 'neo-signing.properties'
assert not properties.exists(), 'NEO signing identity already exists; preserve it for upgrades.'
keytool = shutil.which('keytool') or str(Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Java\jdk-21')) / 'bin/keytool.exe')
if not Path(keytool).is_file(): keytool = None
assert keytool, 'JDK keytool is required'
key = root / 'signing' / 'neo-development.p12'
key.parent.mkdir(exist_ok=True)
assert not key.exists(), 'Keystore already exists; do not replace it'
password = secrets.token_urlsafe(32)
environment = dict(os.environ, NEO_KEY_PASSWORD=password)
subprocess.run([keytool, '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12', '-alias', 'baah-neo-development',
    '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=BAAH NEO Development',
    '-storepass:env', 'NEO_KEY_PASSWORD', '-keypass:env', 'NEO_KEY_PASSWORD'], env=environment, check=True)
properties.write_text('storeFile=signing/neo-development.p12\nkeyAlias=baah-neo-development\nstorePassword=' + password + '\nkeyPassword=' + password + '\n', encoding='utf8')
print('Created independent NEO development identity. Keep signing/ and neo-signing.properties private and backed up.')
