/* Smoke test for the browser ESM build, run under Node: after the shims
   (pako for zlib, a throwing fs stub) the bundle is pure JS, so Node can
   execute it without a browser. Exercises byte-based font/image loading, the
   JS wrapper (camelCase styles, array hiccup), pako inflate (parse, alpha
   PNG) and deflate (alpha PNG re-compress).

     npm run build && node tests/test-browser.mjs */
import {
  parse, serialize, newPdf, newPage, withPage,
  newFont, newImage, withText, withImage, withLayout, toBlob,
} from "../dist/browser/pdf.js";
import { readFileSync, writeFileSync } from "node:fs";

const check = (name, cond) => {
  console.log(`${cond ? "ok  " : "FAIL"}  ${name}`);
  if (!cond) process.exitCode = 1;
};

const latin1 = (bytes) => new TextDecoder("latin1").decode(bytes);
const bytes = (path) => new Uint8Array(readFileSync(path));

/* byte-based loading — the only browser path, there is no filesystem */
const font = newFont(bytes("../test/resources/fonts/Ubuntu-Regular.ttf"));
check("newFont accepts bytes", font != undefined);

const image = newImage(bytes("../test/resources/images/rgba.png"));
check("newImage accepts bytes (alpha PNG, pako inflate + deflate)", image != undefined);

/* the fs shim refuses paths with a pointed error instead of failing to load */
const pathError = (() => {
  try { newFont("../test/resources/fonts/Ubuntu-Regular.ttf"); } catch (e) { return `${e}`; }
})();
check("path loading throws the browser fs error", `${pathError}`.includes("browser"));

/* manual authoring through the wrapper */
const page = withImage(
  withText(newPage(612, 792), "Hello from the browser build", { x: 72, y: 720, font, size: 18 }),
  image, 72, 560, { width: 128 },
);

/* layout authoring: array hiccup + camelCase style objects */
const tree =
  ["div", { style: { font, fontSize: 12, gap: 12, padding: 24 } },
    ["text", { style: { fontSize: 24 } }, "Layout from JS"],
    ["div", { style: { flexDirection: "row", justifyContent: "space-between", padding: [8, 0] } },
      ["text", { style: { color: "#888" } }, "left"],
      ["text", {}, "right"]],
    ["image", { src: image, style: { width: 96 } }],
    ["text", {}, "The quick brown fox jumps over the lazy dog. ".repeat(40)]];

const doc = withLayout(withPage(newPdf(), page), tree, { pageSize: [612, 792], margin: 48 });

const out = serialize(doc);
check("serialize returns a Uint8Array", out instanceof Uint8Array);
check("output starts with %PDF-", latin1(out.slice(0, 5)) === "%PDF-");
check("output ends with %%EOF", latin1(out.slice(-8)).includes("%%EOF"));

/* numbers (not just Dimension records) must survive as coordinates — a nil
   would serialize as PDF null here */
check("withText x/y reach the text matrix", latin1(out).includes("1 0 0 1 72 720 Tm"));
check("newPage numbers reach the media box", latin1(out).includes("0 0 612 792"));

const blob = toBlob(doc);
check("toBlob returns an application/pdf Blob",
      blob instanceof Blob && blob.type === "application/pdf");

/* parse from bytes: xref/object-stream inflate goes through pako */
const ctx = parse(bytes("../test/resources/pdfs/object-streams.pdf"));
check("parse(bytes) inflates xref/object streams through pako", ctx != undefined);
check("parse -> serialize round-trips", latin1(serialize(ctx).slice(0, 5)) === "%PDF-");

/* for the external pypdf/pdfium verification pass */
writeFileSync("dist/browser-smoke.pdf", out);
console.log(`\nbrowser bundle smoke: wrote dist/browser-smoke.pdf (${out.length} bytes)`);
