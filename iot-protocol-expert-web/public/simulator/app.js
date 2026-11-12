const $ = id => document.getElementById(id), periods = ['尖', '峰', '平', '谷'];
let live = {}, selectedGun = '01', logs = [], rateDraft = null, rateDirty = false, busy = false;
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
}[c]));
const num = (n, d = 4) => Number(n || 0).toFixed(d), date = t => String(t || '—').replace('T', ' ').slice(0, 19);

function toast(message, bad = false) {
    $('toast').textContent = message;
    $('toast').className = 'show' + (bad ? ' bad' : '');
    clearTimeout(window.toastTimer);
    window.toastTimer = setTimeout(() => $('toast').className = '', 4500)
}

async function api(path, body) {
    const r = await fetch('/api/simulator-proxy' + path.substring('/api'.length), {
        method: body === undefined ? 'GET' : 'POST',
        headers: {'Content-Type': 'application/json'},
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    let data;
    try {
        data = await r.json()
    } catch {
        throw Error('服务返回格式错误 HTTP ' + r.status)
    }
    if (!r.ok) throw Error(data.message || data.code || 'HTTP ' + r.status);
    return data
}

async function post(path, body = {}) {
    try {
        const r = await api('/api' + path, body);
        toast(r.message || '操作已完成');
        await refresh();
        return r
    } catch (e) {
        toast(e.message, true);
        return null
    }
}

async function loadDevices() {
    const select = $('deviceSelect');
    try {
        const devices = await api('/api/devices');
        if (!devices || !devices.length) {
            select.innerHTML = '<option value="">没有可模拟设备</option>';
            return;
        }
        select.innerHTML = '<option value="">请选择设备</option>' + devices.map(d => `<option value="${d.id}">${esc(d.sn)} · ${esc(d.protocolLabel || d.protocol)} · ${esc(d.protocolDeviceId)}</option>`).join('');
        $('fleetDeviceSelect').innerHTML = select.innerHTML;
        if (live.deviceId) select.value = String(live.deviceId);
    } catch (e) {
        select.innerHTML = '<option value="">设备列表加载失败</option>';
        toast('设备列表加载失败：' + e.message, true);
    }
}

async function selectDevice() {
    const deviceId = $('deviceSelect').value;
    if (!deviceId) return;
    const r = await post('/device/select', {deviceId: Number(deviceId)});
    if (r) { selectedGun = '01'; rateDraft = null; await refresh(); toast('已选择 ' + (r.state?.deviceSn || deviceId) + '，正在重建连接'); }
}

async function gunAction(action) {
    if (live.protocol === 'ocpp16j' && (action === 'offline-start' || action === 'vin-start')) {
        toast('OCPP 1.6J 不支持该启动方式', true);
        return
    }
    const commandGun = selectedGun;
    return post('/guns/' + commandGun + '/' + action)
}

async function remote(action, body) {
    if (!live.pileCode) return;
    const commandGun = selectedGun, pileCode = live.pileCode, physicalCard = $('card').value;
    const r = await post('/platform/devices/' + pileCode + '/' + action, body ?? (action === 'start' ? {
        gunNo: commandGun,
        physicalCard
    } : {gunNo: commandGun}));
    if (r) {
        $('commandResult').textContent = JSON.stringify(r, null, 2);
        if (r.success === false) toast(r.message || '设备返回失败', true);
        if (action === 'param-set') refreshConfig()
    }
    return r
}

function chooseGun(no) {
    selectedGun = no;
    const g = live.guns?.find(g => g.gunNo === no);
    if (g) {
        $('capacity').value = g.capacityKwh;
        $('initialSoc').value = g.soc || 20;
        $('vin').value = g.vin;
        $('card').value = g.physicalCard
    }
    renderState()
}

function renderState() {
    const s = live, g = s.guns?.find(g => g.gunNo === selectedGun);
    window.dispatchEvent(new CustomEvent('simulator-state', {detail: {...s, selectedGun}}));
    if (!g) return;
    $('connection').textContent = s.loggedIn ? '平台已登录' : s.connected ? (s.protocol === 'ocpp16j' ? 'WebSocket 已连接' : 'TCP 已连接') : '平台离线';
    $('connection').className = 'badge ' + (s.loggedIn ? 'ok' : 'bad');
    $('connectBtn').disabled = s.connected;
    $('pileCode').textContent = s.pileCode;
    $('platform').textContent = (s.protocol === 'ocpp16j' ? 'WS ' : 'TCP ') + s.platform;
    if (document.activeElement !== $('deviceSelect') && s.deviceId) $('deviceSelect').value = String(s.deviceId);
    $('deviceProtocol').textContent = (s.protocolLabel || '') + (s.pileCode ? ' · ' + s.pileCode : '');
    $('eyebrow').textContent = 'DEVICE SIMULATION / ' + (s.protocolLabel || '云快充 1.6');
    $('sideProtocol').textContent = s.protocolLabel || '云快充 1.6';
    $('sideTransport').textContent = s.protocol === 'ocpp16j' ? 'WebSocket · OCPP-J' : 'TCP 协议仿真';
    $('power').textContent = num(s.guns.reduce((a, g) => a + Number(g.voltage) * Number(g.current) / 1000, 0), 1);
    $('chargingCount').textContent = s.guns.filter(g => g.stateCode === 3).length + ' 枪正在充电';
    $('energy').textContent = num(g.energy);
    $('amount').textContent = num(g.amount);
    $('price').textContent = '当前' + periods[s.currentPeriod] + '时段 ¥' + num(s.unitPrice, 5) + '/kWh';
    $('error').textContent = s.lastError || '';
    $('simTime').textContent = date(s.simulatedTime);
    if (document.activeElement !== $('speed')) $('speed').value = String(s.speed);
    $('gunTabs').innerHTML = s.guns.map(g => `<button class="${g.gunNo === selectedGun ? 'active' : ''}" onclick="chooseGun('${esc(g.gunNo)}')">${esc(g.gunNo)} 枪</button>`).join('');
    $('gunDetail').innerHTML = `<div class="gun-head"><h3>${esc(g.soc)}<small style="font-size:12px;color:#92a0b1"> % SOC</small></h3><span class="badge ${g.fault ? 'bad' : g.stateCode === 3 ? 'ok' : ''}">${esc(g.phase)}</span></div><div class="soc-track"><span style="width:${Math.min(100, Number(g.soc))}%"></span></div><div class="gun-stats"><div><small>输出电压 V</small><b>${num(g.voltage, 1)}</b></div><div><small>输出电流 A</small><b>${num(g.current, 1)}</b></div><div><small>已充 / 剩余 min</small><b>${g.totalMinutes} / ${g.remainMinutes}</b></div></div><div class="muted">${g.plugged ? '● 充电枪已连接' : '○ 未插枪'} · ${g.allowWork ? '设备可工作' : '设备已停用'} · ${g.capacityKwh} kWh 电池</div>`;
    const stages = ['已插枪', '鉴权中', '握手', '参数配置', '充电', '已结束'];
    let step = g.phase === '恒流充电' || g.phase === '恒压充电' ? 4 : stages.indexOf(g.phase);
    $('flow').innerHTML = stages.map((p, i) => `<span class="${i <= step ? 'done' : ''}">${p.replace('已插枪', '插枪').replace('鉴权中', '鉴权')}</span>`).join('');
    $('billingModel').textContent = g.billingModelNo ? '订单模型 ' + g.billingModelNo : '等待启动';
    $('billingRows').innerHTML = g.billing.length ? g.billing.map(r => `<tr><td><span class="period-dot p${r.period}"></span>${r.name}</td><td>${num(r.energyPrice, 5)}</td><td>${num(r.servicePrice, 5)}</td><td>${num(r.energy)}</td><td>${num(r.lossEnergy)}</td><td>${num(r.electricityFee)}</td><td>${num(r.serviceFee)}</td><td><b>${num(r.amount)}</b></td></tr>`).join('') : '<tr><td colspan="8" class="empty">插枪并启动充电后，在这里查看尖峰平谷明细</td></tr>';
    $('offlineCards').textContent = '本地白名单 ' + s.offlineCards.length + ' 张';
    $('upgradeStatus').textContent = s.upgradeStatus;
    $('pendingCount').textContent = '待确认账单 ' + s.pendingTransactions.length + ' 笔';
    $('history').innerHTML = s.history.length ? s.history.map((t, i) => `<tr><td class="hex">${esc(t.transactionNo)}</td><td>${t.gunNo}</td><td>${date(t.startTime)}<br>${date(t.endTime)}</td><td>${num(t.totalEnergy)}</td><td>${num(t.totalAmount)}</td><td>${stopReason(t.stopReason)}</td><td><button onclick="detail(live.history[${i}])">查看</button></td></tr>`).join('') : '<tr><td colspan="7" class="empty">暂无交易记录</td></tr>';
    $('rateStatus').textContent = '桩当前已接收模型 ' + s.rateModel.modelNo;
    if (!rateDraft) {
        rateDraft = structuredClone(s.rateModel);
        renderRate()
    }
}

function stopReason(n) {
    if (allStopReasons[n]) return allStopReasons[n];
    return ({
        64: 'APP 远程停止',
        65: 'SOC 达到100%',
        66: '电量达到设定值',
        67: '金额达到设定值',
        68: '时间达到设定值',
        69: '本地手动停止',
        110: '余额不足',
        114: '急停故障',
        131: '断电终止'
    })[n] || '0x' + Number(n).toString(16).toUpperCase()
}

function renderLogs() {
    const filter = $('frameFilter').value;
    const bms = ['0x15', '0x17', '0x19', '0x1B', '0x1D', '0x21', '0x23', '0x25'];
    $('logs').innerHTML = logs.filter(f => !filter || (filter === 'BMS' ? bms.includes(f.frameTypeHex) : f.frameTypeHex === filter)).map((f) => `<tr><td>${date(f.time).slice(11)}</td><td><span class="badge ${f.direction === 'IN' ? 'ok' : ''}">${f.direction === 'IN' ? '平台 → 桩' : '桩 → 平台'}</span> #${f.sequence}</td><td>${esc(f.frameTypeHex)} ${esc(f.label)}</td><td>${esc(f.summary)}</td><td class="hex link" onclick="detail(logs.find(f=>f.sequence===${f.sequence}&&f.frameTypeHex==='${f.frameTypeHex}'))">${esc(f.bodyHex)}</td></tr>`).join('') || '<tr><td colspan="5" class="empty">暂无报文</td></tr>'
}

function detail(value) {
    $('detailContent').textContent = JSON.stringify(value, null, 2);
    $('detailDialog').showModal()
}

async function refresh() {
    if (busy) return;
    busy = true;
    try {
        const [s, l] = await Promise.all([api('/api/state'), api('/api/logs?limit=150')]);
        live = s;
        logs = l;
        renderState();
        renderLogs()
    } catch (e) {
        $('error').textContent = '读取模拟器失败：' + e.message;
        window.dispatchEvent(new CustomEvent('simulator-state', {detail: {connected: false, guns: [], selectedGun}}))
    } finally {
        busy = false
    }
}

function renderRate() {
    const r = rateDraft;
    $('modelNo').value = r.modelNo;
    $('lossRatio').value = r.lossRatio;
    const keys = ['sharp', 'peak', 'flat', 'valley'];
    $('ratePrices').innerHTML = keys.map((k, i) => `<div class="price-card"><b><span class="period-dot p${i}"></span>${periods[i]}时段</b><label>电费 ¥/kWh<input type="number" step="0.00001" min="0" value="${r[k + 'Energy']}" oninput="rateDraft['${k}Energy']=Number(this.value);rateDirty=true"></label><label>服务费 ¥/kWh<input type="number" step="0.00001" min="0" value="${r[k + 'Service']}" oninput="rateDraft['${k}Service']=Number(this.value);rateDirty=true"></label></div>`).join('');
    renderSegments()
}

function renderSegments() {
    $('rateGrid').innerHTML = rateDraft.segments.map((p, i) => `<button class="p${p}" onclick="rateDraft.segments[${i}]=Number($('paintPeriod').value);rateDirty=true;renderSegments()" title="${String(Math.floor(i / 2)).padStart(2, '0')}:${i % 2 ? '30' : '00'} — ${periods[p]}">${String(Math.floor(i / 2)).padStart(2, '0')}:${i % 2 ? '30' : '00'}<br>${periods[p]}</button>`).join('')
}

function fillRate() {
    rateDraft.segments.fill(Number($('paintPeriod').value));
    rateDirty = true;
    renderSegments()
}

async function loadPlatformRate() {
    try {
        const rates = await api('/api/platform/rate-models');
        const r = rates.find(r => r.active) || rates[0];
        if (!r) throw Error('平台尚无费率');
        rateDraft = {...r, segments: typeof r.segments === 'string' ? r.segments.split(',').map(Number) : r.segments};
        renderRate();
        toast('已读取平台模型，编辑后下发')
    } catch (e) {
        toast(e.message, true)
    }
}

async function saveRate() {
    if (live.protocol === 'ocpp16j') {
        toast('OCPP 1.6J 没有费率下发帧，平台按当前生效费率自动计费', true);
        return
    }
    try {
        const r = {...rateDraft, modelNo: $('modelNo').value, lossRatio: Number($('lossRatio').value), active: true};
        if (!/^\d{4}$/.test(r.modelNo)) throw Error('模型编号须为4位数字');
        await api('/api/platform/rate-models', r);
        const ack = await api('/api/platform/devices/' + live.pileCode + '/rate-model', {});
        if (!ack.success) throw Error('桩拒绝费率');
        rateDirty = false;
        toast('费率已保存，下发 0x58 / 应答 0x57 成功');
        await refresh()
    } catch (e) {
        toast('费率操作失败：' + e.message, true)
    }
}

async function refreshConfig() {
    try {
        const c = await api('/api/config');
        const labels = {
            pileCode: '桩编号',
            protocolLabel: '当前协议',
            platformUrl: '平台连接地址',
            ocppPort: 'OCPP WebSocket 端口',
            gunCount: '充电枪数量',
            pileType: '桩类型 0直流 / 1交流',
            platformHost: '平台主机',
            platformPort: '平台 TCP 端口',
            protocolVersion: '协议版本字节',
            programVersion: '程序版本',
            ratedVoltage: '额定输出电压 V',
            ratedCurrent: '额定输出电流 A',
            maxPowerPercent: '最大允许输出功率 %',
            heartbeatSeconds: '心跳间隔 s',
            idleRealtimeSeconds: '待机上报间隔 s',
            chargingRealtimeSeconds: '充电上报间隔 s',
            encryptionEnabled: '3DES 加密',
            stateFile: '本地状态文件'
        };
        $('configGrid').innerHTML = Object.entries(labels).map(([k, v]) => `<div class="config-item"><small>${v}</small><b>${esc(c[k])}</b></div>`).join('')
    } catch (e) {
        toast(e.message, true)
    }
}

function setSpeed() {
    post('/clock', {speed: Number($('speed').value)})
}

function applyClock() {
    if (!$('clockInput').value) return toast('请选择时间', true);
    post('/clock', {time: $('clockInput').value})
}

function crossPeriod() {
    const day = String(live.simulatedTime).slice(0, 10);
    $('clockInput').value = day + 'T09:59:50';
    post('/clock', {time: day + 'T09:59:50', speed: 1})
}

$('vehicleForm').addEventListener('submit', async e => {
    e.preventDefault();
    const configured = await post('/guns/' + selectedGun + '/vehicle', {
        capacityKwh: Number($('capacity').value),
        soc: Number($('initialSoc').value),
        vin: $('vin').value,
        physicalCard: $('card').value
    });
    if (!configured) return;
    await post('/guns/' + selectedGun + '/limits', {
        energy: Number($('limitEnergy').value),
        amount: Number($('limitAmount').value),
        minutes: Number($('limitMinutes').value)
    })
});

async function simulateUpgrade() {
    await post('/upgrade-scenario', {result: Number($('upgradeScenario').value)});
    remote('update', {
        pileModel: 1,
        power: 75,
        serverAddress: $('ftpHost').value,
        port: 21,
        username: 'simulator',
        password: '',
        filePath: $('firmwarePath').value,
        control: 2,
        downloadTimeoutMinutes: 1
    })
}

refresh();
refreshConfig();
loadDevices();
setInterval(refresh, 1500);

let protocolCatalog = [];

async function loadCatalog() {
    try {
        protocolCatalog = await api('/api/platform/protocol/catalog');
        $('catalogSelect').innerHTML = protocolCatalog.map(t => `<option value="${t.code}">${t.hex} ${esc(t.name)} (${t.direction})</option>`).join('');
        showCatalog()
    } catch (e) {
        toast(e.message, true)
    }
}

function showCatalog() {
    const t = protocolCatalog.find(t => t.code === Number($('catalogSelect').value));
    $('catalogDetail').textContent = JSON.stringify(t, null, 2)
}

async function decodeWire() {
    try {
        $('decodedWire').textContent = JSON.stringify(await api('/api/platform/protocol/decode', {wireHex: $('decodeWire').value}), null, 2)
    } catch (e) {
        toast(e.message, true)
    }
}

let allStopReasons = {};
api('/api/stop-reasons').then(r => {
    allStopReasons = r;
    $('stopReasonSelect').innerHTML = Object.entries(r).map(([c, n]) => `<option value="${c}">0x${Number(c).toString(16).toUpperCase()} ${esc(n)}</option>`).join('')
}).catch(e => toast(e.message, true));


async function refreshFleet() {
    try {
        const states = await api('/api/fleet');
        if (document.activeElement?.tagName === 'SELECT' && $('fleetList').contains(document.activeElement)) return;
        const selections = Object.fromEntries([...$('fleetList').querySelectorAll('select')].map(s => [s.id, s.value]));
        $('fleetList').innerHTML = states.length ? states.map(d => `<div>${esc(d.deviceSn)} · ${esc(d.protocol)} · ${d.connected ? '已连接' : '未连接'} ${d.guns.map(g => `<span>枪${esc(g.gunNo)} ${esc(g.phase)} <button onclick="fleetAction(${Number(d.deviceId)},'${esc(g.gunNo)}','plug')">插枪</button><button onclick="fleetAction(${Number(d.deviceId)},'${esc(g.gunNo)}','start')">启动</button><button onclick="fleetAction(${Number(d.deviceId)},'${esc(g.gunNo)}','stop')">停充</button><button onclick="fleetAction(${Number(d.deviceId)},'${esc(g.gunNo)}','report')">上报</button><select id="fault-${Number(d.deviceId)}-${esc(g.gunNo)}">${faultOptions(d.protocol).map(c => `<option value="${c}">${c}</option>`).join('')}</select><button onclick="fleetAction(${Number(d.deviceId)},'${esc(g.gunNo)}','fault')">故障</button><button onclick="fleetAction(${Number(d.deviceId)},'${esc(g.gunNo)}','clear-fault')">恢复</button></span>`).join('')} <button onclick="stopFleet(${Number(d.deviceId)})">停止模拟</button></div>`).join('') : '暂无设备';
        for (const [id, value] of Object.entries(selections)) if ($(id)) $(id).value = value;
    } catch (e) { toast(e.message, true) }
}
async function startFleet() {
    const id = Number($('fleetDeviceSelect').value);
    if (!id) return toast('请选择设备', true);
    try { await api('/api/fleet/' + id + '/start', {}); await refreshFleet(); } catch (e) { toast(e.message, true) }
}
async function stopFleet(id) {
    try { await api('/api/fleet/' + id + '/stop', {}); await refreshFleet(); } catch (e) { toast(e.message, true) }
}
refreshFleet();
setInterval(refreshFleet, 5000);

function updateFaultCodes() {
    const select = $('faultCode'), ocpp = live.protocol === 'ocpp16j';
    const codes = faultOptions(live.protocol);
    const key = ocpp ? 'ocpp' : 'ykc';
    if (select.dataset.protocol !== key) { select.innerHTML = codes.map(c => `<option value="${c}">${c}</option>`).join(''); select.dataset.protocol = key; }
}
setInterval(updateFaultCodes, 1500);
updateFaultCodes();

async function fleetAction(id, gun, action) {
    try { const code = action === 'fault' ? '?code=' + encodeURIComponent($('fault-' + id + '-' + gun).value) : ''; const result = await api(`/api/fleet/${id}/guns/${gun}/${action}${code}`, {}); toast(result.message); await refreshFleet(); } catch (e) { toast(e.message, true) }
}

function faultOptions(protocol) {
    return protocol === 'ocpp16j' ? ['OtherError', 'ConnectorLockFailure', 'EVCommunicationError', 'GroundFailure', 'HighTemperature', 'InternalError', 'LocalListConflict', 'OverCurrentFailure', 'OverVoltage', 'PowerMeterFailure', 'PowerSwitchFailure', 'ReaderFailure', 'ResetFailure', 'UnderVoltage', 'WeakSignal'] : ['0x0001', '0x0002', '0x0004', '0x0008', '0x0010', '0x0020', '0x0040', '0x0080', '0x0100', '0x0200', '0x0400', '0x0800', '0x1000', '0x2000', '0x4000', '0x8000'];
}
