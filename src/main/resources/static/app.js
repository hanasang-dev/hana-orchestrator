// 전역 유틸리티 함수들 (가장 먼저 정의)
function escapeHtml(text) {
    if (!text) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

const API_BASE = window.location.origin;

const STATUS_LABELS = { running: '실행 중', completed: '완료', failed: '실패', retrying: '재시도 중' };
function getStatusLabel(status) { return STATUS_LABELS[(status || '').toLowerCase()] || status || ''; }
function formatNodeStats(exec) {
    return (exec.nodeCount > 0 ? `<span class="node-stat">전체: <strong>${exec.nodeCount}</strong></span>` : '') +
        (exec.completedNodes > 0 ? `<span class="node-stat">✅ 성공: <strong>${exec.completedNodes}</strong></span>` : '') +
        (exec.failedNodes > 0 ? `<span class="node-stat">❌ 실패: <strong>${exec.failedNodes}</strong></span>` : '') +
        (exec.runningNodes > 0 ? `<span class="node-stat">🔄 실행중: <strong>${exec.runningNodes}</strong></span>` : '');
}

// ─────────────────────────────────────────────
// 트리 편집기 (Cytoscape.js)
// ─────────────────────────────────────────────
let cyInstance = null;          // Cytoscape 인스턴스
let treeEditorQuery = '';       // 현재 편집 중인 쿼리
let treeEditorLayers = [];      // 팔레트용 레이어 목록
let selectedNodeId = null;      // 우클릭/선택된 노드 id
let nodeCounter = 0;            // 신규 노드 id 채번

let lastReActTree = null;       // 마지막 ReAct 실행 결과 트리 (저장용)
let lastUserMessage = '';       // 마지막 사용자 메시지 (트리 저장 시 query로 사용)
let hoveredEdgeId = null;       // 호버 중인 엣지 id
let edgeHideTimeout = null;     // 엣지 X버튼 hide 딜레이 타이머
let currentNodeResults = {};    // 현재 트리의 노드 실행 결과 (P3)

function showTreeVisualization(executionTree, nodeResults) {
    if (!executionTree || !executionTree.rootNodes || executionTree.rootNodes.length === 0) {
        alert('실행 트리 정보가 없습니다.');
        return;
    }
    currentNodeResults = nodeResults || {};

    // 현재 실행 데이터에서 쿼리 가져오기
    const execData = findExecutionDataByTree(executionTree);
    treeEditorQuery = execData ? execData.query : '';
    document.getElementById('treeModalQuery').textContent = treeEditorQuery || '쿼리 정보 없음';

    // 검토 영역 초기화
    document.getElementById('reviewResult').textContent = '';
    document.getElementById('executeTreeBtn').disabled = true;
    document.getElementById('executeTreeBtn').style.opacity = '0.5';

    document.getElementById('treeModal').style.display = 'flex';

    // Cytoscape 초기화 (모달이 flex된 후 렌더링)
    setTimeout(() => initCytoscape(executionTree), 50);
    buildLayerPalette();
}

function findExecutionDataByTree(executionTree) {
    if (!window.executionDataCache) return null;
    for (const exec of window.executionDataCache.values()) {
        if (exec.executionTree && JSON.stringify(exec.executionTree.rootNodes) === JSON.stringify(executionTree.rootNodes)) {
            return exec;
        }
    }
    return null;
}

function closeTreeModal() {
    document.getElementById('treeModal').style.display = 'none';
    document.getElementById('nodeContextMenu').style.display = 'none';
    closeNodeEditor();
    if (cyInstance) { cyInstance.destroy(); cyInstance = null; }
}

// P4: 빈 트리 편집기 열기
function newEmptyTree() {
    currentNodeResults = {};
    treeEditorQuery = '';
    document.getElementById('treeModalQuery').textContent = '새 트리';
    document.getElementById('reviewResult').textContent = '';
    document.getElementById('executeTreeBtn').disabled = false;
    document.getElementById('executeTreeBtn').style.opacity = '1';
    document.getElementById('treeModal').style.display = 'flex';
    setTimeout(() => initCytoscape({ rootNodes: [], name: 'new_tree' }), 50);
    buildLayerPalette();
}

// ── 노드 Args 편집기 ──
function showNodeEditor(nodeId) {
    const node = cyInstance && cyInstance.getElementById(nodeId);
    if (!node || node.length === 0) return;

    const layerName = node.data('layerName');
    const fnName = node.data('function');
    const currentArgs = node.data('args') || {};
    const hasParent = cyInstance.edges().filter(e => e.data('target') === nodeId).length > 0;

    document.getElementById('nodeEditorTitle').textContent = `${layerName}.${fnName}`;

    // P3: 노드 실행 결과가 있으면 결과 표시 (args 편집기 대신)
    const nr = currentNodeResults[nodeId];
    if (nr) {
        const statusIcon = { SUCCESS: '✅', FAILED: '❌', RUNNING: '⏳', SKIPPED: '⏭' }[nr.status] || '❓';
        const fields = document.getElementById('nodeEditorFields');
        fields.innerHTML = `
            <div style="margin-bottom:6px; font-size:11px; font-weight:700; color:#495057;">${statusIcon} ${nr.status}</div>
            ${nr.result ? `<div style="margin-bottom:6px;"><div style="font-size:10px; color:#868e96; margin-bottom:2px;">결과</div>
                <pre style="font-size:11px; white-space:pre-wrap; word-break:break-all; background:#f8f9fa; border:1px solid #dee2e6; border-radius:4px; padding:6px; margin:0; max-height:120px; overflow:auto;">${escapeHtml(nr.result)}</pre></div>` : ''}
            ${nr.error ? `<div><div style="font-size:10px; color:#e03131; margin-bottom:2px;">에러</div>
                <pre style="font-size:11px; white-space:pre-wrap; word-break:break-all; background:#fff5f5; border:1px solid #ffc9c9; border-radius:4px; padding:6px; margin:0; max-height:80px; overflow:auto;">${escapeHtml(nr.error)}</pre></div>` : ''}
        `;
        document.getElementById('nodeEditorPanel').style.display = 'block';
        return;
    }

    const layer = treeEditorLayers.find(l => l.name === layerName);
    const params = layer?.functionDetails?.[fnName]?.parameters || {};

    const fields = document.getElementById('nodeEditorFields');
    const paramEntries = Object.entries(params);
    if (paramEntries.length === 0) {
        fields.innerHTML = '<p style="font-size:11px; color:#aaa; margin:0;">파라미터 없음</p>';
    } else {
        fields.innerHTML = paramEntries.map(([pName, pInfo]) => {
            const required = pInfo.required !== false;
            const currentVal = currentArgs[pName] !== undefined ? currentArgs[pName] : '';
            const parentBtn = hasParent
                ? `<button type="button" onclick="document.getElementById('argInput_${pName}').value='{{parent}}'"
                     title="부모 실행 결과를 이 인자로 사용"
                     style="padding:4px 6px; font-size:10px; background:#f0f4ff; border:1px solid #667eea; border-radius:4px; cursor:pointer; color:#667eea; white-space:nowrap; flex-shrink:0;">↑부모</button>`
                : '';
            return `<div style="margin-bottom:10px;">
                <label style="font-size:11px; font-weight:600; color:#495057; display:block; margin-bottom:3px;">
                    ${pName}
                    <span style="font-size:10px; color:${required ? '#e03131' : '#aaa'}; font-weight:400;"> ${required ? '필수' : '선택'} · ${pInfo.type}</span>
                </label>
                <div style="display:flex; gap:4px; align-items:center;">
                    <input type="text" id="argInput_${pName}" value="${escapeHtml(String(currentVal))}"
                           placeholder="${pInfo.defaultValue || ''}"
                           style="flex:1; padding:5px 8px; font-size:12px; border:1px solid #dee2e6; border-radius:4px; outline:none; min-width:0;"
                           onfocus="this.style.borderColor='#667eea'" onblur="this.style.borderColor='#dee2e6'">
                    ${parentBtn}
                </div>
            </div>`;
        }).join('');
    }

    document.getElementById('nodeEditorPanel').style.display = 'block';
}

function closeNodeEditor() {
    document.getElementById('nodeEditorPanel').style.display = 'none';
}

// 선택된 노드를 부모에서 분리 (루트로 만들기)
function detachFromParent() {
    if (!selectedNodeId || !cyInstance) return;
    const parentEdges = cyInstance.edges().filter(e => e.data('target') === selectedNodeId);
    if (parentEdges.length === 0) { alert('이미 루트 노드입니다.'); return; }
    parentEdges.remove();
    cyInstance.layout({ name: 'dagre', rankDir: 'TB', nodeSep: 60, rankSep: 80, padding: 30 }).run();
    showNodeEditor(selectedNodeId); // 편집기 갱신 (↑부모 버튼 제거)
    hideContextMenu();
}

function applyNodeArgs() {
    if (!selectedNodeId || !cyInstance) return;
    const node = cyInstance.getElementById(selectedNodeId);
    if (!node || node.length === 0) return;

    const layerName = node.data('layerName');
    const fnName = node.data('function');
    const layer = treeEditorLayers.find(l => l.name === layerName);
    const params = layer?.functionDetails?.[fnName]?.parameters || {};

    const newArgs = {};
    Object.keys(params).forEach(pName => {
        const input = document.getElementById(`argInput_${pName}`);
        if (input && input.value.trim() !== '') newArgs[pName] = input.value.trim();
    });

    node.data('args', newArgs);
    // 라벨 갱신: args 일부 표시
    const argsStr = Object.entries(newArgs).slice(0, 2).map(([k, v]) => `${k}=${v}`).join('\n');
    node.data('label', `${layerName}.${fnName}${argsStr ? '\n' + argsStr : ''}`);

    closeNodeEditor();
}

// ── Cytoscape 초기화 ──
function initCytoscape(executionTree) {
    if (cyInstance) { cyInstance.destroy(); cyInstance = null; }

    const elements = treeToElements(executionTree);
    cyInstance = cytoscape({
        container: document.getElementById('treeCanvas'),
        elements,
        style: cytoscapeStyle(),
        layout: { name: 'dagre', rankDir: 'TB', nodeSep: 60, rankSep: 80, padding: 30 },
        minZoom: 0.3,
        maxZoom: 2.5,
        userZoomingEnabled: true,
        userPanningEnabled: true
    });

    // 우클릭: 삭제 메뉴
    cyInstance.on('cxttap', 'node', e => {
        selectedNodeId = e.target.id();
        showContextMenu(e.originalEvent.clientX, e.originalEvent.clientY);
    });

    // 클릭: 노드 선택 + args 편집기 표시
    cyInstance.on('tap', 'node', e => {
        selectedNodeId = e.target.id();
        showNodeEditor(selectedNodeId);
    });

    // 빈 캔버스 클릭: 편집기 닫기
    cyInstance.on('tap', e => {
        if (e.target === cyInstance) closeNodeEditor();
    });

    // 노드 드래그: 다른 노드 위에 올리면 하이라이트
    cyInstance.on('drag', 'node', e => {
        const dragged = e.target;
        cyInstance.nodes().removeClass('drop-target');
        const over = findOverlappingNode(dragged);
        if (over) over.addClass('drop-target');
    });

    // 노드 드래그 종료: 다른 노드 위에 놓으면 리패런팅
    cyInstance.on('dragfree', 'node', e => {
        const dragged = e.target;
        cyInstance.nodes().removeClass('drop-target');
        const over = findOverlappingNode(dragged);
        if (over) reparentNode(dragged.id(), over.id());
    });

    // 엣지 호버: X 버튼 표시
    cyInstance.on('mouseover', 'edge', e => {
        clearTimeout(edgeHideTimeout);
        hoveredEdgeId = e.target.id();
        const src = cyInstance.getElementById(e.target.data('source'));
        const tgt = cyInstance.getElementById(e.target.data('target'));
        const mx = (src.renderedPosition('x') + tgt.renderedPosition('x')) / 2;
        const my = (src.renderedPosition('y') + tgt.renderedPosition('y')) / 2;
        const btn = document.getElementById('edgeDeleteBtn');
        btn.style.left = mx + 'px';
        btn.style.top = my + 'px';
        btn.style.display = 'block';
    });
    cyInstance.on('mouseout', 'edge', () => {
        edgeHideTimeout = setTimeout(() => {
            document.getElementById('edgeDeleteBtn').style.display = 'none';
            hoveredEdgeId = null;
        }, 150);
    });

    // 팔레트 드롭 이벤트 설정
    const canvas = document.getElementById('treeCanvas');
    canvas.ondragover = e => {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'copy';
        // 드롭 위치 아래 노드 하이라이트
        cyInstance.nodes().removeClass('drop-target');
        const nodeUnder = getNodeAtScreenPos(e.clientX, e.clientY);
        if (nodeUnder) nodeUnder.addClass('drop-target');
    };
    canvas.ondragleave = () => cyInstance.nodes().removeClass('drop-target');
    canvas.ondrop = e => {
        e.preventDefault();
        cyInstance.nodes().removeClass('drop-target');
        try {
            const { layerName, fnName } = JSON.parse(e.dataTransfer.getData('text/plain'));
            const nodeUnder = getNodeAtScreenPos(e.clientX, e.clientY);
            const cyPos = screenToCyPos(e.clientX, e.clientY);
            addNodeAtPos(layerName, fnName, cyPos, nodeUnder ? nodeUnder.id() : null);
        } catch (_) {}
    };

    document.addEventListener('click', hideContextMenu);

    // 노드 실행 결과 색상 오버레이 (P3)
    applyNodeResultStyles(currentNodeResults);
}

// 노드 실행 결과에 따른 Cytoscape 스타일 적용 (P3)
function applyNodeResultStyles(nodeResults) {
    if (!cyInstance || !nodeResults || Object.keys(nodeResults).length === 0) return;
    const colorMap = { SUCCESS: '#4ade80', FAILED: '#f87171', RUNNING: '#60a5fa', SKIPPED: '#d1d5db' };
    const borderMap = { SUCCESS: '#16a34a', FAILED: '#dc2626', RUNNING: '#2563eb', SKIPPED: '#9ca3af' };
    cyInstance.nodes().forEach(node => {
        const nr = nodeResults[node.id()];
        if (!nr) return;
        const bg = colorMap[nr.status] || '#667eea';
        const border = borderMap[nr.status] || '#5a67d8';
        node.style({ 'background-color': bg, 'border-color': border });
    });
}

// 화면 좌표 → Cytoscape 모델 좌표
function screenToCyPos(clientX, clientY) {
    const canvas = document.getElementById('treeCanvas');
    const rect = canvas.getBoundingClientRect();
    const pan = cyInstance.pan();
    const zoom = cyInstance.zoom();
    return {
        x: (clientX - rect.left - pan.x) / zoom,
        y: (clientY - rect.top - pan.y) / zoom
    };
}

// 화면 좌표에 있는 노드 반환
function getNodeAtScreenPos(clientX, clientY) {
    const canvas = document.getElementById('treeCanvas');
    const rect = canvas.getBoundingClientRect();
    const rx = clientX - rect.left;
    const ry = clientY - rect.top;
    return cyInstance.nodes().filter(n => {
        const pos = n.renderedPosition();
        const w = n.renderedWidth() / 2 + 8;
        const h = n.renderedHeight() / 2 + 8;
        return Math.abs(pos.x - rx) < w && Math.abs(pos.y - ry) < h;
    }).first().length > 0
        ? cyInstance.nodes().filter(n => {
            const pos = n.renderedPosition();
            const w = n.renderedWidth() / 2 + 8;
            const h = n.renderedHeight() / 2 + 8;
            return Math.abs(pos.x - rx) < w && Math.abs(pos.y - ry) < h;
        }).first()
        : null;
}

// 드래그 중 겹치는 다른 노드 찾기
function findOverlappingNode(draggedNode) {
    const pos = draggedNode.renderedPosition();
    const result = cyInstance.nodes().filter(n => {
        if (n.id() === draggedNode.id()) return false;
        const np = n.renderedPosition();
        const w = n.renderedWidth() / 2 + 10;
        const h = n.renderedHeight() / 2 + 10;
        return Math.abs(np.x - pos.x) < w && Math.abs(np.y - pos.y) < h;
    }).first();
    return result.length > 0 ? result : null;
}

// 노드 리패런팅: draggedId를 newParentId의 자식으로 이동
function reparentNode(draggedId, newParentId) {
    if (draggedId === newParentId) return;

    // newParent가 dragged의 직접 부모인 경우 → 방향 swap
    const directParentEdge = cyInstance.edges().filter(e =>
        e.data('source') === newParentId && e.data('target') === draggedId
    );
    if (directParentEdge.length > 0) {
        // dragged에 자식이 있으면 swap 불가
        const hasChildren = cyInstance.edges().filter(e => e.data('source') === draggedId).length > 0;
        if (hasChildren) return;
        directParentEdge.remove();
        cyInstance.add({ data: { id: `${draggedId}_${newParentId}_${Date.now()}`, source: draggedId, target: newParentId } });
        cyInstance.layout({ name: 'dagre', rankDir: 'TB', nodeSep: 60, rankSep: 80, padding: 30 }).run();
        return;
    }

    // 순환 방지: newParent가 dragged의 자손이면 중단
    if (isDescendant(newParentId, draggedId)) return;

    // 기존 부모 엣지 제거
    cyInstance.edges().filter(e => e.data('target') === draggedId).remove();

    // 새 부모 엣지 추가
    cyInstance.add({ data: { id: `${newParentId}_${draggedId}_${Date.now()}`, source: newParentId, target: draggedId } });

    // 레이아웃 재정렬
    cyInstance.layout({ name: 'dagre', rankDir: 'TB', nodeSep: 60, rankSep: 80, padding: 30 }).run();
}

// 호버 중인 엣지 삭제
function deleteHoveredEdge() {
    if (!hoveredEdgeId || !cyInstance) return;
    cyInstance.getElementById(hoveredEdgeId).remove();
    document.getElementById('edgeDeleteBtn').style.display = 'none';
    hoveredEdgeId = null;
}

// nodeId가 ancestorId의 자손인지 확인 (순환 방지)
function isDescendant(nodeId, ancestorId) {
    const visited = new Set();
    const queue = [ancestorId];
    while (queue.length > 0) {
        const cur = queue.shift();
        if (cur === nodeId) return true;
        if (visited.has(cur)) continue;
        visited.add(cur);
        cyInstance.edges().filter(e => e.data('source') === cur)
            .forEach(e => queue.push(e.data('target')));
    }
    return false;
}

function cytoscapeStyle() {
    return [
        {
            selector: 'node',
            style: {
                'background-color': '#667eea',
                'label': 'data(label)',
                'color': '#fff',
                'text-valign': 'center',
                'text-halign': 'center',
                'font-size': '11px',
                'font-weight': '600',
                'text-wrap': 'wrap',
                'text-max-width': '120px',
                'width': 'label',
                'height': 'label',
                'padding': '10px',
                'shape': 'roundrectangle',
                'border-width': 2,
                'border-color': '#5a67d8'
            }
        },
        {
            selector: 'node:selected',
            style: { 'background-color': '#e03131', 'border-color': '#c92a2a' }
        },
        {
            selector: 'node.new-node',
            style: { 'background-color': '#2f9e44', 'border-color': '#2b8a3e' }
        },
        {
            selector: 'node.drop-target',
            style: { 'background-color': '#f59f00', 'border-color': '#e67700', 'border-width': 3 }
        },
        {
            selector: 'edge',
            style: {
                'width': 2,
                'line-color': '#adb5bd',
                'target-arrow-color': '#adb5bd',
                'target-arrow-shape': 'triangle',
                'curve-style': 'bezier'
            }
        }
    ];
}

// ExecutionTree → Cytoscape elements 변환
function treeToElements(executionTree) {
    const elements = [];
    function walk(node, parentId) {
        const nodeId = node.id || `node_${nodeCounter++}`;
        const argsStr = node.args ? Object.entries(node.args).slice(0, 2).map(([k, v]) => `${k}=${v}`).join('\n') : '';
        const label = `${node.layerName}.${node.function}${argsStr ? '\n' + argsStr : ''}`;
        elements.push({ data: { id: nodeId, label, layerName: node.layerName, function: node.function, args: node.args || {} } });
        if (parentId) elements.push({ data: { id: `${parentId}_${nodeId}`, source: parentId, target: nodeId } });
        (node.children || []).forEach(child => walk(child, nodeId));
    }
    (executionTree.rootNodes || []).forEach(root => walk(root, null));
    return elements;
}

// Cytoscape elements → ExecutionTreeResponse (백엔드 전송용)
function elementsToTree() {
    if (!cyInstance) return null;
    const nodes = cyInstance.nodes();
    const edges = cyInstance.edges();

    // 부모 → 자식 관계 맵 구성
    const childrenMap = new Map();
    const hasParent = new Set();
    edges.forEach(e => {
        const src = e.data('source'), tgt = e.data('target');
        if (!childrenMap.has(src)) childrenMap.set(src, []);
        childrenMap.get(src).push(tgt);
        hasParent.add(tgt);
    });

    // 루트 노드 = 부모 없는 노드
    const rootIds = nodes.map(n => n.id()).filter(id => !hasParent.has(id));

    function buildNode(nodeId) {
        const n = cyInstance.getElementById(nodeId);
        return {
            id: nodeId,
            layerName: n.data('layerName'),
            function: n.data('function'),
            args: n.data('args') || {},
            children: (childrenMap.get(nodeId) || []).map(buildNode),
            parallel: false
        };
    }

    return { rootNodes: rootIds.map(buildNode), name: 'execution_plan' };
}

// ── 레이어 팔레트 ──
function buildLayerPalette() {
    const palette = document.getElementById('paletteContent');
    palette.innerHTML = treeEditorLayers.length === 0
        ? '<p style="font-size:12px;color:#aaa;">레이어 로딩 중...</p>'
        : treeEditorLayers.map(layer => `
            <div style="margin-bottom:10px;">
                <div style="font-size:12px; font-weight:700; color:#495057; padding:4px 6px; background:#e9ecef; border-radius:4px; margin-bottom:4px;">${layer.name}</div>
                ${layer.functions.map(fn => `
                    <div class="palette-fn" data-layer="${layer.name}" data-fn="${fn}"
                         draggable="true"
                         style="padding:5px 8px; font-size:11px; color:#495057; cursor:grab; border-radius:4px; margin-bottom:2px; border:1px solid transparent;"
                         onmouseover="this.style.background='#e0f2fe';this.style.borderColor='#667eea'"
                         onmouseout="this.style.background='';this.style.borderColor='transparent'"
                         ondragstart="paletteDragStart(event, this.dataset.layer, this.dataset.fn)"
                         ondblclick="addNodeFromPalette(this.dataset.layer, this.dataset.fn)">
                        ⚙ ${fn}
                    </div>
                `).join('')}
            </div>
        `).join('');
}

function paletteDragStart(e, layerName, fnName) {
    e.dataTransfer.setData('text/plain', JSON.stringify({ layerName, fnName }));
    e.dataTransfer.effectAllowed = 'copy';
}

// 팔레트 드래그 또는 더블클릭으로 노드 추가
function addNodeFromPalette(layerName, fnName) {
    // 더블클릭: 선택된 노드의 자식으로, 없으면 루트로
    addNodeAtPos(layerName, fnName, null, selectedNodeId);
}

// 지정 위치/부모에 노드 추가 (팔레트 드래그 + 더블클릭 공통)
function addNodeAtPos(layerName, fnName, cyPos, parentId) {
    if (!cyInstance) return;
    const newId = `node_${layerName}_${fnName}_${Date.now()}`;
    const label = `${layerName}.${fnName}`;
    const nodeData = { data: { id: newId, label, layerName, function: fnName, args: {} }, classes: 'new-node' };
    if (cyPos) nodeData.position = cyPos;

    cyInstance.add(nodeData);
    if (parentId && cyInstance.getElementById(parentId).length > 0) {
        cyInstance.add({ data: { id: `${parentId}_${newId}`, source: parentId, target: newId } });
    }
    cyInstance.layout({ name: 'dagre', rankDir: 'TB', nodeSep: 60, rankSep: 80, padding: 30 }).run();
    selectedNodeId = newId;
    // 신규 노드: 바로 args 편집기 오픈
    showNodeEditor(newId);
}

// ── 컨텍스트 메뉴 ──
function showContextMenu(x, y) {
    const menu = document.getElementById('nodeContextMenu');
    menu.style.display = 'block';
    menu.style.left = x + 'px';
    menu.style.top = y + 'px';
}

function hideContextMenu() {
    document.getElementById('nodeContextMenu').style.display = 'none';
}

function deleteSelectedNode() {
    if (!cyInstance || !selectedNodeId) return;
    const node = cyInstance.getElementById(selectedNodeId);
    // 해당 노드와 연결된 엣지도 함께 삭제
    cyInstance.remove(node.connectedEdges());
    cyInstance.remove(node);
    selectedNodeId = null;
    hideContextMenu();
}

// ── 트리 저장 / 불러오기 ──
async function saveReActTree() {
    if (!lastReActTree) { alert('저장할 트리가 없습니다.'); return; }
    const name = prompt('저장할 이름을 입력하세요:', lastUserMessage.slice(0, 30) || 'react-tree');
    if (!name) return;
    try {
        const res = await fetch('/trees/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, query: lastUserMessage, tree: lastReActTree })
        });
        const data = await res.json();
        if (data.success) alert(`✅ "${name}" 으로 저장되었습니다.`);
        else alert('저장 실패: ' + (data.error || '알 수 없는 오류'));
    } catch (e) {
        alert('저장 실패: ' + e.message);
    }
}

async function saveTree() {
    const tree = elementsToTree();
    if (!tree || tree.rootNodes.length === 0) { alert('저장할 트리가 없습니다.'); return; }
    const name = prompt('저장할 이름을 입력하세요:', treeEditorQuery.slice(0, 30) || 'my-tree');
    if (!name) return;
    try {
        const res = await fetch('/trees/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, query: treeEditorQuery, tree })
        });
        const data = await res.json();
        if (data.success) alert(`✅ "${name}" 으로 저장되었습니다.`);
        else alert('저장 실패: ' + (data.error || '알 수 없는 오류'));
    } catch (e) {
        alert('저장 실패: ' + e.message);
    }
}

async function loadTreeModal() {
    const modal = document.getElementById('loadTreeModal');
    const listEl = document.getElementById('savedTreeList');
    modal.style.display = 'flex';
    listEl.innerHTML = '<div style="padding:20px; color:#666; text-align:center;">⏳ 불러오는 중...</div>';
    try {
        const res = await fetch('/trees');
        const trees = await res.json();
        if (!trees.length) {
            listEl.innerHTML = '<div style="padding:20px; color:#999; text-align:center;">저장된 트리가 없습니다.</div>';
            return;
        }
        listEl.innerHTML = trees.map(t => `
            <div onclick="loadTree('${t.name}')" style="padding:12px 16px; border:1px solid #e0e0e0; border-radius:8px; margin-bottom:8px; cursor:pointer; hover:background:#f5f5f5;">
                <div style="font-weight:600; font-size:14px;">📄 ${t.name}</div>
                <div style="font-size:12px; color:#666; margin-top:4px;">${t.query ? t.query.slice(0, 60) + (t.query.length > 60 ? '...' : '') : ''}</div>
                <div style="font-size:11px; color:#999; margin-top:2px;">${new Date(t.savedAt).toLocaleString()}</div>
            </div>
        `).join('');
    } catch (e) {
        listEl.innerHTML = '<div style="padding:20px; color:#c00;">불러오기 실패: ' + e.message + '</div>';
    }
}

async function loadTree(name) {
    try {
        const res = await fetch(`/trees/${name}`);
        const saved = await res.json();
        treeEditorQuery = saved.query || '';
        document.getElementById('treeModalQuery').textContent = treeEditorQuery || '쿼리 정보 없음';
        document.getElementById('reviewResult').textContent = '';
        document.getElementById('treeModal').style.display = 'flex';
        closeLoadTreeModal();
        setTimeout(() => { initCytoscape(saved.tree); buildLayerPalette(); }, 50);
    } catch (e) {
        alert('트리 로드 실패: ' + e.message);
    }
}

function closeLoadTreeModal() {
    document.getElementById('loadTreeModal').style.display = 'none';
}

// ── LLM 검토 ──
async function reviewTree() {
    const tree = elementsToTree();
    if (!tree || tree.rootNodes.length === 0) { alert('트리가 비어있습니다.'); return; }

    const reviewBtn = document.getElementById('reviewBtn');
    const reviewResult = document.getElementById('reviewResult');
    const executeBtn = document.getElementById('executeTreeBtn');

    reviewBtn.disabled = true;
    reviewBtn.textContent = '🔍 검토 중...';
    reviewResult.textContent = '⏳ LLM이 트리를 검토하고 있습니다...';
    reviewResult.style.color = '#667eea';
    executeBtn.disabled = true;
    executeBtn.style.opacity = '0.5';

    try {
        const res = await fetch(`${API_BASE}/tree/review`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: treeEditorQuery, tree })
        });
        const data = await res.json();
        if (data.approved) {
            reviewResult.textContent = `✅ ${data.reason}`;
            reviewResult.style.color = '#2f9e44';
            executeBtn.disabled = false;
            executeBtn.style.opacity = '1';
        } else {
            reviewResult.textContent = `⚠️ ${data.reason}`;
            reviewResult.style.color = '#e03131';
        }
    } catch (e) {
        reviewResult.textContent = `오류: ${e.message}`;
        reviewResult.style.color = '#e03131';
    } finally {
        reviewBtn.disabled = false;
        reviewBtn.textContent = '🔍 LLM 검토';
    }
}

// ── 트리 실행 ──
async function executeEditedTree() {
    const tree = elementsToTree();
    if (!tree) return;

    const executeBtn = document.getElementById('executeTreeBtn');
    const reviewResult = document.getElementById('reviewResult');
    executeBtn.disabled = true;
    executeBtn.textContent = '⏳ 실행 중...';
    reviewResult.textContent = '⚡ 실행 중입니다...';
    reviewResult.style.color = '#667eea';

    try {
        const res = await fetch(`${API_BASE}/tree/execute`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: treeEditorQuery, tree })
        });
        const data = await res.json();
        if (data.error) {
            reviewResult.textContent = `❌ 실행 오류: ${data.error}`;
            reviewResult.style.color = '#e03131';
        } else {
            reviewResult.textContent = `✅ 실행 완료! 결과: ${(data.results || []).join(', ')}`;
            reviewResult.style.color = '#2f9e44';
            closeTreeModal();
        }
    } catch (e) {
        reviewResult.textContent = `오류: ${e.message}`;
        reviewResult.style.color = '#e03131';
    } finally {
        executeBtn.disabled = false;
        executeBtn.textContent = '▶ 실행';
    }
}

// 레이어 목록 로드
async function loadLayers() {
    const layersList = document.getElementById('layersList');
    layersList.innerHTML = '<p>로딩 중...</p>';

    try {
        const response = await fetch(`${API_BASE}/layers`);
        const layers = await response.json();

        if (layers.error) {
            layersList.innerHTML = `<div class="message error">${layers.error}</div>`;
            return;
        }

        if (layers.length === 0) {
            layersList.innerHTML = '<p>등록된 레이어가 없습니다.</p>';
            return;
        }

        // 팔레트용 레이어 캐시
        treeEditorLayers = layers;
        
        layersList.innerHTML = layers.map(layer => `
            <div class="layer-item">
                <h3>${layer.name}</h3>
                <p>${layer.description}</p>
                <div class="functions">
                    ${layer.functions.map(func => `<span class="function-badge">${func}</span>`).join('')}
                </div>
            </div>
        `).join('');
    } catch (error) {
        layersList.innerHTML = `<div class="message error">레이어 목록을 불러오는 중 오류가 발생했습니다: ${error.message}</div>`;
    }
}

// 원격 레이어 등록
// 채팅 입력창 엔터 키 핸들러
document.getElementById('chatInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        document.getElementById('chatForm').dispatchEvent(new Event('submit'));
    }
});

// 채팅 폼 제출 핸들러
document.getElementById('chatForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const chatInput = document.getElementById('chatInput');
    const chatMessages = document.getElementById('chatMessages');
    const chatStatus = document.getElementById('chatStatus');
    const message = chatInput.value.trim();
    
    if (!message) return;

    lastUserMessage = message;

    // 사용자 메시지 표시
    chatMessages.innerHTML = `<div style="margin-bottom: 10px; padding: 8px 12px; background: #667eea; color: white; border-radius: 8px; text-align: right; max-width: 80%; margin-left: auto;">
        <strong>사용자:</strong> ${message}
    </div>`;
    
    chatInput.value = '';
    chatInput.disabled = true;
    chatStatus.textContent = '요청 처리 중...';
    chatStatus.style.color = '#667eea';
    
    try {
        const response = await fetch(`${API_BASE}/chat`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ message })
        });
        
        const result = await response.json();
        
        if (result.error) {
            chatMessages.innerHTML += `<div style="margin-top: 10px; padding: 8px 12px; background: #ff6b6b; color: white; border-radius: 8px;">
                <strong>오류:</strong> ${result.error}
            </div>`;
            chatStatus.textContent = '실행 실패';
            chatStatus.style.color = '#ff6b6b';
        } else {
            const results = result.results || [];
            if (results.length > 0) {
                results.forEach((res, idx) => {
                    chatMessages.innerHTML += `<div style="margin-top: 10px; padding: 8px 12px; background: #51cf66; color: white; border-radius: 8px;">
                        <strong>결과 ${results.length > 1 ? idx + 1 : ''}:</strong> ${res}
                    </div>`;
                });
            } else {
                chatMessages.innerHTML += `<div style="margin-top: 10px; padding: 8px 12px; background: #51cf66; color: white; border-radius: 8px;">
                    실행 완료 (결과 없음)
                </div>`;
            }
            chatStatus.textContent = '실행 완료';
            chatStatus.style.color = '#51cf66';

            // ReAct 트리 저장 버튼
            const saveBtn = document.getElementById('saveReActTreeBtn');
            if (result.tree && result.tree.rootNodes && result.tree.rootNodes.length > 0) {
                lastReActTree = result.tree;
                if (saveBtn) saveBtn.style.display = 'inline-block';
            } else {
                lastReActTree = null;
                if (saveBtn) saveBtn.style.display = 'none';
            }

            // 실행 이력 새로고침
            loadExecutions();
        }
    } catch (error) {
        chatMessages.innerHTML += `<div style="margin-top: 10px; padding: 8px 12px; background: #ff6b6b; color: white; border-radius: 8px;">
            <strong>오류:</strong> ${error.message}
        </div>`;
        chatStatus.textContent = '요청 실패';
        chatStatus.style.color = '#ff6b6b';
    } finally {
        chatInput.disabled = false;
        chatInput.focus();
        
        // 메시지 영역 스크롤을 맨 아래로
        chatMessages.scrollTop = chatMessages.scrollHeight;
    }
});

document.getElementById('registerForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const baseUrl = document.getElementById('baseUrl').value;
    const messageDiv = document.getElementById('registerMessage');
    const submitBtn = e.target.querySelector('button[type="submit"]');
    
    messageDiv.innerHTML = '';
    submitBtn.disabled = true;
    submitBtn.innerHTML = '등록 중... <span class="loading"></span>';
    
    try {
        const response = await fetch(`${API_BASE}/layers/register-remote`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ baseUrl })
        });
        
        const result = await response.json();
        
        if (result.error) {
            messageDiv.innerHTML = `<div class="message error">${result.error}</div>`;
        } else {
            messageDiv.innerHTML = `<div class="message success">✅ ${result.message}: ${result.layerName} (${result.baseUrl})</div>`;
            document.getElementById('baseUrl').value = '';
            loadLayers();
        }
    } catch (error) {
        messageDiv.innerHTML = `<div class="message error">등록 중 오류가 발생했습니다: ${error.message}</div>`;
    } finally {
        submitBtn.disabled = false;
        submitBtn.innerHTML = '등록';
    }
});

let wsConnection = null;

// 진행 상태 UI 업데이트
function updateProgressUI(progress) {
    const progressContainer = document.getElementById('progressContainer');
    const progressBar = document.getElementById('progressBar');
    const progressMessage = document.getElementById('progressMessage');
    const progressTime = document.getElementById('progressTime');

    if (!progressContainer || !progressBar || !progressMessage || !progressTime) {
        return;
    }

    // 진행 중일 때만 표시
    if (progress.phase === 'COMPLETED' || progress.phase === 'FAILED') {
        // 완료/실패 시 잠시 표시 후 숨김
        setTimeout(() => {
            progressContainer.style.display = 'none';
        }, 2000);
    } else {
        progressContainer.style.display = 'block';
    }

    // 진행률 업데이트
    progressBar.style.width = progress.progress + '%';

    // 메시지 업데이트
    progressMessage.textContent = progress.message;

    // 경과 시간 업데이트
    const elapsedSec = (progress.elapsedMs / 1000).toFixed(1);
    progressTime.textContent = elapsedSec + '초';

    // 페이즈별 색상 변경
    const colors = {
        'STARTING': 'linear-gradient(90deg, #667eea 0%, #764ba2 100%)',
        'TREE_CREATION': 'linear-gradient(90deg, #f093fb 0%, #f5576c 100%)',
        'TREE_VALIDATION': 'linear-gradient(90deg, #4facfe 0%, #00f2fe 100%)',
        'TREE_EXECUTION': 'linear-gradient(90deg, #43e97b 0%, #38f9d7 100%)',
        'RESULT_EVALUATION': 'linear-gradient(90deg, #fa709a 0%, #fee140 100%)',
        'COMPLETED': 'linear-gradient(90deg, #30cfd0 0%, #330867 100%)',
        'FAILED': 'linear-gradient(90deg, #eb3349 0%, #f45c43 100%)'
    };
    progressBar.style.background = colors[progress.phase] || colors['STARTING'];
}

// WebSocket 연결 (실시간 업데이트)
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/executions`;
    
    wsConnection = new WebSocket(wsUrl);
    
    wsConnection.onopen = () => {
        console.log('WebSocket 연결됨');
    };
    
    wsConnection.onmessage = (event) => {
        try {
            if (!event.data || event.data.trim() === '') {
                console.warn('빈 WebSocket 메시지 수신');
                return;
            }
            const data = JSON.parse(event.data);
            console.log('WebSocket 데이터 수신:', data);

            // 메시지 타입 구분 (id+path+diff = ApprovalRequest 고유 필드 조합)
            if (data.id && data.path && data.diff !== undefined) {
                // 파일 수정 승인 요청
                showApprovalPanel(data);
            } else if (data.executionId && data.phase && data.message) {
                // 진행 상태 업데이트
                updateProgressUI(data);
            } else {
                // 실행 이력 업데이트
                if (data.history && data.history[0]) {
                    console.log('첫 번째 실행 이력의 executionTree:', data.history[0].executionTree);
                }
                updateExecutionsUI(data);
            }
        } catch (error) {
            console.error('WebSocket 메시지 파싱 오류:', error, '데이터:', event.data);
        }
    };
    
    wsConnection.onerror = (error) => {
        console.error('WebSocket 오류:', error);
        // 폴링으로 폴백
        setTimeout(connectWebSocket, 3000);
    };
    
    wsConnection.onclose = () => {
        console.log('WebSocket 연결 종료');
        // 재연결 시도
        setTimeout(connectWebSocket, 3000);
    };
}

// 실행 이력 UI 업데이트 (깜빡임·스크롤 초기화 방지: 전체 교체 없이 항목별 패치/추가만)
function updateExecutionsUI(data) {
    const executionsList = document.getElementById('executionsList');
    
    if (!data) {
        executionsList.innerHTML = '<p>데이터를 받지 못했습니다.</p>';
        return;
    }
    
    const ordered = []; // current 먼저, 그다음 history (중복 제외)
    if (data.current) ordered.push(data.current);
    if (data.history) {
        data.history.forEach(exec => {
            if (exec.id !== data.current?.id) ordered.push(exec);
        });
    }
    
    if (ordered.length === 0) {
        executionsList.innerHTML = '<p>실행 이력이 없습니다.</p>';
        if (window.executionDataCache) window.executionDataCache.clear();
        return;
    }
    
    const orderedIds = new Set(ordered.map(e => e.id));
    const existingById = new Map();
    executionsList.querySelectorAll('.execution-item').forEach(el => {
        const id = el.getAttribute('data-id');
        if (id) existingById.set(id, el);
    });
    
    for (let i = 0; i < ordered.length; i++) {
        const exec = ordered[i];
        const isCurrent = exec.id === data.current?.id;
        
        if (existingById.has(exec.id)) {
            patchExecutionItem(existingById.get(exec.id), exec, isCurrent);
            if (exec.logs && exec.logs.length > 0) {
                updateExecutionLogs(exec.id, exec.logs);
            }
        } else {
            const nextId = ordered[i + 1]?.id;
            const nextEl = nextId ? existingById.get(nextId) || null : null;
            const div = document.createElement('div');
            div.innerHTML = renderExecution(exec, isCurrent);
            const newItem = div.firstElementChild;
            if (newItem) {
                if (nextEl) executionsList.insertBefore(newItem, nextEl);
                else executionsList.appendChild(newItem);
                existingById.set(exec.id, newItem);
                attachExecutionItemListeners(newItem, exec.id);
            }
        }
    }
    
    // 데이터에 없는 항목은 DOM에서 제거
    existingById.forEach((el, id) => {
        if (!orderedIds.has(id)) el.remove();
    });
    
    if (window.executionDataCache) {
        ordered.forEach(exec => window.executionDataCache.set(exec.id, exec));
    } else {
        window.executionDataCache = new Map();
        ordered.forEach(exec => window.executionDataCache.set(exec.id, exec));
    }
}

function patchExecutionItem(item, exec, isCurrent) {
    const statusClass = (exec.status || '').toLowerCase();
    const statusText = getStatusLabel(exec.status);
    const duration = exec.endTime
        ? `${((exec.endTime - exec.startTime) / 1000).toFixed(2)}초`
        : isCurrent ? `<span class="elapsed-time">⏱️ ${((Date.now() - exec.startTime) / 1000).toFixed(1)}초 경과</span>` : '';
    const timeStr = new Date(exec.startTime).toLocaleTimeString('ko-KR');
    
    item.className = 'execution-item ' + statusClass;
    item.setAttribute('data-start-time', exec.startTime);
    
    const meta = item.querySelector('.execution-meta');
    if (meta) {
        meta.innerHTML = `<span>${timeStr}</span>${duration ? `<span>${duration}</span>` : ''}<span class="status-badge status-${statusClass}">${statusText}</span>${exec.executionTree ? `<button class="tree-view-btn" data-exec-id="${exec.id}" style="margin-left: 8px; padding: 4px 12px; background: #667eea; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 11px; font-weight: 600;">🌳 트리 보기</button>` : ''}`;

        // 버튼 이벤트 리스너 재연결
        const treeViewBtn = meta.querySelector('.tree-view-btn');
        if (treeViewBtn) {
            treeViewBtn.addEventListener('click', function(e) {
                e.stopPropagation();
                const execData = findExecutionData(exec.id);
                if (execData && execData.executionTree) {
                    showTreeVisualization(execData.executionTree, execData.nodeResults);
                }
            });
        }
    }
    const nodeStats = item.querySelector('.node-stats');
    if (nodeStats) nodeStats.innerHTML = formatNodeStats(exec);
    
    if (isCurrent && (exec.status === 'RUNNING' || exec.status === 'RETRYING')) {
        startElapsedTimeTimer(exec.id, exec.startTime);
    } else {
        stopElapsedTimeTimer(exec.id);
    }
    
    const details = item.querySelector('.execution-details');
    if (!details) return;
    
    let resultBlock = details.querySelector('.execution-result');
    if (exec.error) {
        if (!resultBlock) {
            resultBlock = document.createElement('div');
            resultBlock.className = 'execution-result';
            details.appendChild(resultBlock);
        }
        resultBlock.style.background = '#f8d7da';
        resultBlock.style.color = '#721c24';
        resultBlock.innerHTML = '<strong>에러:</strong><br>' + escapeHtml(exec.error);
    } else if (exec.result && exec.result.trim() !== '') {
        if (!resultBlock) {
            resultBlock = document.createElement('div');
            resultBlock.className = 'execution-result';
            details.appendChild(resultBlock);
        }
        resultBlock.style.background = '';
        resultBlock.style.color = '';
        resultBlock.innerHTML = '<strong>✅ 최종 결과:</strong><div class="result-content">' + escapeHtml(exec.result.trim()) + '</div>';
    } else {
        if (resultBlock) {
            resultBlock.innerHTML = '<span style="color: #999; font-style: italic; padding: 6px 12px;">결과가 아직 없습니다...</span>';
            resultBlock.style.background = '';
            resultBlock.style.color = '';
        }
    }
    // 실행 트리 갱신 (패치 시 트리가 있으면 DOM에 반영)
    if (exec.executionTree) {
        let treeContainer = details.querySelector(`#tree-${exec.id}`);
        if (!treeContainer) {
            treeContainer = document.createElement('div');
            treeContainer.id = `tree-${exec.id}`;
            treeContainer.className = 'execution-tree';
            details.insertBefore(treeContainer, details.firstChild);
        }
        treeContainer.innerHTML = renderExecutionTreeSection(exec.executionTree);
    }
}

function attachExecutionItemListeners(item, execId) {
    const details = item.querySelector('.execution-details');
    const logsDiv = item.querySelector(`#logs-${execId}`);
    const logContent = item.querySelector(`#log-content-${execId}`);
    const header = item.querySelector('.execution-header');
    if (header) {
        header.style.cursor = 'pointer';
        header.addEventListener('click', function(e) {
            e.stopPropagation();
            if (details) {
                const isExpanded = details.classList.contains('expanded');
                details.classList.toggle('expanded');
                if (!isExpanded && logsDiv) {
                    logsDiv.style.display = 'block';
                    const execData = findExecutionData(execId);
                    if (execData && execData.logs && execData.logs.length > 0) {
                        updateExecutionLogs(execId, execData.logs);
                    } else if (logContent) {
                        loadExecutionLogs(execId, logContent);
                    }
                }
            }
        });
    }

    // 트리 보기 버튼 이벤트 연결
    const treeViewBtn = item.querySelector('.tree-view-btn');
    if (treeViewBtn) {
        treeViewBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            const execData = findExecutionData(execId);
            if (execData && execData.executionTree) {
                showTreeVisualization(execData.executionTree);
            }
        });
    }
}

// 실행 이력 로드 (폴링 방식 - 폴백용)
async function loadExecutions() {
    const executionsList = document.getElementById('executionsList');
    
    try {
        const response = await fetch(`${API_BASE}/executions`);
        
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }
        
        const text = await response.text();
        if (!text || text.trim() === '') {
            executionsList.innerHTML = '<p>실행 이력이 없습니다.</p>';
            return;
        }
        
        const data = JSON.parse(text);
        
        if (data.error) {
            executionsList.innerHTML = `<div class="message error">${data.error}</div>`;
            return;
        }
        
        updateExecutionsUI(data);
    } catch (error) {
        console.error('실행 이력 로드 오류:', error);
        executionsList.innerHTML = `<div class="message error">실행 이력을 불러오는 중 오류가 발생했습니다: ${error.message}</div>`;
    }
}

// 실행 중인 작업의 경과 시간을 실시간으로 업데이트하는 타이머
let elapsedTimeTimers = new Map();

function updateElapsedTime(execId, startTime) {
    const element = document.querySelector(`[data-id="${execId}"] .elapsed-time`);
    if (element) {
        const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        element.textContent = `⏱️ ${elapsed}초 경과`;
    }
}

function startElapsedTimeTimer(execId, startTime) {
    if (elapsedTimeTimers.has(execId)) {
        clearInterval(elapsedTimeTimers.get(execId));
    }
    const timer = setInterval(() => {
        updateElapsedTime(execId, startTime);
    }, 100); // 0.1초마다 업데이트
    elapsedTimeTimers.set(execId, timer);
}

function stopElapsedTimeTimer(execId) {
    if (elapsedTimeTimers.has(execId)) {
        clearInterval(elapsedTimeTimers.get(execId));
        elapsedTimeTimers.delete(execId);
    }
}

// 실행 로그 업데이트 함수 (깜빡거림 방지: append 방식)
function updateExecutionLogs(execId, logs) {
    const logContentElement = document.getElementById(`log-content-${execId}`);
    if (!logContentElement || !logs || logs.length === 0) {
        if (logContentElement && logContentElement.children.length === 0) {
            logContentElement.innerHTML = '<div class="log-line log-info">로그가 없습니다.</div>';
        }
        return;
    }
    
    try {
        // 현재 스크롤 위치 확인
        const scrollHeightBefore = logContentElement.scrollHeight;
        const scrollTopBefore = logContentElement.scrollTop;
        const clientHeight = logContentElement.clientHeight;
        const wasScrolledToBottom = scrollHeightBefore - scrollTopBefore <= clientHeight + 10;
        
        // 기존 로그 수 확인
        const existingLogCount = logContentElement.children.length;
        const newLogCount = logs.length;
        
        // 새 로그만 추가 (깜빡거림 방지)
        if (newLogCount > existingLogCount) {
            const newLogs = logs.slice(existingLogCount);
            const fragment = document.createDocumentFragment();
            
            newLogs.forEach(log => {
                if (!log) return;
                let logClass = 'log-info';
                const logStr = String(log);
                if (logStr.includes('❌') || logStr.includes('실패') || logStr.includes('에러') || logStr.includes('ERROR') || logStr.includes('FAILED')) {
                    logClass = 'log-error';
                } else if (logStr.includes('✅') || logStr.includes('성공') || logStr.includes('완료') || logStr.includes('SUCCESS') || logStr.includes('COMPLETED')) {
                    logClass = 'log-success';
                } else if (logStr.includes('⚠️') || logStr.includes('경고') || logStr.includes('WARNING')) {
                    logClass = 'log-warning';
                } else if (logStr.includes('⏱️') || logStr.includes('PERF') || logStr.includes('성능')) {
                    logClass = 'log-perf';
                }
                
                const logDiv = document.createElement('div');
                logDiv.className = `log-line ${logClass}`;
                logDiv.textContent = logStr;
                fragment.appendChild(logDiv);
            });
            
            logContentElement.appendChild(fragment);
            
            // 스크롤 위치 조정
            requestAnimationFrame(() => {
                if (wasScrolledToBottom) {
                    logContentElement.scrollTop = logContentElement.scrollHeight;
                }
            });
        } else if (newLogCount < existingLogCount) {
            // 로그가 줄어든 경우 (이론적으로는 발생하지 않아야 함) 전체 재렌더링
            const html = logs.map(log => {
                if (!log) return '';
                let logClass = 'log-info';
                const logStr = String(log);
                if (logStr.includes('❌') || logStr.includes('실패') || logStr.includes('에러') || logStr.includes('ERROR') || logStr.includes('FAILED')) {
                    logClass = 'log-error';
                } else if (logStr.includes('✅') || logStr.includes('성공') || logStr.includes('완료') || logStr.includes('SUCCESS') || logStr.includes('COMPLETED')) {
                    logClass = 'log-success';
                } else if (logStr.includes('⚠️') || logStr.includes('경고') || logStr.includes('WARNING')) {
                    logClass = 'log-warning';
                } else if (logStr.includes('⏱️') || logStr.includes('PERF') || logStr.includes('성능')) {
                    logClass = 'log-perf';
                }
                const escapedLog = escapeHtml(logStr);
                return '<div class="log-line ' + logClass + '">' + escapedLog + '</div>';
            }).join('');
            logContentElement.innerHTML = html;
        }
    } catch (error) {
        console.error('로그 업데이트 오류:', error);
        logContentElement.innerHTML = '<div class="log-line log-error">로그 표시 오류: ' + String(error.message || error) + '</div>';
    }
}

// 섹션 리사이저 기능
function initSectionResizers() {
    const registerCard = document.getElementById('registerCard');
    const layersCard = document.getElementById('layersCard');
    const executionsCard = document.getElementById('executionsCard');
    const handle1 = document.getElementById('resizeHandle1');
    if (!registerCard || !layersCard || !executionsCard || !handle1) return;
    
    let currentHandle = null;
    let isResizing = false;
    let startY = 0;
    let startRegisterHeight = 0;
    let startLayersHeight = 0;
    
    function startResize(handle, e) {
        isResizing = true;
        currentHandle = handle;
        startY = e.clientY;
        startRegisterHeight = registerCard.offsetHeight;
        startLayersHeight = layersCard.offsetHeight;
        handle.classList.add('resizing');
        document.body.style.cursor = 'ns-resize';
        document.body.style.userSelect = 'none';
        e.preventDefault();
    }
    
    function handleMouseMove(e) {
        if (!isResizing || !currentHandle) return;
        
        const deltaY = e.clientY - startY;
        const container = document.querySelector('.container');
        const header = document.querySelector('.header');
        if (currentHandle === handle1) {
            const newRegisterHeight = startRegisterHeight + deltaY;
            const newLayersHeight = startLayersHeight - deltaY;
            const minHeight = 100;
            if (newRegisterHeight >= minHeight && newLayersHeight >= minHeight) {
                registerCard.style.flex = `0 0 ${newRegisterHeight}px`;
                layersCard.style.flex = `1 1 ${newLayersHeight}px`;
            }
        }
    }
    
    function stopResize() {
        if (isResizing && currentHandle) {
            isResizing = false;
            currentHandle.classList.remove('resizing');
            currentHandle = null;
            document.body.style.cursor = '';
            document.body.style.userSelect = '';
        }
    }
    
    handle1.addEventListener('mousedown', (e) => startResize(handle1, e));
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', stopResize);
    
    // 초기 높이 설정
    registerCard.style.flex = '0 0 auto';
    layersCard.style.flex = '1 1 0';
    executionsCard.style.flex = '1 1 0';
}

function renderExecution(exec, isCurrent) {
    console.log('renderExecution called:', exec.id, 'has executionTree:', !!exec.executionTree, exec.executionTree);
    const statusClass = (exec.status || '').toLowerCase();
    const statusText = getStatusLabel(exec.status);
    
    const duration = exec.endTime 
        ? `${((exec.endTime - exec.startTime) / 1000).toFixed(2)}초`
        : isCurrent 
            ? `<span class="elapsed-time">⏱️ ${((Date.now() - exec.startTime) / 1000).toFixed(1)}초 경과</span>`
            : '';
    
    const timeStr = new Date(exec.startTime).toLocaleTimeString('ko-KR');
    
    // 실행 중 또는 재시도 중이면 경과 타이머 시작
    if (isCurrent && (exec.status === 'RUNNING' || exec.status === 'RETRYING')) {
        setTimeout(() => startElapsedTimeTimer(exec.id, exec.startTime), 0);
    } else {
        stopElapsedTimeTimer(exec.id);
    }
    
    return `
        <div class="execution-item ${statusClass}" data-id="${exec.id}" data-start-time="${exec.startTime}">
            <div class="execution-header">
                <div style="flex: 1;">
                    <div class="execution-title">
                        ${isCurrent ? '🔄 ' : ''}${exec.query || '(빈 쿼리)'}
                    </div>
                    <div class="execution-meta">
                        <span>${timeStr}</span>
                        ${duration ? `<span>${duration}</span>` : ''}
                        <span class="status-badge status-${statusClass}">${statusText}</span>
                        ${exec.executionTree ? `<button class="tree-view-btn" data-exec-id="${exec.id}" style="margin-left: 8px; padding: 4px 12px; background: #667eea; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 11px; font-weight: 600;">🌳 트리 보기</button>` : `<!-- No tree: ${JSON.stringify(exec.executionTree)} -->`}
                    </div>
                    <div class="node-stats">${formatNodeStats(exec)}</div>
                </div>
            </div>
            <div class="execution-details" id="details-${exec.id}">
                ${exec.executionTree ? `<div class="execution-tree" id="tree-${exec.id}">${renderExecutionTreeSection(exec.executionTree)}</div>` : ''}
                ${exec.result && exec.result.trim() !== '' ? `
                    <div class="execution-result"><strong>✅ 최종 결과:</strong><div class="result-content">${escapeHtml(exec.result.trim())}</div></div>
                ` : exec.error ? '' : `
                    <div class="execution-result" style="color: #999; font-style: italic; padding: 6px 12px;">결과가 아직 없습니다...</div>
                `}
                ${exec.error ? `
                    <div class="execution-result" style="background: #f8d7da; color: #721c24;">
                        <strong>에러:</strong><br>
                        ${escapeHtml(exec.error)}
                    </div>
                ` : ''}
                <div class="execution-logs" id="logs-${exec.id}" style="display: none;"><strong>📋 실행 로그:</strong><div class="log-content" id="log-content-${exec.id}" style="padding: 0; margin: 4px 0 0 0;"></div></div>
            </div>
        </div>
    `;
}

// 실행 데이터 찾기 함수
function findExecutionData(execId) {
    if (!window.executionDataCache) {
        return null;
    }
    return window.executionDataCache.get(execId) || null;
}

/** 실행 트리 전체 섹션 HTML (API 응답 executionTree 기준). 패치 시 트리 갱신용 */
function renderExecutionTreeSection(executionTree) {
    if (!executionTree) return '';
    const roots = executionTree.rootNodes || (executionTree.rootNode ? [executionTree.rootNode] : []);
    if (roots.length === 0) return '';
    let html = '<strong>🌳 실행 트리:</strong>';
    roots.forEach((rootNode, index) => {
        const hasChildren = rootNode.children && rootNode.children.length > 0;
        const isParallel = rootNode.parallel;
        const internalType = hasChildren ? (isParallel ? ' (내부 병렬)' : ' (내부 순차)') : '';
        const rootLabel = roots.length > 1 ? `🔹 루트 노드 #${index + 1}${internalType}` : `🔹 루트 노드${internalType}`;
        html += `<div style="margin-top: ${index > 0 ? '15px' : '0'}; padding-left: 0;">
            <div style="color: #667eea; font-weight: 600; margin-bottom: 8px; font-size: 12px;">${rootLabel}</div>
            ${renderExecutionTree(rootNode, 0)}
        </div>`;
    });
    return html;
}

// 실행 트리 렌더링 함수
function renderExecutionTree(node, depth) {
    if (!node) return '';
    
    const indent = depth * 20;
    const isRoot = depth === 0;
    const nodeClass = isRoot ? 'tree-node tree-node-root' : 'tree-node';
    const parallelBadge = node.parallel ? '<span class="tree-node-parallel">[병렬]</span>' : '';
    
    // 노드 레이블 표시
    const nodeLabel = `${node.layerName}.${node.function}${parallelBadge}`;
    
    // 파라미터 전달 여부 명확히 표시
    const hasArgs = node.args && Object.keys(node.args).length > 0;
    const argsDisplay = hasArgs 
        ? `<span style="color: #28a745;">✓ 파라미터 전달됨</span>: ${JSON.stringify(node.args)}`
        : '<span style="color: #999;">파라미터 없음</span>';
    
    let html = `<div class="${nodeClass}" style="margin-left: ${indent}px;">
        <div class="tree-node-label">
            ${nodeLabel}
        </div>
        <div class="tree-node-info">
            ${argsDisplay}
        </div>`;
    
    if (node.children && node.children.length > 0) {
        node.children.forEach(child => {
            html += renderExecutionTree(child, depth + 1);
        });
    }
    
    html += '</div>';
    return html;
}

// 실행 로그 로드 함수
function loadExecutionLogs(execId, logContentElement) {
    if (!logContentElement) return;
    
    // 이미 로그가 있으면 업데이트하지 않음
    if (logContentElement.children.length > 0) {
        return;
    }
    
    // 캐시에서 로그 가져오기
    const execData = findExecutionData(execId);
    if (execData && execData.logs && execData.logs.length > 0) {
        updateExecutionLogs(execId, execData.logs);
    } else {
        // 로그가 정말 없을 때만 메시지 표시
        logContentElement.innerHTML = '<div class="log-line log-info">로그가 아직 없습니다.</div>';
    }
}

// 페이지 로드 시 레이어 목록 및 실행 이력 불러오기
// LLM 상태 확인 함수 (버튼 클릭 시 호출)
async function checkLLMStatus() {
    const statusMessage = document.getElementById('llmStatusMessage');
    statusMessage.textContent = '확인 중...';
    statusMessage.className = 'status-message show';
    
    try {
        const response = await fetch(`${API_BASE}/llm-status`);
        const status = await response.json();
        
        if (status.allReady) {
            statusMessage.textContent = `✅ 모든 LLM이 준비되었습니다!`;
            statusMessage.className = 'status-message show success';
            
            // LLM이 준비되었으면 안내 카드 숨기고 일반 UI 표시
            setTimeout(() => {
                document.getElementById('llmNoticeCard')?.classList.remove('show');
                const leftPanel = document.getElementById('leftPanel');
                const rightPanel = document.getElementById('rightPanel');
                if (leftPanel) leftPanel.classList.remove('hidden');
                if (rightPanel) rightPanel.classList.remove('hidden');
                loadLLMStatus(); // 헤더 상태 업데이트
            }, 1500);
        } else {
            const notReady = [];
            if (!status.simple.ready) notReady.push(`SIMPLE (${status.simple.provider})`);
            if (!status.medium.ready) notReady.push(`MEDIUM (${status.medium.provider})`);
            if (!status.complex.ready) notReady.push(`COMPLEX (${status.complex.provider})`);
            
            statusMessage.textContent = `⚠️ 일부 LLM이 준비되지 않았습니다: ${notReady.join(', ')}`;
            statusMessage.className = 'status-message show error';
        }
    } catch (error) {
        statusMessage.textContent = '❌ LLM 상태를 확인할 수 없습니다. 서버에 연결할 수 없습니다.';
        statusMessage.className = 'status-message show error';
    }
}

// LLM 상태 로드 (헤더용, 자동 갱신)
async function loadLLMStatus() {
    try {
        const response = await fetch(`${API_BASE}/llm-status`);
        const status = await response.json();
        
        const statusText = document.getElementById('llmStatusText');
        const llmNoticeCard = document.getElementById('llmNoticeCard');
        const leftPanel = document.getElementById('leftPanel');
        const rightPanel = document.getElementById('rightPanel');
        
        if (status.allReady) {
            const providers = [
                `${status.simple.provider} (${status.simple.modelId})`,
                `${status.medium.provider} (${status.medium.modelId})`,
                `${status.complex.provider} (${status.complex.modelId})`
            ];
            statusText.innerHTML = `🤖 LLM: <span style="color: #28a745;">준비 완료</span> - ${providers.join(', ')}`;
            
            // LLM이 준비되었으면 일반 UI 표시
            llmNoticeCard?.classList.remove('show');
            if (leftPanel) leftPanel.classList.remove('hidden');
            if (rightPanel) rightPanel.classList.remove('hidden');
        } else {
            const notReady = [];
            if (!status.simple.ready) notReady.push(`SIMPLE`);
            if (!status.medium.ready) notReady.push(`MEDIUM`);
            if (!status.complex.ready) notReady.push(`COMPLEX`);
            
            statusText.innerHTML = `🤖 LLM: <span style="color: #ffc107;">부분 준비</span> - 미준비: ${notReady.join(', ')}`;
            
            // LLM이 준비되지 않았으면 안내 카드 표시
            llmNoticeCard?.classList.add('show');
            if (leftPanel) leftPanel.classList.add('hidden');
            if (rightPanel) rightPanel.classList.add('hidden');
        }
    } catch (error) {
        document.getElementById('llmStatusText').innerHTML = `🤖 LLM: <span style="color: #999;">확인 불가</span>`;
        
        // 에러 시에도 안내 카드 표시
        const llmNoticeCard = document.getElementById('llmNoticeCard');
        const leftPanel = document.getElementById('leftPanel');
        const rightPanel = document.getElementById('rightPanel');
        llmNoticeCard?.classList.add('show');
        if (leftPanel) leftPanel.classList.add('hidden');
        if (rightPanel) rightPanel.classList.add('hidden');
    }
}

// ─────────────────────────────────────────────
// 파일 수정 승인 패널
// ─────────────────────────────────────────────
let currentApprovalId = null;

function showApprovalPanel(data) {
    currentApprovalId = data.id;
    document.getElementById('approvalPath').textContent = data.path;

    const diffEl = document.getElementById('approvalDiff');
    diffEl.innerHTML = (data.diff || '').split('\n').map(line => {
        if (line.startsWith('+')) {
            return `<span style="background:#d3f9d8; display:block; padding:0 2px;">${escapeHtml(line)}</span>`;
        } else if (line.startsWith('-')) {
            return `<span style="background:#ffe3e3; display:block; padding:0 2px;">${escapeHtml(line)}</span>`;
        } else if (line.startsWith('@')) {
            return `<span style="color:#868e96; display:block; padding:0 2px;">${escapeHtml(line)}</span>`;
        } else {
            return `<span style="display:block; padding:0 2px;">${escapeHtml(line)}</span>`;
        }
    }).join('');

    document.getElementById('approvalModal').style.display = 'flex';
}

async function handleApproval(approved) {
    if (!currentApprovalId) return;
    const action = approved ? 'approve' : 'reject';
    try {
        await fetch(`${API_BASE}/approval/${currentApprovalId}/${action}`, { method: 'POST' });
    } catch (e) {
        console.error('Approval request failed:', e);
    }
    document.getElementById('approvalModal').style.display = 'none';
    currentApprovalId = null;
}

// 초기 로드 시 LLM 상태 확인 후 UI 표시 결정
(async function init() {
    await loadLLMStatus();
    
    // LLM이 준비되었으면 일반 UI 로드
    const llmNoticeCard = document.getElementById('llmNoticeCard');
    if (!llmNoticeCard?.classList.contains('show')) {
        loadLayers();
        loadExecutions();
        connectWebSocket(); // WebSocket 연결 시작
        initSectionResizers(); // 섹션 리사이저 초기화
    }
})();

setInterval(loadLLMStatus, 10000); // 10초마다 갱신

// 실행 중인 작업의 경과 시간을 주기적으로 업데이트
setInterval(() => {
    document.querySelectorAll('.execution-item.running').forEach(item => {
        const execId = item.getAttribute('data-id');
        const startTimeAttr = item.getAttribute('data-start-time');
        if (startTimeAttr) {
            const startTime = parseInt(startTimeAttr);
            updateElapsedTime(execId, startTime);
        }
    });
}, 100); // 0.1초마다 업데이트
