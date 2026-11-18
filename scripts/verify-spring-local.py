"""Run isolated Spring applications and verify real TCP, traces, logs and OTLP export.

Existing user instances and business data are untouched. A temporary PostgreSQL database
is required and removed after the run; psycopg2 is required to create it.
"""
import gzip
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    import psycopg2
    from psycopg2 import sql
except ImportError:
    psycopg2 = None
    sql = None

root = Path(__file__).resolve().parents[1]
settings_path = root / 'scripts/local-settings.json'
local_settings = json.loads(settings_path.read_text(encoding='utf-8')) if settings_path.exists() else {}
java_home = os.environ.get('IPE_JAVA_HOME') or local_settings.get('javaHome') or os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else shutil.which('java')
if not java:
    raise SystemExit('Set JAVA_HOME or IPE_JAVA_HOME to a JDK 21 installation')


def read_dotenv(path):
    values = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding='utf-8').splitlines():
        line = raw_line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def admin_database_url(database_url):
    parts = urllib.parse.urlsplit(database_url.removeprefix('jdbc:'))
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, '/postgres', parts.query, parts.fragment))


def create_verification_database(env):
    if psycopg2 is None:
        raise RuntimeError('psycopg2 is required to create an isolated PostgreSQL database')
    source = next(
        (
            (prefix, env[prefix + '_URL'])
            for prefix in ('PEP_CONTROL_DB', 'IPE_OPS_DB')
            if env.get(prefix + '_URL')
        ),
        None,
    )
    if source is None:
        raise RuntimeError(
            'PEP_CONTROL_DB_URL or IPE_OPS_DB_URL must be configured in the environment or .env.local')
    prefix, source_url = source
    database_name = 'ipe_verify_' + uuid.uuid4().hex[:12]
    kwargs = {'connect_timeout': 10}
    source_user = env.get(prefix + '_USER') or 'ipe'
    source_password = env.get(prefix + '_PASSWORD') or ''
    kwargs['user'] = source_user
    kwargs['password'] = source_password
    try:
        connection = psycopg2.connect(admin_database_url(source_url), **kwargs)
        try:
            connection.autocommit = True
            with connection.cursor() as cursor:
                cursor.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(database_name)))
        finally:
            connection.close()
    except Exception as error:
        raise RuntimeError('unable to create the isolated PostgreSQL database') from error
    jdbc_url = source_url.removeprefix('jdbc:')
    parts = urllib.parse.urlsplit(jdbc_url)
    verification_url = urllib.parse.urlunsplit(
        (parts.scheme, parts.netloc, '/' + database_name, parts.query, parts.fragment))
    verification_url = 'jdbc:' + verification_url
    return database_name, admin_database_url(source_url), kwargs, verification_url, source_user, source_password


def drop_verification_database(database_name, admin_url, kwargs):
    if not database_name:
        return
    try:
        connection = psycopg2.connect(admin_url, **kwargs)
        try:
            connection.autocommit = True
            with connection.cursor() as cursor:
                cursor.execute(
                    'SELECT pg_terminate_backend(pid) FROM pg_stat_activity '
                    'WHERE datname = %s AND pid <> pg_backend_pid()',
                    (database_name,))
                cursor.execute(
                    sql.SQL('DROP DATABASE IF EXISTS {}').format(sql.Identifier(database_name)))
        finally:
            connection.close()
    except Exception as error:
        print('warning: isolated PostgreSQL database was not removed', file=sys.stderr)
        print(type(error).__name__, file=sys.stderr)


tmp_root = root / 'tmp'
tmp_root.mkdir(parents=True, exist_ok=True)
work = Path(tempfile.mkdtemp(prefix='spring-verification-', dir=tmp_root))
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
    return json.load(urllib.request.urlopen(url, timeout=30))


def post(path, body, trace_id):
    request = urllib.request.Request('http://127.0.0.1:29081' + path, json.dumps(body).encode(), {
        'Content-Type': 'application/json', 'traceparent': f'00-{trace_id}-0123456789abcdef-01'})
    try:
        with urllib.request.urlopen(request, timeout=100) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def post_json(url, body, timeout=60):
    request = urllib.request.Request(
        url,
        json.dumps(body).encode(),
        {'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def wait_for_trace_spans(port, trace_id, predicate, timeout=30):
    deadline = time.monotonic() + timeout
    spans = []
    names = []
    while time.monotonic() < deadline:
        spans = get(f'http://127.0.0.1:{port}/actuator/telemetry?traceId=' + trace_id)['spans']
        names = [span['name'] for span in spans]
        matching = [span for span in spans if predicate(span)]
        if matching:
            return matching, names
        time.sleep(.25)
    return [], names


receiver = ThreadingHTTPServer(('127.0.0.1', 0), Receiver)
threading.Thread(target=receiver.serve_forever, daemon=True).start()
processes, files = [], []
env = os.environ.copy()
local_env = read_dotenv(root / '.env.local')
for key, value in local_env.items():
    env.setdefault(key, value)
env['IPE_SIMULATOR_DEVICE_ID'] = os.environ.get('IPE_VERIFICATION_SIMULATOR_DEVICE_ID', '')
# 模拟器使用独立配置项；验证脚本复用本机数据库连接，仅访问模拟器独立状态表。
for suffix in ('URL', 'USER', 'PASSWORD'):
    simulator_key = 'IPE_SIMULATOR_DB_' + suffix
    ops_key = 'IPE_OPS_DB_' + suffix
    if not env.get(simulator_key) and env.get(ops_key):
        env[simulator_key] = env[ops_key]
env['IPE_OTLP_ENABLED'] = 'true'
env['IPE_OTLP_ENDPOINT'] = 'http://127.0.0.1:' + str(receiver.server_port)
env['IPE_ENVIRONMENT'] = 'verification'
env['IPE_OPS_TIMEOUT_MS'] = '70000'
env['IPE_PROTOCOL_TIMEOUT_MS'] = '70000'
verification_token = uuid.uuid4().hex
env['IPE_OPS_SERVICE_TOKEN'] = 'verify-' + verification_token
env['IPE_OPS_SIMULATOR_TOKEN'] = 'verify-' + verification_token
env['IPE_OPS_BASE_URL'] = 'http://127.0.0.1:29082'
env['IPE_PROTOCOL_BASE_URL'] = 'http://127.0.0.1:29080'
env['IPE_SIMULATOR_BASE_URL'] = 'http://127.0.0.1:29081'
env['IPE_NACOS_DISCOVERY_ENABLED'] = 'false'
env['IPE_MODEL_ENABLED'] = 'false'
env['IPE_AI_BOOTSTRAP_ENABLED'] = 'false'
env['IPE_AI_PLATFORM_BOOTSTRAP_ENABLED'] = 'false'
env['IPE_AI_PLATFORM_REGISTRY_ENABLED'] = 'false'
database_name, admin_url, database_kwargs, verification_url, database_user, database_password = (
    create_verification_database(env))
env['IPE_OPS_DB_URL'] = verification_url
env['IPE_SIMULATOR_DB_URL'] = verification_url
env['PEP_CONTROL_DB_URL'] = verification_url
env['IPE_OPS_DB_USER'] = database_user
env['IPE_SIMULATOR_DB_USER'] = database_user
env['PEP_CONTROL_DB_USER'] = database_user
env['IPE_OPS_DB_PASSWORD'] = database_password
env['IPE_SIMULATOR_DB_PASSWORD'] = database_password
env['PEP_CONTROL_DB_PASSWORD'] = database_password
try:
    for name, jar, extra, port in [
        ('ops', 'iot-protocol-expert-ops/target/iot-protocol-expert-ops-1.0.0.jar', [], 29082),
        ('protocol', 'iot-protocol-expert-protocol-gateway/target/iot-protocol-expert-protocol-gateway-1.0.0.jar',
         ['--ipe.protocol.tcp-port=28768', '--ipe.protocol.ocpp-port=28769',
          '--ipe.protocol.pending-timeout-seconds=45',
          '--spring.datasource.url=jdbc:h2:file:./protocol;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE'], 29080),
        ('simulator', 'iot-protocol-expert-simulator/target/iot-protocol-expert-simulator-1.0.0.jar',
         ['--ipe.simulator.platform-port=28768', '--ipe.simulator.ocpp-port=28769',
          '--ipe.simulator.platform-http-port=29080', '--ipe.simulator.auto-connect=false',
          '--ipe.simulator.platform-proxy-timeout-seconds=90'], 29081)
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
        if name == 'ops':
            request = urllib.request.Request(
                f'http://127.0.0.1:{port}/api/ops/simulator/devices',
                headers={'X-Ops-Simulator-Token': env['IPE_OPS_SIMULATOR_TOKEN']})
            with urllib.request.urlopen(request, timeout=20) as response:
                json.load(response)
        print(name + ' ready, without Java Agent', flush=True)

    state = get('http://127.0.0.1:29081/api/state')
    if state.get('deviceId') is None:
        requested_device_id = os.environ.get('IPE_VERIFICATION_SIMULATOR_DEVICE_ID')
        devices = get('http://127.0.0.1:29081/api/devices')
        candidates = [
            device for device in devices
            if device.get('enabled') and device.get('allowSimulate')
        ]
        if requested_device_id:
            candidates = [
                device for device in candidates
                if str(device.get('id')) == str(requested_device_id)
            ]
        if not candidates:
            raise RuntimeError('No enabled simulator device is available')
        ykc = [device for device in candidates if device.get('protocol') == 'YKC16']
        selected = (ykc or candidates)[0]
        post_json(
            'http://127.0.0.1:29081/api/device/select',
            {'deviceId': selected['id']})
    for attempt in range(240):
        state = get('http://127.0.0.1:29081/api/state')
        if state['loggedIn'] and state.get('rateReady'):
            break
        time.sleep(.5)
    assert state['loggedIn'], state.get('lastError')
    assert state.get('rateReady'), state.get('lastError')
    subprocess.run([sys.executable, str(root / 'scripts/smoke-test.py'), '--base-url', 'http://127.0.0.1:29081'], check=True)
    trace_id, error_trace_id = uuid.uuid4().hex, uuid.uuid4().hex
    assert post(f'/api/platform/devices/{state["pileCode"]}/read-realtime', {'gunNo': '01'}, trace_id) == 200
    assert post('/api/platform/protocol/decode', {'wireHex': '00'}, error_trace_id) == 400
    print('Real TCP charging smoke and cross-service HTTP checks passed', flush=True)

    ops, _ = wait_for_trace_spans(29082, trace_id, lambda span: True)
    platform, _ = wait_for_trace_spans(29080, trace_id, lambda span: True)
    simulator, _ = wait_for_trace_spans(29081, trace_id, lambda span: True)
    assert ops and platform and simulator, 'RestClient must propagate the HTTP trace'
    commands, platform_names = wait_for_trace_spans(
        29080, trace_id, lambda span: span['name'].startswith('yunkuaichong request'))
    assert commands, 'TCP request span missing; observed spans: ' + ', '.join(sorted(platform_names))
    command_ids = {span['spanId'] for span in commands}
    acknowledgements, platform_names = wait_for_trace_spans(
        29080, trace_id, lambda span: span['name'] == 'yunkuaichong receive 0x13'
        and span['parentSpanId'] in command_ids)
    assert acknowledgements, 'TCP acknowledgement span missing; observed spans: ' + ', '.join(
        sorted(platform_names))
    ack = acknowledgements[0]
    command = next(span for span in commands if span['spanId'] == ack['parentSpanId'])
    platform = get('http://127.0.0.1:29080/actuator/telemetry?traceId=' + trace_id)['spans']
    assert len({s['spanId'] for s in platform}) == len(platform), 'Duplicate span export'
    jdbc = [s for s in ops + simulator if 'jdbc.datasource.name' in s['tags']]
    assert jdbc, 'Datasource Micrometer JDBC spans missing'
    assert all(not any('query[' in k or 'parameter' in k for k in s['tags']) for s in jdbc)
    metrics_by_port = {
        29080: ['ipe_protocol_operations_total', 'ipe_protocol_operation_duration_seconds_bucket'],
        29081: ['ipe_protocol_operations_total', 'ipe_protocol_operation_duration_seconds_bucket'],
        29082: [],
    }
    for port, required_metrics in metrics_by_port.items():
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/prometheus', timeout=30) as response:
                metrics = response.read().decode()
        except urllib.error.HTTPError as error:
            raise AssertionError(f'{port} actuator prometheus endpoint unavailable: HTTP {error.code}') from error
        for metric in required_metrics + ['http_server_requests_seconds_bucket', 'jvm_memory_used_bytes']:
            assert metric in metrics, f'{port} prometheus output missing {metric}'

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
              'ops_spans': len(ops), 'platform_spans': len(platform), 'simulator_spans': len(simulator),
              'jdbc_spans': len(jdbc),
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
    drop_verification_database(database_name, admin_url, database_kwargs)
