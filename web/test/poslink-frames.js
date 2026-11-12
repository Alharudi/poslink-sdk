// POS Link protocol v2 frames (spec/PROTOCOL.md, section 2), for the browser test page.
// Plain ES module, no dependencies. Checked against spec/vectors/frames.json by
// poslink-frames.test.mjs. The TypeScript SDK will grow from this.

export const STX = 0x02;
export const VERSION = 0x02;
export const HEADER_LENGTH = 11;
export const V1_HEADER_LENGTH = 7;
export const MAC_LENGTH = 16;
export const CRC_LENGTH = 2;
export const MAX_PAYLOAD = 8192;

export const TYPE_NAMES = {
  0x01: "HELLO", 0x02: "AUTH", 0x03: "PING", 0x04: "PONG", 0x05: "ACK", 0x06: "NAK",
  0x10: "STATUS", 0x20: "SALE_REQUEST", 0x21: "READY_TO_PAY", 0x22: "PROGRESS",
  0x23: "SALE_RESULT", 0x24: "CANCEL", 0x25: "TXN_STATUS_QUERY", 0x26: "TXN_STATUS",
  0x30: "PAIR_REQUEST", 0x31: "PAIR_KEY", 0x32: "PAIR_REVEAL", 0x33: "PAIR_CONFIRM",
  0x34: "PAIR_FAIL", 0x7f: "ERROR",
};

export const HELLO = 0x01;

/** CRC-16/CCITT-FALSE over bytes[from, to). */
export function crc16(bytes, from = 0, to = bytes.length) {
  let crc = 0xffff;
  for (let i = from; i < to; i++) {
    crc ^= bytes[i] << 8;
    for (let bit = 0; bit < 8; bit++) {
      crc = crc & 0x8000 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
    }
  }
  return crc;
}

/**
 * One frame. `mac` is 16 bytes (all zero for unauthenticated types). Adds the 0x00 pad byte when
 * the frame is an exact multiple of 64 bytes.
 */
export function encodeFrame({ type, seq = 0, counter = 0, payload = new Uint8Array(0), mac = new Uint8Array(MAC_LENGTH) }) {
  const n = payload.length;
  if (n > MAX_PAYLOAD) throw new RangeError(`payload of ${n} bytes is over ${MAX_PAYLOAD}`);
  if (mac.length !== MAC_LENGTH) throw new RangeError("mac must be 16 bytes");
  const total = HEADER_LENGTH + n + MAC_LENGTH + CRC_LENGTH;
  const out = new Uint8Array(total + (total % 64 === 0 ? 1 : 0));
  const view = new DataView(out.buffer);
  out[0] = STX;
  out[1] = VERSION;
  out[2] = type;
  view.setUint16(3, seq);
  view.setUint32(5, counter);
  view.setUint16(9, n);
  out.set(payload, HEADER_LENGTH);
  out.set(mac, HEADER_LENGTH + n);
  const crcAt = HEADER_LENGTH + n + MAC_LENGTH;
  view.setUint16(crcAt, crc16(out, 1, crcAt));
  return out;
}

/** An unauthenticated frame with a JSON payload (HELLO, PAIR_*). */
export function encodeJsonFrame(type, object) {
  return encodeFrame({ type, payload: new TextEncoder().encode(JSON.stringify(object)) });
}

/**
 * Rebuilds frames from a byte stream that may split or merge them. Skips noise, drops frames with
 * a bad CRC, and parses protocol v1 frames far enough to report them.
 */
export class FrameDecoder {
  #buffer = new Uint8Array(0);
  crcErrors = 0;
  discardedBytes = 0;

  /** Feeds bytes; returns the complete frames found so far. */
  add(chunk) {
    const data = this.#buffer.length ? concat(this.#buffer, chunk) : chunk;
    const frames = [];
    let i = 0;
    for (;;) {
      while (i < data.length && data[i] !== STX) {
        if (data[i] !== 0) this.discardedBytes++;
        i++;
      }
      if (data.length - i < 2) break;
      const version = data[i + 1];
      let length;
      let total;
      if (version === VERSION) {
        if (data.length - i < HEADER_LENGTH) break;
        length = u16(data, i + 9);
        total = HEADER_LENGTH + length + MAC_LENGTH + CRC_LENGTH;
      } else if (version === 1) {
        if (data.length - i < V1_HEADER_LENGTH) break;
        length = u16(data, i + 5);
        total = V1_HEADER_LENGTH + length + CRC_LENGTH;
      } else {
        this.discardedBytes++;
        i++;
        continue;
      }
      if (length > MAX_PAYLOAD) {
        this.discardedBytes++;
        i++;
        continue;
      }
      if (data.length - i < total) break;
      const crcAt = i + total - CRC_LENGTH;
      if (crc16(data, i + 1, crcAt) !== u16(data, crcAt)) {
        this.crcErrors++;
        i++;
        continue;
      }
      if (version === VERSION) {
        const payloadEnd = i + HEADER_LENGTH + length;
        frames.push({
          version,
          type: data[i + 2],
          seq: u16(data, i + 3),
          counter: new DataView(data.buffer, data.byteOffset).getUint32(i + 5),
          payload: data.slice(i + HEADER_LENGTH, payloadEnd),
          mac: data.slice(payloadEnd, payloadEnd + MAC_LENGTH),
        });
      } else {
        frames.push({
          version: 1,
          type: data[i + 2],
          seq: u16(data, i + 3),
          counter: 0,
          payload: data.slice(i + V1_HEADER_LENGTH, crcAt),
          mac: new Uint8Array(MAC_LENGTH),
        });
      }
      i += total;
    }
    this.#buffer = i >= data.length ? new Uint8Array(0) : data.slice(i);
    return frames;
  }
}

/** The JSON object in a frame's payload, or null. */
export function payloadJson(frame) {
  if (!frame.payload.length) return null;
  try {
    const value = JSON.parse(new TextDecoder().decode(frame.payload));
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

export function typeName(type) {
  return TYPE_NAMES[type] ?? `0x${type.toString(16).padStart(2, "0")}`;
}

export function toHex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function fromHex(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

function u16(bytes, at) {
  return (bytes[at] << 8) | bytes[at + 1];
}

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}
