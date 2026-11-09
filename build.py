#!/usr/bin/env python3
"""Build a dependency-free APK using Android SDK 35 and the JDK compiler."""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent

def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--platform-jar', required=True, type=Path)
    parser.add_argument('--build-tools', required=True, type=Path)
    args = parser.parse_args()
    platform = args.platform_jar.resolve()
    tools = args.build_tools.resolve()
    java = shutil.which('java')
    keytool = shutil.which('keytool')
    if not java or not keytool:
        raise SystemExit('JDK 17+ with java, keytool and jdk.compiler is required.')
    out = ROOT / 'build'
    if out.exists(): shutil.rmtree(out)
    out.mkdir()
    for part in ['generated', 'classes', 'dex']: (out / part).mkdir()
    main_src = ROOT / 'app/src/main'
    run(tools/'aapt2', 'compile', '--dir', main_src/'res', '-o', out/'resources.zip')
    run(tools/'aapt2', 'link', '-I', platform, '--manifest', main_src/'AndroidManifest.xml',
        '--java', out/'generated', '--min-sdk-version', '26', '--target-sdk-version', '35',
        '-o', out/'resources.apk', out/'resources.zip')
    sources = sorted((main_src/'java').rglob('*.java')) + sorted((out/'generated').rglob('*.java'))
    run(java, 'com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-Xlint:-options', '-bootclasspath', str(platform)+os.pathsep+str(tools/'core-lambda-stubs.jar'),
        '-d', out/'classes', *sources)
    classes = sorted((out/'classes').rglob('*.class'))
    with zipfile.ZipFile(out/'classes.jar', 'w') as archive:
        for f in classes: archive.write(f, str(f.relative_to(out/'classes')))
    run(tools/'d8', '--release', '--min-api', '26', '--lib', platform,
        '--output', out/'dex', out/'classes.jar')
    shutil.copy2(out/'resources.apk', out/'unsigned.apk')
    with zipfile.ZipFile(out/'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
        for f in (out/'dex').glob('*.dex'): archive.write(f, f.name)
    run(tools/'zipalign', '-f', '-p', '4', out/'unsigned.apk', out/'aligned.apk')

    private = ROOT / '.private'
    private.mkdir(mode=0o700, exist_ok=True)
    password = private/'signing-password'
    store = private/'local-signing.p12'
    if not store.exists():
        password.write_text(secrets.token_urlsafe(32), encoding='ascii')
        password.chmod(0o600)
        run(keytool, '-genkeypair', '-alias', 'local-build', '-keystore', store, '-storetype', 'PKCS12',
            '-storepass:file', password, '-keypass:file', password,
            '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650',
            '-dname', 'CN=Quiz Helper Local Build, O=Personal Build, C=CN')
        store.chmod(0o600)
    manifest = ET.parse(main_src/'AndroidManifest.xml').getroot()
    version = manifest.get('{http://schemas.android.com/apk/res/android}versionName')
    apk = out/f'quizhelper-{version}.apk'
    run(tools/'apksigner', 'sign', '--ks', store, '--ks-key-alias', 'local-build',
        '--ks-pass', 'file:'+str(password),
        '--out', apk, out/'aligned.apk')
    run(tools/'apksigner', 'verify', '--verbose', '--print-certs', apk)
    run(tools/'zipalign', '-c', '-v', '4', apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (out/'SHA256SUMS.txt').write_text(f'{digest}  {apk.name}\n', encoding='ascii')
    print('APK:', apk)
    print('SHA-256:', digest)

if __name__ == '__main__': main()
