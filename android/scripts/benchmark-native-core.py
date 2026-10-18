#!/usr/bin/env python3
"""Alternating owned-probe builds. Preserve raw samples; never declare a noisy run a speedup."""
import argparse,hashlib,json,re,statistics,struct,subprocess,time,uuid
from pathlib import Path
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--baseline-root',type=Path,required=True)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--rounds',type=int,default=5);p.add_argument('--cpu',type=int,default=5)
    p.add_argument('--precondition-rounds',type=int,default=0,help='Declared baseline/current warmup pairs, retained separately before the five measured rounds');a=p.parse_args()
    if a.rounds<5:raise ValueError('FIVE_ROUNDS_REQUIRED')
    if not 0<=a.precondition_rounds<=2 or not 0<=a.cpu<256:raise ValueError('BENCH_PRECONDITION_OR_CPU_RANGE')
    root=Path(__file__).resolve().parents[2];out=a.evidence.resolve();out.mkdir(parents=True,exist_ok=False)
    roots={'baseline':a.baseline_root.resolve(),'current':root};receipts={};artifacts={}
    benchmark_names=['android/self-hook-probe/native/probe_native_benchmark.c','android/self-hook-probe/src/dev/tripoder/runtime/OwnedNativeBenchmarkChecks.java',
        'android/self-hook-probe/src/dev/tripoder/selfhookprobe/NativeBenchmark.java','android/self-hook-probe/src/dev/tripoder/selfhookprobe/NativeBenchmarkProbeActivity.java']
    for name in benchmark_names:
        if sha(roots['baseline']/name)!=sha(root/name):raise RuntimeError('BENCHMARK_SOURCE_CHANGED:'+name)
    for label,source in roots.items():
        build=source/'android/self-hook-probe/build';receipt=json.loads((build/'build-receipt.json').read_text())
        for name,digest in receipt['sources'].items():
            if sha(source/name)!=digest:raise RuntimeError('BUILD_SOURCE_CHANGED:'+label+':'+name)
        if sha(build/'self-hook-probe.apk')!=receipt['apkSha256']:raise RuntimeError('BUILD_APK_CHANGED')
        receipts[label]=receipt;dest=out/label;dest.mkdir()
        import shutil
        for name in ['self-hook-probe.apk','libtripoderselfhook.so','build-receipt.json']:shutil.copyfile(build/name,dest/name)
        artifacts[label]=dest/'self-hook-probe.apk'
    def adb(*args,check=True):
        return subprocess.run(['adb','-s',a.serial,*args],capture_output=True,text=True,check=check,timeout=60)
    def frequency():
        snapshot={'hostMonotonicNs':time.monotonic_ns(),'cpu':a.cpu,'values':{},'errors':{}}
        for name in ['scaling_cur_freq','scaling_min_freq','scaling_max_freq','cpuinfo_max_freq','scaling_governor','related_cpus']:
            observed=adb('shell','cat',f'/sys/devices/system/cpu/cpu{a.cpu}/cpufreq/'+name,check=False)
            if observed.returncode:snapshot['errors'][name]=observed.stderr.strip() or observed.stdout.strip()
            else:snapshot['values'][name]=observed.stdout.strip()
        return snapshot
    results=[];package='dev.tripoder.selfhookprobe'
    for round_index in range(-a.precondition_rounds,a.rounds):
        phase='precondition' if round_index<0 else 'measurement'
        for label in (['baseline','current'] if round_index%2==0 else ['current','baseline']):
            run=str(uuid.uuid4());ordinal=round_index+a.precondition_rounds+1 if round_index<0 else round_index+1
            directory=out/f'{phase}-{ordinal}-{label}-{run}';directory.mkdir()
            adb('shell','am','force-stop',package);installed=adb('install','--no-incremental','-r',str(artifacts[label]));(directory/'install.txt').write_text(installed.stdout+installed.stderr)
            (directory/'battery.txt').write_text(adb('shell','dumpsys','battery').stdout)
            (directory/'thermal-before.txt').write_text(adb('shell','dumpsys','thermalservice').stdout)
            frequency_before=frequency();(directory/'frequency-before.json').write_text(json.dumps(frequency_before,indent=2)+'\n')
            launch=adb('shell','am','start','-W','-f','0x10008000','-n',package+'/.NativeBenchmarkProbeActivity','--es','runId',run,'--es','artCompilation','native','--ei','benchmarkCpu',str(a.cpu))
            (directory/'launch.txt').write_text(launch.stdout+launch.stderr)
            if 'LaunchState: COLD' not in launch.stdout:raise RuntimeError('BENCH_COLD_PROCESS_REQUIRED')
            remote='/sdcard/Android/data/'+package+'/files/'+run;deadline=time.monotonic()+120
            while adb('shell','test','-f',remote+'/result.json',check=False).returncode:
                if time.monotonic()>deadline:raise RuntimeError('BENCH_TIMEOUT')
                if adb('shell','pidof',package,check=False).returncode:raise RuntimeError('BENCH_PROCESS_DIED')
                time.sleep(0.5)
            adb('pull',remote,str(directory/'device'));value=json.loads((directory/'device/result.json').read_text());receipt=receipts[label]
            frequency_after=frequency();(directory/'frequency-after.json').write_text(json.dumps(frequency_after,indent=2)+'\n')
            (directory/'thermal-after.txt').write_text(adb('shell','dumpsys','thermalservice').stdout)
            if value.get('passed') is not True or value['runId']!=run or value['apkSha256']!=receipt['apkSha256'] or value['nativeSha256']!=receipt['nativeSha256']:
                raise RuntimeError('BENCH_RESULT:'+str(value.get('error')))
            data=value['benchmark']
            if data.get('workerCpu')!=a.cpu or data.get('affinityVerifiedAfterSamples') is not True:raise RuntimeError('BENCH_AFFINITY_EVIDENCE')
            if data.get('diagnosticInstrumentation') is not False or data.get('sampleBuffersPrefaulted') is not True or data.get('artifactWritesAfterMeasurements') is not True:raise RuntimeError('BENCH_MEASUREMENT_PROTOCOL')
            for name in ['directC','hookC','jniBaseline','jniHook','foreignJniHook','productRule']:
                info=data[name];raw=directory/'device'/info['rawFile']
                if sha(raw)!=info['sha256']:raise RuntimeError('BENCH_RAW_SHA')
                samples=struct.unpack('>'+str(info['count'])+'q',raw.read_bytes());ordered=sorted(samples);batch=info['callsPerSample']
                expected={'meanNs':sum(samples)/len(samples)/batch,'p50Ns':ordered[len(samples)//2]/batch,'p95Ns':ordered[len(samples)*95//100]/batch,'p99Ns':ordered[len(samples)*99//100]/batch}
                if any(abs(info[k]-v)>0.000001 for k,v in expected.items()):raise RuntimeError('BENCH_SUMMARY_MISMATCH')
            mem=adb('shell','dumpsys','meminfo',str(value['pid'])).stdout;(directory/'meminfo.txt').write_text(mem)
            match=re.search(r'TOTAL PSS:\s*(\d+)',mem);data['finalPssKb']=int(match[1]) if match else None
            result={'phase':phase,'round':ordinal,'version':label,'runId':run,'apkSha256':value['apkSha256'],'nativeSha256':value['nativeSha256'],'data':data,'frequencyBefore':frequency_before,'frequencyAfter':frequency_after}
            (directory/'measurement.json').write_text(json.dumps(result,indent=2)+'\n');results.append(result)
            print(json.dumps({'phase':phase,'round':ordinal,'version':label,'hookCMeanNs':data['hookC']['meanNs'],'ruleMeanNs':data['productRule']['meanNs']}),flush=True)
    measured=[r for r in results if r['phase']=='measurement']
    envelopes=[r[key] for r in measured for key in ['frequencyBefore','frequencyAfter']]
    policy_keys=['scaling_min_freq','scaling_max_freq','cpuinfo_max_freq','scaling_governor','related_cpus']
    envelope_consistent=all(not e['errors'] and all(e['values'].get(key) for key in policy_keys) for e in envelopes) and len({tuple(e['values'].get(key) for key in policy_keys) for e in envelopes})==1
    comparisons={};noisy=False;regressed=False
    for path in ['hookC','jniHook','foreignJniHook','productRule']:
        comparisons[path]={}
        for metric in ['meanNs','p95Ns','p99Ns']:
            series={v:[r['data'][path][metric] for r in measured if r['version']==v] for v in roots}
            med={v:statistics.median(values) for v,values in series.items()}
            spread={v:(max(values)-min(values))/med[v]*100 for v,values in series.items()}
            change=(med['current']/med['baseline']-1)*100
            noisy|=spread['baseline']>5;regressed|=change>5
            comparisons[path][metric]={'baselineMedian':med['baseline'],'currentMedian':med['current'],'changePercent':change,'spreadPercent':spread}
    report={'schema':2,'rounds':a.rounds,'preconditionRounds':a.precondition_rounds,'requestedCpu':a.cpu,'benchmarkSourceSha256':{n:sha(root/n) for n in benchmark_names},'comparisons':comparisons,
        'clockOverheadIncluded':True,'cQuantilesDescribe64CallBatches':True,'baselineNoiseExceedsFivePercent':noisy,'medianRegressionExceedsFivePercent':regressed,
        'frequencyPolicyEnvelopeUnchanged':envelope_consistent,'frequencyConstantWithinSamplesProven':False,
        'performanceAccepted':not noisy and not regressed and envelope_consistent,'productAccepted':False,'results':results}
    (out/'comparison.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'report':str(out/'comparison.json'),'performanceAccepted':report['performanceAccepted']}))
if __name__=='__main__':main()
