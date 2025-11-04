(ns pdf.context.content
  "Compiles content streams as data. A content stream is a vector of operations,
   each `[op-keyword & operands]`; operands are ordinary Clojure data rendered by
   `to-pdf` (numbers, `(strings)`, `/names` via symbols/keywords, `[arrays]`).
   This replaces templating operator syntax with `format`."
  (:require
   [clojure.string :as str]
   [pdf.context.stream :refer [string->stream]]
   [pdf.context.operators :refer [operators]]
   [pdf.serialize :refer [to-pdf]]))

(defn op->line
  "Compile one operation to a content-stream line: the operands in PDF syntax
   followed by the operator mnemonic (`[:set-font 'F0 12]` -> `\"/F0 12 Tf\"`)."
  [[op & operands]]
  (let [mnemonic (or (get operators op)
                     (throw (ex-info "Unknown content operator" {:op op})))]
    (str/join " " (conj (mapv to-pdf operands) mnemonic))))

(defn compile-content
  "Compile a vector of operations to a content-stream string."
  [ops]
  (str/join "\n" (map op->line ops)))

(defn ops->stream
  "Compile a vector of operations to a PDF content stream object."
  [ops]
  (string->stream (compile-content ops)))
