"""Finite capability gate; ONLY the disposable ChildWatchHomeTest emulator."""
import argparse, atexit, json, os, re, subprocess, time
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',default='emulator-5582')
args=parser.parse_args()
assert re.fullmatch(r'emulator-\d+',args.serial), 'Physical devices forbidden'
root=Path(__file__).resolve().parent.parent
out=root/'.runtime/assistant-boot-lab';out.mkdir(parents=True,exist_ok=True)
adb=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platform-tools/adb.exe'
pkg='ru.example.parentwatch.debug'
prefix='ru.example.parentwatch.debug.'
def stage(text):print(time.strftime('[%H:%M:%S] ')+text,flush=True)
def run(*command,optional=False):
    r=subprocess.run([str(adb),'-s',args.serial,*command],capture_output=True,timeout=25)
    if not optional and r.returncode:raise RuntimeError('ADB operation failed: '+command[0])
    return r.stdout.decode('utf-8',errors='replace') if r.returncode==0 else ''
def save(name,*command):
    data=run(*command);(out/name).write_text(data,encoding='utf-8');return data
def component(name,state='enable'):
    run('shell','run-as',pkg,'pm',state,pkg+'/'+prefix+name)
assert run('shell','getprop','ro.hardware').strip() in ('ranchu','goldfish')
assert run('emu','avd','name').splitlines()[0].strip()=='ChildWatchHomeTest'
policy=save('device-policy-before.txt','shell','dumpsys','device_policy')
assert not re.search(r'^\s*(Device Owner:|Profile Owner \()',policy,re.M)
stage('Dedicated emulator verified; physical phones excluded')
for permission in ('ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION','ACCESS_BACKGROUND_LOCATION','CAMERA','RECORD_AUDIO','POST_NOTIFICATIONS'):
    run('shell','pm','grant',pkg,'android.permission.'+permission)
for name in ('HomeRecoveryProbeActivity','HomeProbeBootReceiver'):
    component(name,'disable')
component('FamilyAssistantService','disable')  # Lab role must bind the isolated subclass, not production.
homes=run('shell','cmd','package','query-activities','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
normal=[x.strip() for x in homes.splitlines() if x.strip().startswith(('com.android.launcher3/','com.google.android.apps.nexuslauncher/'))]
assert len(normal)==1,'Expected one observed Android launcher'
run('shell','cmd','package','set-home-activity',normal[0])
for name in ('AssistantLabVoiceService','FamilyAssistantSessionService','FamilyRecognitionService','AssistantCaptureProbeService','HomeProbeSetupReceiver'):
    component(name)
run('shell','am','broadcast','-a','ru.example.parentwatch.HOME_PROBE_SETUP','-n',pkg+'/'+prefix+'HomeProbeSetupReceiver')
# Initial setup is allowed before reboot; no Activity or instrumentation after reboot.
run('shell','am','start','-n',pkg+'/ru.example.parentwatch.MainActivity')
run('shell','input','keyevent','3')
run('shell','cmd','role','remove-role-holder','--user','0','android.app.role.ASSISTANT',pkg,optional=True)
run('shell','cmd','role','add-role-holder','--user','0','android.app.role.ASSISTANT',pkg)
role=save('assistant-selected-before.txt','shell','cmd','role','get-role-holders','android.app.role.ASSISTANT')
assert pkg in role
stage('Assistant selected in isolated emulator; checking real family shortcuts')
time.sleep(2)
run('shell','input','keyevent','219')
time.sleep(2)
run('shell','uiautomator','dump','/data/local/tmp/assistant-lab-ui.xml')
xml=save('assistant-ui.xml','shell','cat','/data/local/tmp/assistant-lab-ui.xml')
assert 'Открыть семейный чат' in xml and 'Закрыть' in xml,'Assistant session did not render'
run('shell','screencap','-p','/data/local/tmp/assistant-lab-ui.png')
run('pull','/data/local/tmp/assistant-lab-ui.png',str(out/'assistant-ui.png'))
run('shell','input','keyevent','4')
run('shell','input','keyevent','3')
def disable_capture():
    try:
        run('shell','am','broadcast','-a','ru.example.parentwatch.HOME_PROBE_SETUP',
            '-n',pkg+'/'+prefix+'HomeProbeSetupReceiver',optional=True)
    except (subprocess.TimeoutExpired, OSError):
        stage('Capture cleanup could not reach the isolated emulator')
atexit.register(disable_capture)
run('shell','am','broadcast','-a','ru.example.parentwatch.HOME_PROBE_SETUP','-n',pkg+'/'+prefix+'HomeProbeSetupReceiver','--ez','assistant_capture_lab','true')
time.sleep(5)
stage('Real reboot; ordinary launcher, no ChildWatch Activity or instrumentation')
run('reboot')
deadline=time.monotonic()+180
while time.monotonic()<deadline:
    if run('shell','getprop','sys.boot_completed',optional=True).strip()=='1':break
    time.sleep(2)
else:raise RuntimeError('Boot timeout')
deadline=time.monotonic()+45
while time.monotonic()<deadline:
    ready=run('shell','run-as',pkg,'cat','files/assistant-ready.json',optional=True)
    if ready.strip().startswith('{'):break
    time.sleep(1)
else:raise RuntimeError('System assistant did not become ready after boot')
(out/'ready-after-boot.json').write_text(ready,encoding='utf-8')
run('shell','input','keyevent','223')
save('voice-after-boot.txt','shell','dumpsys','voiceinteraction')
activities=save('activities-after-boot.txt','shell','dumpsys','activity','activities')
assert 'ru.example.parentwatch.MainActivity' not in activities
assert 'HomeRecoveryProbeActivity' not in activities
readyData=json.loads(ready);assert readyData['systemSelected'] and not readyData['appVisible']
save('home-after-boot.txt','shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
stage('System voice service rebound after reboot; display off, waiting for finite capture')
deadline=time.monotonic()+80
snapshot=False
while time.monotonic()<deadline:
    services=run('shell','dumpsys','activity','services',pkg)
    if not snapshot and 'AssistantCaptureProbeService' in services:
        (out/'services-during-capture.txt').write_text(services,encoding='utf-8');snapshot=True
        save('power-during-capture.txt','shell','dumpsys','power')
        save('audio-during-capture.txt','shell','dumpsys','audio')
    result=run('shell','run-as',pkg,'cat','files/assistant-probe-result.json',optional=True)
    if result.strip().startswith('{'):break
    time.sleep(1)
else:raise RuntimeError('No current ordinary-process capture result')
(out/'result.json').write_text(result,encoding='utf-8')
verdict=json.loads(result)
run('shell','am','broadcast','-a','ru.example.parentwatch.HOME_PROBE_SETUP','-n',pkg+'/'+prefix+'HomeProbeSetupReceiver')
save('logcat.txt','logcat','-d')
save('services-after-capture.txt','shell','dumpsys','activity','services',pkg)
save('activities-after-capture.txt','shell','dumpsys','activity','activities')
if verdict.get('pass'):
    photo=subprocess.run([str(adb),'-s',args.serial,'exec-out','run-as',pkg,
        'cat','files/assistant-probe-camera.jpg'],capture_output=True,timeout=25,check=True).stdout
    assert photo.startswith(b'\xff\xd8') and len(photo)==verdict['jpegBytes']
    (out/'camera.jpg').write_bytes(photo)
stage('Result: '+json.dumps(verdict))
raise SystemExit(0 if verdict.get('pass') else 1)
