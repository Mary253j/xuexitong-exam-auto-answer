from pathlib import Path
import re
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT/'app/src/main'
A = '{http://schemas.android.com/apk/res/android}'
manifest = E.parse(SRC/'AndroidManifest.xml').getroot()
permissions = [x.get(A+'name') for x in manifest.findall('uses-permission')]
assert permissions == ['android.permission.INTERNET'], permissions
app = manifest.find('application')
assert app.get(A+'allowBackup') == 'false'
assert app.get(A+'debuggable') == 'false'
assert app.get(A+'usesCleartextTraffic') == 'false'
services = app.findall('service')
assert len(services) == 1
assert services[0].get(A+'permission') == 'android.permission.BIND_ACCESSIBILITY_SERVICE'
assert not app.findall('receiver') and not app.findall('provider')
config = E.parse(SRC/'res/xml/accessibility_service.xml').getroot()
assert config.get(A+'packageNames') == 'com.chaoxing.mobile'
assert config.get(A+'canRetrieveWindowContent') == 'true'
assert config.get(A+'canPerformGestures') == 'true'
assert config.get(A+'canTakeScreenshot') == 'false'
assert 'flagRetrieveInteractiveWindows' not in config.get(A+'accessibilityFlags','')
source = '\n'.join(p.read_text() for p in (SRC/'java').rglob('*.java'))
urls = set(re.findall(r'https?://[^"\s]+', source))
assert urls == {'https://api.siliconflow.cn/v1/chat/completions'}, urls
for forbidden in ['MediaProjection', 'takeScreenshot(', 'Runtime.getRuntime(', 'System.loadLibrary(', 'DexClassLoader', 'WebView(', 'Log.d(', 'Log.e(']:
    assert forbidden not in source, forbidden
assert 'setInstanceFollowRedirects(false)' in source
assert 'setHostnameVerifier' not in source and 'setSSLSocketFactory' not in source
reader=(SRC/'java/cn/local/quizhelper/PageReader.java').read_text()
assert reader.count('service.dispatchGesture(')==1
assert 'expected.signature.equals(signature(n))' in reader
vault=(SRC/'java/cn/local/quizhelper/KeyVault.java').read_text()
assert '"AndroidKeyStore"' in vault and 'SecretBox.seal(key(true), value)' in vault
assert '.putString("ciphertext", encrypted)' in vault
assert 'setRandomizedEncryptionRequired(true)' in vault
print('Manifest, sensitive-capability and endpoint checks passed.')
