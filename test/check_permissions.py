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
assert config.get(A+'isAccessibilityTool') == 'false'
assert 'flagRetrieveInteractiveWindows' not in config.get(A+'accessibilityFlags','')
source = '\n'.join(p.read_text() for p in (SRC/'java').rglob('*.java'))
urls = set(re.findall(r'https?://[^"\s]+', source))
assert urls == {'https://api.siliconflow.cn/v1/chat/completions','https://i.chaoxing.com/base'}, urls
for forbidden in ['MediaProjection', 'takeScreenshot(', 'PixelCopy', 'ImageReader', 'VirtualDisplay', 'Bitmap.createBitmap(', 'getDrawingCache(', 'getDisplayMedia', 'TYPE_ACCESSIBILITY_OVERLAY', 'TYPE_APPLICATION_OVERLAY', 'TYPE_SYSTEM_ALERT', 'TYPE_PHONE', 'SYSTEM_ALERT_WINDOW', 'ACTION_MANAGE_OVERLAY_PERMISSION', 'FLAG_ACTIVITY_LAUNCH_ADJACENT', 'enterPictureInPictureMode(', 'setLaunchWindowingMode(', 'setLaunchBounds(', 'Runtime.getRuntime(', 'System.loadLibrary(', 'DexClassLoader', 'addJavascriptInterface(', 'Log.d(', 'Log.e(']:
    assert forbidden not in source, forbidden
assert 'setInstanceFollowRedirects(false)' in source
assert 'setHostnameVerifier' not in source and 'setSSLSocketFactory' not in source
activities={item.get(A+'name'):item for item in app.findall('activity')}
assert activities['.WebActivity'].get(A+'exported')=='false'
web=(SRC/'java/cn/local/quizhelper/WebActivity.java').read_text()
assert 'settings.setAllowFileAccess(false)' in web and 'settings.setAllowContentAccess(false)' in web
assert 'WebSettings.MIXED_CONTENT_NEVER_ALLOW' in web and 'handler.cancel()' in web
assert 'WebAddress.allowed' in web and 'WebAddress.resource' in web
assert 'ModelClient.' not in web and 'KeyVault' not in web
assert 'R.raw.web_read' in web and 'WebView.setWebContentsDebuggingEnabled(false)' in web
assert 'WebAddress.upgrade(target)' in web and '"GET".equals(request.getMethod())' in web
assert 'view.loadUrl(upgraded)' in web
assert 'settings.setUseWideViewPort(true)' in web and 'settings.setLoadWithOverviewMode(true)' in web
assert 'settings.setBuiltInZoomControls(true)' in web and 'settings.setSupportZoom(true)' in web
for forbidden in ['getRequestHeaders(', 'getCookie(', 'getHttpAuthUsernamePassword(', '.postUrl(']:
    assert forbidden not in web, forbidden
client=(SRC/'java/cn/local/quizhelper/ModelClient.java').read_text()
assert set(re.findall(r'https?://[^"\s]+',client))=={'https://api.siliconflow.cn/v1/chat/completions'}
runner=(SRC/'java/cn/local/quizhelper/WebAnswerRunner.java').read_text()
assert 'new KeyVault(context).load()' in runner and 'ModelClient.call(session.api,q)' in runner
assert '.put("key"' not in runner and '.put("api_key"' not in runner and '.put("model"' not in runner
assert runner.count('session.api.key')==1 and 'session.api.key=""' in runner
assert 'stopAnswers(' in web.split('onPause()',1)[1]
script=(SRC/'res/raw/web_read.js').read_text()
assert script.count('row.click()')==1
for forbidden in ['.submit(', '.requestSubmit(', 'fetch(', 'XMLHttpRequest', '.value', 'getDisplayMedia', 'getUserMedia', 'navigator.mediaDevices', 'html2canvas', 'toDataURL(', 'captureStream(']:
    assert forbidden not in script, forbidden
reader=(SRC/'java/cn/local/quizhelper/PageReader.java').read_text()
assert reader.count('service.dispatchGesture(')==1
assert 'expected.signature.equals(signature(n))' in reader
vault=(SRC/'java/cn/local/quizhelper/KeyVault.java').read_text()
assert '"AndroidKeyStore"' in vault and 'SecretBox.seal(key(true), value)' in vault
assert '.putString("ciphertext", encrypted)' in vault
assert 'setRandomizedEncryptionRequired(true)' in vault
print('Manifest, capture/overlay/split-screen capability and endpoint checks passed.')
