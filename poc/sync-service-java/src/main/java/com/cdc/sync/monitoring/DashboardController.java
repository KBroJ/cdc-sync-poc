package com.cdc.sync.monitoring;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 대시보드 HTML 페이지 제공
 */
@Controller
public class DashboardController {

    @GetMapping("/dashboard")
    @ResponseBody
    public String dashboard() {
        return """
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CDC 모니터링 대시보드</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background-color: #1a1a2e;
            color: #eee;
            padding: 20px;
        }
        .header {
            text-align: center;
            margin-bottom: 20px;
        }
        .header h1 { color: #00d4ff; font-size: 1.8em; margin-bottom: 5px; }
        .header .subtitle { color: #888; font-size: 0.9em; }

        .controls {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 20px;
            flex-wrap: wrap;
            gap: 10px;
        }
        .auto-refresh {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .toggle-switch {
            position: relative;
            width: 50px;
            height: 25px;
            background: #333;
            border-radius: 25px;
            cursor: pointer;
        }
        .toggle-switch.active { background: #00d4ff; }
        .toggle-switch::after {
            content: '';
            position: absolute;
            width: 21px;
            height: 21px;
            background: #fff;
            border-radius: 50%;
            top: 2px;
            left: 2px;
            transition: 0.3s;
        }
        .toggle-switch.active::after { left: 27px; }

        .test-buttons {
            display: flex;
            gap: 10px;
            flex-wrap: wrap;
        }
        .btn {
            padding: 10px 20px;
            border: none;
            border-radius: 8px;
            cursor: pointer;
            font-weight: bold;
            transition: all 0.3s;
            font-size: 0.9em;
        }
        .btn:hover { transform: scale(1.05); }
        .btn-primary { background: #00d4ff; color: #1a1a2e; }
        .btn-success { background: #00ff88; color: #1a1a2e; }
        .btn-warning { background: #ffa502; color: #1a1a2e; }
        .btn-danger { background: #ff4757; color: #fff; }
        .btn-secondary { background: #444; color: #fff; }

        .stats-container {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
            gap: 15px;
            margin-bottom: 20px;
        }
        .stat-card {
            background: linear-gradient(135deg, #16213e 0%, #1a1a2e 100%);
            border-radius: 12px;
            padding: 20px;
            text-align: center;
            border: 1px solid #333;
        }
        .stat-card.success { border-left: 4px solid #00ff88; }
        .stat-card.failed { border-left: 4px solid #ff4757; }
        .stat-card.total { border-left: 4px solid #00d4ff; }
        .stat-card.rate { border-left: 4px solid #ffa502; }
        .stat-value { font-size: 2.2em; font-weight: bold; margin-bottom: 5px; }
        .stat-card.success .stat-value { color: #00ff88; }
        .stat-card.failed .stat-value { color: #ff4757; }
        .stat-card.total .stat-value { color: #00d4ff; }
        .stat-card.rate .stat-value { color: #ffa502; }
        .stat-label { color: #888; font-size: 0.85em; text-transform: uppercase; }

        .grid-2 {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
            margin-bottom: 20px;
        }
        @media (max-width: 1200px) {
            .grid-2 { grid-template-columns: 1fr; }
        }

        .section {
            background: #16213e;
            border-radius: 12px;
            padding: 15px;
            margin-bottom: 20px;
            border: 1px solid #333;
        }
        .section-title {
            color: #00d4ff;
            font-size: 1.1em;
            margin-bottom: 12px;
            display: flex;
            align-items: center;
            gap: 8px;
        }

        table { width: 100%; border-collapse: collapse; font-size: 0.9em; }
        th, td { padding: 10px 8px; text-align: left; border-bottom: 1px solid #333; }
        th { background: #1a1a2e; color: #00d4ff; font-weight: 600; }
        tr:hover { background: #1a1a2e; }

        .status-badge {
            padding: 3px 10px;
            border-radius: 15px;
            font-size: 0.75em;
            font-weight: bold;
        }
        .status-success { background: rgba(0, 255, 136, 0.2); color: #00ff88; }
        .status-failed { background: rgba(255, 71, 87, 0.2); color: #ff4757; }

        .op-badge {
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 0.7em;
            font-weight: bold;
        }
        .op-insert { background: #00d4ff33; color: #00d4ff; }
        .op-update { background: #ffa50233; color: #ffa502; }
        .op-delete { background: #ff475733; color: #ff4757; }

        .data-preview {
            font-family: 'Consolas', 'Monaco', monospace;
            font-size: 0.8em;
            color: #aaa;
            background: #0d0d1a;
            padding: 5px 8px;
            border-radius: 4px;
            max-width: 400px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .hash { font-family: 'Consolas', monospace; color: #666; font-size: 0.8em; }
        .error-msg {
            color: #ff4757;
            font-size: 0.8em;
            max-width: 250px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .timestamp { color: #666; font-size: 0.85em; }
        .no-data { text-align: center; color: #666; padding: 20px; }

        .test-result {
            background: #0d0d1a;
            border-radius: 8px;
            padding: 15px;
            margin-top: 10px;
            display: none;
        }
        .test-result.show { display: block; }
        .test-result.success { border-left: 3px solid #00ff88; }
        .test-result.error { border-left: 3px solid #ff4757; }
        .test-result pre {
            font-family: 'Consolas', monospace;
            font-size: 0.85em;
            white-space: pre-wrap;
            word-break: break-all;
        }

        .data-table-container {
            max-height: 200px;
            overflow-y: auto;
        }

        .input-group {
            display: flex;
            gap: 10px;
            align-items: center;
            margin-bottom: 10px;
        }
        .input-group label { color: #888; min-width: 80px; }
        .input-group input {
            background: #0d0d1a;
            border: 1px solid #333;
            color: #fff;
            padding: 8px 12px;
            border-radius: 6px;
            flex: 1;
        }
        .input-group input:focus { outline: none; border-color: #00d4ff; }
    </style>
</head>
<body>
    <div class="header">
        <h1>CDC 모니터링 대시보드</h1>
        <p class="subtitle">실시간 CDC 이벤트 처리 현황 및 테스트</p>
    </div>

    <div class="controls">
        <div class="auto-refresh">
            <span>자동 새로고침 (3초)</span>
            <div class="toggle-switch active" id="autoRefreshToggle" onclick="toggleAutoRefresh()"></div>
            <button class="btn btn-secondary" onclick="loadData()">새로고침</button>
            <button class="btn btn-danger" onclick="resetStats()">통계 초기화</button>
        </div>
        <div class="test-buttons">
            <button class="btn btn-success" onclick="runAsisTest()">ASIS UPDATE 테스트</button>
            <button class="btn btn-warning" onclick="runTobeTest()">TOBE UPDATE 테스트</button>
        </div>
    </div>

    <div class="stats-container">
        <div class="stat-card total">
            <div class="stat-value" id="totalReceived">-</div>
            <div class="stat-label">총 수신</div>
        </div>
        <div class="stat-card success">
            <div class="stat-value" id="totalSuccess">-</div>
            <div class="stat-label">성공</div>
        </div>
        <div class="stat-card failed">
            <div class="stat-value" id="totalFailed">-</div>
            <div class="stat-label">실패</div>
        </div>
        <div class="stat-card rate">
            <div class="stat-value" id="successRate">-</div>
            <div class="stat-label">성공률</div>
        </div>
    </div>

    <!-- 테스트 패널 -->
    <div class="section">
        <div class="section-title">🧪 테스트 패널</div>
        <div class="grid-2">
            <div>
                <h4 style="color:#00ff88; margin-bottom:10px;">ASIS DB 테스트</h4>
                <div class="input-group">
                    <label>Book ID:</label>
                    <input type="number" id="asisBookId" value="1" min="1" max="10">
                </div>
                <div class="input-group">
                    <label>제목:</label>
                    <input type="text" id="asisTitle" placeholder="변경할 제목 입력">
                </div>
                <button class="btn btn-success" onclick="runAsisTestCustom()">UPDATE 실행</button>
                <div class="test-result" id="asisResult"></div>
            </div>
            <div>
                <h4 style="color:#ffa502; margin-bottom:10px;">TOBE DB 테스트</h4>
                <div class="input-group">
                    <label>Book ID:</label>
                    <input type="number" id="tobeBookId" value="1" min="1" max="10">
                </div>
                <div class="input-group">
                    <label>제목:</label>
                    <input type="text" id="tobeTitle" placeholder="변경할 제목 입력">
                </div>
                <button class="btn btn-warning" onclick="runTobeTestCustom()">UPDATE 실행</button>
                <div class="test-result" id="tobeResult"></div>
            </div>
        </div>
    </div>

    <!-- 최근 이벤트 -->
    <div class="section">
        <div class="section-title">📋 최근 이벤트 (전송된 데이터 포함)</div>
        <div class="data-table-container">
            <table>
                <thead>
                    <tr>
                        <th>시간</th>
                        <th>상태</th>
                        <th>토픽</th>
                        <th>대상 테이블</th>
                        <th>작업</th>
                        <th>전송된 데이터</th>
                    </tr>
                </thead>
                <tbody id="eventsTable">
                    <tr><td colspan="6" class="no-data">데이터 로딩 중...</td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <!-- 최근 에러 -->
    <div class="section">
        <div class="section-title">⚠️ 최근 에러</div>
        <table>
            <thead>
                <tr>
                    <th>시간</th>
                    <th>토픽</th>
                    <th>대상 테이블</th>
                    <th>작업</th>
                    <th>에러 메시지</th>
                </tr>
            </thead>
            <tbody id="errorsTable">
                <tr><td colspan="5" class="no-data">에러 없음</td></tr>
            </tbody>
        </table>
    </div>

    <!-- DB 현황 -->
    <div class="grid-2">
        <div class="section">
            <div class="section-title">📊 ASIS DB 현재 데이터</div>
            <div class="data-table-container">
                <table>
                    <thead>
                        <tr><th>BOOK_ID</th><th>BOOK_TITLE</th><th>AUTHOR</th><th>STATUS</th></tr>
                    </thead>
                    <tbody id="asisDataTable">
                        <tr><td colspan="4" class="no-data">로딩 중...</td></tr>
                    </tbody>
                </table>
            </div>
            <button class="btn btn-secondary" style="margin-top:10px;" onclick="loadAsisData()">새로고침</button>
        </div>
        <div class="section">
            <div class="section-title">📊 TOBE CDC 테이블 현황</div>
            <div class="data-table-container">
                <table>
                    <thead>
                        <tr><th>SEQ</th><th>OP</th><th>BOOK_ID</th><th>TITLE</th><th>P</th></tr>
                    </thead>
                    <tbody id="tobeCdcTable">
                        <tr><td colspan="5" class="no-data">로딩 중...</td></tr>
                    </tbody>
                </table>
            </div>
            <button class="btn btn-secondary" style="margin-top:10px;" onclick="loadTobeCdc()">새로고침</button>
        </div>
    </div>

    <script>
        let autoRefresh = true;
        let refreshInterval;

        function toggleAutoRefresh() {
            autoRefresh = !autoRefresh;
            document.getElementById('autoRefreshToggle').classList.toggle('active', autoRefresh);
            if (autoRefresh) startAutoRefresh();
            else stopAutoRefresh();
        }

        function startAutoRefresh() {
            refreshInterval = setInterval(loadData, 3000);
        }

        function stopAutoRefresh() {
            clearInterval(refreshInterval);
        }

        function getOpBadgeClass(op) {
            switch(op?.toUpperCase()) {
                case 'INSERT': return 'op-insert';
                case 'UPDATE': return 'op-update';
                case 'DELETE': return 'op-delete';
                default: return '';
            }
        }

        function formatTopic(topic) {
            if (!topic) return '-';
            const parts = topic.split('.');
            return parts.length > 2 ? parts[2] : topic;
        }

        async function loadData() {
            try {
                const response = await fetch('/api/monitoring/dashboard');
                const data = await response.json();

                // 통계 업데이트
                document.getElementById('totalReceived').textContent = data.stats.totalReceived || 0;
                document.getElementById('totalSuccess').textContent = data.stats.totalSuccess || 0;
                document.getElementById('totalFailed').textContent = data.stats.totalFailed || 0;
                document.getElementById('successRate').textContent =
                    (data.stats.successRate || 0).toFixed(1) + '%';

                // 이벤트 테이블 업데이트
                const eventsTable = document.getElementById('eventsTable');
                if (data.recentEvents && data.recentEvents.length > 0) {
                    eventsTable.innerHTML = data.recentEvents.map(event => `
                        <tr>
                            <td class="timestamp">${event.timestamp || '-'}</td>
                            <td>
                                <span class="status-badge ${event.status === 'SUCCESS' ? 'status-success' : 'status-failed'}">
                                    ${event.status || '-'}
                                </span>
                            </td>
                            <td>${formatTopic(event.topic)}</td>
                            <td>${event.targetTable || '-'}</td>
                            <td>
                                <span class="op-badge ${getOpBadgeClass(event.operation)}">
                                    ${event.operation || '-'}
                                </span>
                            </td>
                            <td>
                                ${event.dataPreview
                                    ? `<div class="data-preview" title="${escapeHtml(event.dataPreview)}">${escapeHtml(event.dataPreview)}</div>`
                                    : (event.errorMessage ? `<span class="error-msg" title="${escapeHtml(event.errorMessage)}">${escapeHtml(event.errorMessage)}</span>` : '-')
                                }
                            </td>
                        </tr>
                    `).join('');
                } else {
                    eventsTable.innerHTML = '<tr><td colspan="6" class="no-data">아직 이벤트가 없습니다</td></tr>';
                }

                // 에러 테이블 업데이트
                const errorsTable = document.getElementById('errorsTable');
                if (data.recentErrors && data.recentErrors.length > 0) {
                    errorsTable.innerHTML = data.recentErrors.map(error => `
                        <tr>
                            <td class="timestamp">${error.timestamp || '-'}</td>
                            <td>${formatTopic(error.topic)}</td>
                            <td>${error.targetTable || '-'}</td>
                            <td>
                                <span class="op-badge ${getOpBadgeClass(error.operation)}">
                                    ${error.operation || '-'}
                                </span>
                            </td>
                            <td class="error-msg" title="${escapeHtml(error.errorMessage || '')}">${escapeHtml(error.errorMessage || '-')}</td>
                        </tr>
                    `).join('');
                } else {
                    errorsTable.innerHTML = '<tr><td colspan="5" class="no-data">에러 없음 ✓</td></tr>';
                }

            } catch (error) {
                console.error('데이터 로딩 실패:', error);
            }
        }

        function escapeHtml(text) {
            if (!text) return '';
            return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        }

        async function resetStats() {
            if (confirm('통계를 초기화하시겠습니까?')) {
                await fetch('/api/monitoring/reset', { method: 'POST' });
                loadData();
            }
        }

        async function runAsisTest() {
            const title = '테스트-' + new Date().toLocaleTimeString();
            await runTest('/api/test/asis/update', { bookId: '1', title: title }, 'asisResult');
        }

        async function runTobeTest() {
            const title = '테스트-' + new Date().toLocaleTimeString();
            await runTest('/api/test/tobe/update', { bookId: '1', title: title }, 'tobeResult');
        }

        async function runAsisTestCustom() {
            const bookId = document.getElementById('asisBookId').value;
            const title = document.getElementById('asisTitle').value || '테스트-' + Date.now();
            await runTest('/api/test/asis/update', { bookId, title }, 'asisResult');
        }

        async function runTobeTestCustom() {
            const bookId = document.getElementById('tobeBookId').value;
            const title = document.getElementById('tobeTitle').value || '테스트-' + Date.now();
            await runTest('/api/test/tobe/update', { bookId, title }, 'tobeResult');
        }

        async function runTest(url, data, resultId) {
            const resultDiv = document.getElementById(resultId);
            resultDiv.className = 'test-result show';
            resultDiv.innerHTML = '<pre>실행 중...</pre>';

            try {
                const response = await fetch(url, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                const result = await response.json();

                resultDiv.className = 'test-result show ' + (result.success ? 'success' : 'error');
                resultDiv.innerHTML = '<pre>' + JSON.stringify(result, null, 2) + '</pre>';

                // 3초 후 DB 현황 새로고침
                setTimeout(() => {
                    loadAsisData();
                    loadTobeCdc();
                    loadData();
                }, 3000);

            } catch (error) {
                resultDiv.className = 'test-result show error';
                resultDiv.innerHTML = '<pre>에러: ' + error.message + '</pre>';
            }
        }

        async function loadAsisData() {
            try {
                const response = await fetch('/api/test/asis/data');
                const result = await response.json();
                const table = document.getElementById('asisDataTable');

                if (result.success && result.data.length > 0) {
                    table.innerHTML = result.data.map(row => `
                        <tr>
                            <td>${row.BOOK_ID}</td>
                            <td>${row.BOOK_TITLE || '-'}</td>
                            <td>${row.AUTHOR || '-'}</td>
                            <td>${row.STATUS || '-'}</td>
                        </tr>
                    `).join('');
                } else {
                    table.innerHTML = '<tr><td colspan="4" class="no-data">데이터 없음</td></tr>';
                }
            } catch (error) {
                console.error('ASIS 데이터 로딩 실패:', error);
            }
        }

        async function loadTobeCdc() {
            try {
                const response = await fetch('/api/test/tobe/cdc');
                const result = await response.json();
                const table = document.getElementById('tobeCdcTable');

                if (result.success && result.data.length > 0) {
                    table.innerHTML = result.data.map(row => `
                        <tr>
                            <td>${row.CDC_SEQ}</td>
                            <td><span class="op-badge ${getOpBadgeClass(row.OPERATION)}">${row.OPERATION}</span></td>
                            <td>${row.BOOK_ID || '-'}</td>
                            <td>${row.BOOK_TITLE || '-'}</td>
                            <td>${row.PROCESSED_YN || '-'}</td>
                        </tr>
                    `).join('');
                } else {
                    table.innerHTML = '<tr><td colspan="5" class="no-data">데이터 없음</td></tr>';
                }
            } catch (error) {
                console.error('TOBE CDC 데이터 로딩 실패:', error);
            }
        }

        // 초기 로딩
        loadData();
        loadAsisData();
        loadTobeCdc();
        startAutoRefresh();
    </script>
</body>
</html>
""";
    }
}
