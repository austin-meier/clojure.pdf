(ns site.editor
  (:require
   ["@codemirror/state" :refer [EditorState]]
   ["@codemirror/view" :refer [EditorView]]
   ["codemirror" :refer [basicSetup]]
   ["@nextjournal/lang-clojure" :refer [clojure]]))

(defn change-listener [on-change]
  (.of (.-updateListener ^js EditorView)
       (fn [^js update] (when (.-docChanged update) (on-change)))))

(defn make-editor
  [parent doc on-change]
  (EditorView.
   #js {:parent parent
        :state  (.create EditorState
                         #js {:doc        doc
                              :extensions #js [basicSetup (clojure) (change-listener on-change)]})}))

(defn current-doc [^js view]
  (.toString (.. view -state -doc)))

(defn set-doc! [^js view text]
  (.dispatch view #js {:changes #js {:from 0 :to (.. view -state -doc -length) :insert text}}))
