// 历史报告页面脚本

let currentPage = 0;
const pageSize = 10;
let totalPages = 0;
let activeTraceGraph = null;
let activeTraceTaskId = null;
let activeTraceNodeId = null;
let activeTraceDebate = null;

document.addEventListener('DOMContentLoaded', function() {
    loadHistory();
    
    // 监听回车键查询
    document.getElementById('nameFilter').addEventListener('keypress', function(e) {
        if (e.key === 'Enter') {
            searchHistory();
        }
    });

    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') {
            closeTraceGraph();
            closeQualityGate();
        }
    });
});

/**
 * 加载历史记录
 */
function loadHistory(page = 0) {
    currentPage = page;
    
    // 构建查询参数
    const params = new URLSearchParams();
    params.append('page', currentPage);
    params.append('size', pageSize);
    
    const name = document.getElementById('nameFilter').value;
    if (name) params.append('name', name);
    
    const scope = document.getElementById('scopeFilter').value;
    if (scope) params.append('reviewType', scope);

    const status = document.getElementById('statusFilter').value;
    if (status) params.append('status', status);
    
    // 处理日期
    const startDate = document.getElementById('startDateFilter').value;
    const endDate = document.getElementById('endDateFilter').value;
    
    // 如果选择了快捷时间范围
    const timeRange = document.getElementById('timeRangeFilter').value;
    if (timeRange) {
        const now = new Date();
        let start = new Date();
        
        if (timeRange === 'today') {
            start.setHours(0, 0, 0, 0);
        } else if (timeRange === 'week') {
            const day = now.getDay();
            const diff = now.getDate() - day + (day === 0 ? -6 : 1); // adjust when day is sunday
            start.setDate(diff);
            start.setHours(0, 0, 0, 0);
        } else if (timeRange === 'month') {
            start.setDate(1);
            start.setHours(0, 0, 0, 0);
        }
        
        params.append('startTime', formatDateForApi(start));
        params.append('endTime', formatDateForApi(now));
    } else {
        if (startDate) params.append('startTime', startDate + 'T00:00:00');
        if (endDate) params.append('endTime', endDate + 'T23:59:59');
    }
    
    // 发起请求
    fetch(`/api/review/history?${params.toString()}`)
        .then(response => response.json())
        .then(data => {
            renderTable(data.content);
            updatePagination(data);
        })
        .catch(error => {
            console.error('加载历史记录失败:', error);
            alert('加载历史记录失败，请重试');
        });
}

/**
 * 渲染表格
 */
function renderTable(items) {
    const tbody = document.getElementById('historyTableBody');
    tbody.innerHTML = '';
    
    if (!items || items.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align: center; padding: 20px;">暂无记录</td></tr>';
        return;
    }
    
    items.forEach((item, index) => {
        const tr = document.createElement('tr');
        
        // 序号
        const serialNum = index + 1 + (currentPage * pageSize);
        
        // 格式化时间
        const reviewTime = formatDateTime(item.createdAt);
        
        // 构建问题统计
        const statsHtml = `
            <div class="issue-stats">
                <span class="stat-item stat-critical" title="严重">严重:${item.criticalCount || 0}</span>
                <span class="stat-item stat-high" title="高危">高危:${item.highCount || 0}</span>
                <span class="stat-item stat-medium" title="中危">中危:${item.mediumCount || 0}</span>
                <span class="stat-item stat-low" title="低危">低危:${item.lowCount || 0}</span>
            </div>
        `;
        
    // 范围显示：统一使用审查类型的固定标签
        let scopeDisplay = mapReviewTypeToZh(item.reviewType);
        
        let actionButtons = renderTaskActions(item);

        tr.innerHTML = `
            <td>${serialNum}</td>
            <td>${item.taskName || '未命名片段'}</td>
            <td>${scopeDisplay}</td>
            <td>${renderStatusBadge(item.status, item.statusLabel)}</td>
            <td>${reviewTime}</td>
            <td>${statsHtml}</td>
            <td>
                ${actionButtons}
            </td>
        `;
        
        tbody.appendChild(tr);
    });
}

function renderStatusBadge(status, statusLabel) {
    const normalized = String(status || 'PENDING').toUpperCase();
    const label = statusLabel || mapTaskStatusToZh(normalized);
    return `<span class="history-status-badge ${escapeHtml(normalized.toLowerCase())}">${escapeHtml(label)}</span>`;
}

function renderTaskActions(item) {
    const taskId = item.taskId;
    const status = String(item.status || '').toUpperCase();
    let buttons = '';

    if (status === 'COMPLETED') {
        buttons += `
            <a href="#" class="action-btn" onclick="viewReport(${taskId})">
                <i class="fas fa-eye"></i> 查看
            </a>
            <a href="#" class="action-btn" onclick="downloadReport(${taskId})">
                <i class="fas fa-download"></i> 下载
            </a>
            <a href="#" class="action-btn gate-action-btn" onclick="openQualityGate(${taskId}); return false;">
                <i class="fas fa-shield-alt"></i> 门禁
            </a>
        `;
    }

    if (item.canCancel === true || status === 'PENDING' || status === 'RUNNING') {
        buttons += `
            <a href="#" class="action-btn cancel-action-btn" onclick="cancelTask(${taskId}); return false;">
                <i class="fas fa-ban"></i> 取消
            </a>
            <a href="#" class="action-btn" onclick="loadHistory(currentPage); return false;">
                <i class="fas fa-sync"></i> 刷新
            </a>
        `;
    }

    if (item.canRetry === true || status === 'FAILED' || status === 'CANCELLED') {
        buttons += `
            <a href="#" class="action-btn retry-action-btn" onclick="retryTask(${taskId}); return false;">
                <i class="fas fa-redo"></i> 重试
            </a>
        `;
    }

    buttons += `
        <a href="#" class="action-btn trace-action-btn" onclick="openTraceGraph(${taskId}); return false;">
            <i class="fas fa-project-diagram"></i> 溯源图
        </a>
    `;

    if (typeof canAdmin !== 'undefined' && canAdmin) {
        buttons += `
            <a href="#" class="action-btn" onclick="deleteTask(${taskId}); return false;">
                <i class="fas fa-trash"></i> 删除
            </a>
        `;
    }

    return buttons;
}

/**
 * 更新分页控件
 */
function updatePagination(data) {
    totalPages = data.totalPages;
    const totalElements = data.totalElements;
    
    const paginationInfo = document.getElementById('paginationInfo');
    paginationInfo.textContent = `共 ${totalElements} 条记录，当前第 ${data.number + 1}/${data.totalPages} 页`;
    
    const pageDisplay = document.getElementById('pageDisplay');
    pageDisplay.textContent = `第 ${data.number + 1}/${data.totalPages} 页`;
    
    const prevBtn = document.querySelector('.prev-btn');
    const nextBtn = document.querySelector('.next-btn');
    
    prevBtn.disabled = data.first;
    nextBtn.disabled = data.last;
}

/**
 * 搜索
 */
function searchHistory() {
    loadHistory(0);
}

/**
 * 重置筛选
 */
function resetFilters() {
    document.getElementById('nameFilter').value = '';
    document.getElementById('scopeFilter').value = '';
    document.getElementById('statusFilter').value = '';
    document.getElementById('timeRangeFilter').value = '';
    document.getElementById('startDateFilter').value = '';
    document.getElementById('endDateFilter').value = '';
    loadHistory(0);
}

/**
 * 上一页
 */
function prevPage() {
    if (currentPage > 0) {
        loadHistory(currentPage - 1);
    }
}

/**
 * 下一页
 */
function nextPage() {
    if (currentPage < totalPages - 1) {
        loadHistory(currentPage + 1);
    }
}

/**
 * 查看报告
 */
function viewReport(taskId) {
    window.open(`/api/report/${taskId}/html`, '_blank');
}

/**
 * 下载报告
 */
function downloadReport(taskId) {
    // 触发下载PDF
    const a = document.createElement('a');
    a.href = `/api/report/${taskId}/pdf`;
    a.download = `review_report_${taskId}.pdf`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
}

async function postReviewTaskAction(taskId, action) {
    const response = await fetch(`/api/review/task/${encodeURIComponent(taskId)}/${action}`, {
        method: 'POST'
    });
    if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.message || errorData.error || `操作失败: HTTP ${response.status}`);
    }
    return response.json();
}

async function cancelTask(taskId) {
    if (!confirm('确认取消该审查任务？')) {
        return;
    }
    try {
        await postReviewTaskAction(taskId, 'cancel');
        loadHistory(currentPage);
    } catch (error) {
        console.error('取消任务失败:', error);
        alert('取消任务失败: ' + (error.message || '未知错误'));
    }
}

async function retryTask(taskId) {
    try {
        const task = await postReviewTaskAction(taskId, 'retry');
        alert(`已创建重试任务 #${task.taskId}`);
        loadHistory(0);
    } catch (error) {
        console.error('重试任务失败:', error);
        alert('重试任务失败: ' + (error.message || '未知错误'));
    }
}

async function openTraceGraph(taskId) {
    const modal = document.getElementById('traceGraphModal');
    const body = document.getElementById('traceGraphBody');
    const title = document.getElementById('traceGraphTitle');
    if (!modal || !body) return;

    activeTraceTaskId = taskId;
    activeTraceGraph = null;
    activeTraceNodeId = null;
    activeTraceDebate = null;
    if (title) {
        title.textContent = `溯源图 #${taskId}`;
    }
    body.innerHTML = '<div class="trace-loading">正在加载溯源图...</div>';
    modal.style.display = 'flex';

    try {
        const [graph, debate] = await Promise.all([
            fetchJson(`/api/review/task/${taskId}/trace-graph?t=${Date.now()}`),
            loadDebateTimeline(taskId)
        ]);
        if (activeTraceTaskId !== taskId) {
            return;
        }
        activeTraceGraph = graph;
        activeTraceDebate = debate;
        renderTraceGraph(graph, debate);
    } catch (error) {
        console.error('加载溯源图失败:', error);
        body.innerHTML = `
            <div class="trace-error">
                溯源图加载失败，请确认当前账号有查询权限，并稍后重试。
                <div class="trace-error-detail">${escapeHtml(error.message || String(error))}</div>
            </div>`;
    }
}

function closeTraceGraph() {
    const modal = document.getElementById('traceGraphModal');
    if (modal) {
        modal.style.display = 'none';
    }
    activeTraceGraph = null;
    activeTraceTaskId = null;
    activeTraceNodeId = null;
    activeTraceDebate = null;
}

function closeTraceGraphFromBackdrop(event) {
    if (event && event.target && event.target.id === 'traceGraphModal') {
        closeTraceGraph();
    }
}

async function fetchJson(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
    }
    return response.json();
}

async function loadDebateTimeline(taskId) {
    try {
        const [auditEvents, evidenceItems] = await Promise.all([
            fetchJson(`/api/review/task/${taskId}/audit?t=${Date.now()}`),
            fetchJson(`/api/review/task/${taskId}/evidence?t=${Date.now()}`)
        ]);
        return buildDebateTimeline(auditEvents, evidenceItems);
    } catch (error) {
        console.warn('加载 Agent Debate 记录失败:', error);
        return {
            actions: [],
            auditEvents: [],
            evidenceItems: [],
            completedEvent: null,
            error: error.message || String(error)
        };
    }
}

function buildDebateTimeline(auditEvents, evidenceItems) {
    const debateAuditEvents = (Array.isArray(auditEvents) ? auditEvents : [])
        .filter(event => event && (
            event.eventType === 'AGENT_DEBATE_ACTION_SELECTED'
            || event.eventType === 'AGENT_DEBATE_LOOP_COMPLETED'
        ));
    const debateEvidenceItems = (Array.isArray(evidenceItems) ? evidenceItems : [])
        .filter(item => item && (
            item.evidenceType === 'AGENT_DEBATE_ACTIONS'
            || item.locator === 'agent-debate-actions.json'
        ));
    const actionMap = new Map();

    debateEvidenceItems.forEach(item => {
        parseDebateEvidenceActions(item).forEach((action, index) => {
            const normalized = normalizeDebateAction(action, {
                index,
                evidenceId: item.id,
                evidenceHash: item.contentHash
            });
            mergeDebateAction(actionMap, normalized);
        });
    });

    debateAuditEvents
        .filter(event => event.eventType === 'AGENT_DEBATE_ACTION_SELECTED')
        .forEach((event, index) => {
            const normalized = normalizeDebateAction(event.metadata || {}, {
                index,
                auditEvent: event
            });
            mergeDebateAction(actionMap, normalized);
        });

    const actions = Array.from(actionMap.values())
        .sort((left, right) => (left.iteration || 0) - (right.iteration || 0));

    return {
        actions,
        auditEvents: debateAuditEvents,
        evidenceItems: debateEvidenceItems,
        completedEvent: debateAuditEvents.find(event => event.eventType === 'AGENT_DEBATE_LOOP_COMPLETED') || null,
        error: null
    };
}

function parseDebateEvidenceActions(evidence) {
    if (!evidence || !evidence.excerpt) {
        return [];
    }
    try {
        const parsed = JSON.parse(evidence.excerpt);
        return Array.isArray(parsed) ? parsed : [];
    } catch (error) {
        console.warn('解析 Agent Debate evidence 失败:', error);
        return [];
    }
}

function normalizeDebateAction(source, options = {}) {
    const auditEvent = options.auditEvent || null;
    const fallbackIteration = typeof options.index === 'number' ? options.index + 1 : null;
    const iteration = toDebateNumber(source.iteration ?? options.iteration ?? fallbackIteration);
    return {
        iteration,
        action: source.action || source.eventType || '',
        reason: source.reason || (auditEvent ? auditEvent.message : ''),
        draftIds: toDebateList(source.draftIds),
        beforeCount: toDebateNumber(source.beforeCount),
        afterCount: toDebateNumber(source.afterCount),
        terminal: toDebateBoolean(source.terminal),
        auditEventId: auditEvent ? auditEvent.id : null,
        auditEventHash: auditEvent ? auditEvent.eventHash : null,
        auditCreatedAt: auditEvent ? auditEvent.createdAt : null,
        evidenceId: options.evidenceId || null,
        evidenceHash: options.evidenceHash || null
    };
}

function mergeDebateAction(actionMap, action) {
    if (!action || !action.iteration) {
        return;
    }
    const existing = actionMap.get(action.iteration) || {};
    actionMap.set(action.iteration, {
        ...existing,
        ...Object.fromEntries(Object.entries(action).filter(([, value]) => {
            if (Array.isArray(value)) {
                return value.length > 0;
            }
            return value !== null && value !== undefined && value !== '';
        })),
        draftIds: action.draftIds && action.draftIds.length > 0
            ? action.draftIds
            : (existing.draftIds || [])
    });
}

function toDebateNumber(value) {
    if (value === null || value === undefined || value === '') {
        return null;
    }
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
}

function toDebateBoolean(value) {
    if (value === true || value === 'true') {
        return true;
    }
    if (value === false || value === 'false') {
        return false;
    }
    return null;
}

function toDebateList(value) {
    if (Array.isArray(value)) {
        return value.map(item => String(item)).filter(Boolean);
    }
    if (typeof value === 'string' && value.trim()) {
        return value.split(',').map(item => item.trim()).filter(Boolean);
    }
    return [];
}

async function openQualityGate(taskId) {
    const modal = document.getElementById('qualityGateModal');
    const body = document.getElementById('qualityGateBody');
    const title = document.getElementById('qualityGateTitle');
    if (!modal || !body) return;

    if (title) {
        title.textContent = `门禁结果 #${taskId}`;
    }
    body.innerHTML = '<div class="trace-loading">正在加载门禁结果...</div>';
    modal.style.display = 'flex';

    try {
        const response = await fetch(`/api/v1/cicd/status/${taskId}?blockOn=CRITICAL&t=${Date.now()}`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const data = await response.json();
        renderQualityGate(data);
    } catch (error) {
        console.error('加载门禁结果失败:', error);
        body.innerHTML = `
            <div class="trace-error">
                门禁结果加载失败，请确认当前账号有查询权限，并稍后重试。
                <div class="trace-error-detail">${escapeHtml(error.message || String(error))}</div>
            </div>`;
    }
}

function closeQualityGate() {
    const modal = document.getElementById('qualityGateModal');
    if (modal) {
        modal.style.display = 'none';
    }
}

function closeQualityGateFromBackdrop(event) {
    if (event && event.target && event.target.id === 'qualityGateModal') {
        closeQualityGate();
    }
}

function renderQualityGate(data) {
    const body = document.getElementById('qualityGateBody');
    if (!body) return;

    const gate = data.qualityGate || {};
    const summary = data.summary || {};
    const taskId = data.taskId;
    const completed = data.status === 'COMPLETED';
    const passed = data.passed === true && completed;
    const stateClass = passed ? 'ok' : (completed ? 'bad' : 'warn');
    const stateLabel = passed ? '通过' : (completed ? '未通过' : '进行中');
    const message = data.message || (passed ? '审查通过' : '等待门禁结果');

    body.innerHTML = `
        <section class="gate-hero ${stateClass}">
            <div>
                <div class="gate-kicker">Task #${escapeHtml(taskId || '-')}</div>
                <div class="gate-title">${escapeHtml(stateLabel)}</div>
                <div class="gate-message">${escapeHtml(message)}</div>
            </div>
            <div class="gate-status-pill ${stateClass}">
                ${escapeHtml(data.status || '-')}
            </div>
        </section>

        <section class="gate-summary-grid">
            ${renderGateMetric('Critical', summary.critical, 'critical')}
            ${renderGateMetric('High', summary.high, 'high')}
            ${renderGateMetric('Medium', summary.medium, 'medium')}
            ${renderGateMetric('Low', summary.low, 'low')}
        </section>

        <section class="gate-check-grid">
            ${renderGateCheck('严重级别门禁', gate.severityBlocked, gate.severityBlocked ? '存在 CRITICAL 级别及以上阻断项' : '未触发严重级别阻断', false)}
            ${renderGateCheck('溯源策略门禁', gate.provenanceBlocked, buildGroundingDetail(gate), false)}
            ${renderGateCheck('证明包完整性', gate.proofBundleValid, gate.proofBundleReason || '证明包 hash / 签名 / 当前状态校验')}
            ${renderGateCheck('审计链完整性', gate.auditChainValid, '审计事件链、签名、顺序与覆盖率校验')}
            ${renderGateCheck('审计覆盖率', gate.auditCoverageValid, '关键审查阶段是否都有审计事件')}
            ${renderGateCheck('审计顺序', gate.auditOrderValid, '审计事件时间顺序是否一致')}
            ${renderGateCheck('运行时清单', gate.runtimeGuardValid, gate.dbGuardReason || '运行环境和数据库保护校验')}
            ${renderGateCheck('数据库追加保护', gate.dbAppendOnlyGuardsInstalled, 'finding / evidence / audit 相关表是否安装追加型保护')}
        </section>

        <section class="gate-detail-panel">
            <div class="gate-detail-title">门禁依据</div>
            <div class="gate-detail-grid">
                ${renderGateDetail('reason', gate.reason)}
                ${renderGateDetail('groundingMinSeverity', gate.groundingMinSeverity)}
                ${renderGateDetail('ungroundedCritical', gate.ungroundedCritical)}
                ${renderGateDetail('ungroundedHigh', gate.ungroundedHigh)}
                ${renderGateDetail('groundingViolationCount', gate.groundingViolationCount)}
                ${renderGateDetail('missingSourceEvidenceCount', gate.missingSourceEvidenceCount)}
                ${renderGateDetail('evidenceHashMismatchCount', gate.evidenceHashMismatchCount)}
                ${renderGateDetail('dbGuardUpdatesBlocked', formatGateBoolean(gate.dbGuardUpdatesBlocked))}
                ${renderGateDetail('dbGuardDeletesBlocked', formatGateBoolean(gate.dbGuardDeletesBlocked))}
            </div>
            <div class="gate-link-row">
                <a href="/api/v1/cicd/result/${encodeURIComponent(taskId)}?blockOn=CRITICAL" target="_blank" rel="noopener">
                    <i class="fas fa-file-code"></i> result.json
                </a>
                <a href="/api/v1/cicd/sarif/${encodeURIComponent(taskId)}" target="_blank" rel="noopener">
                    <i class="fas fa-code-branch"></i> SARIF
                </a>
                <a href="/report/${encodeURIComponent(taskId)}" target="_blank" rel="noopener">
                    <i class="fas fa-file-alt"></i> 审查报告
                </a>
            </div>
        </section>`;
}

function renderGateMetric(label, value, tone) {
    return `
        <div class="gate-metric ${escapeHtml(tone)}">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value ?? 0)}</strong>
        </div>`;
}

function renderGateCheck(label, value, detail, passWhenTrue = true) {
    const hasValue = value !== null && value !== undefined;
    const passed = hasValue ? (passWhenTrue ? value === true : value === false) : null;
    const stateClass = passed === null ? 'warn' : (passed ? 'ok' : 'bad');
    const statusLabel = passed === null ? '未知' : (passed ? '通过' : '失败');

    return `
        <div class="gate-check ${stateClass}">
            <div class="gate-check-head">
                <span>${escapeHtml(label)}</span>
                <strong>${escapeHtml(statusLabel)}</strong>
            </div>
            <div class="gate-check-detail">${escapeHtml(detail || '-')}</div>
        </div>`;
}

function renderGateDetail(label, value) {
    return `
        <div class="gate-detail-item">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value ?? '-')}</strong>
        </div>`;
}

function buildGroundingDetail(gate) {
    const critical = gate.ungroundedCritical ?? 0;
    const high = gate.ungroundedHigh ?? 0;
    const violations = gate.groundingViolationCount ?? 0;
    if (gate.provenanceBlocked) {
        return `未满足溯源策略：critical=${critical}, high=${high}, violations=${violations}`;
    }
    return `高风险问题均满足溯源策略：critical=${critical}, high=${high}, violations=${violations}`;
}

function formatGateBoolean(value) {
    if (value === true) return 'true';
    if (value === false) return 'false';
    return '-';
}

function renderTraceGraph(graph, debate = activeTraceDebate) {
    const body = document.getElementById('traceGraphBody');
    if (!body) return;

    const nodes = Array.isArray(graph.nodes) ? graph.nodes : [];
    const edges = Array.isArray(graph.edges) ? graph.edges : [];
    if (nodes.length === 0) {
        body.innerHTML = '<div class="trace-empty">暂无可展示的溯源节点。</div>';
        return;
    }

    body.innerHTML = `
        ${renderTraceSummary(graph)}
        ${renderDebateTimeline(debate)}
        <div class="trace-graph-shell">
            <div class="trace-graph-canvas" id="traceGraphCanvas">
                ${renderTraceGraphSvg(nodes, edges)}
            </div>
            <aside class="trace-node-panel" id="traceNodePanel"></aside>
        </div>
        <div class="trace-legend">
            ${renderTraceLegend()}
        </div>
    `;

    document.querySelectorAll('.trace-svg-node').forEach(nodeEl => {
        nodeEl.addEventListener('click', function() {
            selectTraceNode(this.getAttribute('data-node-id'));
        });
    });

    const defaultNode = nodes.find(node => node.type === 'PROOF_BUNDLE')
        || nodes.find(node => node.type === 'TASK')
        || nodes[0];
    selectTraceNode(defaultNode.id);
}

function renderTraceSummary(graph) {
    const summary = graph.summary || {};
    const valid = summary.proofBundleValid === true;
    const current = summary.currentStateMatch === true;
    const dbGuarded = summary.databaseAppendOnlyGuardsInstalled === true;
    const chips = [
        ['Task', graph.taskId || '-'],
        ['Nodes', summary.nodeCount ?? (graph.nodes || []).length],
        ['Edges', summary.edgeCount ?? (graph.edges || []).length],
        ['Findings', summary.findingCount ?? '-'],
        ['Evidence', summary.evidenceCount ?? '-'],
        ['Audit events', summary.auditEventCount ?? '-'],
        ['Proof', valid ? 'VALID' : 'BROKEN', valid ? 'ok' : 'bad'],
        ['Current', current ? 'MATCH' : 'CHECK', current ? 'ok' : 'warn'],
        ['DB guard', dbGuarded ? 'INSTALLED' : 'UNKNOWN', dbGuarded ? 'ok' : 'warn']
    ];

    return `
        <section class="trace-summary">
            <div>
                <div class="trace-summary-title">审查证据链</div>
                <div class="trace-summary-subtitle">
                    reviewState=${escapeHtml(shortHash(graph.reviewStateHash))} · bundle=${escapeHtml(shortHash(graph.bundleHash))}
                </div>
            </div>
            <div class="trace-summary-chips">
                ${chips.map(([label, value, state]) => `
                    <span class="trace-summary-chip ${state || ''}">
                        <strong>${escapeHtml(label)}</strong>
                        ${escapeHtml(value)}
                    </span>
                `).join('')}
            </div>
        </section>`;
}

function renderDebateTimeline(debate) {
    const data = debate || { actions: [] };
    const actions = Array.isArray(data.actions) ? data.actions : [];
    const evidenceCount = Array.isArray(data.evidenceItems) ? data.evidenceItems.length : 0;
    const auditCount = Array.isArray(data.auditEvents) ? data.auditEvents.length : 0;

    if (data.error) {
        return `
            <section class="debate-panel debate-panel-error">
                <div class="debate-panel-header">
                    <div>
                        <div class="debate-kicker">多 Agent 辩论</div>
                        <h3>审查辩论时间线</h3>
                    </div>
                    <span class="debate-status warn">加载失败</span>
                </div>
                <div class="debate-empty">辩论记录暂时不可用：${escapeHtml(data.error)}</div>
            </section>`;
    }

    if (actions.length === 0) {
        return `
            <section class="debate-panel">
                <div class="debate-panel-header">
                    <div>
                        <div class="debate-kicker">多 Agent 辩论</div>
                        <h3>审查辩论时间线</h3>
                    </div>
                    <span class="debate-status">0 轮</span>
                </div>
                <div class="debate-empty">暂无多 Agent 辩论记录。</div>
            </section>`;
    }

    const completedAt = data.completedEvent ? formatDateTime(data.completedEvent.createdAt) : '';
    return `
        <section class="debate-panel">
            <div class="debate-panel-header">
                <div>
                    <div class="debate-kicker">多 Agent 辩论</div>
                    <h3>审查辩论时间线</h3>
                </div>
                <div class="debate-panel-meta">
                    <span>${actions.length} 轮</span>
                    <span>审计 ${auditCount}</span>
                    <span>证据 ${evidenceCount}</span>
                    ${completedAt ? `<span>完成 ${escapeHtml(completedAt)}</span>` : ''}
                </div>
            </div>
            <div class="debate-timeline">
                ${actions.map(renderDebateStep).join('')}
            </div>
        </section>`;
}

function renderDebateStep(action) {
    const actionKey = String(action.action || '').toUpperCase();
    const actionLabel = formatDebateAction(actionKey);
    const reason = formatDebateReason(action.reason, actionKey);
    const countChange = formatDebateCountChange(action.beforeCount, action.afterCount);
    const tone = formatDebateTone(actionKey);
    const meta = renderDebateMeta(action);
    const drafts = renderDebateDrafts(action.draftIds);

    return `
        <article class="debate-step ${escapeHtml(tone)}">
            <div class="debate-step-index">${escapeHtml(action.iteration || '-')}</div>
            <div class="debate-step-main">
                <div class="debate-step-head">
                    <strong>${escapeHtml(actionLabel)}</strong>
                    <span>${escapeHtml(countChange)}</span>
                    ${action.terminal ? '<em>结束</em>' : ''}
                </div>
                <div class="debate-step-reason">${escapeHtml(reason)}</div>
                ${drafts}
                ${meta}
            </div>
        </article>`;
}

function renderDebateDrafts(draftIds) {
    if (!Array.isArray(draftIds) || draftIds.length === 0) {
        return '';
    }
    return `
        <div class="debate-drafts">
            <span>影响候选</span>
            ${draftIds.slice(0, 8).map(id => `<code>${escapeHtml(id)}</code>`).join('')}
            ${draftIds.length > 8 ? `<code>+${draftIds.length - 8}</code>` : ''}
        </div>`;
}

function renderDebateMeta(action) {
    const items = [];
    if (action.auditEventId) {
        items.push(`审计 #${action.auditEventId}`);
    }
    if (action.evidenceId) {
        items.push(`证据 #${action.evidenceId}`);
    }
    if (action.auditCreatedAt) {
        items.push(formatDateTime(action.auditCreatedAt));
    }
    const hash = action.evidenceHash || action.auditEventHash;
    if (hash) {
        items.push(`hash ${shortHash(hash)}`);
    }
    if (items.length === 0) {
        return '';
    }
    return `<div class="debate-step-meta">${items.map(escapeHtml).join(' · ')}</div>`;
}

function formatDebateAction(action) {
    const labels = {
        ASK_CRITIC: '质疑弱定位',
        REQUEST_MORE_EVIDENCE: '要求补证',
        REVISE_FINDING: '修订问题',
        MERGE_DUPLICATES: '合并重复',
        FINALIZE: '确认完成'
    };
    return labels[action] || (action ? action : '未知动作');
}

function formatDebateReason(reason, action) {
    const text = String(reason || '').trim();
    const translations = {
        'Duplicate candidate findings share the same location/category/title.': '候选问题的位置、类别和标题重复，已合并。',
        'Critic rejected candidates without actionable source line anchors.': '候选问题缺少可定位源码行，已被质疑并移除。',
        'High-risk candidates need explicit source/RAG evidence before finalization.': '高风险候选问题在确认前需要明确的源码或知识库证据。',
        'No duplicate, weak-locator, or high-risk evidence issues remain.': '未发现重复、弱定位或高风险缺证据问题，进入最终确认。'
    };
    if (translations[text]) {
        return translations[text];
    }
    if (text) {
        return text;
    }
    const fallback = {
        ASK_CRITIC: '本轮对候选问题进行质疑。',
        REQUEST_MORE_EVIDENCE: '本轮要求补充审查证据。',
        REVISE_FINDING: '本轮修订候选问题。',
        MERGE_DUPLICATES: '本轮合并重复候选问题。',
        FINALIZE: '本轮确认 debate 完成。'
    };
    return fallback[action] || '本轮已记录动作。';
}

function formatDebateCountChange(beforeCount, afterCount) {
    const before = beforeCount === null || beforeCount === undefined ? '-' : beforeCount;
    const after = afterCount === null || afterCount === undefined ? '-' : afterCount;
    return `候选 ${before} -> ${after}`;
}

function formatDebateTone(action) {
    const tones = {
        ASK_CRITIC: 'critic',
        REQUEST_MORE_EVIDENCE: 'evidence',
        REVISE_FINDING: 'revise',
        MERGE_DUPLICATES: 'merge',
        FINALIZE: 'final'
    };
    return tones[action] || 'default';
}

function renderTraceGraphSvg(nodes, edges) {
    const layout = buildTraceLayout(nodes);
    const positions = layout.positions;
    const marker = `
        <defs>
            <marker id="traceArrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto">
                <path d="M0,0 L9,4.5 L0,9 Z" class="trace-arrow-head"></path>
            </marker>
        </defs>`;
    const edgeMarkup = edges.map(edge => {
        const source = positions[edge.source];
        const target = positions[edge.target];
        if (!source || !target) return '';
        const startX = source.x + layout.nodeWidth;
        const startY = source.y + (layout.nodeHeight / 2);
        const endX = target.x;
        const endY = target.y + (layout.nodeHeight / 2);
        const midX = startX + ((endX - startX) / 2);
        const path = `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${endY}, ${endX} ${endY}`;
        const backward = endX < startX;
        const linePath = backward
            ? `M ${source.x} ${startY} C ${source.x - 70} ${startY}, ${target.x + layout.nodeWidth + 70} ${endY}, ${target.x + layout.nodeWidth} ${endY}`
            : path;
        return `
            <path class="trace-svg-edge" d="${linePath}" marker-end="url(#traceArrow)">
                <title>${escapeHtml(edge.label || edge.type || '')}</title>
            </path>`;
    }).join('');

    const nodeMarkup = nodes.map(node => {
        const point = positions[node.id];
        if (!point) return '';
        const label = truncateText(node.label || node.id, 24);
        const status = truncateText(node.status || '', 18);
        const type = formatTraceType(node.type);
        const hash = shortHash(node.hash);
        const typeClass = String(node.type || 'UNKNOWN').toLowerCase().replace(/[^a-z0-9_-]/g, '-');
        return `
            <g class="trace-svg-node trace-node-${escapeHtml(typeClass)}" data-node-id="${escapeHtml(node.id)}" transform="translate(${point.x}, ${point.y})">
                <rect width="${layout.nodeWidth}" height="${layout.nodeHeight}" rx="8" ry="8"></rect>
                <text class="trace-svg-type" x="12" y="18">${escapeHtml(type)}</text>
                <text class="trace-svg-label" x="12" y="38">${escapeHtml(label)}</text>
                <text class="trace-svg-status" x="12" y="56">${escapeHtml(status || hash || node.id)}</text>
                <title>${escapeHtml(node.type || '')}: ${escapeHtml(node.label || node.id)}</title>
            </g>`;
    }).join('');

    return `
        <svg class="trace-svg" viewBox="0 0 ${layout.width} ${layout.height}" width="${layout.width}" height="${layout.height}" role="img" aria-label="审查溯源图">
            ${marker}
            <rect class="trace-svg-bg" x="0" y="0" width="${layout.width}" height="${layout.height}" rx="8"></rect>
            <g>${edgeMarkup}</g>
            <g>${nodeMarkup}</g>
        </svg>`;
}

function buildTraceLayout(nodes) {
    const layerOrder = [
        'TASK',
        'FINDING',
        'EVIDENCE',
        'AUDIT_EVENT',
        'GROUNDING_POLICY',
        'PROOF_BUNDLE',
        'RUNTIME_MANIFEST',
        'DATABASE_GUARD',
        'GROUNDING_VIOLATION',
        'UNKNOWN'
    ];
    const nodeWidth = 170;
    const nodeHeight = 66;
    const xGap = 70;
    const yGap = 22;
    const padding = 24;
    const layers = new Map(layerOrder.map(type => [type, []]));

    nodes.forEach(node => {
        const type = layerOrder.includes(node.type) ? node.type : 'UNKNOWN';
        layers.get(type).push(node);
    });

    const visibleLayers = Array.from(layers.entries()).filter(([, items]) => items.length > 0);
    const maxLayerSize = Math.max(...visibleLayers.map(([, items]) => items.length), 1);
    const width = padding * 2 + visibleLayers.length * nodeWidth + Math.max(0, visibleLayers.length - 1) * xGap;
    const height = padding * 2 + maxLayerSize * nodeHeight + Math.max(0, maxLayerSize - 1) * yGap;
    const positions = {};

    visibleLayers.forEach(([, items], layerIndex) => {
        const x = padding + layerIndex * (nodeWidth + xGap);
        const laneHeight = items.length * nodeHeight + Math.max(0, items.length - 1) * yGap;
        const top = padding + Math.max(0, (height - padding * 2 - laneHeight) / 2);
        items.forEach((node, itemIndex) => {
            positions[node.id] = {
                x,
                y: top + itemIndex * (nodeHeight + yGap)
            };
        });
    });

    return { positions, width, height, nodeWidth, nodeHeight };
}

function selectTraceNode(nodeId) {
    if (!activeTraceGraph || !nodeId) return;
    activeTraceNodeId = nodeId;
    document.querySelectorAll('.trace-svg-node').forEach(nodeEl => {
        nodeEl.classList.toggle('selected', nodeEl.getAttribute('data-node-id') === nodeId);
    });
    const node = (activeTraceGraph.nodes || []).find(item => item.id === nodeId);
    renderTraceNodePanel(node);
}

function renderTraceNodePanel(node) {
    const panel = document.getElementById('traceNodePanel');
    if (!panel || !activeTraceGraph) return;
    if (!node) {
        panel.innerHTML = '<div class="trace-empty">请选择一个节点。</div>';
        return;
    }

    const connectedEdges = (activeTraceGraph.edges || [])
        .filter(edge => edge.source === node.id || edge.target === node.id)
        .slice(0, 12);
    const metadata = node.metadata && typeof node.metadata === 'object'
        ? Object.entries(node.metadata).filter(([, value]) => value !== null && value !== undefined && value !== '')
        : [];

    panel.innerHTML = `
        <div class="trace-node-detail">
            <div class="trace-node-detail-type">${escapeHtml(formatTraceType(node.type))}</div>
            <div class="trace-node-detail-title">${escapeHtml(node.label || node.id)}</div>
            <div class="trace-node-detail-row">
                <span>Status</span>
                <strong>${escapeHtml(node.status || '-')}</strong>
            </div>
            <div class="trace-node-detail-row">
                <span>Hash</span>
                <strong title="${escapeHtml(node.hash || '')}">${escapeHtml(shortHash(node.hash) || '-')}</strong>
            </div>
            <div class="trace-node-detail-row">
                <span>ID</span>
                <strong>${escapeHtml(node.id)}</strong>
            </div>
            ${metadata.length > 0 ? `
                <div class="trace-node-metadata">
                    ${metadata.slice(0, 10).map(([key, value]) => `
                        <span><strong>${escapeHtml(key)}</strong>${escapeHtml(formatTraceValue(value))}</span>
                    `).join('')}
                </div>
            ` : ''}
            <div class="trace-edge-list">
                <div class="trace-edge-list-title">关联边</div>
                ${connectedEdges.length > 0 ? connectedEdges.map(edge => `
                    <button type="button" class="trace-edge-item" onclick="selectTraceNode('${escapeJsString(edge.source === node.id ? edge.target : edge.source)}')">
                        <span>${escapeHtml(edge.source)}</span>
                        <i class="fas fa-arrow-right"></i>
                        <span>${escapeHtml(edge.target)}</span>
                        <em>${escapeHtml(edge.type || edge.label || '')}</em>
                    </button>
                `).join('') : '<div class="trace-empty compact">暂无关联边。</div>'}
            </div>
        </div>`;
}

function renderTraceLegend() {
    const types = [
        ['TASK', '审查任务'],
        ['FINDING', '问题'],
        ['EVIDENCE', '证据'],
        ['AUDIT_EVENT', '审计事件'],
        ['PROOF_BUNDLE', '证明包'],
        ['RUNTIME_MANIFEST', '运行清单'],
        ['DATABASE_GUARD', '数据库保护']
    ];
    return types.map(([type, label]) => `
        <span class="trace-legend-item trace-legend-${type.toLowerCase().replace(/_/g, '-')}">
            <i></i>${escapeHtml(label)}
        </span>
    `).join('');
}

function formatTraceType(type) {
    const labels = {
        TASK: 'Task',
        FINDING: 'Finding',
        EVIDENCE: 'Evidence',
        AUDIT_EVENT: 'Audit',
        PROOF_BUNDLE: 'Proof',
        RUNTIME_MANIFEST: 'Runtime',
        DATABASE_GUARD: 'DB Guard',
        GROUNDING_POLICY: 'Policy',
        GROUNDING_VIOLATION: 'Violation'
    };
    return labels[type] || (type || 'Unknown');
}

function formatTraceValue(value) {
    if (Array.isArray(value)) {
        return value.join(', ');
    }
    if (typeof value === 'object') {
        return JSON.stringify(value);
    }
    return String(value);
}

function shortHash(hash) {
    if (!hash) return '';
    const text = String(hash);
    return text.length > 16 ? `${text.slice(0, 8)}...${text.slice(-6)}` : text;
}

function truncateText(value, maxLength) {
    const text = String(value || '');
    if (text.length <= maxLength) return text;
    return `${text.slice(0, Math.max(0, maxLength - 3))}...`;
}

function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    const div = document.createElement('div');
    div.textContent = String(value);
    return div.innerHTML;
}

function escapeJsString(value) {
    return String(value || '').replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

function deleteTask(taskId) {
    if (!confirm('确认删除该历史记录？删除后不可恢复')) {
        return;
    }
    fetch(`/api/review/task/${taskId}`, { method: 'DELETE' })
        .then(resp => {
            if (!resp.ok) throw new Error('删除失败');
            loadHistory(currentPage);
        })
        .catch(err => {
            alert('删除失败，请稍后重试');
            console.error(err);
        });
}

/**
 * 格式化日期时间
 */
function formatDateTime(isoString) {
    if (!isoString) return '';
    const date = new Date(isoString);
    return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    }).replace(/\//g, '-');
}

/**
 * 格式化日期为API需要的格式 (yyyy-MM-ddTHH:mm:ss)
 */
function formatDateForApi(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    const seconds = String(date.getSeconds()).padStart(2, '0');
    return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}`;
}

/**
 * 类型映射为中文
 */
function mapReviewTypeToZh(type) {
    if (!type) return '片段';
    const t = String(type).toUpperCase();
    if (t === 'PROJECT') return '整个项目';
    if (t === 'DIRECTORY') return '指定目录';
    if (t === 'FILE') return '指定文件';
    if (t === 'SNIPPET') return '代码片段';
    if (t === 'GIT') return 'git项目';
    return '代码片段';
}

function mapTaskStatusToZh(status) {
    const s = String(status || '').toUpperCase();
    if (s === 'PENDING') return '待处理';
    if (s === 'RUNNING') return '运行中';
    if (s === 'COMPLETED') return '已完成';
    if (s === 'FAILED') return '失败';
    if (s === 'CANCELLED') return '已取消';
    return '待处理';
}
