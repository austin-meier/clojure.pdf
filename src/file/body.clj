(ns file.body
  (:require
   [clojure.string :as str]
   [protocols.pdf-serializable-protocol :refer [to-pdf]]))


;; PDF binary streams
(defn serialize-object
  "Converts an object from the context to a serialized pdf string"
  [idx object]
  (str/join "\n" [(format "%d 0 obj" (inc idx)) (to-pdf object) "endobj"]))

(defn serialize-objects
  "Serializes all objects from the :ctx of the serialization context.
   Updates:
   - :serialized-bytes=accumulated UTF-8 bytes of all objects
   - :object-offsete=absolute byte offsets for each object start."
  [serialization-ctx]
  (reduce
   (fn [ctx [idx obj]]
     (let [offset (count (:serialized-bytes ctx))
           obj-str (serialize-object idx obj)
           obj-bytes (.getBytes (str obj-str "\n") "UTF-8")]
       (-> ctx
           (update :object-offsets conj offset)
           (update :serialized-bytes into obj-bytes))))
   serialization-ctx
   (map-indexed vector (get-in serialization-ctx [:ctx :objects]))))
