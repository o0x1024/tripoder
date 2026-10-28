import subprocess, json, time, sys, uuid
S = 'd861a4fa'; HOST = 'dev.tripoder.cmbq'; TEST = 'dev.tripoder.cmbqt'
def adb(*a, check=True, timeout=60):
    return subprocess.run(['adb', '-s', S, 'shell', *a], capture_output=True, text=True, timeout=timeout, check=check)
run = 'control-' + uuid.uuid4().hex[:16]
adb('am', 'force-stop', HOST); adb('am', 'force-stop', TEST)
cmd = ['adb', '-s', S, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'runId', run,
       '-e', 'retryEngineOnce', 'true', '-e', 'customPackage', 'cmb.pb',
       '-e', 'customSha256', 'd01869749503636d6c776fe6c1c3f5eac945bbdaf38e43167cfa87305c1cfe1d',
       '-e', 'customSource', '/data/app/~~xROLXf_I5o6ibDEWy2nXCA==/cmb.pb-V7YpkIs3Gk33GtmNQK5QWQ==/base.apk',
       '-e', 'stabilitySeconds', '30', '-e', 'capture', 'false', '-e', 'stopAfterTest', 'true',
       TEST + '/dev.tripoder.controltest.TargetLaunchIntegration']
print('RUN', run, flush=True)
p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
until = time.monotonic() + 40; started = False
while p.poll() is None and time.monotonic() < until:
    r = adb('cat', f'/sdcard/Android/data/{HOST}/files/launch-workbench-{run}.json', check=False)
    if r.returncode == 0:
        print('SIGNAL', r.stdout, flush=True)
        adb('am', 'start', '-W', '-n', f'{HOST}/dev.tripoder.hooklab.WorkbenchActivity', check=False)
        started = True; break
    time.sleep(0.2)
print('WORKBENCH_STARTED', started, flush=True)
try:
    out, err = p.communicate(timeout=420)
except subprocess.TimeoutExpired:
    p.kill(); out, err = p.communicate(); print('TIMEOUT', flush=True)
print('STDOUT', out[-1500:], flush=True); print('STDERR', err[-500:], flush=True)
r = adb('cat', f'/sdcard/Android/data/{HOST}/files/result-{run}.json', check=False)
print('RESULT_RC', r.returncode); print(r.stdout[:3000])
