// A callback endpoint: verifies Adera's signature, de-duplicates on Idempotency-Key, answers 2xx.
// Run: ADERA_BASE=... ADERA_KEY_ID=x ADERA_PRIVATE_KEY_FILE=... node callback-receiver.js 8098
const http = require("node:http");
const { verifyCallback } = require("./adera-client");

const seen = new Set(); // use a database or cache with expiry in production

http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", async () => {
    const raw = Buffer.concat(chunks);                       // the raw bytes: verify BEFORE parsing
    try {
      await verifyCallback(req.url, raw, req.headers);       // req.url = path + query exactly as received
    } catch (e) {
      console.log("REJECTED:", e.message);
      res.writeHead(401).end();
      return;
    }
    const key = req.headers["idempotency-key"];
    if (seen.has(key)) console.log("duplicate delivery ignored", key);
    else { seen.add(key); console.log("callback", req.headers["x-adera-event"], raw.toString()); }
    res.writeHead(204).end();                                // any 2xx means "received"
  });
}).listen(Number(process.argv[2]), "0.0.0.0");
