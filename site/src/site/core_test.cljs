(ns site.core-test
  (:require
   ["fs" :as fs]
   [site.eval :as e]
   [site.examples :as examples]
   [pdf.context.text.font :as font]))

(defn eval-example [ttf {:keys [name code]}]
  (try
    (let [bytes (e/eval->bytes (e/make-ctx (font/new-font ttf)) code)
          head  (.decode (js/TextDecoder. "latin1") (.slice bytes 0 5))]
      {:name name
       :ok   (and (instance? js/Uint8Array bytes) (= "%PDF-" head))
       :info (str (.-length bytes) " bytes")})
    (catch :default ex
      {:name name :ok false :info (str ex)})))

(defn -main [& _]
  (let [ttf     (js/Uint8Array. (fs/readFileSync "../test/resources/fonts/Ubuntu-Regular.ttf"))
        results (mapv #(eval-example ttf %) examples/all)]
    (doseq [{:keys [name ok info]} results]
      (println (str (if ok "ok  " "FAIL") "  " name " — " info)))
    (when-not (every? :ok results) (js/process.exit 1))))
