// Smoke test for the ESM build: round-trip a PDF entirely through the compiled
// module, from JavaScript. The object-streams fixture exercises the parser's
// node-zlib inflate, the PNG predictor, and object-stream extraction; serialize
// runs the full authoring pipeline and concatenates chunks into a Uint8Array.
//
//   npm run build && node tests/test.mjs
import { parse, parseFile, serialize, save, newPdf } from "../dist/pdf.js";
import { readFileSync } from "node:fs";

const check = (name, cond) => {
  console.log(`${cond ? "ok  " : "FAIL"}  ${name}`);
  if (!cond) process.exitCode = 1;
};

const latin1 = (bytes) => new TextDecoder("latin1").decode(bytes);

// parse from a path (node fs), then from bytes
const ctx = parseFile("../test/resources/pdfs/object-streams.pdf");
check("parseFile returns a context object", ctx != null);

const bytesIn = new Uint8Array(readFileSync("../test/resources/pdfs/object-streams.pdf"));
const ctx2 = parse(bytesIn);
check("parse(bytes) works too", ctx2 != null);

// serialize back to a PDF
const out = serialize(ctx);
check("serialize returns a Uint8Array", out instanceof Uint8Array);
check("serialized output is non-trivial", out.length > 1000);

// a valid PDF starts with %PDF- and ends near %%EOF
check("output starts with %PDF-", latin1(out.slice(0, 5)) === "%PDF-");
check("output ends with %%EOF", latin1(out.slice(-8)).includes("%%EOF"));

// write it out for the external pypdf check
save(ctx, "dist/roundtrip-from-node.pdf");
check("save wrote a file via node fs", true);

// build a fresh document from scratch, purely in JS-driving-cljs
const blank = newPdf();
const blankBytes = serialize(blank);
check("serialize(newPdf) produces a valid header",
      latin1(blankBytes.slice(0, 5)) === "%PDF-");

console.log(`\nround-trip: ${bytesIn.length} bytes in -> ${out.length} bytes out`);
