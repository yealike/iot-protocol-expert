"""Run isolated Spring applications and verify real TCP, local traces, logs and OTLP export.

Uses only Python's standard library. Existing user instances on 19080/19081 are untouched.
"""
import gzip
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

root = Path(__file__).resolve().parents[1]
settings_path = root / 'scripts/local-settings.json'
local_settings = json.loads(settings_path.read_text(encoding='utf-8')) if settings_path.exists() else {}
java_home = os.environ.get('IPE_JAVA_HOME') or local_settings.get('javaHome') or os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else shutil.which('java')
if not java:
    raise SystemExit('Set JAVA_HOME or IPE_JAVA_HOME to a JDK 21 installation')
work = root / 'tmp' / ('spring-verification-' + time.strftime('%Y%m%d-%H%M%S'))
work.mkdir(parents=True)
payloads = {'/v1/traces': [], '/v1/metrics': [], '/v1/logs': []}


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        if self.headers.get('Content-Encoding') == 'gzip':
            body = gzip.decompress(body)
        if self.path not in payloads:
            self.send_error(404)
            return
        payloads[self.path].append(body)
        # Empty protobuf is a successful OTLP Export*ServiceResponse.
        self.send_response(200)
        self.send_header('Content-Type', 'application/x-protobuf')
        self.send_header('Content-Length', '0')
        self.end_headers()

    def log_message(self, *args):
        pass


def get(url):
    return json.load(urllib.request.urlopen(url, timeout=10))


def post(path, body, trace_id):
    request = urllib.request.Request('http://127.0.0.1:29081' + path, json.dumps(body).encode(), {
        'Content-Type': 'application/json', 'traceparent': f'00-{trace_id}-0123456789abcdef-01'})
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


receiver = ThreadingHTTPServer(('127.0.0.1', 0), Receiver)
threading.Thread(target=receiver.serve_forever, daemon=True).start()
processes, files = [], []
env = os.environ.copy()
env['IPE_OTLP_ENABLED'] = 'true'
env['IPE_OTLP_ENDPOINT'] = 'http://127.0.0.1:' + str(receiver.server_port)
env['IPE_ENVIRONMENT'] = 'verification'
try:
    for name, jar, extra, port in [
        ('protocol', 'iot-protocol-expert-protocol/target/iot-protocol-expert-protocol-1.0.0-exec.jar',
         ['--ipe.protocol.tcp-port=28768', '--spring.datasource.url=jdbc:h2:file:./protocol;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE'], 29080),
        ('simulator', 'iot-protocol-expert-simulator/target/iot-protocol-expert-simulator-1.0.0.jar',
         ['--ipe.simulator.platform-port=28768', '--ipe.simulator.platform-http-port=29080', '--ipe.simulator.state-file=./simulator.properties'], 29081)
    ]:
        stream = (work / (name + '.log')).open('wb')
        files.append(stream)
        command = [java, '-Xmx512m', '-jar', str(root / jar),
                   '--spring.profiles.active=local', '--server.address=127.0.0.1', f'--server.port={port}',
                   '--management.opentelemetry.tracing.export.schedule-delay=1s',
                   '--management.otlp.metrics.export.step=2s', *extra]
        process = subprocess.Popen(command, cwd=work, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        processes.append(process)
        for attempt in range(60):
            if process.poll() is not None:
                raise RuntimeError(f'{name} exited; inspect {stream.name}')
            try:
                if get(f'http://127.0.0.1:{port}/actuator/health')['status'] == 'UP':
                    break
            except Exception:
                pass
            time.sleep(1)
        else:
            raise RuntimeError(name + ' health timeout')
        print(name + ' ready, without Java Agent', flush=True)

    for attempt in range(30):
        state = get('http://127.0.0.1:29081/api/state')
        if state['loggedIn']:
            break
        time.sleep(.5)
    assert state['loggedIn']
    subprocess.run([sys.executable, str(root / 'scripts/smoke-test.py'), '--base-url', 'http://127.0.0.1:29081'], check=True)
    trace_id, error_trace_id = uuid.uuid4().hex, uuid.uuid4().hex
    assert post(f'/api/platform/devices/{state["pileCode"]}/read-realtime', {'gunNo': '01'}, trace_id) == 200
    assert post('/api/platform/protocol/decode', {'wireHex': '00'}, error_trace_id) == 400
    print('Real TCP charging smoke and cross-service HTTP checks passed', flush=True)

    platform = get('http://127.0.0.1:29080/actuator/telemetry?traceId=' + trace_id)['spans']
    simulator = get('http://127.0.0.1:29081/actuator/telemetry?traceId=' + trace_id)['spans']
    assert platform and simulator, 'RestClient must propagate the HTTP trace'
    command = next(s for s in platform if s['name'].startswith('yunkuaichong request'))
    ack = next(s for s in platform if s['name'] == 'yunkuaichong receive 0x13')
    assert ack['parentSpanId'] == command['spanId']
    assert len({s['spanId'] for s in platform}) == len(platform), 'Duplicate span export'
    jdbc = [s for s in platform if 'jdbc.datasource.name' in s['tags']]
    assert jdbc, 'Datasource Micrometer JDBC spans missing'
    assert all(not any('query[' in k or 'parameter' in k for k in s['tags']) for s in jdbc)
    for port in [29080, 29081]:
        metrics = urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/prometheus').read().decode()
        for metric in ['ipe_protocol_operations_total', 'ipe_protocol_operation_duration_seconds_bucket', 'http_server_requests_seconds_bucket', 'jvm_memory_used_bytes']:
            assert metric in metrics, metric

    for attempt in range(25):
        if all(payloads.values()) and any(b'ipe.protocol.operations' in b for b in payloads['/v1/metrics']):
            break
        time.sleep(1)
    assert all(payloads.values()), {k: len(v) for k, v in payloads.items()}
    assert any(b'protocol.operation' in b for b in payloads['/v1/logs']), 'OTLP structured event fields missing'
    assert any(b'ipe.protocol.operations' in b for b in payloads['/v1/metrics']), 'Native Micrometer OTLP metrics missing'
    correlated = {}
    for name in ['protocol', 'simulator']:
        records = []
        for line in (work / (name + '.log')).read_text(encoding='utf-8', errors='replace').splitlines():
            try:
                records.append(json.loads(line))
            except ValueError:
                pass
        events = [r for r in records if r.get('event.name') == 'protocol.operation']
        assert events and all(r.get('trace_id') and r.get('span_id') for r in events)
        assert any(r.get('trace_id') == error_trace_id for r in records), 'Cross-service logs lost correlation'
        correlated[name] = len(events)

    result = {'verified_at': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'java_agent': False,
              'real_tcp_charge_smoke': True, 'http_trace_id': trace_id, 'error_trace_id': error_trace_id,
              'platform_spans': len(platform), 'simulator_spans': len(simulator), 'jdbc_spans': len(jdbc),
              'pending_ack_parent_verified': True, 'json_correlated_protocol_events': correlated,
              'otlp_batches_received': {k: len(v) for k, v in payloads.items()},
              'prometheus_http_tcp_jvm_histograms': True, 'server_deployed': False}
    (root / 'docs/spring-observability-verification.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2), flush=True)
finally:
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
    for stream in files:
        stream.close()
    receiver.shutdown()
    receiver.server_close()
