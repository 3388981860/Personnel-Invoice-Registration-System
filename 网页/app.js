/* 人数发票登记台账 —— 前端逻辑（原生 JS，无外部依赖）
   模型：一张「报销单」下面挂 N 张发票；报销状态、报销人、收款人按单统一处理 */
'use strict';

var $ = function (id) { return document.getElementById(id); };

var state = {
    me: null,             // 当前登录用户
    records: [],
    batches: [],
    persons: [],
    months: [],
    stage: [],            // 本单待提交的发票
    stageSeq: 0,
    sort: 'invoice_date',
    desc: true,
    page: 1,              // 台账当前页
    pageSize: 20,         // 每页条数
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
        if (r.status === 401) {
            // 没登录 / 会话过期：直接回登录页，别在页面上留一堆报错
            location.href = '/login';
            return { ok: false, error: '请先登录' };
        }
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
        reader_missing: !!f.reader_missing,
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

        // 运行库丢了：只提示这一件事，别的提示都别刷屏
        if (it.reader_missing) {
            tr.className = 'bad';
            cell.innerHTML = '⛔ <b>缺少 PDF 解析组件</b>，无法自动识别号码。<br>' +
                '请把完整的「运行库」文件夹放回项目目录，然后重启。';
            return;
        }

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
    var readerMissing = false;
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
            if (it.reader_missing) readerMissing = true;
            state.stage.push(it);
        }).catch(function () {
            toast('识别失败：' + f.name, 'err');
        }).then(function () {
            left--;
            if (left <= 0) {
                $('busy').classList.remove('show');
                renderStage();
                checkStageDup();
                if (readerMissing) {
                    showAlert('danger', '⛔ 系统缺少 PDF 解析组件，读不出发票号码',
                        '项目里的「<b>运行库</b>」文件夹丢失或不完整，所以没法自动识别发票。<br><br>' +
                        '<b>怎么修：</b><br>' +
                        '1. 关掉那个黑色窗口<br>' +
                        '2. 把完整的「<b>运行库</b>」文件夹放回项目目录（和 启动.bat 同一层），提示覆盖选「是」<br>' +
                        '3. 重新双击 启动.bat<br><br>' +
                        '💡 也请跑一下项目里的「<b>自检.bat</b>」，把生成的「自检报告.txt」发给管理员，' +
                        '好确认是不是运行库出的问题。');
                }
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
    var noAmount = items.filter(function (it) { return !num0(it.amount); });
    var total = 0;
    items.forEach(function (it) { total += num0(it.amount); });

    // 逐条确认（号码位数 → 无金额 → 最终提交），全部用系统弹窗
    (async function () {
        if (bad.length) {
            var okBad = await askYesNo('发票号码不是 20 位',
                '有 ' + bad.length + ' 张发票的号码不是 20 位：\n' +
                bad.map(function (it) { return it.invoice_no; }).join('\n') +
                '\n\n确定按这些号码登记吗？', '仍然登记', true);
            if (!okBad) return;
        }
        if (noAmount.length) {
            var okAmt = await askYesNo('有发票没有金额',
                '有 ' + noAmount.length + ' 张发票没有金额，确定继续吗？', '继续');
            if (!okAmt) return;
        }
        var okFinal = await askYesNo('确认提交',
            '发票 ' + items.length + ' 张，合计 ' + moneyY(total) + '\n' +
            '报销人：' + $('person').value.trim() + '\n' +
            '收款人：' + $('payee').value.trim() + '\n\n' +
            '提交后不能自己修改，要等管理员审核。\n' +
            '如果被退回，你可以在「已退回」状态下修改后重新提交。',
            '提交');
        if (!okFinal) return;

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
        sendBatch(payload);
    })();
}

function sendBatch(payload) {
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
    if ($('faudit').value !== '') p.push('audit=' + encodeURIComponent($('faudit').value));
    if ($('fbatch').value !== '') p.push('batch=' + encodeURIComponent($('fbatch').value));
    if ($('freview') && $('freview').value) p.push('review=' + encodeURIComponent($('freview').value));
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
    renderAuditStat(s);
}

/* 台账标题旁边的"查验概况"：全部 + 本月 */
function renderAuditStat(s) {
    var a = s.audit || { 0: 0, 1: 0, 2: 0, 3: 0 };
    var m = s.audit_month || { 0: 0, 1: 0, 2: 0, 3: 0 };
    var total = (a[0] || 0) + (a[1] || 0) + (a[2] || 0) + (a[3] || 0);
    if (!total) { $('auditStat').innerHTML = ''; return; }
    var bits = [];
    if (a[1]) bits.push('<span class="tag au-pass">通过 ' + a[1] + '</span>');
    if (a[2]) bits.push('<span class="tag au-fail">不通过 ' + a[2] + '</span>');
    if (a[0]) bits.push('<span class="tag au-none">未查 ' + a[0] + '</span>');
    if (a[3]) bits.push('<span class="tag au-skip">无需查 ' + a[3] + '</span>');
    var mt = (m[0] || 0) + (m[1] || 0) + (m[2] || 0) + (m[3] || 0);
    var html = '查验 ' + bits.join(' ') +
        (mt ? '　<span class="au-stat-month">本月 ' + mt + ' 张：通过 ' + (m[1] || 0) +
            (m[2] ? ' / 不通过 ' + m[2] : '') +
            (m[0] ? ' / 未查 ' + m[0] : '') + '</span>' : '');
    if (s.audit_warn) {
        html += '<span class="au-legend"><span class="au-warn">!</span>' +
            ' = 查验通过了，但有 ' + s.audit_warn + ' 张需要留意（点开详情看原因）</span>';
    }
    $('auditStat').innerHTML = html;
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
    if (!isAdmin()) {
        // 普通用户不能改报销状态，只显示
        return Number(r.status) === 1
            ? '<span class="tag ok">已报销</span>'
            : '<span class="tag bad">未报销</span>';
    }
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
    var badge = '';
    var st = r.batch_review || '';
    if (st === 'submitted') badge = ' <span class="tag rv-sub" title="已提交，等待管理员审核">待审</span>';
    else if (st === 'returned') badge = ' <span class="tag rv-ret" title="被退回，请修改后重新提交">退回</span>';
    if (!r.batch_no) {
        return '<span class="payee-none" title="这张发票还没有归到报销单">未归单</span>';
    }
    return '<a href="javascript:void(0)" class="batch-link" data-batch="' + r.batch_id + '">' +
        esc(r.batch_no) + '</a>' + badge;
}

/* 这一行能不能改：管理员看是否已通过；用户只有在"已退回"时能改 */
function rowEditable(r) {
    if (isAdmin()) return r.batch_review !== 'approved';
    if (!r.batch_id) return true;
    return r.batch_review === 'returned';
}

/* 能不能删：只有提交人本人 + 「已退回」，管理员也不例外 */
function canDeleteRow(r) {
    if (!state.me) return false;
    var owner = Number(r.owner_id || r.batch_owner || 0);
    if (owner !== Number(state.me.id)) return false;
    if (!r.batch_id) return true;
    return r.batch_review !== 'approved';     // 待审核可撤回、已退回可删
}

function canDeleteBatch(b) {
    if (!b || !state.me) return false;
    if (Number(b.owner_id || 0) !== Number(state.me.id)) return false;
    return String(b.review_status || '') !== 'approved';
}

/* ---------------- 查验状态 ---------------- */
var AUDIT_TEXT = { 0: '未查验', 1: '查验通过', 2: '查验不通过', 3: '无需查验' };
var AUDIT_CLASS = { 0: 'au-none', 1: 'au-pass', 2: 'au-fail', 3: 'au-skip' };

function auditStatus(r) {
    var v = (r.audit_status === null || r.audit_status === undefined) ? 0 : Number(r.audit_status);
    return isNaN(v) ? 0 : v;
}

/* 收款人 ≠ 报销人：只做弱提示，不点亮感叹号 */
function payeeHintText(r) {
    var person = (r.person || '').trim();
    var payee = (r.payee || '').trim();
    if (person && payee && person !== payee) {
        return '收款人（' + payee + '）与报销人（' + person + '）不是同一人，付款前确认一下。';
    }
    return '';
}

function auditBadge(r) {    var v = auditStatus(r);
    var tip = AUDIT_TEXT[v];
    if (r.audit_at) tip += '（' + r.audit_at + '）';
    if (r.audit_msg) tip += '\n' + r.audit_msg;
    if (r.audit_warn) tip += '\n⚠ ' + r.audit_warn;
    if (v === 0) tip += '\n还没查过，勾选后点「查验选中」';
    var html = '<span class="tag ' + (AUDIT_CLASS[v] || 'au-none') + '" title="' + esc(tip) + '">' +
        AUDIT_TEXT[v] + '</span>';
    if (r.audit_warn) {
        html += '<span class="au-warn" title="' + esc('⚠ ' + r.audit_warn) + '">!</span>';
    }
    return html;
}

function renderTable() {
    var tb = $('tbody');
    tb.innerHTML = '';
    var all = state.records || [];
    $('empty').style.display = all.length ? 'none' : 'block';

    // 合计按「全部记录」算，不受当前页影响
    var total = 0, unpaidTotal = 0;
    all.forEach(function (r) {
        total += num0(r.amount);
        if (Number(r.status) !== 1) unpaidTotal += num0(r.amount);
    });

    // 只渲染当前页
    clampPage();
    var start = (state.page - 1) * state.pageSize;
    all.slice(start, start + state.pageSize).forEach(function (r) {
        var paid = Number(r.status) === 1;
        var tr = document.createElement('tr');
        tr.dataset.id = r.id;
        if (paid) tr.className = 'paid';
        var canEdit = rowEditable(r);
        tr.innerHTML =
            '<td><input type="checkbox" data-act="pick"' + (state.selected[r.id] ? ' checked' : '') + '></td>' +
            '<td class="batchno">' + batchCell(r) + '</td>' +
            '<td class="no">' + esc(r.invoice_no) + '</td>' +
            '<td>' + esc(r.invoice_date || '') + '</td>' +
            '<td class="money">' + moneyY(r.amount) + '</td>' +
            '<td class="person">' + esc(r.person || '') + '</td>' +
            '<td class="payee">' + payeeCell(r) + '</td>' +
            '<td class="st">' + statusSwitch(r) + '</td>' +
            '<td class="au">' + auditBadge(r) + '</td>' +
            '<td class="note" title="' + esc(r.note || '') + '">' + esc(r.note || '') + '</td>' +
            '<td class="ops">' +
            ((canEdit || canDeleteRow(r))
                ? (canEdit ? '<button type="button" data-act="edit" title="修改这张发票">修改</button>' : '') +
                  (canDeleteRow(r)
                      ? '<button type="button" class="del" data-act="del" title="删除这张发票">删除</button>'
                      : '')
                : '<span class="locked" title="已提交，只有管理员退回后才能修改">已锁</span>') +
            '</td>';
        tb.appendChild(tr);
    });

    $('count').textContent = all.length
        ? ('共 ' + all.length + ' 条　合计 ' + moneyY(total) + '　其中未报销 ' + moneyY(unpaidTotal))
        : '';
    updateSortArrows();
    renderSelbar();
    renderPager();
    $('checkAll').checked = false;
}

/* ---------------- 分页 ---------------- */
function pageCount() {
    var n = Math.max(1, Math.ceil((state.records || []).length / state.pageSize));
    return n;
}

function clampPage() {
    var pc = pageCount();
    if (state.page > pc) state.page = pc;
    if (state.page < 1) state.page = 1;
    return pc;
}

function renderPager() {
    var total = (state.records || []).length;
    var pc = pageCount();
    var box = $('pager');
    if (!box) return;
    // 有记录就一直显示（只有一页时按钮禁用）。
    // 不能因为"只有一页"就藏起来，否则每页改成 50 之后，就再也改不回 20 了。
    box.className = 'pager' + (total ? ' show' : '');
    if (!total) return;

    var from = (state.page - 1) * state.pageSize + 1;
    var to = Math.min(total, state.page * state.pageSize);
    $('pgInfo').textContent = '第 ' + from + '–' + to + ' 条，共 ' + total + ' 条';
    $('pgNum').textContent = state.page + ' / ' + pc;
    $('pgPrev').disabled = state.page <= 1;
    $('pgNext').disabled = state.page >= pc;
    $('pgSize').value = String(state.pageSize);
}

function gotoPage(n) {
    state.page = Math.max(1, Math.min(n, pageCount()));
    renderTable();
    // 翻页后把台账滚回视野顶部
    var card = $('tbody').closest('.card');
    if (card) {
        var top = card.getBoundingClientRect().top + window.scrollY - 80;
        window.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
    }
}

/* 顶栏是 sticky 的，表头吸顶时要让开它的高度（窄屏会换行变高） */
function syncTopbarHeight() {
    var bar = document.querySelector('.topbar');
    if (bar) {
        document.documentElement.style.setProperty('--topbar-h', bar.offsetHeight + 'px');
    }
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
    openDialog({
        title: '新建报销单',
        text: '把选中的 ' + ids.length + ' 张发票归到一张新单里。',
        ok: '新建',
        fields: [{
            key: 'batch_no', label: '报销单号（留空自动生成）',
            placeholder: '不填就按日期自动编号，例如 BX20261118-001'
        }]
    }).then(function (r) {
        if (!r) return;
        postJSON('/api/attach', { ids: ids, batch_no: r.batch_no || null }).then(function (res) {
            if (!res.ok) { toast(res.error || '归单失败', 'err'); return; }
            var b = res.batch || {};
            toast('已新建报销单 ' + (b.batch_no || ''), 'ok');
            clearSelection();
            loadList();
            openBatchDrawer(b.id);
        });
    });
}

function detachSelected() {
    var ids = selectedIds();
    if (!ids.length) return;
    askYesNo('从报销单拆出',
        '把选中的 ' + ids.length + ' 张发票拆出来？\n\n' +
        '拆出后它们会变成「未归单」，可以再归到别的单里。', '拆出')
        .then(function (yes) {
            if (!yes) return;
            postJSON('/api/detach', { ids: ids }).then(function (r) {
                if (!r.ok) { toast(r.error || '拆出失败', 'err'); return; }
                toast('已拆出 ' + ids.length + ' 张', 'ok');
                clearSelection();
                loadList();
            });
        });
}

/* ------------------------------------------------------------------ */
/* 发票查验                                                            */
/* ------------------------------------------------------------------ */
var auditTimer = null;

function showAuditPanel() {
    $('auditPanel').classList.add('show');
    $('auditPanel').setAttribute('aria-hidden', 'false');
}

function hideAuditPanel() {
    $('auditPanel').classList.remove('show');
    $('auditPanel').setAttribute('aria-hidden', 'true');
}

function scheduleAuditPoll(ms) {
    clearTimeout(auditTimer);
    auditTimer = setTimeout(pollAudit, ms || 1500);
}

function startAudit(payload) {
    postJSON('/api/audit/start', payload).then(function (r) {
        if (!r.ok) { toast(r.error || '查验没能启动', 'err'); return; }
        showAuditPanel();
        renderAudit(r.state);
        toast('开始查验 ' + (r.count || 0) + ' 张发票，进度在右下角', 'ok');
        scheduleAuditPoll(500);
    });
}

function auditSelected() {
    var ids = selectedIds();
    if (ids.length) {
        startAudit({ ids: ids });
        return;
    }
    askYesNo('查验哪些发票？',
        '没有勾选发票。\n\n要查验全部「未查验 / 上次没通过」的发票吗？', '全部查验')
        .then(function (yes) {
            if (yes) startAudit({ scope: 'unchecked' });
        });
}
function pollAudit() {
    api('/api/audit/status').then(function (r) {
        if (!r.ok) { scheduleAuditPoll(3000); return; }
        var s = r.state || {};
        renderAudit(s);
        if (s.running) {
            scheduleAuditPoll(1500);
        } else {
            clearSelection();
            loadList().then(refreshOpenDrawer);
        }
    });
}

/* 查验跑完后，如果详情抽屉还开着，把里面的查验结果一起刷新，
   否则会出现"平台刚查过、抽屉里还显示旧时间"的情况 */
function refreshOpenDrawer() {
    var d = state.drawer;
    if (!d) return;
    if (d.type === 'invoice') {
        renderInvoiceDrawer();
    } else if (d.type === 'batch') {
        api('/api/batch?id=' + d.id).then(function (r) {
            if (!r.ok) return;
            state.drawer.batch = r.batch;
            renderBatchDrawer();
        });
    }
}

function renderAudit(s) {
    if (!s) return;
    var total = Number(s.total || 0), done = Number(s.done || 0);
    $('apCount').textContent = done + '/' + total;
    $('apFill').style.width = (total ? Math.round(done / total * 100) : 0) + '%';
    $('apSum').innerHTML = '成功 <b>' + (s.ok || 0) + '</b>　不通过 <b>' + (s.fail || 0) +
        '</b>　跳过 <b>' + (s.skip || 0) + '</b>' +
        (s.running ? '　当前：' + esc(s.current || '…') : '　已结束');
    $('apLog').innerHTML = (s.log || []).slice(-40).map(function (e) {
        return '<div>[' + esc(e.t || '') + '] ' + esc(e.text || '') + '</div>';
    }).join('');
    $('apLog').scrollTop = $('apLog').scrollHeight;
    $('apStop').disabled = !s.running;
    $('apStop').textContent = s.running ? '停止查验' : '已结束';
}

function stopAudit() {
    postJSON('/api/audit/stop', {}).then(function (r) {
        toast('已请求停止，当前这张查完就停', 'ok');
        scheduleAuditPoll(800);
    });
}

/* 详情里点缩略图看大图 */
function showShotBig(id) {
    var box = document.getElementById('shotBig');
    if (!box) {
        box = document.createElement('div');
        box.id = 'shotBig';
        box.className = 'au-shot-big';
        box.innerHTML = '<img alt="查验结果截图">' +
            '<div class="au-shot-tip">点任意处关闭</div>';
        box.addEventListener('click', function () { hideShotBig(); });
        document.body.appendChild(box);
    }
    box.querySelector('img').src = '/api/audit/shot?id=' + id + '&t=' + Date.now();
    box.classList.add('show');
}

function hideShotBig() {
    var box = document.getElementById('shotBig');
    if (box) box.classList.remove('show');
}

function shotBigOpen() {
    var box = document.getElementById('shotBig');
    return !!(box && box.classList.contains('show'));
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
        h += '<div class="dl-sec">查验结果</div>';
        h += dlRow('查验状态', auditBadge(r));
        h += dlRow('查验时间', esc(r.audit_at || '—'));
        h += dlRow('查验说明', esc(r.audit_msg || '—'));
        // 弱提示：按台账现有人名现场算，改了人立刻消失，不进感叹号
        var payeeHint = payeeHintText(r);
        if (payeeHint) h += dlRow('一点提示', '<span class="au-note">' + esc(payeeHint) + '</span>');
        if (r.audit_warn) {
            var ws = String(r.audit_warn).split('；').filter(function (x) { return x.trim(); });
            h += '<div class="dl-row"><div class="dl-k">需要留意</div><div class="dl-v">' +
                ws.map(function (x) { return '<div class="au-warn-row">⚠ ' + esc(x) + '</div>'; }).join('') +
                '</div></div>';
        }
        if (r.audit_shot) {
            h += '<div class="dl-row"><div class="dl-k">结果截图</div>' +
                '<div class="dl-v">' +
                '<img class="au-shot" alt="查验结果截图" src="/api/audit/shot?id=' + r.id +
                '&t=' + encodeURIComponent(r.audit_at || '') + '" data-shot="' + r.id + '">' +
                '<div class="dl-note">点图片看大图，可作为已查验的凭证。</div></div></div>';
        } else {
            h += dlRow('结果截图', '<span class="payee-none">还没有截图</span>');
        }
        h += '<div class="dl-sec">其他</div>';
        h += dlRow('登记时间', esc(r.created_at || '—'));
        h += dlRow('原件文件', r.file_name ? esc(r.file_name) : '<span class="payee-none">没有留存原件</span>');
        if (r.file_rel) h += dlRow('存放位置', esc(r.file_rel));

        $('drawerFoot').innerHTML =
            (r.file_rel ? '<button type="button" data-d="view">查看原件</button>'
                : '<button type="button" disabled>无原件</button>') +
            '<button type="button" data-d="audit">查验这张</button>' +
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

/* ---------------- 审核 ---------------- */
function reviewBatch(action) {
    var b = state.drawer && state.drawer.batch;
    if (!b) return;
    var ids = [];
    if (action === 'return') {
        document.querySelectorAll('#drawerBody input[data-issue]:checked')
            .forEach(function (el) { ids.push(Number(el.dataset.issue)); });
        openDialog({
            title: '退回修改',
            text: '单据 ' + b.batch_no + '（' + moneyY(b.amount) + '）\n' +
                (ids.length ? '已勾选 ' + ids.length + ' 张有问题的发票，提交人会看到红标。'
                            : '没有勾选具体发票，退回后整单都可以修改。'),
            ok: '退回',
            danger: true,
            fields: [{
                key: 'note', type: 'textarea', required: true,
                label: '退回原因（提交人一定会看到，请写清楚要改什么）',
                placeholder: '例如：第 2 张发票金额与发票不符，请核对后重新提交',
                err: '请写明退回原因，不然提交人不知道要改什么'
            }]
        }).then(function (r) {
            if (!r) return;
            doReview(action, b, r.note, ids);
        });
    } else {
        askYesNo('审核通过',
            '确认审核通过「' + b.batch_no + '」？\n\n' +
            '共 ' + b.count + ' 张，合计 ' + moneyY(b.amount) + '\n' +
            '通过后提交人将不能再修改这张单。',
            '审核通过')
            .then(function (yes) {
                if (yes) doReview(action, b, '', []);
            });
    }
}

function doReview(action, b, note, ids) {
    postJSON('/api/batch/review', {
        id: b.id, action: action, note: note, issues: ids
    }).then(function (r) {
        if (!r.ok) { toast(r.error || '操作失败', 'err'); return; }
        toast(action === 'approve' ? '已通过，单据已锁定' : '已退回，提交人会收到通知', 'ok');
        state.drawer.batch = r.batch;
        renderBatchDrawer();
        loadList();
    });
}

function resubmitBatch() {
    var b = state.drawer && state.drawer.batch;
    if (!b) return;
    askYesNo('重新提交审核',
        '确认修改完成、重新提交「' + b.batch_no + '」？\n\n' +
        '提交后又要等管理员审核，这期间不能修改。', '重新提交')
        .then(function (yes) {
            if (!yes) return;
            postJSON('/api/batch_resubmit', { id: b.id }).then(function (r) {
                if (!r.ok) { toast(r.error || '提交失败', 'err'); return; }
                toast('已重新提交，等待审核', 'ok');
                state.drawer.batch = r.batch;
                renderBatchDrawer();
                loadList();
            });
        });
}

function renderBatchDrawer() {
    var b = state.drawer && state.drawer.batch;
    if (!b) return;
    var st = b.status === 1 ? '<span class="tag ok">整单已报销</span>'
        : (b.status === -1 ? '<span class="tag part">部分已报销（' + b.paid_count + '/' + b.count + '）</span>'
            : '<span class="tag bad">未报销</span>');
    var rv = b.review_status || 'approved';
    var rvTag = rv === 'submitted' ? '<span class="tag rv-sub">待审核</span>'
        : (rv === 'returned' ? '<span class="tag rv-ret">已退回</span>'
            : '<span class="tag ok">已通过</span>');
    var issues = {};
    (b.issues || []).forEach(function (id) { issues[id] = 1; });
    var admin = isAdmin();
    var canReview = admin && rv === 'submitted';
    var h = '';

    if (state.drawerEdit === 'batch') {
        h += '<div class="dl-sec">报销单信息</div>';
        h += editRow('报销单号', 'batch_no', b.batch_no, 'text');
        h += editRow('报销人', 'person', b.person, 'text');
        h += editRow('收款人', 'payee', b.payee, 'text');
        h += editRow('报销日期', 'reimburse_date', b.reimburse_date, 'date');
        h += editRow('备注', 'batch_note', b.note, 'text');
        h += '<div class="dl-note">改报销人 / 收款人 / 报销日期，会同步改到本单里的每一张发票。</div>';

        // 编辑状态下也要能改发票 / 补发票，否则"退回后修改"只能改单头
        h += '<div class="dl-sec">本单发票' +
            '<span class="dl-hint">可以直接改号码、日期、金额，点保存一起提交</span></div>';
        h += '<div class="mini-wrap"><table class="mini mini-edit"><thead><tr>' +
            '<th>#</th><th>发票号码</th><th>开票日期</th><th>金额</th><th>操作</th>' +
            '</tr></thead><tbody>';
        (b.invoices || []).forEach(function (i, idx) {
            h += '<tr data-inv="' + i.id + '">' +
                '<td>' + (idx + 1) + '</td>' +
                '<td><input class="mi-no" value="' + esc(i.invoice_no || '') + '"></td>' +
                '<td><input class="mi-date" type="date" value="' +
                esc((i.invoice_date || '').slice(0, 10)) + '"></td>' +
                '<td><input class="mi-amt" value="' +
                esc(i.amount === null || i.amount === undefined ? '' : i.amount) + '"></td>' +
                '<td>' + (canDeleteBatch(b)
                    ? '<button type="button" class="mi-del" data-rm="' + i.id + '">移除</button>'
                    : '') + '</td>' +
                '</tr>';
        });
        if (!(b.invoices || []).length) {
            h += '<tr><td colspan="5" class="mi-empty">本单还没有发票，点下面「添加发票」</td></tr>';
        }
        h += '</tbody></table></div>';
        h += '<button type="button" class="add-inv-line" data-addinv="1">' +
            '＋ 添加发票（PDF / OFD，可多选）</button>';
        h += '<div class="dl-note">「移除」只是把这张发票从本单删掉，原件会进「数据\\已删除」。</div>';

        $('drawerFoot').innerHTML =
            '<button type="button" data-d="savebatch" class="solid">保存</button>' +
            '<button type="button" data-d="canceledit">取消</button>';
    } else {
        h += '<div class="dl-sec">报销单信息</div>';
        h += dlRow('报销单号', '<b>' + esc(b.batch_no) + '</b>');
        h += dlRow('审核状态', rvTag);
        h += dlRow('报销人', esc(b.person || '—'));
        h += dlRow('收款人', esc(b.payee || '—'));
        h += dlRow('报销日期', esc(b.reimburse_date || '—'));
        h += dlRow('报销状态', st);
        if (b.status === 1) h += dlRow('报销确认时间', esc(b.paid_at || '—'));
        h += dlRow('本单合计', '<span class="big">' + moneyY(b.amount) + '</span>');
        h += dlRow('发票张数', b.count + ' 张');
        h += dlRow('查验情况', batchAuditSummary(b.invoices || []));
        h += dlRow('备注', esc(b.note || '—'));
        h += dlRow('建单时间', esc(b.created_at || '—'));
        if (b.reviewed_at) h += dlRow('审核时间', esc(b.reviewed_at));
        if (b.review_note) {
            h += dlRow(rv === 'returned' ? '退回原因' : '审核备注',
                '<span class="' + (rv === 'returned' ? 'rv-note' : '') + '">' +
                esc(b.review_note) + '</span>');
        }

        h += '<div class="dl-sec">本单明细' +
            (canReview ? '<span class="dl-hint">勾选有问题的发票，退回时会告诉提交人</span>' : '') + '</div>';
        h += '<div class="mini-wrap' + (canReview ? ' mini-rv' : '') + '"><table class="mini"><thead><tr>' +
            (canReview ? '<th style="width:28px"></th>' : '') +
            '<th>#</th><th>发票号码</th><th>开票日期</th><th>金额</th><th>状态</th><th>查验</th></tr></thead><tbody>';
        (b.invoices || []).forEach(function (i, idx) {
            h += '<tr data-inv="' + i.id + '"' + (issues[i.id] ? ' class="rv-flag"' : '') + '>' +
                (canReview ? '<td><input type="checkbox" data-issue="' + i.id + '"' +
                    (issues[i.id] ? ' checked' : '') + '></td>' : '') +
                '<td>' + (idx + 1) + '</td>' +
                '<td class="no">' + esc(i.invoice_no) + '</td>' +
                '<td>' + esc(i.invoice_date || '') + '</td>' +
                '<td class="money">' + moneyY(i.amount) + '</td>' +
                '<td>' + (Number(i.status) === 1
                    ? '<span class="tag ok">已报销</span>'
                    : '<span class="tag bad">未报销</span>') + '</td>' +
                '<td>' + auditBadge(i) + '</td></tr>';
        });
        h += '</tbody></table></div>';
        h += '<div class="dl-note">点上面任意一行，可以打开那张发票的详情。</div>';

        // 底部按钮：先放审核动作，再放常规动作
        var foot = '';
        if (canReview) {
            foot += '<button type="button" class="solid" data-d="approve">审核通过</button>' +
                '<button type="button" class="danger" data-d="reject">退回修改</button>';
        }
        if (rv === 'returned' && (!admin || admin)) {
            foot += '<button type="button" class="solid" data-d="resubmit">修改完成，重新提交</button>';
        }
        if (admin) {
            foot += (b.status === 1
                ? '<button type="button" data-d="batchstatus" data-v="0">整单改回未报销</button>'
                : '<button type="button" data-d="batchstatus" data-v="1" class="solid">整单标记已报销</button>');
            foot += '<button type="button" data-d="auditbatch">查验本单</button>';
        }
        if (b.can_edit) foot += '<button type="button" data-d="editbatch">修改单信息</button>';
        // 删除整单：只有提交人本人，且不是「已通过」。待审核时等于"撤回"
        if (canDeleteBatch(b)) {
            foot += '<span class="spacer"></span>' +
                '<button type="button" class="danger" data-d="delbatch">' +
                (String(b.review_status || '') === 'submitted' ? '撤回并删除' : '删除整单') +
                '</button>';
        }
        $('drawerFoot').innerHTML = foot;
    }
    $('drawerBody').innerHTML = h;
}

/* 一张报销单的查验汇总，例如「3 张：通过 2 / 未查 1」 */
function batchAuditSummary(invs) {
    if (!invs.length) return '—';
    var c = { 0: 0, 1: 0, 2: 0, 3: 0 }, warn = 0;
    invs.forEach(function (i) {
        c[auditStatus(i)] = (c[auditStatus(i)] || 0) + 1;
        if (i.audit_warn) warn++;
    });
    var parts = [];
    if (c[1]) parts.push('<span class="tag au-pass">通过 ' + c[1] + '</span>');
    if (c[2]) parts.push('<span class="tag au-fail">不通过 ' + c[2] + '</span>');
    if (c[0]) parts.push('<span class="tag au-none">未查 ' + c[0] + '</span>');
    if (c[3]) parts.push('<span class="tag au-skip">无需查 ' + c[3] + '</span>');
    var s = parts.join(' ');
    if (warn) s += ' <span class="au-warn" title="本单有 ' + warn + ' 张带提示">!</span>';
    return s;
}

function saveBatchEdit() {
    var b = state.drawer && state.drawer.batch;
    if (!b) return;
    var patch = { id: b.id };
    document.querySelectorAll('#drawerBody input[data-e]').forEach(function (el) {
        var k = el.dataset.e;
        patch[k === 'batch_note' ? 'note' : k] = el.value.trim() || null;
    });

    // 收集发票行的改动
    var chans = [];
    document.querySelectorAll('#drawerBody tr[data-inv] input.mi-no').forEach(function (noEl) {
        var tr = noEl.closest('tr');
        var id = Number(tr.dataset.inv);
        var old = (b.invoices || []).filter(function (x) { return x.id === id; })[0] || {};
        var no = digits(noEl.value);
        var date = tr.querySelector('.mi-date').value || null;
        var amtRaw = tr.querySelector('.mi-amt').value.trim();
        var amt = amtRaw === '' ? null : Number(amtRaw);
        if (isNaN(amt)) amt = null;
        var changed = (no !== digits(old.invoice_no)) ||
            ((date || null) !== ((old.invoice_date || '').slice(0, 10) || null)) ||
            (amountOf(amt) !== amountOf(old.amount));
        if (!no) { chans.push({ id: id, error: '发票号码不能为空' }); return; }
        if (changed) chans.push({ id: id, invoice_no: no, invoice_date: date, amount: amt });
    });
    var bad = chans.filter(function (c) { return c.error; });
    if (bad.length) { toast(bad[0].error, 'err'); return; }

    postJSON('/api/batch_update', patch).then(function (r) {
        if (!r.ok) { toast(r.error || '保存失败', 'err'); return; }
        if (!chans.length) { finishBatchEdit(r.batch); return; }
        // 逐张保存，任何一张失败就停在那张
        var left = chans.length, failed = null;
        chans.forEach(function (c) {
            var payload = { id: c.id, invoice_no: c.invoice_no, amount: c.amount };
            if (c.invoice_date) payload.invoice_date = c.invoice_date;
            postJSON('/api/update', payload).then(function (res) {
                if (!res.ok && !failed) failed = res.error || '修改失败';
                left--;
                if (left <= 0) {
                    if (failed) { toast(failed, 'err'); loadList(); return; }
                    // 重新取一次最新数据
                    api('/api/batch?id=' + b.id).then(function (rr) {
                        finishBatchEdit(rr.batch || r.batch);
                    });
                }
            });
        });
    });
}

function amountOf(v) {
    var n = Number(v);
    return isNaN(n) ? 0 : Math.round(n * 100) / 100;
}

function finishBatchEdit(batch) {
    toast('已保存', 'ok');
    state.drawerEdit = null;
    if (batch) state.drawer.batch = batch;
    renderBatchDrawer();
    loadList();
}

/* 在编辑状态下给本单补发票：选文件 → 识别 → 建记录并挂到本单 */
function addInvoicesToBatch() {
    var b = state.drawer && state.drawer.batch;
    if (!b) return;
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = '.pdf,.ofd';
    input.multiple = true;
    input.onchange = function () { doAddInvoices(b, input.files); };
    input.click();
}

function doAddInvoices(b, files) {
    var arr = Array.prototype.slice.call(files || []);
    if (!arr.length) return;
    $('busy').classList.add('show');
    var results = [];
    var left = arr.length;
    arr.forEach(function (f) {
        f.arrayBuffer().then(function (buf) {
            return fetch('/api/parse', {
                method: 'POST',
                headers: { 'X-File-Name': b64utf8(f.name) },
                body: buf
            }).then(function (r) { return r.json(); });
        }).then(function (r) {
            if (r && r.ok) results.push({ fields: r.fields, token: r.token, exists: r.exists });
            else toast((r && r.error) || ('识别失败：' + f.name), 'err');
        }).catch(function () {
            toast('上传失败：' + f.name, 'err');
        }).then(function () {
            left--;
            if (left === 0) { $('busy').classList.remove('show'); showAddResult(b, results); }
        });
    });
}

function showAddResult(b, results) {
    if (!results.length) return;
    var lines = results.map(function (x, i) {
        var f = x.fields || {};
        return (i + 1) + '. ' + (f.invoice_no || '（没读出号码）') +
            '　' + (f.amount ? '¥' + f.amount : '无金额') +
            (x.exists ? '　⚠ 已登记过，会跳过' : '');
    }).join('\n');
    var usable = results.filter(function (x) { return x.fields && x.fields.invoice_no && !x.exists; });
    openDialog({
        title: '把发票加进本单',
        text: '识别到 ' + results.length + ' 张：\n\n' + lines +
            (usable.length ? '\n\n确认后会把 ' + usable.length + ' 张加入「' + b.batch_no + '」。' : ''),
        ok: usable.length ? '加入本单' : '知道了',
        cancel: usable.length ? '取消' : null
    }).then(function (ok) {
        if (!ok || !usable.length) return;
        var ids = [];
        var seq = usable.length;
        usable.forEach(function (x) {
            var f = x.fields || {};
            postJSON('/api/add', {
                invoice_no: f.invoice_no, invoice_date: f.invoice_date || null,
                amount: f.amount, seller: f.seller || null, buyer: f.buyer || null,
                category: f.category || null, source: f.source || null,
                file_name: x.file_name || f.file_name || null,
                person: b.person || null, payee: b.payee || null,
                reimburse_date: b.reimburse_date || null,
                token: x.token || null
            }).then(function (r) {
                if (r.ok) ids.push(r.id);
                else toast(r.error || '有一张没加上', 'err');
                seq--;
                if (seq === 0 && ids.length) {
                    postJSON('/api/attach', { ids: ids, batch_id: b.id }).then(function (rr) {
                        if (!rr.ok) { toast(rr.error || '加入本单失败', 'err'); return; }
                        toast('已加入 ' + ids.length + ' 张', 'ok');
                        state.drawer.batch = rr.batch;
                        renderBatchDrawer();
                        loadList();
                    });
                }
            });
        });
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
    askYesNo('删除发票记录',
        '发票号码：' + rec.invoice_no + '\n' +
        '报销人：' + (rec.person || '—') + '\n' +
        '金额：' + moneyY(rec.amount) + '\n\n' +
        '（发票原件会移入「数据\\已删除」，不会真正删掉）', '删除', true)
        .then(function (yes) {
            if (!yes) return;
            postJSON('/api/delete', { id: id }).then(function (r) {
                if (!r.ok) { toast(r.error || '删除失败', 'err'); return; }
                toast('已删除', 'ok');
                delete state.selected[id];
                if (after) after();
            });
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
    if (!state.stage.length) { clearStage(); toast('已清空'); return; }
    askYesNo('清空本单',
        '清空已拖入的 ' + state.stage.length + ' 张发票？\n\n' +
        '只是从"本单"里移走，不会影响已经登记到台账的记录。', '清空', true)
        .then(function (yes) {
            if (!yes) return;
            clearStage();
            toast('已清空');
        });
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
    else if (act === 'audit') { startAudit({ ids: [id] }); toast('已开始查验，进度在右下角', 'ok'); }
    else if (act === 'editinv') { state.drawerEdit = 'invoice'; renderInvoiceDrawer(); }
    else if (act === 'saveinv') saveInvoiceEdit();
    else if (act === 'canceledit') {
        state.drawerEdit = null;
        if (state.drawer.type === 'batch') renderBatchDrawer(); else renderInvoiceDrawer();
    }
    else if (act === 'del') deleteInvoice(id, function () { closeDrawer(); loadList(); });
    else if (act === 'openbatch') { var r0 = findRecord(id); if (r0) openBatchDrawer(r0.batch_id); }
    else if (act === 'batchstatus') setBatchStatus(id, Number(btn.dataset.v));
    else if (act === 'auditbatch') {
        var bb = state.drawer.batch || {};
        var ids = (bb.invoices || []).filter(function (i) {
            return auditStatus(i) !== 1;      // 已经通过的不必再查，省配额
        }).map(function (i) { return i.id; });
        if (!ids.length) {
            toast('本单发票都已查验通过，不用再查');
            return;
        }
        startAudit({ ids: ids });
        toast('开始查验本单 ' + ids.length + ' 张，进度在右下角', 'ok');
    }
    else if (act === 'editbatch') { state.drawerEdit = 'batch'; renderBatchDrawer(); }
    else if (act === 'approve') reviewBatch('approve');
    else if (act === 'reject') reviewBatch('return');
    else if (act === 'resubmit') resubmitBatch();
    else if (act === 'savebatch') saveBatchEdit();
    else if (act === 'addinv') addInvoicesToBatch();
    else if (act === 'delbatch') {
        var b = state.drawer.batch;
        var isSub = String(b.review_status || '') === 'submitted';
        askYesNo(isSub ? '撤回并删除' : '删除整张报销单',
            '报销单「' + b.batch_no + '」，共 ' + b.count + ' 张发票、' +
            moneyY(b.amount) + '。\n\n' +
            (isSub ? '这张单还在待审核，撤回后管理员就不用再审了。\n' : '') +
            '单里的发票会一起删掉（原件移入「数据\\已删除」，不会真正删除）',
            isSub ? '撤回并删除' : '删除整单', true)
            .then(function (yes) {
                if (!yes) return;
                doDeleteBatch(b);
            });
    }
});

function doDeleteBatch(b) {
    // 一次请求删整单：后端一次校验、一起删，不会出现"删了一半"
    postJSON('/api/batch_delete', { id: b.id }).then(function (r) {
        if (!r.ok) { toast(r.error || '删除失败', 'err'); return; }
        closeDrawer();
        toast('整单已删除（' + (r.deleted || 0) + ' 张）', 'ok');
        loadList();
    });
}

$('drawerBody').addEventListener('click', function (ev) {
    var shot = ev.target.closest('img[data-shot]');
    if (shot) { showShotBig(Number(shot.dataset.shot)); return; }
    // 编辑本单时：把某张发票从本单删掉
    var rm = ev.target.closest('button[data-rm]');
    if (rm) {
        ev.stopPropagation();
        var rid = Number(rm.dataset.rm);
        var bb = state.drawer && state.drawer.batch;
        askYesNo('从本单移除',
            '把这张发票从「' + (bb ? bb.batch_no : '') + '」删除？\n\n' +
            '记录会一起删掉，原件移入「数据\\已删除」，不会真正删除。', '移除', true)
            .then(function (yes) {
                if (!yes) return;
                postJSON('/api/delete', { id: rid }).then(function (r) {
                    if (!r.ok) { toast(r.error || '移除失败', 'err'); return; }
                    toast('已移除', 'ok');
                    api('/api/batch?id=' + bb.id).then(function (rr) {
                        if (rr.ok) state.drawer.batch = rr.batch;
                        renderBatchDrawer();
                        loadList();
                    });
                });
            });
        return;
    }
    // 编辑本单时：表格下方的「添加发票」
    if (ev.target.closest('[data-addinv]')) {
        ev.stopPropagation();
        addInvoicesToBatch();
        return;
    }
    // 审核时勾选"有问题的发票"不能顺带打开发票详情
    if (ev.target.closest('input[data-issue]')) return;
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
$('btnAudit').addEventListener('click', auditSelected);

$('apClose').addEventListener('click', hideAuditPanel);
$('apStop').addEventListener('click', stopAudit);

document.querySelectorAll('th.sortable').forEach(function (th) {
    th.addEventListener('click', function () {
        var s = th.dataset.sort;
        if (state.sort === s) state.desc = !state.desc;
        else { state.sort = s; state.desc = true; }
        state.page = 1;          // 排序变了，回到第一页
        loadList();
    });
});
var searchTimer = null;
$('q').addEventListener('input', function () {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(function () { state.page = 1; loadList(); }, 280);
});
['fperson', 'fmonth', 'fstatus', 'faudit', 'fbatch', 'freview'].forEach(function (k) {
    $(k).addEventListener('change', function () { state.page = 1; loadList(); });
});
$('refresh').addEventListener('click', loadList);

/* 分页 */
$('pgPrev').addEventListener('click', function () { gotoPage(state.page - 1); });
$('pgNext').addEventListener('click', function () { gotoPage(state.page + 1); });
$('pgSize').addEventListener('change', function () {
    state.pageSize = Number(this.value) || 20;
    state.page = 1;
    renderTable();
});
window.addEventListener('resize', function () {
    syncTopbarHeight();
    var panel = $('notifyPanel');
    if (panel && !panel.hidden) positionNotify();
});

document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && shotBigOpen()) { hideShotBig(); return; }
    if ((e.ctrlKey || e.metaKey) && e.key === 's') { e.preventDefault(); submitBatch(); }
    if (e.key === 'Escape') {
        if ($('modal').classList.contains('show')) { closeModal(); return; }
        if ($('drawer').classList.contains('show')) { closeDrawer(); return; }
        clearSelection();
        hideAlert();
    }
});

$('reimburse_date').value = todayISO();
syncTopbarHeight();
renderStage();

/* ---------------- 登录状态与角色 ---------------- */
function isAdmin() {
    return !!(state.me && state.me.role === 'admin');
}

function renderUserChip() {
    if (!state.me) return;
    var name = state.me.display_name || state.me.username;
    var av = $('userAvatar');
    var un = $('userName');
    if (av) av.textContent = String(name || '?').slice(0, 1);
    if (un) un.textContent = name;
    var head = $('udHead');
    if (head) {
        head.innerHTML = esc(state.me.username) + ' · ' +
            (isAdmin() ? '管理员' : '普通用户') +
            ' · 构建 ' + esc(state.build || '—');
    }
    syncTopbarHeight();   // 加上用户名后顶栏可能变高，吸顶偏移要重量
}

/* ---------------- 右上角账号菜单 ---------------- */
function toggleUserMenu(open) {
    var drop = $('userDrop');
    var btn = $('btnUser');
    if (!drop) return;
    var show = (open === undefined) ? drop.hidden : !!open;
    drop.hidden = !show;
    if (btn) btn.classList.toggle('open', show);
}

function openAccountDialog() {
    var me = state.me || {};
    openDialog({
        title: '账号设置',
        text: '账号：' + (me.username || '') + '\n' +
              '姓名：' + (me.display_name || me.username || '') + '\n' +
              '角色：' + (isAdmin() ? '管理员（可审核、管用户）' : '普通用户（只提交自己的）') +
              '\n\n要换密码请点下面的按钮。',
        ok: '修改密码',
        cancel: '关闭'
    }).then(function (r) {
        if (r) openPasswordDialog();
    });
}

function bindUserMenu() {
    var btn = $('btnUser');
    if (btn) {
        btn.addEventListener('click', function (ev) {
            ev.stopPropagation();
            toggleUserMenu();
        });
    }
    var st = $('udSettings');
    if (st) {
        st.addEventListener('click', function (ev) {
            ev.stopPropagation();
            toggleUserMenu(false);
            openAccountDialog();
        });
    }
    var out = $('udLogout');
    if (out) {
        out.addEventListener('click', function (ev) {
            ev.stopPropagation();
            toggleUserMenu(false);
            postJSON('/api/logout', {}).then(function () { location.href = '/login'; });
        });
    }
    document.addEventListener('click', function (ev) {
        var drop = $('userDrop');
        if (!drop || drop.hidden) return;
        if (ev.target.closest && ev.target.closest('#userChip')) return;
        toggleUserMenu(false);
    });
    document.addEventListener('keydown', function (ev) {
        if (ev.key === 'Escape') toggleUserMenu(false);
    });
}

bindUserMenu();

/* 改自己的密码（要验原密码，不会把自己踢下线） */
function openPasswordDialog() {
    openDialog({
        title: '修改密码',
        text: '登录账号：' + (state.me ? state.me.username : ''),
        ok: '保存',
        fields: [
            { key: 'old', type: 'password', label: '原密码', required: true, err: '请输入原密码' },
            { key: 'new1', type: 'password', label: '新密码（至少 8 位）', required: true,
              err: '请输入新密码' },
            { key: 'new2', type: 'password', label: '再输一次新密码', required: true,
              err: '请再输一次' }
        ],
        validate: function (v) {
            if (v.new1 !== v.new2) return '两次输入的新密码不一样';
            if (String(v.new1).length < 8) return '新密码至少 8 位';
            if (v.new1 === v.old) return '新密码不能和原密码一样';
            return null;
        }
    }).then(function (r) {
        if (!r) return;
        postJSON('/api/password', { old: r.old, new: r.new1 }).then(function (res) {
            if (!res.ok) { toast(res.error || '修改失败', 'err'); return; }
            toast('密码已修改', 'ok');
        });
    });
}

/* 按角色收起用不了的东西（真正的拦截在服务端，这里只是不让人误点） */
function applyRole() {
    var admin = isAdmin();
    var auditBtn = $('btnAudit');
    if (auditBtn) auditBtn.style.display = admin ? '' : 'none';
    // 标了 data-admin-only 的控件（比如「全部审核」筛选）普通用户看不到
    document.querySelectorAll('[data-admin-only]').forEach(function (el) {
        el.style.display = admin ? '' : 'none';
    });
    if (!admin) {
        ['btnFolderArchive', 'btnFolderData'].forEach(function (id) {
            var el = $(id);
            if (el) el.style.display = 'none';
        });
    }
}

function boot() {
    api('/api/me').then(function (r) {
        if (!r.ok) { location.href = '/login'; return; }
        state.me = r.user;
        state.build = r.build || '';
        renderUserChip();
        applyRole();
        renderBell(r.unread || 0);
        loadList();
        if (r.user.must_change_pw) {
            toast('这是初始密码，请尽快修改', 'err');
        }
    });
}

/* ---------------- 系统弹窗（替代 confirm / prompt） ----------------
   用法：
     askYesNo('标题', '正文')                        → Promise<bool>
     askText('标题', '正文', {value, placeholder})    → Promise<string|null>
     openDialog({title, text, fields, ok, cancel, danger}) → Promise<object|null>
*/
var EYE_ICON = '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">' +
    '<path d="M1.8 12S5.4 5.6 12 5.6 22.2 12 22.2 12 18.6 18.4 12 18.4 1.8 12 1.8 12Z" ' +
    'fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>' +
    '<circle cx="12" cy="12" r="3.1" fill="none" stroke="currentColor" stroke-width="1.7"/></svg>';
var EYE_OFF_ICON = '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">' +
    '<path d="M1.8 12S5.4 5.6 12 5.6 22.2 12 22.2 12 18.6 18.4 12 18.4 1.8 12 1.8 12Z" ' +
    'fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>' +
    '<circle cx="12" cy="12" r="3.1" fill="none" stroke="currentColor" stroke-width="1.7"/>' +
    '<path d="M3.4 3.4 L20.6 20.6" stroke="currentColor" stroke-width="1.7" ' +
    'stroke-linecap="round"/></svg>';

var dlgState = null;

function openDialog(opts) {
    opts = opts || {};
    return new Promise(function (resolve) {
        var mask = $('dlgMask');
        dlgState = { resolve: resolve, fields: opts.fields || [], validate: opts.validate };
        $('dlgTitle').textContent = opts.title || '';
        $('dlgText').innerHTML = opts.text
            ? (opts.rawText ? opts.text : esc(opts.text))
            : '';
        $('dlgOk').textContent = opts.ok || '确定';
        $('dlgCancel').textContent = opts.cancel || '取消';
        $('dlgOk').className = 'solid' + (opts.danger ? ' danger' : '');

        var box = $('dlgFields');
        box.innerHTML = dlgState.fields.map(function (f, i) {
            var id = 'dlgf' + i;
            var input;
            if (f.type === 'textarea') {
                input = '<textarea id="' + id + '" placeholder="' +
                    esc(f.placeholder || '') + '">' + esc(f.value || '') + '</textarea>';
            } else if (f.type === 'select') {
                input = '<select id="' + id + '">' + (f.options || []).map(function (o) {
                    return '<option value="' + esc(o.value) + '"' +
                        (String(o.value) === String(f.value) ? ' selected' : '') + '>' +
                        esc(o.label) + '</option>';
                }).join('') + '</select>';
            } else if (f.type === 'password') {
                input = '<span class="pw-wrap">' +
                    '<input id="' + id + '" type="password" value="' + esc(f.value || '') +
                    '" placeholder="' + esc(f.placeholder || '') + '">' +
                    '<button type="button" class="pw-eye" data-pw="' + id +
                    '" title="显示密码" aria-label="显示密码">' + EYE_ICON + '</button>' +
                    '</span>';
            } else {
                input = '<input id="' + id + '" type="text" value="' + esc(f.value || '') +
                    '" placeholder="' + esc(f.placeholder || '') + '">';
            }
            return '<div class="dlg-field" data-fi="' + i + '">' +
                (f.label ? '<label for="' + id + '">' + esc(f.label) + '</label>' : '') +
                input +
                '<div class="dlg-err">' + esc(f.err || '这一项必须填') + '</div>' +
                '</div>';
        }).join('');

        mask.hidden = false;
        var first = box.querySelector('input, textarea, select');
        if (first) {
            first.focus();
            if (first.select && first.tagName === 'INPUT') first.select();
        } else {
            $('dlgOk').focus();
        }
    });
}

function closeDialog(result) {
    var st = dlgState;
    dlgState = null;
    $('dlgMask').hidden = true;
    if (st && st.resolve) st.resolve(result);
}

function dlgCollect() {
    var out = {};
    var bad = null;
    (dlgState.fields || []).forEach(function (f, i) {
        var el = document.getElementById('dlgf' + i);
        var wrap = document.querySelector('.dlg-field[data-fi="' + i + '"]');
        var raw = el ? String(el.value || '') : '';
        // 密码不做 trim，否则用户设的"首尾空格"会被悄悄改掉
        var v = f.type === 'password' ? raw : raw.trim();
        if (f.required && !v) {
            if (wrap) wrap.classList.add('bad');
            if (!bad) bad = el;
            return;
        }
        if (wrap) wrap.classList.remove('bad');
        out[f.key || ('f' + i)] = v;
    });
    if (bad) { bad.focus(); return null; }

    // 附加校验（比如两次密码要一致）
    if (typeof dlgState.validate === 'function') {
        var problem = dlgState.validate(out);
        if (problem) {
            var errBox = $('dlgErr');
            if (errBox) { errBox.textContent = problem; errBox.hidden = false; }
            return null;
        }
    }
    return out;
}

function askYesNo(title, text, okText, danger) {
    return openDialog({
        title: title, text: text,
        ok: okText || '确定',
        danger: !!danger
    }).then(function (r) { return !!r; });
}

function askText(title, text, opts) {
    opts = opts || {};
    return openDialog({
        title: title,
        text: text,
        ok: opts.ok,
        danger: opts.danger,
        fields: [{
            key: 'value', type: opts.type || 'text',
            value: opts.value || '',
            placeholder: opts.placeholder || '',
            required: opts.required !== false
        }]
    }).then(function (r) { return r ? r.value : null; });
}

$('dlgOk').addEventListener('click', function () {
    if (!dlgState) return;
    var vals = dlgCollect();
    if (vals === null) return;
    closeDialog(vals);
});

/* 密码框的小眼睛：点一下在明文/密文之间切换 */
$('dlgFields').addEventListener('click', function (ev) {
    var btn = ev.target.closest('.pw-eye');
    if (!btn) return;
    ev.preventDefault();
    var el = document.getElementById(btn.dataset.pw);
    if (!el) return;
    var show = el.type === 'password';
    el.type = show ? 'text' : 'password';
    btn.innerHTML = show ? EYE_OFF_ICON : EYE_ICON;
    btn.classList.toggle('on', show);
    btn.title = show ? '隐藏密码' : '显示密码';
    el.focus();
});
$('dlgCancel').addEventListener('click', function () { closeDialog(null); });
$('dlgMask').addEventListener('mousedown', function (ev) {
    if (ev.target === $('dlgMask')) closeDialog(null);
});
document.addEventListener('keydown', function (ev) {
    if (!dlgState) return;
    if (ev.key === 'Escape') { ev.preventDefault(); closeDialog(null); return; }
    if (ev.key === 'Enter' && ev.target.tagName !== 'TEXTAREA') {
        ev.preventDefault();
        $('dlgOk').click();
    }
});

/* ---------------- 用户管理 ---------------- */
var ACTION_TEXT = {
    login: '登录', register: '注册', change_password: '改密码',
    submit: '提交单据', resubmit: '重新提交', review_approved: '审核通过',
    review_returned: '审核退回', batch_paid: '标记报销',
    delete_invoice: '删除发票', user_create: '新建用户',
    user_update: '修改用户', user_reset_pw: '重置密码'
};

function hideAllViews() {
    ['entryCard', 'ledgerCard', 'statsView', 'usersView'].forEach(function (id) {
        var el = $(id);
        if (el) el.hidden = true;
    });
    // 离开总览页就把图表销毁，回来时重建。
    // 否则视图隐藏期间画布被压成 0 高，再切回来就是一片空白。
    Object.keys(charts).forEach(function (k) {
        if (charts[k]) {
            try { charts[k].dispose(); } catch (e) { /* 忽略 */ }
        }
        delete charts[k];
    });
}

function showUsers() {
    if (!isAdmin()) return;
    hideAllViews();
    $('usersView').hidden = false;
    location.hash = '#users';
    loadUsers();
    loadLogs();
}

function showLedger() {
    hideAllViews();
    $('entryCard').hidden = false;
    $('ledgerCard').hidden = false;
    location.hash = '';
    Object.keys(charts).forEach(function (k) {
        if (charts[k]) charts[k].resize();
    });
}

function umMsg(text, kind, password) {
    var box = $('umMsg');
    if (!text) { box.hidden = true; box.innerHTML = ''; return; }
    box.hidden = false;
    box.className = 'um-msg ' + (kind || '');
    box.innerHTML = '<span>' + esc(text) +
        (password ? '：<b>' + esc(password) + '</b>' : '') + '</span>' +
        (password ? '<button type="button" id="umCopy">复制密码</button>' : '');
    var btn = document.getElementById('umCopy');
    if (btn) {
        btn.addEventListener('click', function () {
            if (navigator.clipboard) navigator.clipboard.writeText(password);
            else {
                var ta = document.createElement('textarea');
                ta.value = password;
                document.body.appendChild(ta);
                ta.select();
                document.execCommand('copy');
                ta.remove();
            }
            toast('密码已复制', 'ok');
        });
    }
}

function loadUsers() {
    api('/api/users').then(function (r) {
        if (!r.ok) { toast(r.error || '读取用户失败', 'err'); return; }
        renderUsers(r.users || []);
    });
}

function renderUsers(users) {
    var meId = state.me ? state.me.id : 0;
    $('umSub').textContent = '共 ' + users.length + ' 个账号，' +
        users.filter(function (u) { return u.status == 1; }).length + ' 个在用';
    $('umBody').innerHTML = users.map(function (u) {
        var on = u.status == 1;
        var isMe = u.id === meId;
        return '<tr data-uid="' + u.id + '">' +
            '<td><span class="um-name">' + esc(u.username) + '</span>' +
            (isMe ? '<span class="um-me">我</span>' : '') + '</td>' +
            '<td>' + esc(u.display_name || '—') + '</td>' +
            '<td>' + (u.role === 'admin'
                ? '<span class="tag ok">管理员</span>'
                : '<span class="tag grey">用户</span>') + '</td>' +
            '<td>' + (on ? '<span class="tag ok">启用</span>'
                : '<span class="tag bad">停用</span>') + '</td>' +
            '<td class="um-sub">' + esc(fmtTime(u.last_login_at) || '从未登录') + '</td>' +
            '<td class="ops">' +
            '<button type="button" data-uact="rename">改姓名</button>' +
            (isMe
                // 自己这一行不给"重置密码"：重置会立刻把自己踢下线，新密码还只显示一次
                ? '<button type="button" data-uact="selfpw">改密码</button>'
                : '<button type="button" data-uact="role">' +
                  (u.role === 'admin' ? '设为用户' : '设为管理员') + '</button>' +
                  '<button type="button" class="danger" data-uact="toggle">' +
                  (on ? '停用' : '启用') + '</button>' +
                  '<button type="button" data-uact="reset">重置密码</button>') +
            '</td></tr>';
    }).join('') || '<tr><td colspan="6" class="um-sub">还没有账号</td></tr>';
}

var TARGET_TEXT = { user: '账号', invoice: '发票', batch: '单据' };
var LOG_CAT = {
    login: 'login', register: 'account',
    submit: 'biz', resubmit: 'biz',
    review_approved: 'biz', review_returned: 'biz',
    batch_paid: 'biz', delete_invoice: 'biz',
    user_create: 'account', user_update: 'account',
    user_reset_pw: 'account', change_password: 'account'
};
var LOG_FALLBACK = {
    login: '登录成功', register: '自助注册',
    change_password: '修改了自己的密码', user_create: '新建账号',
    user_reset_pw: '重置了密码', batch_paid: '标记报销状态'
};
var logState = { cat: 'all', q: '', days: 30, rows: [] };

function logTagClass(action) {
    if (action === 'review_approved') return 'ok';
    if (action === 'review_returned' || action === 'delete_invoice') return 'bad';
    if (action === 'login') return '';
    if (LOG_CAT[action] === 'account') return 'purple';
    return 'blue';
}

function loadLogs() {
    api('/api/logs').then(function (r) {
        if (!r.ok) return;
        logState.rows = r.logs || [];
        renderLogs();
    });
}

function renderLogs() {
    var rows = logState.rows || [];
    var now = new Date();
    var cutoff = null;
    if (logState.days > 0) {
        cutoff = new Date(now.getTime() - logState.days * 86400000);
    }
    // 先按时间范围过滤，分类计数用这个结果
    var inRange = rows.filter(function (x) {
        if (!cutoff) return true;
        var d = new Date(String(x.created_at || '').replace(' ', 'T'));
        return !isNaN(d.getTime()) && d >= cutoff;
    });
    var counts = { all: inRange.length, biz: 0, account: 0, login: 0 };
    inRange.forEach(function (x) {
        var c = LOG_CAT[x.action] || 'biz';
        counts[c] = (counts[c] || 0) + 1;
    });
    document.querySelectorAll('#logTabs button').forEach(function (b) {
        b.classList.toggle('on', b.dataset.cat === logState.cat);
        var n = b.querySelector('b');
        if (n) n.textContent = counts[b.dataset.cat] || 0;
    });

    var q = (logState.q || '').trim().toLowerCase();
    var list = inRange.filter(function (x) {
        if (logState.cat !== 'all' && (LOG_CAT[x.action] || 'biz') !== logState.cat) return false;
        if (!q) return true;
        var hay = [(x.detail || ''), (x.username || ''),
                   (ACTION_TEXT[x.action] || x.action || '')].join(' ').toLowerCase();
        return hay.indexOf(q) >= 0;
    });

    $('logSub').textContent = '共 ' + list.length + ' 条' +
        (logState.days ? '，近 ' + logState.days + ' 天' : '，全部时间');
    var box = $('logList');
    if (!list.length) {
        box.innerHTML = '<div class="log-empty">这段时间没有日志</div>';
        return;
    }

    // 按天分组
    var groups = [], last = null;
    list.forEach(function (x) {
        var day = String(x.created_at || '').slice(0, 10);
        if (!last || last.day !== day) {
            last = { day: day, items: [] };
            groups.push(last);
        }
        last.items.push(x);
    });

    var WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
    box.innerHTML = groups.map(function (g) {
        var d = new Date(g.day + 'T00:00:00');
        var w = isNaN(d.getTime()) ? '' : ' ' + WEEK[d.getDay()];
        var head = '<div class="log-day">' + esc(g.day) + w +
            '<span class="n">' + g.items.length + ' 条</span></div>';
        var body = g.items.map(function (x) {
            var obj = TARGET_TEXT[x.target_type] || x.target_type || '—';
            var desc = x.detail || LOG_FALLBACK[x.action] || '—';
            return '<div class="log-row">' +
                '<span class="t">' + esc(String(x.created_at || '').slice(11, 19)) + '</span>' +
                '<span><i class="log-obj">' + esc(obj) + '</i></span>' +
                '<span><i class="log-tag ' + logTagClass(x.action) + '">' +
                esc(ACTION_TEXT[x.action] || x.action || '') + '</i></span>' +
                '<span class="d" title="' + esc(desc) + '">' + esc(desc) + '</span>' +
                '<span class="who">' + esc(x.username || '—') + '</span>' +
                '</div>';
        }).join('');
        return head + body;
    }).join('');
}

$('logTabs').addEventListener('click', function (ev) {
    var btn = ev.target.closest('button[data-cat]');
    if (!btn) return;
    logState.cat = btn.dataset.cat;
    renderLogs();
});
$('logDays').addEventListener('change', function () {
    logState.days = Number(this.value) || 0;
    renderLogs();
});
$('logQ').addEventListener('input', function () {
    logState.q = this.value;
    renderLogs();
});

$('btnUsers').addEventListener('click', showUsers);
$('umBack').addEventListener('click', showLedger);

$('umAdd').addEventListener('click', function () {
    openDialog({
        title: '新建用户',
        text: '创建后系统会生成一个初始密码，只显示一次。对方第一次登录会被要求改密码。',
        ok: '创建',
        fields: [
            { key: 'username', label: '用户名', placeholder: '字母开头，3-20 位，字母数字下划线',
              required: true, err: '请填写用户名（字母开头，3-20 位）' },
            { key: 'display_name', label: '姓名', placeholder: '填真实姓名，方便财务对照' },
            { key: 'role', label: '角色', type: 'select', value: 'user',
              options: [
                  { value: 'user', label: '普通用户（只提交、看自己的）' },
                  { value: 'admin', label: '管理员（看全部台账、审核、管用户）' }
              ] }
        ]
    }).then(function (r) {
        if (!r) return;
        postJSON('/api/users/create', {
            username: r.username, display_name: r.display_name, role: r.role
        }).then(function (res) {
            if (!res.ok) { umMsg(res.error || '新建失败', 'err'); return; }
            umMsg('账号 ' + r.username + ' 已创建，初始密码', 'ok', res.password);
            loadUsers();
            loadLogs();
        });
    });
});

$('umBody').addEventListener('click', function (ev) {
    var btn = ev.target.closest('button[data-uact]');
    if (!btn) return;
    var tr = btn.closest('tr[data-uid]');
    var uid = Number(tr.dataset.uid);
    var act = btn.dataset.uact;
    var name = tr.querySelector('.um-name').textContent;

    if (act === 'selfpw') {
        openPasswordDialog();
        return;
    }
    if (act === 'rename') {
        var cur = tr.children[1].textContent.trim();
        openDialog({
            title: '修改姓名',
            text: '用户「' + name + '」',
            ok: '保存',
            fields: [{ key: 'display_name', label: '姓名',
                       value: cur === '—' ? '' : cur,
                       required: true, err: '姓名不能为空' }]
        }).then(function (r) {
            if (!r) return;
            postJSON('/api/users/update', { id: uid, display_name: r.display_name })
                .then(function (res) {
                    if (!res.ok) { umMsg(res.error || '修改失败', 'err'); return; }
                    umMsg('已修改姓名', 'ok');
                    loadUsers();
                });
        });
    } else if (act === 'role') {
        var toAdmin = btn.textContent.indexOf('设为管理员') >= 0;
        askYesNo('修改角色',
            '把「' + name + '」' + (toAdmin ? '设为管理员？' : '降为普通用户？') + '\n\n' +
            (toAdmin ? '管理员能看全部台账、审核单据、管理用户。' : '降级后只能看自己的提交记录。'),
            toAdmin ? '设为管理员' : '降为普通用户', toAdmin)
            .then(function (yes) {
                if (!yes) return;
                postJSON('/api/users/update', { id: uid, role: toAdmin ? 'admin' : 'user' })
                    .then(function (r) {
                        if (!r.ok) { umMsg(r.error || '修改失败', 'err'); return; }
                        umMsg('已修改角色', 'ok');
                        loadUsers();
                        loadLogs();
                    });
            });
    } else if (act === 'toggle') {
        var enabling = btn.textContent.indexOf('启用') >= 0;
        askYesNo(enabling ? '启用账号' : '停用账号',
            (enabling ? '启用「' + name + '」？启用后可以正常登录。'
                      : '停用「' + name + '」？\n\n停用后这个人会立刻掉线，也登录不进来，' +
                        '但他交过的单据都还在。'),
            enabling ? '启用' : '停用', !enabling)
            .then(function (yes) {
                if (!yes) return;
                postJSON('/api/users/update', { id: uid, status: enabling ? 1 : 0 })
                    .then(function (r) {
                        if (!r.ok) { umMsg(r.error || '操作失败', 'err'); return; }
                        umMsg('已' + (enabling ? '启用' : '停用'), 'ok');
                        loadUsers();
                        loadLogs();
                    });
            });
    } else if (act === 'reset') {
        askYesNo('重置密码',
            '重置「' + name + '」的密码？\n\n会生成一个新密码，只显示这一次，' +
            '对方下次登录要用新密码。',
            '重置密码', true)
            .then(function (yes) {
                if (!yes) return;
                postJSON('/api/users/reset_password', { id: uid }).then(function (r) {
                    if (!r.ok) { umMsg(r.error || '重置失败', 'err'); return; }
                    umMsg('已重置 ' + name + ' 的密码，新密码', 'ok', r.password);
                    loadUsers();
                    loadLogs();
                });
            });
    }
});

/* ---------------- 数据总览 ---------------- */
var charts = {};

function statCards(o) {
    var rate = o.audit_rate;
    function card(k, v, unit, cls) {
        return '<div class="sv-card' + (cls ? ' ' + cls : '') + '">' +
            '<div class="k">' + k + '</div>' +
            '<div class="v">' + v + (unit ? '<small>' + unit + '</small>' : '') + '</div></div>';
    }
    return [
        card('待审核单据', o.pending || 0, '单', (o.pending ? 'warn' : 'ok')),
        card('已退回单据', o.returned || 0, '单', (o.returned ? 'bad' : '')),
        card('本月发票', o.month_count || 0, '张'),
        card('本月金额', moneyY(o.month_amount || 0), ''),
        card('累计发票', o.claims || 0, '张'),
        card('累计金额', moneyY(o.claims_amount || 0), ''),
        card('查验通过率', (rate === undefined || rate === null) ? '—' : rate + '%', ''),
        card('平均审核时长', o.avg_review_hours ? o.avg_review_hours : '—',
             o.avg_review_hours ? '小时' : '')
    ].join('');
}

function chartBox(id, ok) {
    var el = $(id);
    if (!el) return null;
    if (typeof echarts === 'undefined') {
        el.innerHTML = '<div class="sv-empty">图表库没加载成功，请刷新页面</div>';
        return null;
    }
    if (!ok) {
        el.innerHTML = '<div class="sv-empty">暂无数据</div>';
        return null;
    }
    el.innerHTML = '';
    if (!charts[id]) charts[id] = echarts.init(el);
    return charts[id];
}

function showStats() {
    if (!isAdmin()) return;
    hideAllViews();
    $('statsView').hidden = false;
    location.hash = '#stats';
    api('/api/stats/overview').then(function (r) {
        if (!r.ok) { toast(r.error || '统计读取失败', 'err'); return; }
        var o = r.overview || {};
        // 查验通过率 = 通过 / (通过+不通过)
        var pass = 0, fail = 0;
        (r.by_audit || []).forEach(function (a) {
            if (a.status === 1) pass = a.count;
            if (a.status === 2) fail = a.count;
        });
        o.audit_rate = (pass + fail) ? Math.round(pass * 1000 / (pass + fail)) / 10 : null;
        $('svCards').innerHTML = statCards(o);
        $('svSub').textContent = '共 ' + (o.claims || 0) + ' 张发票，' +
            (o.users || 0) + ' 个在用账号';
        drawCharts(r);
    });
}

function hideStats() {
    showLedger();
}

/* 从总览页点浏览器后退，回到台账 */
window.addEventListener('hashchange', function () {
    var h = location.hash;
    if (h === '#stats' && $('statsView').hidden) { showStats(); return; }
    if (h === '#users' && $('usersView').hidden) { showUsers(); return; }
    if (h !== '#stats' && h !== '#users' &&
        (!$('statsView').hidden || !$('usersView').hidden)) {
        showLedger();
    }
});

function drawCharts(r) {
    var trend = r.trend || [];
    var c1 = chartBox('chartTrend', trend.length);
    if (c1) {
        c1.setOption({
            grid: { left: 44, right: 56, top: 42, bottom: 28 },
            tooltip: {
                trigger: 'axis',
                formatter: function (ps) {
                    var rows = ['<b>' + ps[0].axisValue + '</b>'];
                    ps.forEach(function (p) {
                        rows.push(p.marker + p.seriesName + '：' +
                            (p.seriesName === '金额（元）' ? moneyY(p.value) : p.value + ' 张'));
                    });
                    return rows.join('<br>');
                }
            },
            // 单位写进图例，省掉坐标轴上的"张""元"两个悬浮文字（原来会跟图例打架）
            legend: { right: 8, top: 6, itemWidth: 12, itemHeight: 8, itemGap: 16 },
            xAxis: {
                type: 'category',
                data: trend.map(function (t) { return t.month; }),
                axisTick: { show: false },
                axisLine: { lineStyle: { color: '#E2E8F0' } },
                axisLabel: { color: '#64748B', fontSize: 11, hideOverlap: true }
            },
            yAxis: [
                { type: 'value',
                  splitLine: { lineStyle: { color: '#F1F5F9' } },
                  axisLabel: { color: '#64748B', fontSize: 11 } },
                { type: 'value',
                  splitLine: { show: false },
                  axisLabel: { color: '#64748B', fontSize: 11,
                               formatter: function (v) { return v >= 10000 ? (v / 10000) + '万' : v; } } }
            ],
            series: [
                { name: '张数（张）', type: 'bar', barMaxWidth: 26,
                  itemStyle: { color: '#93C5FD', borderRadius: [4, 4, 0, 0] },
                  data: trend.map(function (t) { return t.count; }) },
                { name: '金额（元）', type: 'line', yAxisIndex: 1, smooth: false,
                  symbol: 'circle', symbolSize: 6, clip: true,
                  itemStyle: { color: '#2563EB' },
                  lineStyle: { width: 2, color: '#2563EB' },
                  areaStyle: { color: 'rgba(37,99,235,.06)' },
                  data: trend.map(function (t) { return t.amount; }) }
            ]
        }, true);
    }

    var rv = (r.overview || {});
    var c2 = chartBox('chartReview', (rv.pending + rv.returned + rv.approved) > 0);
    if (c2) {
        c2.setOption({
            tooltip: { trigger: 'item', formatter: '{b}：{c} 单（{d}%）' },
            legend: { bottom: 0, itemWidth: 10, itemHeight: 10 },
            series: [{
                type: 'pie', radius: ['46%', '68%'], center: ['50%', '45%'],
                avoidLabelOverlap: true,
                label: { show: false },
                data: [
                    { name: '待审核', value: rv.pending || 0, itemStyle: { color: '#F59E0B' } },
                    { name: '已退回', value: rv.returned || 0, itemStyle: { color: '#EF4444' } },
                    { name: '已通过', value: rv.approved || 0, itemStyle: { color: '#10B981' } }
                ]
            }]
        }, true);
    }

    var AUDIT_NAME = ['未查验', '查验通过', '查验不通过', '无需查验'];
    var AUDIT_COLOR = ['#CBD5E1', '#10B981', '#EF4444', '#94A3B8'];
    var byAudit = r.by_audit || [];
    var c3 = chartBox('chartAudit', byAudit.length);
    if (c3) {
        c3.setOption({
            tooltip: { trigger: 'item', formatter: '{b}：{c} 张（{d}%）' },
            legend: { bottom: 0, itemWidth: 10, itemHeight: 10 },
            series: [{
                type: 'pie', radius: ['46%', '68%'], center: ['50%', '45%'],
                label: { show: false },
                data: byAudit.map(function (a) {
                    return { name: AUDIT_NAME[a.status] || '其他', value: a.count,
                             itemStyle: { color: AUDIT_COLOR[a.status] || '#CBD5E1' } };
                })
            }]
        }, true);
    }

    ['chartPerson', 'chartSeller'].forEach(function (id, i) {
        var rows = (i === 0 ? r.by_person : r.by_seller) || [];
        renderRank(id, rows, i === 0 ? '#60A5FA' : '#34D399');
    });
}

/* 排行榜：名字完整显示（长了就换行），下面是占比条 + 张数 */
function renderRank(id, rows, color) {
    var el = $(id);
    if (!el) return;
    if (!rows.length) {
        el.innerHTML = '<div class="sv-empty">暂无数据</div>';
        return;
    }
    var max = 1;
    rows.forEach(function (r) { max = Math.max(max, r.amount || 0); });
    el.innerHTML = rows.map(function (r, i) {
        var pct = Math.max(2, Math.round((r.amount || 0) * 100 / max));
        return '<div class="rk" title="' + esc(r.name) + '">' +
            '<div class="rk-top">' +
            '<span class="rk-no">' + (i + 1) + '</span>' +
            '<span class="rk-name">' + esc(r.name) + '</span>' +
            '<span class="rk-amt">' + moneyY(r.amount) + '</span>' +
            '</div>' +
            '<div class="rk-bar"><i style="width:' + pct + '%;background:' + color + '"></i></div>' +
            '<div class="rk-meta">' + (r.count || 0) + ' 张</div>' +
            '</div>';
    }).join('');
}

$('btnStats').addEventListener('click', showStats);
$('svBack').addEventListener('click', hideStats);
window.addEventListener('resize', function () {
    Object.keys(charts).forEach(function (k) {
        if (charts[k]) charts[k].resize();
    });
});
var KIND_ICON = { submit: '📥', return: '↩️', approve: '✅', account: '👤', withdraw: '🚫' };

function renderBell(n) {
    var badge = $('bellBadge');
    var bell = $('btnBell');
    if (!badge || !bell) return;
    if (n > 0) {
        badge.textContent = n > 99 ? '99+' : String(n);
        badge.hidden = false;
        bell.classList.add('has-unread');
    } else {
        badge.hidden = true;
        bell.classList.remove('has-unread');
    }
}

/* 未读数：30 秒轮询一次，够用了，不用 WebSocket */
function refreshUnread() {
    api('/api/me').then(function (r) {
        if (r && r.ok) renderBell(r.unread || 0);
    });
}

function fmtTime(s) {
    if (!s) return '';
    var t = String(s).replace('T', ' ');
    var ymd = t.slice(0, 10), hm = t.slice(11, 16);
    var now = new Date();
    var today = now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    var y = new Date(now.getTime() - 86400000);
    var yest = y.getFullYear() + '-' + pad2(y.getMonth() + 1) + '-' + pad2(y.getDate());
    if (ymd === today) return '今天 ' + hm;
    if (ymd === yest) return '昨天 ' + hm;
    return ymd.slice(5) + ' ' + hm;
}

function pad2(n) { return n < 10 ? '0' + n : String(n); }

/* 面板贴着铃铛定位：顶栏换行、窗口缩放都不会跑偏 */
function positionNotify() {
    var bell = $('btnBell');
    var panel = $('notifyPanel');
    if (!bell || !panel) return;
    var r = bell.getBoundingClientRect();
    panel.style.top = Math.round(r.bottom + 8) + 'px';
    panel.style.right = Math.max(12, Math.round(window.innerWidth - r.right)) + 'px';
}

function openNotify() {
    var panel = $('notifyPanel');
    if (!panel) return;
    positionNotify();
    panel.hidden = false;
    $('bellBadge').hidden = true;
    api('/api/notifications').then(function (r) {
        if (!r.ok) { toast(r.error || '消息加载失败', 'err'); return; }
        renderNotify(r.items || []);
        renderBell(r.unread || 0);
    });
}

function closeNotify() {
    var panel = $('notifyPanel');
    if (panel) panel.hidden = true;
}

function renderNotify(items) {
    var panel = $('notifyPanel');
    var box = $('npList');
    if (!box) return;
    panel.classList.toggle('empty', !items.length);
    $('npEmpty').style.display = items.length ? 'none' : 'block';
    box.innerHTML = items.map(function (m) {
        var unread = !m.read_at;
        return '<button type="button" class="np-item' + (unread ? ' unread' : '') +
            '" data-nid="' + m.id + '" data-batch="' + (m.batch_id || '') + '">' +
            '<span class="np-top">' +
            (unread ? '<i class="np-dot"></i>' : '') +
            '<span class="np-title">' + (KIND_ICON[m.kind] || '🔔') + ' ' +
            esc(m.title || '') + '</span>' +
            '<span class="np-time">' + esc(fmtTime(m.created_at)) + '</span>' +
            '</span>' +
            '<span class="np-body">' + esc(m.body || '') + '</span>' +
            '</button>';
    }).join('');
}

function markAllRead() {
    postJSON('/api/notifications/read', {}).then(function (r) {
        if (!r.ok) { toast(r.error || '操作失败', 'err'); return; }
        renderBell(r.unread || 0);
        openNotify();
    });
}

$('btnBell').addEventListener('click', function (ev) {
    ev.stopPropagation();
    var panel = $('notifyPanel');
    if (panel.hidden) openNotify(); else closeNotify();
});

$('npReadAll').addEventListener('click', function (ev) {
    ev.stopPropagation();
    markAllRead();
});

$('npList').addEventListener('click', function (ev) {
    var item = ev.target.closest('.np-item');
    if (!item) return;
    ev.stopPropagation();
    var nid = Number(item.dataset.nid);
    var bid = item.dataset.batch;
    postJSON('/api/notifications/read', { ids: [nid] }).then(function (r) {
        if (r.ok) renderBell(r.unread || 0);
    });
    item.classList.remove('unread');
    var dot = item.querySelector('.np-dot');
    if (dot) dot.remove();
    if (bid) {
        closeNotify();
        openBatchDrawer(Number(bid));
    }
});

/* 点面板外面 / 按 Esc 关掉 */
document.addEventListener('click', function (ev) {
    var panel = $('notifyPanel');
    if (!panel || panel.hidden) return;
    if (panel.contains(ev.target)) return;
    if (ev.target.closest && ev.target.closest('#btnBell')) return;
    closeNotify();
});
document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') closeNotify();
});

setInterval(refreshUnread, 30000);

boot();

/* 打开页面时如果后台正在查验（比如刷新了浏览器），把进度浮层接回来 */
api('/api/audit/status').then(function (r) {
    if (r.ok && r.state && r.state.running) {
        showAuditPanel();
        renderAudit(r.state);
        scheduleAuditPoll(1200);
    }
});

/* "打开文件夹"只有本机（Windows 桌面）才有意义。
   按钮在 HTML 里默认 hidden，能打开才显示，避免刷新时闪一下又消失。 */
api('/api/ping').then(function (r) {
    if (r && r.ok && r.can_open_local && isAdmin()) {
        ['btnFolderArchive', 'btnFolderData'].forEach(function (id) {
            var el = $(id);
            if (el) el.hidden = false;
        });
    }
});
