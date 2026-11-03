// Ore Kingdom wallet-rig stub chain + price source (CI test equipment).
//
// The JSON-RPC stub (port 18741) is the chain the rig host verifies
// against (OK_RPC_URL) and the chain the rig's patched Mock MWA Wallet
// broadcasts to. It accepts REAL signed transactions via
// sendTransaction, parses them (legacy message format), and serves
// getSignaturesForAddress / getTransaction in exactly the shape the
// host's SIM-off verifier consumes (the shape the staging battery's
// crafted fixtures established): instructions carry account INDEX
// arrays; token legs surface as pre/post token-balance deltas;
// lamport legs as pre/post balance deltas. Nothing is pre-registered:
// every served transaction is a real client-built, wallet-signed
// transaction the stub received over the wire.
//
// The price source (port 18742) answers the wrapper's OK_PRICE_URL
// contract: {data: {<priceId>: {price}}, ts} with a fresh timestamp
// and in-band fixed prices, so session quotes are deterministic.

const RPC_PORT = Number(process.env.RIG_RPC_PORT ?? 18741);
const PRICE_PORT = Number(process.env.RIG_PRICE_PORT ?? 18742);

// ---------- base58 ----------
const B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
function b58encode(bytes: Uint8Array): string {
  let zeros = 0;
  while (zeros < bytes.length && bytes[zeros] === 0) zeros++;
  let n = 0n;
  for (const b of bytes) n = n * 256n + BigInt(b);
  let s = "";
  while (n > 0n) { s = B58[Number(n % 58n)] + s; n = n / 58n; }
  return "1".repeat(zeros) + s;
}

// ---------- legacy transaction parsing ----------
function readCompactU16(buf: Uint8Array, off: number): [number, number] {
  let value = 0, shift = 0, i = off;
  for (;;) {
    const byte = buf[i++];
    value |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) break;
    shift += 7;
  }
  return [value, i];
}

type StoredTx = {
  signature: string;
  slot: number;
  blockTime: number;
  keys: string[];
  signerFlags: boolean[];
  writableFlags: boolean[];
  instructions: { programIdIndex: number; accounts: number[]; data: Uint8Array }[];
  numRequiredSignatures: number;
};

const SYSTEM_PROGRAM = "11111111111111111111111111111111";
const TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";

function parseTransaction(raw: Uint8Array): StoredTx | null {
  try {
    let off = 0;
    let sigCount: number; [sigCount, off] = readCompactU16(raw, off);
    const signatures: string[] = [];
    for (let i = 0; i < sigCount; i++) {
      signatures.push(b58encode(raw.slice(off, off + 64)));
      off += 64;
    }
    if (signatures.length === 0) return null;
    const numRequired = raw[off++];
    const numReadonlySigned = raw[off++];
    const numReadonlyUnsigned = raw[off++];
    let keyCount: number; [keyCount, off] = readCompactU16(raw, off);
    const keys: string[] = [];
    for (let i = 0; i < keyCount; i++) {
      keys.push(b58encode(raw.slice(off, off + 32)));
      off += 32;
    }
    off += 32; // recent blockhash
    let ixCount: number; [ixCount, off] = readCompactU16(raw, off);
    const instructions: StoredTx["instructions"] = [];
    for (let i = 0; i < ixCount; i++) {
      const programIdIndex = raw[off++];
      let acctCount: number; [acctCount, off] = readCompactU16(raw, off);
      const accounts: number[] = [];
      for (let j = 0; j < acctCount; j++) accounts.push(raw[off++]);
      let dataLen: number; [dataLen, off] = readCompactU16(raw, off);
      const data = raw.slice(off, off + dataLen);
      off += dataLen;
      instructions.push({ programIdIndex, accounts, data });
    }
    const signerFlags = keys.map((_, i) => i < numRequired);
    const writableFlags = keys.map((_, i) =>
      i < numRequired ? i < numRequired - numReadonlySigned : i < keyCount - numReadonlyUnsigned);
    return {
      signature: signatures[0],
      slot: 0,
      blockTime: 0,
      keys,
      signerFlags,
      writableFlags,
      instructions,
      numRequiredSignatures: numRequired,
    };
  } catch {
    return null;
  }
}

function u64le(data: Uint8Array, off: number): bigint {
  let v = 0n;
  for (let i = 7; i >= 0; i--) v = v * 256n + BigInt(data[off + i] ?? 0);
  return v;
}
function u32le(data: Uint8Array, off: number): number {
  return (data[off] ?? 0) | ((data[off + 1] ?? 0) << 8) | ((data[off + 2] ?? 0) << 16) | ((data[off + 3] ?? 0) << 24);
}

// ---------- the chain ----------
const txs: StoredTx[] = [];
let slotCounter = 250_000_000;

function synthesizeGetTransaction(tx: StoredTx) {
  const preBalances = tx.keys.map(() => 2_000_000_000);
  const postBalances = [...preBalances];
  const preToken = new Map<number, any>();
  const postToken = new Map<number, any>();
  let feeCharged = false;
  for (const ix of tx.instructions) {
    const program = tx.keys[ix.programIdIndex];
    if (program === SYSTEM_PROGRAM && ix.data.length >= 12 && u32le(ix.data, 0) === 2) {
      const lamports = Number(u64le(ix.data, 4));
      const src = ix.accounts[0], dst = ix.accounts[1];
      if (src != null) postBalances[src] -= lamports;
      if (dst != null) postBalances[dst] += lamports;
    } else if (program === TOKEN_PROGRAM && ix.data.length >= 10 && ix.data[0] === 12) {
      // transferChecked: accounts [source, mint, destination, owner]
      const amount = u64le(ix.data, 1);
      const [srcIdx, mintIdx, dstIdx, ownerIdx] = ix.accounts;
      const mint = tx.keys[mintIdx];
      const mk = (idx: number, amt: bigint) => ({
        accountIndex: idx,
        mint,
        owner: tx.keys[ownerIdx],
        uiTokenAmount: { amount: amt.toString(), decimals: ix.data[9] ?? 6, uiAmount: Number(amt) / 10 ** (ix.data[9] ?? 6), uiAmountString: amt.toString() },
      });
      const base = 10_000_000_000n;
      if (srcIdx != null) { preToken.set(srcIdx, mk(srcIdx, base)); postToken.set(srcIdx, mk(srcIdx, base - amount)); }
      if (dstIdx != null) { if (!preToken.has(dstIdx)) preToken.set(dstIdx, mk(dstIdx, 0n)); postToken.set(dstIdx, mk(dstIdx, (postToken.get(dstIdx) ? BigInt(postToken.get(dstIdx).uiTokenAmount.amount) : 0n) + amount)); }
    }
  }
  // network fee from the payer, as a real chain would record
  if (!feeCharged && tx.numRequiredSignatures > 0) { postBalances[0] -= 5000; feeCharged = true; }
  return {
    slot: tx.slot,
    blockTime: tx.blockTime,
    meta: {
      err: null,
      fee: 5000,
      preBalances,
      postBalances,
      preTokenBalances: [...preToken.values()],
      postTokenBalances: [...postToken.values()],
      logMessages: [],
      status: { Ok: null },
      loadedAddresses: { writable: [], readonly: [] },
      computeUnitsConsumed: 1500,
    },
    transaction: {
      signatures: [tx.signature],
      message: {
        accountKeys: tx.keys.map((pubkey, i) => ({ pubkey, signer: tx.signerFlags[i], writable: tx.writableFlags[i] })),
        header: { numRequiredSignatures: tx.numRequiredSignatures, numReadonlySignedAccounts: 0, numReadonlyUnsignedAccounts: 0 },
        instructions: tx.instructions.map((ix) => ({
          programIdIndex: ix.programIdIndex,
          accounts: ix.accounts,
          data: b58encode(ix.data),
        })),
        recentBlockhash: "4uQeVj5tqViQh7yWWGStvkEG1Zmhx6uasJtWCJziofM",
      },
    },
    version: "legacy",
  };
}

function rpcResult(id: any, result: any) {
  return Response.json({ jsonrpc: "2.0", id, result });
}
function rpcError(id: any, code: number, message: string) {
  return Response.json({ jsonrpc: "2.0", id, error: { code, message } });
}

const rpc = Bun.serve({
  port: RPC_PORT,
  async fetch(req) {
    const url = new URL(req.url);
    if (url.pathname === "/healthz") return new Response("ok");
    if (req.method !== "POST") return rpcError(null, -32600, "POST only");
    let body: any;
    try { body = await req.json(); } catch { return rpcError(null, -32700, "parse error"); }
    const { id, method, params } = body ?? {};
    switch (method) {
      case "sendTransaction": {
        const raw = Uint8Array.from(Buffer.from(String(params?.[0] ?? ""), "base64"));
        const tx = parseTransaction(raw);
        if (!tx) return rpcError(id, -32602, "invalid transaction bytes");
        tx.slot = ++slotCounter;
        tx.blockTime = Math.floor(Date.now() / 1000);
        txs.unshift(tx);
        console.log(`[stub] sendTransaction -> ${tx.signature} (${tx.keys.length} keys, ${tx.instructions.length} ix)`);
        return rpcResult(id, tx.signature);
      }
      case "getSignaturesForAddress": {
        const address = String(params?.[0] ?? "");
        const limit = Number(params?.[1]?.limit ?? 100);
        const found = txs.filter((t) => t.keys.includes(address)).slice(0, limit)
          .map((t) => ({ signature: t.signature, slot: t.slot, blockTime: t.blockTime, err: null, memo: null, confirmationStatus: "finalized" }));
        return rpcResult(id, found);
      }
      case "getTransaction": {
        const sig = String(params?.[0] ?? "");
        const tx = txs.find((t) => t.signature === sig);
        return rpcResult(id, tx ? synthesizeGetTransaction(tx) : null);
      }
      case "getSignatureStatuses": {
        const sigs: string[] = params?.[0] ?? [];
        return rpcResult(id, {
          context: { slot: slotCounter, apiVersion: "2.0.0" },
          value: sigs.map((s) => {
            const tx = txs.find((t) => t.signature === s);
            return tx ? { slot: tx.slot, confirmations: null, err: null, confirmationStatus: "finalized", status: { Ok: null } } : null;
          }),
        });
      }
      case "getLatestBlockhash":
        return rpcResult(id, { context: { slot: slotCounter, apiVersion: "2.0.0" }, value: { blockhash: "4uQeVj5tqViQh7yWWGStvkEG1Zmhx6uasJtWCJziofM", lastValidBlockHeight: 999_999_999 } });
      case "getBlockHeight":
        return rpcResult(id, slotCounter);
      case "getSlot":
        return rpcResult(id, slotCounter);
      case "getVersion":
        return rpcResult(id, { "solana-core": "2.0.0", "feature-set": 1 });
      case "getHealth":
        return rpcResult(id, "ok");
      default:
        return rpcError(id, -32601, `method not found: ${method}`);
    }
  },
});

// ---------- price source ----------
const PRICES: Record<string, string> = {
  "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3": "0.02",
  "oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp": "100.0",
  "So11111111111111111111111111111111111111112": "150.0",
  "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v": "1.0",
};
const prices = Bun.serve({
  port: PRICE_PORT,
  fetch() {
    const data: Record<string, { price: string }> = {};
    for (const [k, v] of Object.entries(PRICES)) data[k] = { price: v };
    return Response.json({ data, ts: Date.now() });
  },
});

console.log(`[stub] rpc on ${rpc.port}, prices on ${prices.port}`);
