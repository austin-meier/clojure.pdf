#!/usr/bin/env python3
"""External verification for the parser fixtures.

For each committed fixture: parse it with our library and re-serialize (done by
the Clojure side, which writes `<name>.reserialized.pdf` next to the fixture),
then confirm pypdf extracts the same text and reports the same page count from
the re-serialized file as from the original. Run manually, like the font/image
verification work — not part of `clojure -X:test`.

    clojure -M -e "(require '[pdf.parse.core :as p] '[pdf.context.pdf :as c]) \
      (doseq [n [\"object-streams\" \"binary-id\"]] \
        (c/save (p/parse-file (str \"test/resources/pdfs/\" n \".pdf\")) \
                (str \"test/resources/pdfs/\" n \".reserialized.pdf\")))"
    python test/resources/pdfs/verify.py
"""
import os
from pypdf import PdfReader

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = ("object-streams", "binary-id", "hybrid")


def page_text(path):
    reader = PdfReader(path)
    return len(reader.pages), "".join(page.extract_text() for page in reader.pages)


def verify(name):
    original = os.path.join(HERE, f"{name}.pdf")
    reserialized = os.path.join(HERE, f"{name}.reserialized.pdf")
    o_pages, o_text = page_text(original)
    r_pages, r_text = page_text(reserialized)
    ok = o_pages == r_pages and o_text == r_text
    print(f"{name}: pages {o_pages}=={r_pages}, text {'match' if o_text == r_text else 'DIFFER'}"
          f" -> {'ok' if ok else 'FAIL'}")
    return ok


if __name__ == "__main__":
    results = [verify(name) for name in FIXTURES]
    raise SystemExit(0 if all(results) else 1)
