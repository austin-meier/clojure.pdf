const noFs = (name) => () => {
  throw new Error(
    `${name}: no filesystem in the browser — pass Uint8Array bytes instead of a path`,
  );
};

export const readFileSync = noFs("readFileSync");
export const writeFileSync = noFs("writeFileSync");
