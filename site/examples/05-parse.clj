(require '[pdf.api :as pdf])

;; Parsing is the serializer run backwards: PDF bytes into the same context map you build with
(let [doc (-> (pdf/new-pdf)
              (pdf/with-page
                (-> (pdf/new-page 612 792)
                    (pdf/with-text 72 684
                      (assoc (pdf/new-text "Written, then read back." font)
                             :font-size 20)))))
      ;; round-trip through bytes and back into a context
      ctx (pdf/parse (pdf/serialize doc))]
  ;; what comes back is just a map, so rotate every page a quarter turn
  (update ctx :objects
          (fn [objs]
            (mapv (fn [{:keys [obj] :as entry}]
                    (if (= :page (:type obj))
                      (assoc-in entry [:obj :rotate] 90)
                      entry))
                  objs))))
