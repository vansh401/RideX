// WebSockets across replicas: shows that a live update reaches a browser whichever core pod holds its connection.
//
//   node scripts/loadtest/ws-cross-pod.mjs --token <access token> [--sockets 12] [--host ridex.localhost] [--port 8088] [--kill-phase]
//
// What it does
//   1. Opens N WebSocket connections as the same rider (like N browser tabs) through the ingress, speaks STOMP over them, and asks each
//      connection which core pod it landed on (the X-Instance header of the HTTP upgrade response).
//   2. Changes the rider's ride through the REST API (request a ride, cancel it). That request is handled by ONE core pod (see its
//      X-Instance header). The core publishes the live update to Redis and every core pod delivers it to the connections it holds.
//   3. Reports which connections received the update. All of them should, including those on the pods that did NOT handle the REST call.
//   4. With --kill-phase it then waits for you to delete a core pod, shows its connections drop and reconnect, and repeats the check.
//
// No dependencies: it speaks the WebSocket protocol itself over a TCP socket, so it can send a Host header for the ingress rule.

import net from 'node:net';
import http from 'node:http';
import crypto from 'node:crypto';

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] !== undefined && !args[i + 1].startsWith('--') ? args[i + 1] : fallback;
};
const TOKEN = opt('token');
const SOCKETS = Number(opt('sockets', '12'));
const HOST = opt('host', 'ridex.localhost');
const PORT = Number(opt('port', '8088'));
const KILL_PHASE = args.includes('--kill-phase');
if (!TOKEN) {
  console.error('usage: node ws-cross-pod.mjs --token <access token> [--sockets 12] [--kill-phase]');
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(...a);

// ---------------------------------------------------------------------------------------------------------------- minimal WebSocket

function frame(opcode, payload) {
  const data = Buffer.from(payload);
  const mask = crypto.randomBytes(4);   // frames from a client MUST be masked
  let header;
  if (data.length < 126) header = Buffer.from([0x80 | opcode, 0x80 | data.length]);
  else header = Buffer.from([0x80 | opcode, 0x80 | 126, data.length >> 8, data.length & 255]);
  const masked = Buffer.from(data.map((b, i) => b ^ mask[i % 4]));
  return Buffer.concat([header, mask, masked]);
}

class Socket {
  constructor(id) {
    this.id = id;
    this.instance = '?';          // core pod holding this connection (from the STOMP CONNECTED frame)
    this.gateway = '?';           // gateway pod that proxied the upgrade
    this.messages = [];           // MESSAGE frames received
    this.open = false;
    this.reconnects = 0;
  }

  connect() {
    return new Promise((resolve, reject) => {
      const key = crypto.randomBytes(16).toString('base64');
      const socket = net.connect(PORT, '127.0.0.1');
      this.socket = socket;
      let buffer = Buffer.alloc(0);
      let upgraded = false;
      let stompBuffer = '';
      socket.on('connect', () => {
        socket.write(
          `GET /ws HTTP/1.1\r\nHost: ${HOST}:${PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\n` +
            `Sec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: v12.stomp\r\n\r\n`,
        );
      });
      socket.on('data', (chunk) => {
        buffer = Buffer.concat([buffer, chunk]);
        if (!upgraded) {
          const end = buffer.indexOf('\r\n\r\n');
          if (end < 0) return;
          const head = buffer.subarray(0, end).toString();
          buffer = buffer.subarray(end + 4);
          if (!head.startsWith('HTTP/1.1 101')) return reject(new Error('upgrade refused: ' + head.split('\r\n')[0]));
          this.gateway = (/x-gateway-instance:\s*(\S+)/i.exec(head) || [])[1] || '?';
          // the core pod that accepted the upgrade says its name in X-Instance (a browser shows it in the Network tab; script cannot read it)
          this.instance = (/^x-instance:\s*(\S+)/im.exec(head) || [])[1] || '(pod: see counts)';
          upgraded = true;
          socket.write(frame(1, `CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer ${TOKEN}\n\n\0`));
        }
        // parse WebSocket frames (server frames are not masked)
        while (buffer.length >= 2) {
          const opcode = buffer[0] & 0x0f;
          let length = buffer[1] & 0x7f;
          let offset = 2;
          if (length === 126) { if (buffer.length < 4) return; length = buffer.readUInt16BE(2); offset = 4; }
          else if (length === 127) { if (buffer.length < 10) return; length = Number(buffer.readBigUInt64BE(2)); offset = 10; }
          if (buffer.length < offset + length) return;
          const payload = buffer.subarray(offset, offset + length);
          buffer = buffer.subarray(offset + length);
          if (opcode === 8) { this.open = false; socket.end(); return; }
          if (opcode === 9) { socket.write(frame(10, payload)); continue; }
          if (opcode === 1) {
            stompBuffer += payload.toString();
            let nul;
            while ((nul = stompBuffer.indexOf('\0')) >= 0) {
              const text = stompBuffer.slice(0, nul).replace(/^\n+/, '');
              stompBuffer = stompBuffer.slice(nul + 1);
              this.onStomp(text, resolve);
            }
          }
        }
      });
      socket.on('close', () => { this.open = false; this.onClose?.(); });
      socket.on('error', (e) => { this.open = false; reject(e); });
    });
  }

  onStomp(text, resolve) {
    const command = text.split('\n', 1)[0];
    if (command === 'CONNECTED') {
      this.open = true;
      for (const [i, destination] of ['/user/queue/ride-updates', '/user/queue/notifications'].entries()) {
        this.socket.write(frame(1, `SUBSCRIBE\nid:sub-${i}\ndestination:${destination}\n\n\0`));
      }
      resolve(this);
    } else if (command === 'MESSAGE') {
      const destination = (/destination:(.*)/.exec(text) || [])[1];
      const body = text.slice(text.indexOf('\n\n') + 2);
      this.messages.push({ destination, body });
    } else if (command === 'ERROR') {
      log(`  socket ${this.id}: STOMP ERROR ${text.split('\n').slice(0, 3).join(' | ')}`);
    }
  }

  close() { this.deliberatelyClosed = true; try { this.socket.destroy(); } catch { /* already closed */ } }
}

// ---------------------------------------------------------------------------------------------------------------- REST

function rest(method, path, body) {
  return new Promise((resolve, reject) => {
    const payload = body ? JSON.stringify(body) : null;
    const req = http.request(
      { host: '127.0.0.1', port: PORT, path, method, headers: { Host: `${HOST}:${PORT}`, Authorization: `Bearer ${TOKEN}`,
          ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) } : {}) } },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => resolve({ status: res.statusCode, instance: res.headers['x-instance'], gateway: res.headers['x-gateway-instance'], body: data }));
      },
    );
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

const ride = { category: 'CAB', pickup: { address: 'WS demo pickup', location: { latitude: 12.9716, longitude: 77.5946 } },
  dropoff: { address: 'WS demo dropoff', location: { latitude: 13.0827, longitude: 77.5877 } } };

/** Change the rider's ride (request, then cancel): produces live updates. Returns the pods that handled the two REST calls. */
async function triggerUpdate() {
  const current = await rest('GET', '/api/rides/current');
  if (current.status === 200 && current.body.trim()) {
    const id = JSON.parse(current.body).id;
    const c = await rest('POST', `/api/rides/${id}/cancel`);
    return { pods: [c.instance], note: 'cancelled the ride already in progress' };
  }
  const created = await rest('POST', '/api/rides', ride);
  if (created.status !== 201) throw new Error(`could not request a ride: HTTP ${created.status} ${created.body.slice(0, 120)}`);
  const id = JSON.parse(created.body).id;
  await sleep(300);
  const cancelled = await rest('POST', `/api/rides/${id}/cancel`);
  return { pods: [created.instance, cancelled.instance], note: 'requested and cancelled a ride' };
}

// ---------------------------------------------------------------------------------------------------------------- the experiment

function table(sockets) {
  const byPod = {};
  for (const s of sockets) byPod[s.instance] = (byPod[s.instance] || 0) + 1;
  for (const s of sockets) log(`  socket ${String(s.id).padStart(2)}  connection held by ${s.instance.padEnd(28)} (upgrade proxied by ${s.gateway})  ${s.open ? '' : '[closed]'}`);
  log('  ->', Object.entries(byPod).map(([p, n]) => `${p}: ${n} connection(s)`).join('   '));
  return byPod;
}

async function checkDelivery(sockets, label) {
  for (const s of sockets) s.messages = [];
  const { pods, note } = await triggerUpdate();
  log(`  REST: ${note}; handled by core pod(s): ${[...new Set(pods)].join(', ')}`);
  await sleep(3500);
  let delivered = 0;
  const open = sockets.filter((s) => s.open);
  for (const s of open) {
    const updates = s.messages.filter((m) => m.destination === '/user/queue/ride-updates');
    if (updates.length) delivered++;
    log(`  socket ${String(s.id).padStart(2)} on ${s.instance.padEnd(28)} received ${updates.length} ride update(s)`);
  }
  const restPods = new Set(pods);
  const restPodNames = [...restPods].join(' and ');
  log(`  ${label}: ${delivered} of ${open.length} open connections received the live update (REST was handled by ${restPodNames})`);
  return delivered === open.length && open.length > 0;
}

const sockets = [];
log(`\nOpening ${SOCKETS} WebSocket connections through the ingress (127.0.0.1:${PORT}, Host ${HOST}) ...`);
for (let i = 1; i <= SOCKETS; i++) {
  const s = new Socket(i);
  try { await s.connect(); sockets.push(s); } catch (e) { log(`  socket ${i} failed: ${e.message}`); }
  await sleep(150);   // a few new TCP connections spread over the pods
}
log('\nWhere the connections landed (through which gateway pod):');
const placement = table(sockets);
if (sockets.length === 0) {
  log('\nRESULT FAIL (no connection could be opened)');
  process.exit(1);
}
log('  (the gateway does not pass the core pod\'s X-Instance header on the upgrade; k8s-websocket-demo.ps1 counts the connections per pod)');
log('\nSOCKETS_OPEN');

log('\nChanging the rider\'s ride through REST; the live update must reach every connection:');
const phase1 = await checkDelivery(sockets, 'before any failure');
let phase2 = true;

if (KILL_PHASE) {
  log('\nREADY_FOR_KILL');   // the calling script deletes the core pod that holds most connections now
  for (const s of sockets) {
    // a lost connection is reopened, like the reconnect logic of the real frontend
    s.onClose = async () => {
      if (s.reconnecting || s.deliberatelyClosed) return;
      s.reconnecting = true;
      const before = s.instance;
      log(`  socket ${s.id}: connection was lost`);
      for (let attempt = 1; attempt <= 40; attempt++) {
        await sleep(1000);
        try {
          s.messages = [];
          await s.connect();
          s.reconnects++;
          s.reconnecting = false;
          log(`  socket ${s.id}: reconnected after ${attempt} s (through the gateway, which picks a pod again)`);
          return;
        } catch { /* the pod is still being replaced */ }
      }
      log(`  socket ${s.id}: could not reconnect`);
    };
  }
  await sleep(35000);   // time for the pod to disappear, the connections to drop and reconnect
  log('\nAfter the failure:');
  table(sockets);
  phase2 = await checkDelivery(sockets, 'after the pod was killed');
}

for (const s of sockets) s.close();
log(`\nRESULT ${phase1 && phase2 ? 'PASS' : 'FAIL'}`);
process.exit(phase1 && phase2 ? 0 : 1);
