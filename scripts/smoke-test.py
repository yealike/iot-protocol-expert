import urllib.error,urllib.request,json,time
from pathlib import Path
import argparse
parser=argparse.ArgumentParser(description='Run mutating charge smoke checks against an isolated simulator')
parser.add_argument('--base-url',required=True)
args=parser.parse_args()
base=args.base_url.rstrip('/')+'/api'
def req(path,body=None):
    data=None if body is None else json.dumps(body).encode()
    try:
        r=urllib.request.urlopen(urllib.request.Request(base+path,data,{'Content-Type':'application/json'}),timeout=60)
        return json.load(r)
    except urllib.error.HTTPError as e:
        detail=e.read().decode('utf-8',errors='replace')
        raise RuntimeError(f'{path}: HTTP {e.code} {detail}') from None
def wait_for_state(predicate,description,timeout_seconds=60):
    deadline=time.monotonic()+timeout_seconds
    while True:
        state=req('/state')
        if predicate(state):
            return state
        if time.monotonic()>=deadline:
            raise AssertionError(
                f'{description}; timeout={timeout_seconds}s, '
                f'pendingTransactions={state["pendingTransactions"]}, '
                f'lastError={state.get("lastError")}')
        time.sleep(.5)
def command_data(response):
    assert response['success'],response
    payload=response.get('message')
    if isinstance(payload,str):
        payload=json.loads(payload)
    assert isinstance(payload,dict) and payload.get('success'),response
    return payload.get('data',{})
def transaction_numbers(state):
    return {item['transactionNo'] for item in state['history']}
s=wait_for_state(lambda state:state['loggedIn'],'simulator login')
print('login + rate',s['rateModel']['modelNo'])
for g in s['guns']:
    if g['stateCode']==3:req('/guns/'+g['gunNo']+'/stop',{})
req('/platform/devices/'+s['pileCode']+'/param-set',{'allowWork':True,'maxPowerPercent':100})
req('/clock',{'time':'2026-11-09T09:59:00','speed':0})
req('/guns/01/plug',{})
history_before=transaction_numbers(req('/state'))
r=req('/platform/devices/'+s['pileCode']+'/start',{'gunNo':'01','physicalCard':'00000000D14B0A54'})
assert r['success'],r
s=req('/clock',{'advanceSeconds':124})
assert s['guns'][0]['energy']==2.5,s['guns'][0]
r=req('/platform/devices/'+s['pileCode']+'/stop',{'gunNo':'01'})
assert r['success'],r
s=wait_for_state(
    lambda state:not state['pendingTransactions'] and bool(transaction_numbers(state)-history_before),
    'remote stop bill was not acknowledged')
print('remote start, cross period, stop, ack',s['history'][0]['totalAmount'])
r=req('/platform/devices/'+s['pileCode']+'/read-realtime',{'gunNo':'01'})
realtime=command_data(r)['realtime']
assert realtime['gunNo']=='01',realtime
r=req('/platform/devices/'+s['pileCode']+'/param-set',{'allowWork':True,'maxPowerPercent':50});assert r['success'],r
r=req('/platform/devices/'+s['pileCode']+'/rate-model',{});assert r['success'],r
r=req('/platform/devices/'+s['pileCode']+'/cards/sync',{'cards':[{'logicalCard':'0000000010000001','physicalCard':'00000000D14B0A54'}]});assert r['success'],r
r=req('/platform/devices/'+s['pileCode']+'/cards/query',{'physicalCards':['00000000D14B0A54','0000000000000000']})
assert command_data(r)['count']==2,r
req('/disconnect',{});time.sleep(.2)
req('/guns/01/offline-start',{})
req('/clock',{'advanceSeconds':64});req('/guns/01/stop',{})
assert len(req('/state')['pendingTransactions'])==1
req('/connect',{})
s=wait_for_state(
    lambda state:state['loggedIn'] and not state['pendingTransactions'],
    'offline bill was not replayed after reconnect')
print('offline card + durable outbox replay OK')
req('/guns/02/plug',{})
r=req('/parallel-start',{})
s=wait_for_state(
    lambda state:all(gun['stateCode']==3 for gun in state['guns']),
    'parallel start did not reach charging state')
s=req('/clock',{'advanceSeconds':64});assert all(g['stateCode']==3 for g in s['guns']),s['guns']
history_before=transaction_numbers(s)
req('/guns/01/stop',{});req('/guns/02/stop',{})
s=wait_for_state(
    lambda state:not state['pendingTransactions'] and len(transaction_numbers(state)-history_before)>=2,
    'parallel per-gun bills were not acknowledged')
print('remote parallel per-gun billing OK')
logs=req('/logs');assert logs and any('decoded' in f for f in logs),'decoder log missing'
print('All core TCP smoke checks passed')
