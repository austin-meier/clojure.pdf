import pako from "pako";

export const inflateSync = (data) => pako.inflate(data);
export const deflateSync = (data) => pako.deflate(data);
