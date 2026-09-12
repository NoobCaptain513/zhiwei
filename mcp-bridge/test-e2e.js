#!/usr/bin/env node

/**
 * MCP 端到端测试：工具发现 + 工具调用。
 * 依赖：后端已启动在 http://localhost:8080
 *
 * 用法：node test-e2e.js
 */

const http = require('http');

const SERVER_URL = process.env.MCP_SERVER_URL || 'http://localhost:8080';
const MCP_PATH = '/api/mcp';

let passed = 0;
let failed = 0;

// ========== HTTP 工具 ==========

function post(body) {
  return new Promise((resolve, reject) => {
    const url = new URL(MCP_PATH, SERVER_URL);
    const data = JSON.stringify(body);
    const req = http.request({
      hostname: url.hostname,
      port: url.port,
      path: url.pathname,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) },
      timeout: 30000,
    }, (res) => {
      let buf = '';
      res.on('data', (c) => { buf += c; });
      res.on('end', () => {
        try { resolve(JSON.parse(buf)); } catch { reject(new Error('Bad JSON: ' + buf)); }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Timeout')); });
    req.write(data);
    req.end();
  });
}

function assert(name, condition) {
  if (condition) {
    console.log(`  ✅ ${name}`);
    passed++;
  } else {
    console.log(`  ❌ ${name}`);
    failed++;
  }
}

// ========== 测试用例 ==========

async function testInitialize() {
  console.log('\n📋 initialize');
  const resp = await post({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} });
  assert('jsonrpc = 2.0', resp.jsonrpc === '2.0');
  assert('有 serverInfo', !!resp.result?.serverInfo);
  assert('serverInfo.name = zhiwei-mcp-server', resp.result?.serverInfo?.name === 'zhiwei-mcp-server');
  assert('protocolVersion 存在', !!resp.result?.protocolVersion);
}

async function testToolsList() {
  console.log('\n📋 tools/list');
  const resp = await post({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });
  const tools = resp.result?.tools || [];
  assert('返回工具数组', Array.isArray(tools));
  assert('工具数量 = 6', tools.length === 6);

  const names = tools.map(t => t.name).sort();
  assert('包含 queryServerStatus', names.includes('queryServerStatus'));
  assert('包含 searchLogs', names.includes('searchLogs'));
  assert('包含 queryDeployHistory', names.includes('queryDeployHistory'));
  assert('包含 createTicket', names.includes('createTicket'));
  assert('包含 queryMetrics', names.includes('queryMetrics'));
  assert('包含 rag_search', names.includes('rag_search'));

  // 检查结构
  const first = tools[0];
  assert('每个工具有 name', typeof first.name === 'string');
  assert('每个工具有 description', typeof first.description === 'string');
  assert('每个工具有 inputSchema', !!first.inputSchema);
}

async function testCallQueryServerStatus() {
  console.log('\n📋 tools/call → queryServerStatus');
  const resp = await post({
    jsonrpc: '2.0', id: 3, method: 'tools/call',
    params: { name: 'queryServerStatus', arguments: { hostname: '192.168.1.100' } }
  });
  assert('jsonrpc = 2.0', resp.jsonrpc === '2.0');
  assert('有 result', !!resp.result);
  assert('result.content 是数组', Array.isArray(resp.result?.content));
  assert('content 非空', resp.result?.content?.length > 0);
  assert('无 error', !resp.result?.isError);
  const text = resp.result?.content?.[0]?.text || '';
  assert('返回包含 hostname', text.includes('192.168.1.100'));
}

async function testCallSearchLogs() {
  console.log('\n📋 tools/call → searchLogs');
  const resp = await post({
    jsonrpc: '2.0', id: 4, method: 'tools/call',
    params: { name: 'searchLogs', arguments: { service: 'payment-service', keyword: 'ERROR' } }
  });
  assert('返回成功', !resp.result?.isError);
  const text = resp.result?.content?.[0]?.text || '';
  assert('返回包含 payment-service', text.includes('payment-service'));
}

async function testCallCreateTicket() {
  console.log('\n📋 tools/call → createTicket');
  const resp = await post({
    jsonrpc: '2.0', id: 5, method: 'tools/call',
    params: { name: 'createTicket', arguments: { title: '测试工单', description: 'E2E 测试自动创建', priority: 'P3' } }
  });
  assert('返回成功', !resp.result?.isError);
  const text = resp.result?.content?.[0]?.text || '';
  assert('返回包含 TK-', text.includes('TK-'));
  assert('返回包含 测试工单', text.includes('测试工单'));
}

async function testCallRagSearch() {
  console.log('\n📋 tools/call → rag_search');
  const resp = await post({
    jsonrpc: '2.0', id: 6, method: 'tools/call',
    params: { name: 'rag_search', arguments: { query: '故障降级', topK: 3 } }
  });
  assert('返回成功', !resp.result?.isError);
  const text = resp.result?.content?.[0]?.text || '';
  assert('返回包含 结果', text.includes('结果') || text.includes('未找到'));
}

async function testCallUnknownTool() {
  console.log('\n📋 tools/call → 未知工具');
  const resp = await post({
    jsonrpc: '2.0', id: 7, method: 'tools/call',
    params: { name: 'nonexistent_tool', arguments: {} }
  });
  // 应该返回 error 或 isError=true
  const isError = resp.result?.isError || !!resp.error;
  assert('返回错误', isError);
}

async function testUnknownMethod() {
  console.log('\n📋 未知方法');
  const resp = await post({ jsonrpc: '2.0', id: 8, method: 'foo/bar', params: {} });
  assert('返回 error', !!resp.error);
  assert('error.code = -32601', resp.error?.code === -32601);
}

// ========== 主流程 ==========

async function main() {
  console.log(`\n🚀 智维 MCP E2E 测试 → ${SERVER_URL}${MCP_PATH}\n`);

  const tests = [
    testInitialize,
    testToolsList,
    testCallQueryServerStatus,
    testCallSearchLogs,
    testCallCreateTicket,
    testCallRagSearch,
    testCallUnknownTool,
    testUnknownMethod,
  ];

  for (const fn of tests) {
    try {
      await fn();
    } catch (e) {
      console.log(`  ❌ ${fn.name} 抛出异常: ${e.message}`);
      failed++;
    }
  }

  console.log(`\n📊 结果: ${passed} passed, ${failed} failed, ${passed + failed} total\n`);
  process.exit(failed > 0 ? 1 : 0);
}

main();