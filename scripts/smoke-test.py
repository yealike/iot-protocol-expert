import urllib.request,json,time
from pathlib import Path
import argparse
parser=argparse.ArgumentParser(description='Run mutating charge smoke checks against an isolated simulator')
parser.add_argument('--base-url',required=True)
args=parser.parse_args()
base=args.base_url.rstrip('/')+'/api'
def req(path,body=None):
    data=None if body is None else json.dumps(body).encode()
    r=urllib.request.urlopen(urllib.request.Request(base+path,data,{'Content-Type':'application/json'}),timeout=25)
    return json.load(r)
for _ in range(20):
    s=req('/state')
    if s['loggedIn']:break
    time.sleep(.25)
assert s['loggedIn'],s['lastError']
print('login + rate',s['rateModel']['modelNo'])
for g in s['guns']:
    if g['stateCode']==3:req('/guns/'+g['gunNo']+'/stop',{})
req('/platform/devices/'+s['pileCode']+'/param-set',{'allowWork':True,'maxPowerPercent':100})
req('/clock',{'time':'2026-11-09T09:59:00','speed':0})
req('/guns/01/plug',{})
r=req('/platform/devices/'+s['pileCode']+'/start',{'gunNo':'01','physicalCard':'00000000D14B0A54'})
assert r['success'],r
s=req('/clock',{'advanceSeconds':124})
assert s['guns'][0]['energy']==2.5,s['guns'][0]
r=req('/platform/devices/'+s['pileCode']+'/stop',{'gunNo':'01'})
assert r['success'],r
time.sleep(.3)
s=req('/state');assert len(s['pendingTransactions'])==0,s['pendingTransactions']
print('remote start, cross period, stop, ack',s['history'][0]['totalAmount'])
r=req('/platform/devices/'+s['pileCode']+'/read-realtime',{'gunNo':'01'})
assert r['gunNo']=='01',r
r=req('/platform/devices/'+s['pileCode']+'/param-set',{'allowWork':True,'maxPowerPercent':50});assert r['success'],r
req('/platform/devices/'+s['pileCode']+'/rate-model',{})
r=req('/platform/devices/'+s['pileCode']+'/cards/sync',{'cards':[{'logicalCard':'0000000010000001','physicalCard':'00000000D14B0A54'}]});assert r['success'],r
r=req('/platform/devices/'+s['pileCode']+'/cards/query',{'physicalCards':['00000000D14B0A54','0000000000000000']});assert [v['exists'] for v in r['results']]==[True,False],r
req('/disconnect',{});time.sleep(.2)
req('/guns/01/offline-start',{})
req('/clock',{'advanceSeconds':64});req('/guns/01/stop',{})
assert len(req('/state')['pendingTransactions'])==1
req('/connect',{})
for _ in range(30):
    s=req('/state')
    if s['loggedIn'] and not s['pendingTransactions']:break
    time.sleep(.2)
assert not s['pendingTransactions'],s
print('offline card + durable outbox replay OK')
req('/guns/02/plug',{})
r=req('/platform/devices/'+s['pileCode']+'/parallel-start',{})
s=req('/clock',{'advanceSeconds':64});assert all(g['stateCode']==3 for g in s['guns']),s['guns']
req('/guns/01/stop',{});req('/guns/02/stop',{})
print('remote parallel per-gun billing OK')
logs=req('/logs');assert any('decoded' in f for f in logs),logs[0]
print('All core TCP smoke checks passed')
