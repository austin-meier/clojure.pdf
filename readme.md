# Clojure PDF

A clojure only pdf library with the goal of being elegant.

Based on the [Adobe 2.0 PDF Specification](https://developer.adobe.com/document-services/docs/assets/5b15559b96303194340b99820d3a70fa/PDF_ISO_32000-2.pdf)

## Getting Started

## Design

This library is intended to be data centric. You can build PDF documents using core language eleoquency.

There are many builders provider with examples you can find, by the great news is that even if high-level api does not contain
what you are looking for. All contextual representations are _just data_ and you can manipulate them to your liking.

- PDF names and clojure keywords are mapped between kebab-case and PasalCase (ex: :font-name = /FontName)
- Clojure symbols go directly to PDF Name (ex: 'SubType = /SubType)
- PDF arrays are expressed as sequences
- PDF dictionaries are expressed as maps
- PDF data streams are expressed as byte-array

## Roadmap

The PDF document format is both incredibly impressive and unnaturally wide. I work with PDF a good bit so I do intended to add
some of the more intricate parts of the format. For now my primary goal is to create a library elegant for _producing_ PDFs
from clojure contexts. Building out the elegant clojure internal context system for large support preceeds building a proper
parser to deserialize into this well made context.

1. PDF Support
   1. Pages
      1. Create pages
      1. Resize pages
   1. Layout
      1. Create a layout engine (ouch)
   1. Content
      1. Text
         1. Fonts
            1. Embed a font to a document
      1. Images
      1. Embedded PDFs (yeah that's a thing)
   1. Serialization
      1. File Serializer
   1. Deserialization
      1. Parser
   1. PDF Verification
      1. Warnings
      1. Errors
