(ns pdf.context.svg.xml
  "The JVM I/O edge for SVG: XML -> the {:tag :attrs :content} tree
   pdf.context.svg/svg->form consumes. clojure.xml (in the box, no deps) with
   external-DTD fetching disabled — SVG files commonly declare the SVG 1.1
   DOCTYPE and SAX would otherwise go to w3.org for it on every parse."
  (:require
   [clojure.java.io :as io]
   [clojure.xml :as xml]))

(defn- startparse-no-dtd
  [s ch]
  (let [factory (doto (javax.xml.parsers.SAXParserFactory/newInstance)
                  (.setFeature "http://apache.org/xml/features/nonvalidating/load-external-dtd" false))]
    (.parse (.newSAXParser factory) ^java.io.InputStream s ^org.xml.sax.helpers.DefaultHandler ch)))

(defn string->tree
  "Parse an SVG document string into an element tree."
  [s]
  (with-open [in (io/input-stream (.getBytes ^String s "UTF-8"))]
    (xml/parse in startparse-no-dtd)))

(defn file->tree
  "Parse the SVG file at `path` into an element tree."
  [path]
  (with-open [in (io/input-stream path)]
    (xml/parse in startparse-no-dtd)))
