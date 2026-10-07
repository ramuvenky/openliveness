import type { WebSocket } from 'ws';
import { BrowserMessage, PhoneMessage } from './messages';

// In memory, so one relay instance. Running several would need Redis pub/sub here.
const browsers = new Map<string, Set<WebSocket>>();
const phones = new Map<string, Set<WebSocket>>();

function add(map: Map<string, Set<WebSocket>>, id: string, ws: WebSocket) {
  if (!map.has(id)) map.set(id, new Set());
  map.get(id)!.add(ws);
  ws.on('close', () => {
    const set = map.get(id);
    set?.delete(ws);
    if (set && set.size === 0) map.delete(id);
  });
}

export const addBrowser = (id: string, ws: WebSocket) => add(browsers, id, ws);
export const addPhone = (id: string, ws: WebSocket) => add(phones, id, ws);

function sendAll(set: Set<WebSocket> | undefined, msg: object) {
  if (!set) return;
  const text = JSON.stringify(msg);
  for (const ws of set) if (ws.readyState === ws.OPEN) ws.send(text);
}

export const toBrowser = (id: string, msg: BrowserMessage) => sendAll(browsers.get(id), msg);
export const toPhone = (id: string, msg: PhoneMessage) => sendAll(phones.get(id), msg);
