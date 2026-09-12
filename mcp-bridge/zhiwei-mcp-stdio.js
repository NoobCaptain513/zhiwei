#!/usr/bin/env node

/**
 * 智维 MCP stdio → HTTP 桥接脚本。
 *
 * 功能：
 *   读取 stdin JSON-RPC → POST 到 http://localhost:8080/api/mcp → 写回 stdout
 *   同时支持 notifications（无 id 的请求，不回写）
 *
 * 用法：
 *   node zhiwei-mcp-stdio.js                     # 默认 http://localhost:8080
 *   MCP_SERVER_URL=http://host:port node zhiwei-mcp-stdio.js
 *
 * AI 客户端配置示例（Claude Desktop / Cursor）：
 *   {
 *     "mcpServers": {
 *       "zhiwei": {
 *         "command": "node",
 *         "args": ["D:\\javaproject\\zhiwei\\mcp-bridge\\zhiwei-mcp-stdio.js"],
 *         "env": { "MCP_SERVER_URL": "http://localhost:8080" }
 *       }
 *     }
 *   }
 */

const http = require('http');
const readline = require('readline');

const SERVER_URL = process.env.MCP_SERVER_URL || 'http://localhost:8080';
const MCP_PATH = '/api/mcp';

// ========== HTTP 请求 ==========

function postJsonRpc(body) {
  return new Promise((resolve, reject) => {
    const url = new URL(MCP_PATH, SERVER_URL);
    const data = JSON.stringify(body);

    const req = http.request({
      hostname: url.hostname,
      port: url.port,
      path: url.pathname,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data),
      },
      timeout: 30000,
    }, (res) => {
      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => {
        try {
          resolve(JSON.parse(body));
        } catch (e) {
          reject(new Error(`Invalid JSON response: ${body}`));
        }
      });
    });

    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('Request timeout')); });
    req.write(data);
    req.end();
  });
}

// ========== stdio 读写 ==========

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: false,
});

function writeResponse(response) {
  const json = JSON.stringify(response);
  process.stdout.write(json + '\n');
}

// ========== 主循环 ==========

rl.on('line', async (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;

  let request;
  try {
    request = JSON.parse(trimmed);
  } catch (e) {
    writeResponse({
      jsonrpc: '2.0',
      id: null,
      error: { code: -32700, message: 'Parse error: ' + e.message },
    });
    return;
  }

  // 通知（无 id）：发出去但不等响应
  if (request.id === undefined || request.id === null) {
    postJsonRpc(request).catch(() => {});
    return;
  }

  // 正常请求
  try {
    const response = await postJsonRpc(request);
    if (response) {
      writeResponse(response);
    }
  } catch (e) {
    writeResponse({
      jsonrpc: '2.0',
      id: request.id,
      error: { code: -32000, message: 'Bridge error: ' + e.message },
    });
  }
});

rl.on('close', () => {
  process.exit(0);
});

process.stderr.write(`[zhiwei-mcp-stdio] bridge started → ${SERVER_URL}${MCP_PATH}\n`);