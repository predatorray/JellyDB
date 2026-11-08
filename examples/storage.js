// Standalone examples, not a supported SDK. Every operation is asynchronous.
export function keyToken(key) {
  const text = String(key);
  if (!text.isWellFormed()) throw new TypeError("Key contains an unpaired Unicode surrogate");
  const bytes = new TextEncoder().encode(text);
  let binary = ''; for (const byte of bytes) binary += String.fromCharCode(byte);
  return '~' + btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}
export async function request(base, path, init = {}) {
  const response = await fetch(base + path, { ...init, credentials: 'omit', cache: 'no-store' });
  if (response.status === 404) return { missing: true };
  if (!response.ok) throw new Error(`${response.status}: ${await response.text()}`);
  return { missing: false, value: response.status === 204 ? undefined : await response.json() };
}
export async function createNamespace(base) {
  return (await request(base, '/v1/namespaces', { method: 'POST' })).value;
}
export function storageExample(base, id) {
  const root = `/v1/namespaces/${id}`;
  return {
    async getItem(key) {
      const result = await request(base, `${root}/items/${keyToken(key)}`);
      return result.missing ? null : result.value.value;
    },
    async setItem(key, value) {
      const result = await request(base, `${root}/items/${keyToken(key)}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ value: String(value) })
      });
      if (result.missing) throw new Error('Namespace is missing or expired; create a new one');
    },
    async removeItem(key) {
      const result = await request(base, `${root}/items/${keyToken(key)}`, { method: 'DELETE' });
      if (result.missing) throw new Error('Namespace is missing or expired');
    },
    async clear() {
      const result = await request(base, `${root}/items`, { method: 'DELETE' });
      if (result.missing) throw new Error('Namespace is missing or expired');
    },
    async *keys() {
      let cursor = null;
      do {
        const result = await request(base, `${root}/keys?limit=100${cursor ? `&cursor=${cursor}` : ''}`);
        if (result.missing) throw new Error('Namespace is missing or expired');
        for (const key of result.value.keys) yield key;
        cursor = result.value.nextCursor;
      } while (cursor);
    },
    async length() { let count = 0; for await (const key of this.keys()) count++; return count; },
    async key(index) {
      if (!Number.isInteger(index) || index < 0) return null;
      let i = 0; for await (const key of this.keys()) if (i++ === index) return key; return null;
    },
    async setJSON(key, value) {
      const serialized = JSON.stringify(value); // circular references / BigInt throw before any request
      if (serialized === undefined) throw new TypeError('Value is not JSON serializable');
      await this.setItem(key, serialized);
    },
    async getJSON(key) {
      const value = await this.getItem(key);
      // Tagged result distinguishes missing from a stored JSON null. Invalid JSON throws.
      return value === null ? { found: false } : { found: true, value: JSON.parse(value) };
    }
  };
}
