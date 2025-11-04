/* Browser stand-in for node:fs (see shadow-cljs.edn :browser :resolve). The
   import must resolve for the bundle to load, but there is no filesystem —
   byte-based entry points (parse, serialize, newFont/newImage over
   Uint8Array) are the browser API. */
const noFs = (name) => () => {
  throw new Error(
    `${name}: no filesystem in the browser — pass Uint8Array bytes instead of a path ` +
    `(parse(bytes), newFont(bytes), newImage(bytes), serialize(ctx))`,
  );
};

export const readFileSync = noFs("readFileSync");
export const writeFileSync = noFs("writeFileSync");
