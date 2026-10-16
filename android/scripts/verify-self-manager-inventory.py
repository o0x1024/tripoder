#!/usr/bin/env python3
"""Reconcile actual fixed ART declarations, not an SDK-only method list."""
import argparse
import hashlib
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha

OWNERS=['android.app.ActivityManager','android.net.ConnectivityManager','android.telephony.TelephonyManager',
        'android.media.AudioManager','android.os.BatteryManager','android.os.PowerManager','android.net.wifi.p2p.WifiP2pManager','android.app.usage.NetworkStatsManager','android.app.NotificationManager','android.content.pm.ShortcutManager','android.app.JobSchedulerImpl']

def verify(directory,run_id):
    result,build,report=read(directory,run_id,'H4-fixed-manager-inventory');families=result['families']
    need([f['owner'] for f in families]==OWNERS,'FIXED_MANAGER_OWNER_SET')
    total=0
    for f in families:
        declarations=f['declarations'];need(declarations==sorted(set(declarations)) and declarations,'UNIQUE_SORTED_DECLARATIONS')
        need(hashlib.sha256(('\n'.join(declarations)+'\n').encode()).hexdigest()==f['sha256'],'FIXED_MANAGER_DECLARATION_HASH')
        instance=statics=constructors=abstracts=natives=0
        for declaration in declarations:
            name,raw=declaration.rsplit(' ',1);flags=int(raw)
            if name.startswith('<init>'):constructors+=1
            elif flags&8:statics+=1
            else:
                instance+=1;abstracts+=bool(flags&1024);natives+=bool(flags&256)
        need((instance,statics,constructors,abstracts,natives)==(f['instanceMethods'],f['staticMethods'],f['constructors'],f['abstractMethods'],f['nativeMethods']),'FIXED_MANAGER_COUNTS')
        total+=instance
    need(total==result['totalInstanceMethods'] and result['hookInstalled'] is result['productAccepted'] is result['processPolicy']['owned'] is False,'READ_ONLY_INVENTORY')
    need(result['frameworkAccess']['ready'] is True and result['frameworkAccess']['jniIdType']==0,'PINNED_FRAMEWORK_REGISTRY')
    report.update(totalInstanceMethods=total,families=[{k:v for k,v in f.items() if k!='declarations'} for f in families],
        verifierSha256=sha(Path(__file__)),scope='Exact fixed boot declared inventory only; no Hook installation or caller coverage')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
