const state = { devices: [], frames: [], selectedGuns: {} };

function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

function timeText(value) {
    if (!value) return '-';
    const text = String(value).replace('T', ' ');
    return text.length >= 19 ? text.substring(5, 19) : text;
}

function toast(message, bad) {
    let el = document.querySelector('.toast');
    if (!el) {
        el = document.createElement('div');
        el.className = 'toast';
        document.body.appendChild(el);
    }
    el.textContent = message;
    el.classList.toggle('bad', !!bad);
    el.classList.add('show');
    clearTimeout(el._timer);
    el._timer = setTimeout(() => el.classList.remove('show'), 4200);
}

async function api(path, options) {
    const response = await fetch(path, Object.assign({
        headers: { 'Content-Type': 'application/json' }
    }, options || {}));
    const text = await response.text();
    const data = text ? JSON.parse(text) : null;
    if (!response.ok) {
        throw new Error((data && (data.message || data.code)) || ('HTTP ' + response.status));
    }
    return data;
}

async function command(pileCode, action, body) {
    try {
        if(body===undefined && ['start','stop','read-realtime'].includes(action))body={gunNo:state.selectedGuns[pileCode]||'01'};
        const result = await api('/api/devices/' + encodeURIComponent(pileCode) + '/' + action, {
            method: 'POST',
            body: body === undefined ? '{}' : JSON.stringify(body)
        });
        toast(pileCode + ' · ' + action + ' → ' + summarizeResult(result));
        await refresh();
    } catch (e) {
        toast(pileCode + ' · ' + action + ' 失败：' + e.message, true);
    }
}

function summarizeResult(result) {
    if (!result) return '完成';
    if (typeof result === 'string') return result;
    if (result.success !== undefined) return (result.success ? '成功' : '失败') + (result.message ? '：' + result.message : '');
    if (result.status !== undefined) return '状态=' + result.status;
    if (result.pileCode) return '应答已收到';
    return '完成';
}

function renderDevices(devices) {
    const body = document.getElementById('devices');
    if (!devices.length) {
        body.innerHTML = '<tr><td colspan="7" class="empty">暂无设备，启动模拟器后自动建档</td></tr>';
        return;
    }
    body.innerHTML = devices.map(d => {
        const online = d.online
            ? '<span class="badge ok">在线</span>'
            : '<span class="badge off">离线</span>';
        return `<tr>
            <td class="mono">${esc(d.pileCode)}</td>
            <td>${online}</td>
            <td><select onchange="state.selectedGuns['${esc(d.pileCode)}']=this.value">${Array.from({length:d.gunCount},(_,i)=>String(i+1).padStart(2,'0')).map(g=>`<option ${(state.selectedGuns[d.pileCode]||'01')===g?'selected':''}>${g}</option>`).join('')}</select></td>
            <td>v${(Number(d.protocolVersion) / 10).toFixed(1)}</td>
            <td class="mono">${esc(d.programVersion)}</td>
            <td>${esc(timeText(d.lastSeen))}</td>
            <td>
                <button class="primary" onclick="command('${esc(d.pileCode)}','start')">启动</button>
                <button class="danger" onclick="command('${esc(d.pileCode)}','stop')">停机</button>
                <button onclick="command('${esc(d.pileCode)}','read-realtime')">读实时</button>
                <button onclick="command('${esc(d.pileCode)}','time-sync')">对时</button>
                <button onclick="command('${esc(d.pileCode)}','rate-model')">推费率</button>
                <button onclick="command('${esc(d.pileCode)}','reboot')">重启</button>
                <button onclick="command('${esc(d.pileCode)}','parallel-start')">并充</button>
                <details><summary>配置参数</summary><pre>允许工作: ${esc(d.allowWork)}<br>功率上限: ${esc(d.maxPowerPercent)}%<br>枪数: ${esc(d.gunCount)}<br>协议版本: ${esc(d.protocolVersion)}</pre></details>
            </td>
        </tr>`;
    }).join('');
}

function renderOrders(orders) {
    const body = document.getElementById('orders');
    if (!orders || !orders.length) {
        body.innerHTML = '<tr><td colspan="5" class="empty">暂无订单</td></tr>';
        return;
    }
    const badge = status => {
        const map = {
            PENDING: 'warn', CHARGING: 'ok', STOPPED: 'warn', SETTLED: 'ok', FAILED: 'bad'
        };
        return `<span class="badge ${map[status] || 'off'}">${esc(status)}</span>`;
    };
    body.innerHTML = orders.map(o => `<tr>
        <td class="mono">${esc(o.transactionNo)}</td>
        <td>${esc(o.gunNo)}</td>
        <td>${badge(o.status)}</td>
        <td>${esc(o.energy ?? 0)}</td>
        <td>${esc(o.amount ?? 0)}</td>
    </tr>`).join('');
}

function renderTransactions(rows) {
    const body = document.getElementById('transactions');
    if (!rows || !rows.length) {
        body.innerHTML = '<tr><td colspan="5" class="empty">暂无交易</td></tr>';
        return;
    }
    body.innerHTML = rows.map(t => `<tr>
        <td class="mono">${esc(t.transactionNo)}</td>
        <td>${esc(t.gunNo)}</td>
        <td>${esc(t.totalEnergy)}</td>
        <td>${esc(t.totalAmount)}</td>
        <td>0x${Number(t.stopReason).toString(16).toUpperCase().padStart(2, '0')}</td>
    </tr>`).join('');
}

function renderFrames(frames) {
    const body = document.getElementById('frames');
    if (!frames || !frames.length) {
        body.innerHTML = '<tr><td colspan="6" class="empty">暂无数据</td></tr>';
        return;
    }
    body.innerHTML = frames.map(f => {
        const dir = f.direction === 'IN'
            ? '<span class="badge dir-in">上行</span>'
            : '<span class="badge dir-out">下行</span>';
        return `<tr>
            <td>${esc(timeText(f.createdAt))}</td>
            <td>${dir}</td>
            <td class="mono">${esc(f.pileCode || '-')}</td>
            <td>${esc(f.frameLabel)}<br><span class="mono">0x${Number(f.frameType).toString(16).toUpperCase().padStart(2, '0')}</span></td>
            <td>${esc(f.summary || '')}</td>
            <td class="mono"><details><summary>${esc((f.bodyHex||'').substring(0,50))}…</summary><pre style="max-width:650px;white-space:pre-wrap;word-break:break-all">${esc(JSON.stringify(f.decoded,null,2))}</pre></details></td>
        </tr>`;
    }).join('');
}

async function refresh() {
    try {
        const [overview, devices, frames] = await Promise.all([
            api('/api/overview'),
            api('/api/devices'),
            api('/api/frames?limit=100&pileCode=' + encodeURIComponent(document.getElementById('pileFilter').value.trim()))
        ]);
        document.getElementById('deviceCount').textContent = overview.deviceCount;
        document.getElementById('onlineCount').textContent = overview.onlineCount;
        document.getElementById('runningOrders').textContent = overview.runningOrders;
        document.getElementById('frameCount').textContent = overview.inboundFrames + ' / ' + overview.outboundFrames;
        renderDevices(devices);
        renderOrders(overview.orders);
        renderTransactions(overview.transactions);
        renderFrames(frames);
    } catch (e) {
        toast('刷新失败：' + e.message, true);
    }
}

function tick() {
    document.getElementById('clock').textContent = new Date().toLocaleString('zh-CN', { hour12: false });
}

tick();
setInterval(tick, 1000);
refresh();
setInterval(refresh, 3000);