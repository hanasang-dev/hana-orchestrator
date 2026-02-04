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
            updateExecutionsUI(data);
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
        meta.innerHTML = `<span>${timeStr}</span>${duration ? `<span>${duration}</span>` : ''}<span class="status-badge status-${statusClass}">${statusText}</span>`;
    }
    const nodeStats = item.querySelector('.node-stats');
    if (nodeStats) nodeStats.innerHTML = formatNodeStats(exec);
    
    if (isCurrent && exec.status === 'RUNNING') {
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
    const statusClass = (exec.status || '').toLowerCase();
    const statusText = getStatusLabel(exec.status);
    
    const duration = exec.endTime 
        ? `${((exec.endTime - exec.startTime) / 1000).toFixed(2)}초`
        : isCurrent 
            ? `<span class="elapsed-time">⏱️ ${((Date.now() - exec.startTime) / 1000).toFixed(1)}초 경과</span>`
            : '';
    
    const timeStr = new Date(exec.startTime).toLocaleTimeString('ko-KR');
    
    // 실행 중이면 타이머 시작
    if (isCurrent && exec.status === 'RUNNING') {
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
                        ${exec.error}
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
