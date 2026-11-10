"""Historical server acceptance helper. For the current local Spring setup use verify-spring-local.py."""
import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--host', default='127.0.0.1')
parser.add_argument('--loki-url', default='http://127.0.0.1:3100')
parser.add_argument('--output', default='observability-verification.json')
args = parser.parse_args()


def get(url):
    with urllib.request.urlopen(url, timeout=15) as response:
        return json.load(response)


def query(base, expression):
    return get(base + '/api/v1/query?' + urllib.parse.urlencode({'query': expression}))['data']['result']


host = 'http://' + args.host
for port in [19080, 19081]:
    assert get(f'{host}:{port}/actuator/health')['status'] == 'UP'
state = get(f'{host}:19081/api/state')
assert state['loggedIn'], state.get('lastError')
trace_id = uuid.uuid4().hex
request = urllib.request.Request(
    f'{host}:19081/api/platform/protocol/decode', json.dumps({'wireHex': '00'}).encode(),
    {'Content-Type': 'application/json', 'traceparent': f'00-{trace_id}-0123456789abcdef-01'})
try:
    urllib.request.urlopen(request, timeout=15)
    raise AssertionError('Malformed frame must return HTTP 400')
except urllib.error.HTTPError as error:
    assert error.code == 400, error.code
print('Both services healthy, TCP logged in, stateless validation request rejected with HTTP 400', flush=True)
command_trace_id = uuid.uuid4().hex
command = urllib.request.Request(
    f'{host}:19081/api/platform/devices/{state["pileCode"]}/read-realtime',
    json.dumps({'gunNo': state['guns'][0]['gunNo']}).encode(),
    {'Content-Type': 'application/json', 'traceparent': f'00-{command_trace_id}-0123456789abcdef-01'})
with urllib.request.urlopen(command, timeout=20) as response:
    assert response.status == 200
print('Read-realtime command returned successfully across the real TCP connection', flush=True)

for attempt in range(30):
    try:
        trace = get(f'{host}:16686/api/traces/{trace_id}')['data'][0]
        services = sorted({p['serviceName'] for p in trace['processes'].values()})
        assert services == ['iot-protocol-expert-protocol', 'iot-protocol-expert-simulator'], services
        expression = '{service_name=~"iot-protocol-expert-(protocol|simulator)",deployment_environment_name="personal"} | trace_id="' + trace_id + '"'
        logs = get(args.loki_url.rstrip('/') + '/loki/api/v1/query_range?' + urllib.parse.urlencode({
            'query': expression, 'since': '15m', 'limit': 30}))['data']['result']
        assert len(logs) >= 2, 'Both services must have correlated logs'
        metrics = query(f'{host}:9090', 'sum by(application_name,side) (ipe_protocol_operations_total{active_profile="personal"})')
        assert len(metrics) >= 2, metrics
        targets = query(f'{host}:9090', 'up{job=~"ipe-(otel|jaeger-spm)"}')
        assert len(targets) == 2 and all(float(item['value'][1]) == 1 for item in targets), targets
        spm = query(f'{host}:9090', 'sum by(service_name) (traces_span_metrics_calls_total{service_name=~"iot-protocol-expert-.*"})')
        assert len(spm) >= 2, 'Jaeger SPM metrics missing'
        command_trace = get(f'{host}:16686/api/traces/{command_trace_id}')['data'][0]
        commands = [s for s in command_trace['spans'] if s['operationName'].startswith('yunkuaichong request')]
        assert commands, 'TCP command span missing'
        ack = [s for s in command_trace['spans'] if s['operationName'] == 'yunkuaichong receive 0x13']
        assert ack and any(ref['spanID'] == commands[0]['spanID'] for ref in ack[0]['references']), 'Pending ACK parent context missing'
        result = {
            'verified_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
            'trace_id': trace_id, 'trace_url': f'{host}:16686/trace/{trace_id}',
            'services_in_same_http_trace': services, 'span_count': len(trace['spans']),
            'loki_correlated_streams': len(logs),
            'protocol_metrics': [{'labels': m['metric'], 'count': m['value'][1]} for m in metrics],
            'scrape_jobs_up': 2, 'spm_services': len(spm),
            'tcp_command_trace_id': command_trace_id,
            'tcp_command_trace_url': f'{host}:16686/trace/{command_trace_id}',
            'tcp_command_span_count': len(command_trace['spans']),
            'pending_ack_parent_verified': True,
            'tcp_boundary': 'Standard Yunkuaichong wire carries no traceparent; TCP receives are independent roots. Local command ACKs use the pending request context.'}
        Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
        break
    except (AssertionError, KeyError, IndexError, urllib.error.URLError) as error:
        if attempt == 29:
            raise
        if attempt % 5 == 0:
            print('Waiting for batched trace/log/metric export:', str(error), flush=True)
        time.sleep(2)
