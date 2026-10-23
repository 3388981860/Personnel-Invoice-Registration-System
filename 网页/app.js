/* 人数发票登记台账 —— 前端逻辑（原生 JS，无外部依赖）
   模型：一张「报销单」下面挂 N 张发票；报销状态、报销人、收款人按单统一处理 */
'use strict';

var $ = function (id) { return document.getElementById(id); };

var state = {
    records: [],
    batches: [],
    persons: [],
    months: [],
    stage: [],            // 本单待提交的发票
    stageSeq: 0,
    sort: 'invoice_date',
    desc: true,
    selected: {},         // 台账里勾选的发票 id
    drawer: null,         // {type:'invoice'|'batch', id, batch}
    drawerEdit: null      // 'invoice' | 'batch' | null
};

/* ------------------------------------------------------------------ */
/* 小工具                                                             */
/* ------------------------------------------------------------------ */
function digits(s) { return (s || '').replace(/\D/g, ''); }

function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}

function money(v) {
    if (v === null || v === undefined || v === '') return '';
    var n = Number(v);
    if (isNaN(n)) return '';
    return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function moneyY(v) {
    var m = money(v);
    return m === '' ? '' : '¥' + m;
}

function num0(v) { var n = Number(v); return isNaN(n) ? 0 : n; }

function toast(msg, kind) {
    var t = $('toast');
    t.textContent = msg;
    t.className = 'show ' + (kind || '');
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.className = ''; }, 2600);
}

function api(path, opts) {
    return fetch(path, opts || {}).then(function (r) {
        return r.json().catch(function () { return { ok: false, error: '服务无响应' }; })
            .then(function (j) { return j; });
    });
}

function postJSON(path, obj) {
    return api(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(obj)
    });
}

function b64utf8(s) {
    var bytes = new TextEncoder().encode(s);
    var bin = '';
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
}

function todayISO() {
    var d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

function findRecord(id) {
    for (var i = 0; i < state.records.length; i++) {
        if (state.records[i].id === id) return state.records[i];
    }
    return null;
}

function findStage(key) {
    for (var i = 0; i < state.stage.length; i++) {
        if (state.stage[i].key === key) return state.stage[i];
    }
    return null;
}

/* 必填项没填：闪红框 + 提示 + 光标跳过去 */
function markNeed(id, msg) {
    toast(msg, 'err');
    var el = $(id);
    if (!el) return;
    el.classList.add('need');
    el.focus();
    var clear = function () {
        el.classList.remove('need');
        el.removeEventListener('input', clear);
        el.removeEventListener('change', clear);
    };
    el.addEventListener('input', clear);
    el.addEventListener('change', clear);
}

function showAlert(kind, title, detail) {
    var a = $('alert');
    a.className = 'alert show ' + kind;
    a.innerHTML = '<div class="title">' + title + '</div>' +
        (detail ? '<div class="detail">' + detail + '</div>' : '');
}

function hideAlert() { $('alert').className = 'alert'; }

/* ------------------------------------------------------------------ */
/* 本单发票（待提交列表）                                              */
/* ------------------------------------------------------------------ */
function newStageItem(fields, token, fileName) {
    state.stageSeq++;
    var f = fields || {};
    return {
        key: 's' + state.stageSeq,
        token: token || null,
        file_name: fileName || null,
        invoice_no: f.invoice_no || '',
        invoice_date: f.invoice_date || '',
        amount: (f.amount === null || f.amount === undefined) ? '' : String(f.amount),
        amount_net: f.amount_net,
        tax: f.tax,
        seller: f.seller || '',
        buyer: f.buyer || '',
        category: f.category || '',
        source: f.source || '',
        warnings: f.warnings || [],
        dup: null,
        error: '',
        _checked: null
    };
}

function stageTotals() {
    var n = 0, sum = 0;
    state.stage.forEach(function (it) {
        if ((it.invoice_no || '').trim()) { n++; sum += num0(it.amount); }
    });
    return { count: n, amount: sum };
}

function renderStage() {
    var tb = $('stageBody');
    tb.innerHTML = '';
    var rows = state.stage;
    $('stageEmpty').style.display = rows.length ? 'none' : 'block';

    rows.forEach(function (it, i) {
        var tr = document.createElement('tr');
        tr.dataset.key = it.key;
        tr.innerHTML =
            '<td>' + (i + 1) + '</td>' +
            '<td><input class="cell" data-f="invoice_no" value="' + esc(it.invoice_no) + '" placeholder="20 位号码"></td>' +
            '<td><input class="cell" type="date" data-f="invoice_date" value="' + esc(it.invoice_date) + '"></td>' +
            '<td><input class="cell right" data-f="amount" value="' + esc(it.amount) + '" placeholder="0.00"></td>' +
            '<td><input class="cell" data-f="seller" value="' + esc(it.seller) + '"></td>' +
            '<td class="hint-cell"></td>' +
            '<td class="ops"><button type="button" class="del" data-act="rmstage" title="从本单移除">移除</button></td>';
        tb.appendChild(tr);
    });
    updateStageFlags();
    renderStageSum();
}

function renderStageSum() {
    var t = stageTotals();
    $('stageSum').textContent = t.count
        ? ('共 ' + t.count + ' 张　合计 ' + moneyY(t.amount))
        : '还没有发票';
    $('stageSum').className = 'stage-sum' + (t.count ? ' has' : '');
}

function updateStageFlags() {
    var seen = {};
    state.stage.forEach(function (it) { it.error = ''; });
    state.stage.forEach(function (it) {
        var no = digits(it.invoice_no);
        if (!no) return;
        if (seen[no]) { seen[no].error = '本单里有重复号码'; it.error = '本单里有重复号码'; }
        else seen[no] = it;
    });

    state.stage.forEach(function (it) {
        var tr = document.querySelector('#stageBody tr[data-key="' + it.key + '"]');
        if (!tr) return;
        var cell = tr.querySelector('.hint-cell');
        var no = digits(it.invoice_no);
        var msgs = [];

        if (it.dup) msgs.push('⛔ 这张之前登记过了');
        if (it.error) msgs.push('⚠ ' + it.error);
        if (no && no.length !== 20) msgs.push('⚠ 号码 ' + no.length + ' 位（应为 20 位）');
        if (!no && it.file_name) msgs.push('⚠ 没读出号码，请手工填');
        if (!num0(it.amount) && no) msgs.push('⚠ 没有金额');
        (it.warnings || []).forEach(function (w) {
            if (w.indexOf('未能自动识别') < 0) msgs.push('⚠ ' + w);
        });
        if (it.source === '二维码') msgs.push('· 号码取自二维码');

        tr.className = (it.dup || it.error) ? 'bad' : (msgs.length ? 'warn' : '');
        cell.innerHTML = msgs.length
            ? msgs.map(esc).join('<br>')
            : '<span class="okmark">✓ 正常</span>';
    });
}

var stageCheckTimer = null;
function scheduleStageCheck() {
    clearTimeout(stageCheckTimer);
    stageCheckTimer = setTimeout(checkStageDup, 260);
}

function checkStageDup() {
    var pending = state.stage.filter(function (it) {
        return digits(it.invoice_no).length === 20 && it._checked !== digits(it.invoice_no);
    });
    if (!pending.length) { updateStageFlags(); return; }
    var jobs = pending.map(function (it) {
        var no = digits(it.invoice_no);
        it._checked = no;
        return api('/api/check?no=' + no).then(function (r) { it.dup = r.exists ? r.record : null; });
    });
    Promise.all(jobs).then(function () { updateStageFlags(); renderStageSum(); });
}

/* 拖入 / 选择文件 */
function handleFiles(files) {
    if (!files || !files.length) return;
    var arr = Array.prototype.slice.call(files);
    $('busy').classList.add('show');
    var left = arr.length;
    arr.forEach(function (f) {
        f.arrayBuffer().then(function (buf) {
            return fetch('/api/parse', {
                method: 'POST',
                headers: { 'X-File-Name': b64utf8(f.name) },
                body: buf
            }).then(function (r) { return r.json(); });
        }).then(function (r) {
            if (!r.ok) { toast(r.error || ('识别失败：' + f.name), 'err'); return; }
            var it = newStageItem(r.fields, r.exists ? null : r.token, r.file_name);
            if (r.exists) it.dup = r.record;
            state.stage.push(it);
        }).catch(function () {
            toast('识别失败：' + f.name, 'err');
        }).then(function () {
            left--;
            if (left <= 0) {
                $('busy').classList.remove('show');
                renderStage();
                checkStageDup();
            }
        });
    });
}

function addManualRow() {
    var it = newStageItem({}, null, null);
    state.stage.push(it);
    renderStage();
    var tr = document.querySelector('#stageBody tr[data-key="' + it.key + '"]');
    if (tr) tr.querySelector('input[data-f="invoice_no"]').focus();
}

function clearStage() {
    state.stage = [];
    $('batch_no').value = '';
    $('batch_note').value = '';
    renderStage();
    hideAlert();
}

function submitBatch() {
    var items = state.stage.filter(function (it) { return digits(it.invoice_no); });
    if (!items.length) { toast('本单还没有发票，先拖入发票或手工加一行', 'err'); return; }
    if (!$('person').value.trim()) { markNeed('person', '请填写报销人'); return; }
    if (!$('payee').value.trim()) { markNeed('payee', '请填写收款人'); return; }

    var dup = items.filter(function (it) { return it.dup || it.error; });
    if (dup.length) {
        showAlert('danger', '⛔ 本单里有问题，不能提交',
            dup.map(function (it) {
                return '<b>' + esc(it.invoice_no) + '</b>：' +
                    (it.dup ? '这张发票之前已经登记过了' : esc(it.error));
            }).join('<br>') + '<br>请把有问题的发票移除或修正后再提交。');
        return;
    }
    var bad = items.filter(function (it) { return digits(it.invoice_no).length !== 20; });
    if (bad.length && !confirm('有 ' + bad.length + ' 张发票的号码不是 20 位：\n' +
        bad.map(function (it) { return it.invoice_no; }).join('\n') +
        '\n\n确定按这些号码登记吗？')) return;
    var noAmount = items.filter(function (it) { return !num0(it.amount); });
    if (noAmount.length && !confirm('有 ' + noAmount.length + ' 张发票没有金额，确定继续吗？')) return;

    var payload = {
        person: $('person').value.trim(),
        payee: $('payee').value.trim(),
        reimburse_date: $('reimburse_date').value || null,
        batch_no: $('batch_no').value.trim() || null,
        note: $('batch_note').value.trim() || null,
        items: items.map(function (it) {
            return {
                invoice_no: digits(it.invoice_no),
                invoice_date: it.invoice_date || null,
                amount: it.amount === '' ? null : it.amount,
                amount_net: it.amount_net,
                tax: it.tax,
                seller: it.seller || null,
                buyer: it.buyer || null,
                category: it.category || null,
                source: it.source || null,
                file_name: it.file_name,
                token: it.token
            };
        })
    };

    $('submitBatch').disabled = true;
    postJSON('/api/batch_add', payload).then(function (r) {
        $('submitBatch').disabled = false;
        if (!r.ok) {
            if (r.field) { markNeed(r.field, r.error); return; }
            if (r.duplicate) {
                showAlert('danger', '⛔ 提交被拦下', esc(r.error) +
                    '<br>请把这张发票从本单里移除，或修正号码后再提交。');
                toast(r.error, 'err');
                return;
            }
            toast(r.error || '提交失败', 'err');
            return;
        }
        var b = r.batch || {};
        state.stage = [];
        $('batch_no').value = '';
        $('batch_note').value = '';
        renderStage();
        showAlert('ok', '✅ 报销单已登记：' + esc(b.batch_no || ''),
            '本单共 <b>' + (b.count || 0) + '</b> 张发票，合计 <b>' + moneyY(b.amount) + '</b>　' +
            '报销人：<b>' + esc(b.person || '') + '</b>　收款人：<b>' + esc(b.payee || '') + '</b><br>' +
            '在下面的台账里点这个单号，可以整单标记已报销。');
        toast('报销单已登记：' + (b.batch_no || ''), 'ok');
        loadList();
    }).catch(function () {
        $('submitBatch').disabled = false;
        toast('提交失败，请检查服务窗口是否还在运行', 'err');
    });
}

/* ------------------------------------------------------------------ */
/* 台账                                                               */
/* ------------------------------------------------------------------ */
function qs() {
    var p = [];
    if ($('q').value.trim()) p.push('q=' + encodeURIComponent($('q').value.trim()));
    if ($('fperson').value) p.push('person=' + encodeURIComponent($('fperson').value));
    if ($('fmonth').value) p.push('month=' + encodeURIComponent($('fmonth').value));
    if ($('fstatus').value !== '') p.push('status=' + encodeURIComponent($('fstatus').value));
    if ($('fbatch').value !== '') p.push('batch=' + encodeURIComponent($('fbatch').value));
    p.push('sort=' + state.sort);
    p.push('desc=' + (state.desc ? '1' : '0'));
    return '?' + p.join('&');
}

function loadList() {
    return api('/api/list' + qs()).then(function (r) {
        if (!r.ok) { toast(r.error || '读取失败', 'err'); return; }
        state.records = r.records;
        state.batches = r.batches || [];
        renderTable();
        renderStats(r.stats);
        renderPersons(r.persons);
        renderMonths(r.months);
        renderBatchFilter();
    });
}

function renderStats(s) {
    if (!s) return;
    var un = s.unpaid || { count: 0, amount: 0 };
    $('stats').innerHTML =
        '<span class="todo">待报销 <b>' + (s.unpaid_batches || 0) + '</b> 单 / <b>' + un.count +
        '</b> 张　合计 <span class="money">' + moneyY(un.amount) + '</span></span>' +
        '<span>本月 <b>' + s.month.count + '</b> 张　合计 <span class="money">' + moneyY(s.month.amount) + '</span></span>' +
        '<span>本年 <b>' + s.year.count + '</b> 张　合计 <span class="money">' + moneyY(s.year.amount) + '</span></span>' +
        '<span>累计 <b>' + s.total.count + '</b> 张　合计 <span class="money">' + moneyY(s.total.amount) + '</span></span>';
}

function renderPersons(list) {
    var sel = $('fperson'), dl = $('personlist');
    var cur = sel.value;
    sel.innerHTML = '<option value="">全部报销人</option>';
    dl.innerHTML = '';
    (list || []).forEach(function (p) {
        sel.insertAdjacentHTML('beforeend', '<option value="' + esc(p) + '">' + esc(p) + '</option>');
        dl.insertAdjacentHTML('beforeend', '<option value="' + esc(p) + '">');
    });
    sel.value = cur;
}

function renderMonths(list) {
    var sel = $('fmonth'), cur = sel.value;
    sel.innerHTML = '<option value="">全部月份</option>';
    (list || []).forEach(function (m) {
        sel.insertAdjacentHTML('beforeend', '<option value="' + esc(m) + '">' + esc(m) + '</option>');
    });
    sel.value = cur;
}

function renderBatchFilter() {
    var sel = $('fbatch'), cur = sel.value;
    sel.innerHTML = '<option value="">全部报销单</option><option value="none">未归单</option>';
    (state.batches || []).forEach(function (b) {
        sel.insertAdjacentHTML('beforeend',
            '<option value="' + b.id + '">' + esc(b.batch_no) + '</option>');
    });
    sel.value = cur;
}

function statusBadge(paid) {
    return paid ? '<span class="tag ok">已报销</span>' : '<span class="tag bad">未报销</span>';
}

function statusSwitch(r) {
    var paid = Number(r.status) === 1;
    var tip = paid
        ? ('已报销' + (r.paid_at ? '（' + r.paid_at + '）' : '') + '　点击可改回未报销')
        : '点击标记为已报销';
    return '<label class="sw' + (paid ? ' on' : '') + '" title="' + esc(tip) + '">' +
        '<input type="checkbox" data-act="status"' + (paid ? ' checked' : '') + '>' +
        '<span class="track"><span class="knob"></span></span>' +
        '<span class="txt">' + (paid ? '已报销' : '未报销') + '</span></label>';
}

function payeeCell(r) {
    var payee = (r.payee || '').trim();
    if (!payee) {
        return Number(r.status) === 1
            ? '<span class="payee-none" title="这条记录没有填收款人">—</span>'
            : '<span class="payee-none" title="还没填收款人，打开详情补上">待填</span>';
    }
    if (r.person && payee !== r.person) {
        return '<span class="payee-diff" title="收款人和报销人不是同一个人">' + esc(payee) + '</span>';
    }
    return esc(payee);
}

function batchCell(r) {
    if (!r.batch_no) return '<span class="payee-none" title="这张发票还没有归到报销单">未归单</span>';
    return '<a href="javascript:void(0)" class="batch-link" data-batch="' + r.batch_id + '">' +
        esc(r.batch_no) + '</a>';
}

function renderTable() {
    var tb = $('tbody');
    tb.innerHTML = '';
    var rows = state.records;
    $('empty').style.display = rows.length ? 'none' : 'block';

    var total = 0, unpaidTotal = 0;
    rows.forEach(function (r) {
        total += num0(r.amount);
        var paid = Number(r.status) === 1;
        if (!paid) unpaidTotal += num0(r.amount);
        var tr = document.createElement('tr');
        tr.dataset.id = r.id;
        if (paid) tr.className = 'paid';
        tr.innerHTML =
            '<td><input type="checkbox" data-act="pick"' + (state.selected[r.id] ? ' checked' : '') + '></td>' +
            '<td class="batchno">' + batchCell(r) + '</td>' +
            '<td class="no">' + esc(r.invoice_no) + '</td>' +
            '<td>' + esc(r.invoice_date || '') + '</td>' +
            '<td class="money">' + moneyY(r.amount) + '</td>' +
            '<td class="person">' + esc(r.person || '') + '</td>' +
            '<td class="payee">' + payeeCell(r) + '</td>' +
            '<td class="st">' + statusSwitch(r) + '</td>' +
            '<td class="note" title="' + esc(r.note || '') + '">' + esc(r.note || '') + '</td>' +
            '<td class="ops">' +
            '<button type="button" data-act="edit" title="修改这张发票">修改</button>' +
            '<button type="button" class="del" data-act="del" title="删除这张发票">删除</button>' +
            '</td>';
        tb.appendChild(tr);
    });

    $('count').textContent = rows.length
        ? ('共 ' + rows.length + ' 条　合计 ' + moneyY(total) + '　其中未报销 ' + moneyY(unpaidTotal))
        : '';
    updateSortArrows();
    renderSelbar();
    $('checkAll').checked = false;
}

function updateSortArrows() {
    document.querySelectorAll('th.sortable').forEach(function (th) {
        var a = th.querySelector('.arrow');
        if (a) a.textContent = (th.dataset.sort === state.sort) ? (state.desc ? '▼' : '▲') : '';
    });
}

/* ------------------------------------------------------------------ */
/* 批量选择与归单                                                      */
/* ------------------------------------------------------------------ */
function selectedIds() {
    return Object.keys(state.selected)
        .filter(function (k) { return state.selected[k]; }).map(Number);
}

function renderSelbar() {
    var ids = selectedIds();
    $('selCount').textContent = ids.length;
    $('selbar').className = 'selbar' + (ids.length ? ' show' : '');
}

function clearSelection() {
    state.selected = {};
    document.querySelectorAll('#tbody input[data-act="pick"]').forEach(function (c) { c.checked = false; });
    renderSelbar();
}

function attachSelected() {
    if (!selectedIds().length) return;
    openModal('加入已有报销单');
}

function newBatchFromSelected() {
    var ids = selectedIds();
    if (!ids.length) return;
    var no = prompt('给这张新报销单起个单号（留空自动生成）：', '');
    if (no === null) return;
    postJSON('/api/attach', { ids: ids, batch_no: (no || '').trim() || null }).then(function (r) {
        if (!r.ok) { toast(r.error || '归单失败', 'err'); return; }
        var b = r.batch || {};
        toast('已新建报销单 ' + (b.batch_no || ''), 'ok');
        clearSelection();
        loadList();
        openBatchDrawer(b.id);
    });
}

function detachSelected() {
    var ids = selectedIds();
    if (!ids.length) return;
    if (!confirm('把选中的 ' + ids.length + ' 张发票从报销单里拆出来？\n\n拆出后它们会变成「未归单」，可以再归到别的单里。')) return;
    postJSON('/api/detach', { ids: ids }).then(function (r) {
        if (!r.ok) { toast(r.error || '拆出失败', 'err'); return; }
        toast('已拆出 ' + ids.length + ' 张', 'ok');
        clearSelection();
        loadList();
    });
}

/* ------------------------------------------------------------------ */
/* 选择报销单的小窗口                                                  */
/* ------------------------------------------------------------------ */
function openModal(title) {
    $('modalTitle').textContent = title;
    $('modal').classList.add('show');
    $('modalMask').classList.add('show');
    $('modalSearch').value = '';
    loadBatchList();
    $('modalSearch').focus();
}

function closeModal() {
    $('modal').classList.remove('show');
    $('modalMask').classList.remove('show');
}

function loadBatchList() {
    var q = $('modalSearch').value.trim();
    api('/api/batches' + (q ? '?q=' + encodeURIComponent(q) : '')).then(function (r) {
        var box = $('batchList');
        var bs = (r.batches || []);
        if (!bs.length) {
            box.innerHTML = '<div class="empty">还没有报销单。可以先点「新建报销单」。</div>';
            return;
        }
        box.innerHTML = bs.map(function (b) {
            var st = b.status === 1 ? '<span class="tag ok">已报销</span>'
                : (b.status === -1 ? '<span class="tag part">部分已报销</span>'
                    : '<span class="tag bad">未报销</span>');
            return '<div class="batch-row" data-pick="' + b.id + '">' +
                '<div class="bl-l"><b>' + esc(b.batch_no) + '</b> ' + st + '</div>' +
                '<div class="bl-r">' + (b.count || 0) + ' 张　' + moneyY(b.amount) +
                '<br><span class="muted">' + esc(b.person || '') + ' → ' + esc(b.payee || '') + '</span></div>' +
                '</div>';
        }).join('');
    });
}

/* ------------------------------------------------------------------ */
/* 右侧抽屉                                                           */
/* ------------------------------------------------------------------ */
function openDrawerShell(title) {
    $('drawerTitle').textContent = title;
    $('drawer').classList.add('show');
    $('drawerMask').classList.add('show');
    $('drawer').setAttribute('aria-hidden', 'false');
}

function closeDrawer() {
    state.drawer = null;
    state.drawerEdit = null;
    $('drawer').classList.remove('show');
    $('drawerMask').classList.remove('show');
    $('drawer').setAttribute('aria-hidden', 'true');
}

function dlRow(k, v, cls) {
    return '<div class="dl-row"><div class="dl-k">' + k + '</div>' +
        '<div class="dl-v ' + (cls || '') + '">' + v + '</div></div>';
}

function editRow(k, field, val, type) {
    var t = (type === 'date') ? 'date' : 'text';
    return '<div class="dl-row"><div class="dl-k">' + k + '</div>' +
        '<div class="dl-v"><input class="cell wide" type="' + t + '" data-e="' + field + '" value="' +
        esc(val === null || val === undefined ? '' : val) + '"></div></div>';
}

/* ---------------- 发票详情 ---------------- */
function openInvoiceDrawer(id, edit) {
    if (!findRecord(id)) return;
    state.drawer = { type: 'invoice', id: Number(id) };
    state.drawerEdit = edit ? 'invoice' : null;
    renderInvoiceDrawer();
    openDrawerShell(edit ? '修改发票' : '发票详情');
}

function renderInvoiceDrawer() {
    var r = findRecord(state.drawer.id);
    if (!r) { closeDrawer(); return; }
    var paid = Number(r.status) === 1;
    var h = '';

    if (state.drawerEdit === 'invoice') {
        h += '<div class="dl-sec">发票信息</div>';
        h += editRow('发票号码', 'invoice_no', r.invoice_no, 'text');
        h += editRow('开票日期', 'invoice_date', r.invoice_date, 'date');
        h += editRow('金额（含税）', 'amount', r.amount, 'text');
        h += editRow('不含税金额', 'amount_net', r.amount_net, 'text');
        h += editRow('税额', 'tax', r.tax, 'text');
        h += editRow('销售方', 'seller', r.seller, 'text');
        h += editRow('购买方', 'buyer', r.buyer, 'text');
        h += editRow('项目', 'category', r.category, 'text');
        h += '<div class="dl-sec">报销信息</div>';
        h += editRow('报销人', 'person', r.person, 'text');
        h += editRow('收款人', 'payee', r.payee, 'text');
        h += editRow('报销日期', 'reimburse_date', r.reimburse_date, 'date');
        h += editRow('备注', 'note', r.note, 'text');
        h += '<div class="dl-sec">其他</div>';
        h += dlRow('报销单号', esc(r.batch_no || '未归单'));
        h += dlRow('登记时间', esc(r.created_at || '—'));

        $('drawerFoot').innerHTML =
            '<button type="button" data-d="saveinv" class="solid">保存</button>' +
            '<button type="button" data-d="canceledit">取消</button>' +
            '<span class="spacer"></span>' +
            '<button type="button" class="danger" data-d="del">删除</button>';
    } else {
        h += '<div class="dl-sec">发票信息</div>';
        h += dlRow('发票号码', '<b>' + esc(r.invoice_no) + '</b>');
        h += dlRow('开票日期', esc(r.invoice_date || '—'));
        h += dlRow('金额（含税）', '<span class="big">' + moneyY(r.amount) + '</span>');
        h += dlRow('不含税金额', money(r.amount_net) || '—', 'money');
        h += dlRow('税额', money(r.tax) || '—', 'money');
        h += dlRow('销售方', esc(r.seller || '—'));
        h += dlRow('购买方', esc(r.buyer || '—'));
        h += dlRow('项目', esc(r.category || '—'));
        h += '<div class="dl-sec">报销信息</div>';
        h += dlRow('报销单号', r.batch_no
            ? '<a href="javascript:void(0)" class="batch-link" data-batch="' + r.batch_id + '">' +
              esc(r.batch_no) + '</a>'
            : '<span class="payee-none">未归单</span>');
        h += dlRow('报销人', esc(r.person || '—'));
        h += dlRow('收款人', r.payee
            ? (esc(r.payee) + (r.person && r.payee !== r.person
                ? ' <span class="payee-diff">与报销人不同</span>' : ''))
            : '<span class="payee-none">未填</span>');
        h += dlRow('报销日期', esc(r.reimburse_date || '—'));
        h += dlRow('报销状态', statusBadge(paid));
        if (paid) h += dlRow('报销确认时间', esc(r.paid_at || '—'));
        h += dlRow('备注', esc(r.note || '—'));
        h += '<div class="dl-sec">其他</div>';
        h += dlRow('登记时间', esc(r.created_at || '—'));
        h += dlRow('原件文件', r.file_name ? esc(r.file_name) : '<span class="payee-none">没有留存原件</span>');
        if (r.file_rel) h += dlRow('存放位置', esc(r.file_rel));

        $('drawerFoot').innerHTML =
            (r.file_rel ? '<button type="button" data-d="view">查看原件</button>'
                : '<button type="button" disabled>无原件</button>') +
            '<button type="button" data-d="editinv">修改</button>' +
            '<span class="spacer"></span>' +
            (r.batch_id ? '<button type="button" data-d="openbatch">打开报销单</button>' : '') +
            '<button type="button" class="danger" data-d="del">删除</button>';
    }
    $('drawerBody').innerHTML = h;
}

function saveInvoiceEdit() {
    var r = findRecord(state.drawer.id);
    if (!r) return;
    var patch = { id: r.id };
    document.querySelectorAll('#drawerBody input[data-e]').forEach(function (el) {
        patch[el.dataset.e] = el.value.trim() || null;
    });
    if (!patch.invoice_no) { toast('发票号码不能为空', 'err'); return; }
    postJSON('/api/update', patch).then(function (res) {
        if (!res.ok) { toast(res.error || '保存失败', 'err'); return; }
        toast('已保存', 'ok');
        state.drawerEdit = null;
        loadList().then(function () { renderInvoiceDrawer(); });
    });
}

/* ---------------- 报销单详情 ---------------- */
function openBatchDrawer(id) {
    state.drawer = { type: 'batch', id: Number(id) };
    state.drawerEdit = null;
    api('/api/batch?id=' + id).then(function (r) {
        if (!r.ok) { toast(r.error || '找不到这张报销单', 'err'); return; }
        state.drawer.batch = r.batch;
        renderBatchDrawer();
        openDrawerShell('报销单详情');
    });
}

function renderBatchDrawer() {
    var b = state.drawer && state.drawer.batch;
    if (!b) return;
    var st = b.status === 1 ? '<span class="tag ok">整单已报销</span>'
        : (b.status === -1 ? '<span class="tag part">部分已报销（' + b.paid_count + '/' + b.count + '）</span>'
            : '<span class="tag bad">未报销</span>');
    var h = '';

    if (state.drawerEdit === 'batch') {
        h += '<div class="dl-sec">报销单信息</div>';
        h += editRow('报销单号', 'batch_no', b.batch_no, 'text');
        h += editRow('报销人', 'person', b.person, 'text');
        h += editRow('收款人', 'payee', b.payee, 'text');
        h += editRow('报销日期', 'reimburse_date', b.reimburse_date, 'date');
        h += editRow('备注', 'batch_note', b.note, 'text');
        h += '<div class="dl-note">改报销人 / 收款人 / 报销日期，会同步改到本单里的每一张发票。</div>';
        $('drawerFoot').innerHTML =
            '<button type="button" data-d="savebatch" class="solid">保存</button>' +
            '<button type="button" data-d="canceledit">取消</button>';
    } else {
        h += '<div class="dl-sec">报销单信息</div>';
        h += dlRow('报销单号', '<b>' + esc(b.batch_no) + '</b>');
        h += dlRow('报销人', esc(b.person || '—'));
        h += dlRow('收款人', esc(b.payee || '—'));
        h += dlRow('报销日期', esc(b.reimburse_date || '—'));
        h += dlRow('报销状态', st);
        if (b.status === 1) h += dlRow('报销确认时间', esc(b.paid_at || '—'));
        h += dlRow('本单合计', '<span class="big">' + moneyY(b.amount) + '</span>');
        h += dlRow('发票张数', b.count + ' 张');
        h += dlRow('备注', esc(b.note || '—'));
        h += dlRow('建单时间', esc(b.created_at || '—'));

        h += '<div class="dl-sec">本单明细</div>';
        h += '<div class="mini-wrap"><table class="mini"><thead><tr>' +
            '<th>#</th><th>发票号码</th><th>开票日期</th><th>金额</th><th>状态</th></tr></thead><tbody>';
        (b.invoices || []).forEach(function (i, idx) {
            h += '<tr data-inv="' + i.id + '"><td>' + (idx + 1) + '</td>' +
                '<td class="no">' + esc(i.invoice_no) + '</td>' +
                '<td>' + esc(i.invoice_date || '') + '</td>' +
                '<td class="money">' + moneyY(i.amount) + '</td>' +
                '<td>' + (Number(i.status) === 1
                    ? '<span class="tag ok">已报销</span>'
                    : '<span class="tag bad">未报销</span>') + '</td></tr>';
        });
        h += '</tbody></table></div>';
        h += '<div class="dl-note">点上面任意一行，可以打开那张发票的详情。</div>';

        $('drawerFoot').innerHTML =
            (b.status === 1
                ? '<button type="button" data-d="batchstatus" data-v="0">整单改回未报销</button>'
                : '<button type="button" data-d="batchstatus" data-v="1" class="solid">整单标记已报销</button>') +
            '<button type="button" data-d="editbatch">修改单信息</button>' +
            '<span class="spacer"></span>' +
            '<button type="button" class="danger" data-d="delbatch">删除整单</button>';
    }
    $('drawerBody').innerHTML = h;
}

function saveBatchEdit() {
    var b = state.drawer && state.drawer.batch;
    if (!b) return;
    var patch = { id: b.id };
    document.querySelectorAll('#drawerBody input[data-e]').forEach(function (el) {
        var k = el.dataset.e;
        patch[k === 'batch_note' ? 'note' : k] = el.value.trim() || null;
    });
    postJSON('/api/batch_update', patch).then(function (r) {
        if (!r.ok) { toast(r.error || '保存失败', 'err'); return; }
        toast('已保存', 'ok');
        state.drawerEdit = null;
        state.drawer.batch = r.batch;
        renderBatchDrawer();
        loadList();
    });
}

function setBatchStatus(bid, want) {
    postJSON('/api/batch_status', { id: bid, status: want }).then(function (r) {
        if (!r.ok) { toast(r.error || '操作失败', 'err'); return; }
        toast(want ? '整单已标记为「已报销」' : '整单已改回「未报销」', 'ok');
        state.drawer.batch = r.batch;
        renderBatchDrawer();
        if (r.stats) renderStats(r.stats);
        loadList();
    });
}

/* ------------------------------------------------------------------ */
/* 其它操作                                                           */
/* ------------------------------------------------------------------ */
function statusTip(paid, rec) {
    return paid
        ? ('已报销' + (rec && rec.paid_at ? '（' + rec.paid_at + '）' : '') + '　点击可改回未报销')
        : '点击标记为已报销';
}

function applyStatusToRow(id, want, rec) {
    var tr = document.querySelector('#tbody tr[data-id="' + id + '"]');
    if (!tr) return;
    tr.classList.toggle('paid', want === 1);
    var lab = tr.querySelector('.sw');
    if (!lab) return;
    lab.classList.toggle('on', want === 1);
    var t = lab.querySelector('.txt');
    if (t) t.textContent = want ? '已报销' : '未报销';
    lab.title = statusTip(want === 1, rec);
}

function toggleStatus(id, want, cb) {
    cb.disabled = true;
    postJSON('/api/status', { id: id, status: want }).then(function (r) {
        cb.disabled = false;
        if (!r.ok) { cb.checked = !cb.checked; toast(r.error || '切换失败', 'err'); return; }
        toast(want ? '已标记为「已报销」' : '已改回「未报销」', 'ok');
        var rec = r.record, lab = cb.closest('.sw');
        if (lab) {
            lab.classList.toggle('on', want === 1);
            var t = lab.querySelector('.txt');
            if (t) t.textContent = want ? '已报销' : '未报销';
            lab.title = statusTip(want === 1, rec);
        }
        var item = findRecord(id);
        if (item) item.status = want;
        applyStatusToRow(id, want, rec);
        if (r.stats) renderStats(r.stats);
        if ($('fstatus').value !== '') loadList();
    });
}

function deleteInvoice(id, after) {
    var rec = findRecord(id);
    if (!rec) return;
    if (!confirm('确定删除这条发票记录吗？\n\n发票号码：' + rec.invoice_no +
        '\n报销人：' + (rec.person || '') + '\n金额：' + moneyY(rec.amount) +
        '\n\n（发票原件会移入「数据\\已删除」，不会真正删掉）')) return;
    postJSON('/api/delete', { id: id }).then(function (r) {
        if (!r.ok) { toast(r.error || '删除失败', 'err'); return; }
        toast('已删除', 'ok');
        delete state.selected[id];
        if (after) after();
    });
}

function viewOriginal(id) { window.open('/api/file?id=' + id, '_blank'); }

function openFolder(which) {
    api('/api/openfolder?which=' + which).then(function (r) {
        if (!r.ok) toast(r.error || '打开失败', 'err');
    });
}

function exportExcel() {
    window.location.href = '/api/export';
    toast('正在导出 Excel…', 'ok');
}

/* ------------------------------------------------------------------ */
/* 事件绑定                                                           */
/* ------------------------------------------------------------------ */
$('drop').addEventListener('click', function () { $('file').click(); });
$('file').addEventListener('change', function (e) { handleFiles(e.target.files); e.target.value = ''; });
['dragenter', 'dragover'].forEach(function (t) {
    $('drop').addEventListener(t, function (e) {
        e.preventDefault(); e.stopPropagation(); $('drop').classList.add('over');
    });
});
['dragleave', 'drop'].forEach(function (t) {
    $('drop').addEventListener(t, function (e) {
        e.preventDefault(); e.stopPropagation(); $('drop').classList.remove('over');
    });
});
$('drop').addEventListener('drop', function (e) {
    if (e.dataTransfer && e.dataTransfer.files) handleFiles(e.dataTransfer.files);
});
window.addEventListener('dragover', function (e) { e.preventDefault(); });
window.addEventListener('drop', function (e) { e.preventDefault(); });

$('stageBody').addEventListener('input', function (ev) {
    var el = ev.target;
    if (!el.dataset || !el.dataset.f) return;
    var tr = el.closest('tr');
    var it = tr && findStage(tr.dataset.key);
    if (!it) return;
    var f = el.dataset.f;
    it[f] = el.value;
    if (f === 'invoice_no') {
        it.dup = null;
        it.error = '';
        it._checked = null;
        scheduleStageCheck();
    }
    if (f === 'amount') renderStageSum();
    updateStageFlags();
});
$('stageBody').addEventListener('click', function (ev) {
    var btn = ev.target.closest('button[data-act="rmstage"]');
    if (!btn) return;
    var tr = btn.closest('tr');
    state.stage = state.stage.filter(function (x) { return x.key !== tr.dataset.key; });
    renderStage();
});

$('addRow').addEventListener('click', addManualRow);
$('submitBatch').addEventListener('click', submitBatch);
$('clearStage').addEventListener('click', function () {
    if (state.stage.length && !confirm('清空本单已拖入的 ' + state.stage.length + ' 张发票？')) return;
    clearStage();
    toast('已清空');
});

$('checkAll').addEventListener('change', function () {
    var on = $('checkAll').checked;
    document.querySelectorAll('#tbody input[data-act="pick"]').forEach(function (c) {
        c.checked = on;
        state.selected[Number(c.closest('tr').dataset.id)] = on;
    });
    renderSelbar();
});

$('tbody').addEventListener('change', function (ev) {
    var el = ev.target;
    if (el.getAttribute('data-act') === 'pick') {
        var id = Number(el.closest('tr').dataset.id);
        if (el.checked) state.selected[id] = true; else delete state.selected[id];
        renderSelbar();
        return;
    }
    if (el.getAttribute('data-act') === 'status') {
        toggleStatus(Number(el.closest('tr').dataset.id), el.checked ? 1 : 0, el);
    }
});

$('tbody').addEventListener('click', function (ev) {
    var link = ev.target.closest('a[data-batch]');
    if (link) { openBatchDrawer(link.dataset.batch); return; }
    if (ev.target.closest('.sw') || ev.target.closest('input[data-act="pick"]')) return;
    var tr = ev.target.closest('tr');
    if (!tr) return;
    var id = Number(tr.dataset.id);
    var btn = ev.target.closest('button[data-act]');
    if (btn) {
        if (btn.dataset.act === 'edit') { openInvoiceDrawer(id, true); return; }
        if (btn.dataset.act === 'del') { deleteInvoice(id, function () { loadList(); }); return; }
    }
    openInvoiceDrawer(id);   // 点行 = 打开这张发票的详情
});

$('drawerFoot').addEventListener('click', function (ev) {
    var btn = ev.target.closest('button[data-d]');
    if (!btn || !state.drawer) return;
    var act = btn.dataset.d;
    var id = state.drawer.id;

    if (act === 'view') viewOriginal(id);
    else if (act === 'editinv') { state.drawerEdit = 'invoice'; renderInvoiceDrawer(); }
    else if (act === 'saveinv') saveInvoiceEdit();
    else if (act === 'canceledit') {
        state.drawerEdit = null;
        if (state.drawer.type === 'batch') renderBatchDrawer(); else renderInvoiceDrawer();
    }
    else if (act === 'del') deleteInvoice(id, function () { closeDrawer(); loadList(); });
    else if (act === 'openbatch') { var r0 = findRecord(id); if (r0) openBatchDrawer(r0.batch_id); }
    else if (act === 'batchstatus') setBatchStatus(id, Number(btn.dataset.v));
    else if (act === 'editbatch') { state.drawerEdit = 'batch'; renderBatchDrawer(); }
    else if (act === 'savebatch') saveBatchEdit();
    else if (act === 'delbatch') {
        var b = state.drawer.batch;
        if (!confirm('删除整张报销单「' + b.batch_no + '」？\n\n单里的 ' + b.count +
            ' 张发票也会一起删掉（原件移入「数据\\已删除」，不会真正删除）')) return;
        var ids = (b.invoices || []).map(function (i) { return i.id; });
        var left = ids.length;
        if (!left) { closeDrawer(); return; }
        ids.forEach(function (iid) {
            postJSON('/api/delete', { id: iid }).then(function () {
                left--;
                if (left <= 0) { closeDrawer(); toast('整单已删除', 'ok'); loadList(); }
            });
        });
    }
});

$('drawerBody').addEventListener('click', function (ev) {
    if (state.drawerEdit) return;
    var link = ev.target.closest('a[data-batch]');
    if (link) { openBatchDrawer(link.dataset.batch); return; }
    var tr = ev.target.closest('tr[data-inv]');
    if (tr) openInvoiceDrawer(Number(tr.dataset.inv));
});

$('drawerClose').addEventListener('click', closeDrawer);
$('drawerMask').addEventListener('click', closeDrawer);

$('modalClose').addEventListener('click', closeModal);
$('modalMask').addEventListener('click', closeModal);
$('modalSearch').addEventListener('input', function () {
    clearTimeout($('modalSearch')._t);
    $('modalSearch')._t = setTimeout(loadBatchList, 250);
});
$('batchList').addEventListener('click', function (ev) {
    var row = ev.target.closest('[data-pick]');
    if (!row) return;
    var ids = selectedIds();
    if (!ids.length) { closeModal(); return; }
    postJSON('/api/attach', { ids: ids, batch_id: Number(row.dataset.pick) }).then(function (r) {
        if (!r.ok) { toast(r.error || '归单失败', 'err'); return; }
        var b = r.batch || {};
        toast(ids.length + ' 张已加入 ' + (b.batch_no || ''), 'ok');
        closeModal();
        clearSelection();
        loadList();
        openBatchDrawer(b.id);
    });
});

$('btnAttach').addEventListener('click', attachSelected);
$('btnNewBatch').addEventListener('click', newBatchFromSelected);
$('btnDetach').addEventListener('click', detachSelected);
$('btnClearSel').addEventListener('click', clearSelection);

document.querySelectorAll('th.sortable').forEach(function (th) {
    th.addEventListener('click', function () {
        var s = th.dataset.sort;
        if (state.sort === s) state.desc = !state.desc;
        else { state.sort = s; state.desc = true; }
        loadList();
    });
});
var searchTimer = null;
$('q').addEventListener('input', function () {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(loadList, 280);
});
['fperson', 'fmonth', 'fstatus', 'fbatch'].forEach(function (k) {
    $(k).addEventListener('change', loadList);
});
$('refresh').addEventListener('click', loadList);

document.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); submitBatch(); }
    if (e.key === 'Escape') {
        if ($('modal').classList.contains('show')) { closeModal(); return; }
        if ($('drawer').classList.contains('show')) { closeDrawer(); return; }
        clearSelection();
        hideAlert();
    }
});

$('reimburse_date').value = todayISO();
renderStage();
loadList();
