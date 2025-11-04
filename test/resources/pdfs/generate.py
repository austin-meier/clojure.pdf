#!/usr/bin/env python3
"""Regenerate the parser fixtures from a base PDF.

The base is any clean, unencrypted PDF our own library emits; by default the
`example.hello` document:

    clojure -M:examples -m example.hello        # writes example-hello.pdf
    python test/resources/pdfs/generate.py example-hello.pdf

qpdf (via pikepdf) rewrites it into the cross-reference variants the parser has
to handle:

  - binary-id.pdf      classic xref table, with a binary /ID in the trailer
                       (qpdf keeps the table but adds an /ID; the 16-byte
                       random strings exercise byte-string round-tripping).
  - object-streams.pdf an xref *stream* (PNG /Predictor 12) plus compressed
                       object streams — the modern 1.5+ shape most tools emit.

The third fixture, hybrid.pdf (a classic table + /XRefStm hybrid, which qpdf
won't emit), is built by the Clojure side — see `write-hybrid!` in
test/pdf/parse/hybrid_test.clj.

Committed alongside the fixtures so they are reproducible; the parser tests read
the committed files, they do not run this.
"""
import sys
import os
import pikepdf

HERE = os.path.dirname(os.path.abspath(__file__))


def generate(base):
    with pikepdf.open(base) as p:
        p.save(os.path.join(HERE, "binary-id.pdf"),
               object_stream_mode=pikepdf.ObjectStreamMode.disable)
    with pikepdf.open(base) as p:
        p.save(os.path.join(HERE, "object-streams.pdf"),
               object_stream_mode=pikepdf.ObjectStreamMode.generate)
    for name in ("binary-id.pdf", "object-streams.pdf"):
        path = os.path.join(HERE, name)
        print(f"wrote {name} ({os.path.getsize(path)} bytes)")


if __name__ == "__main__":
    base = sys.argv[1] if len(sys.argv) > 1 else "example-hello.pdf"
    generate(base)
