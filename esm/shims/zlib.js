/* Browser stand-in for node:zlib (see shadow-cljs.edn :browser :resolve).
   pako is bundled at build time; only the two functions pdf.utils.flate calls,
   both sync and Uint8Array-in/out like their node counterparts. */
import pako from "pako";

export const inflateSync = (data) => pako.inflate(data);
export const deflateSync = (data) => pako.deflate(data);
