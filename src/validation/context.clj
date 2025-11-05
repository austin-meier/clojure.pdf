(ns validation.context)

(def validators
  {:ctx-nil {:message "Context is nil" :path [] :severity :error}
   :no-objects {:message "PDF context has no :objects vector" :path [:objects] :severity :error}
   :no-catalog {:message "Missing catalog object (:type :catalog)" :path [:objects] :severity :error}
   :pages-empty {:message "The root pages object has empty :kids" :path [:objects] :severity :error}
   :catalog-pages-ref-invalid {:message "Catalog pages reference is invalid" :path [:objects] :severity :error}})

(defn make-error [id & {:keys [message path severity data] :or {message nil path nil severity nil data nil}}]
  (let [base (get validators id {})]
    {:id id
     :message (or message (:message base) "Unknown validation error")
     :path (or path (:path base) [])
     :severity (or severity (:severity base) :error)
     :data data}))

(defn ctx-not-nil? [ctx]
  (if (some? ctx)
    {:ok true}
    {:ok false :error (make-error :ctx-nil)}))

(defn has-objects? [ctx]
  (if (and (map? ctx) (vector? (:objects ctx)) (seq (:objects ctx)))
    {:ok true}
    {:ok false :error (make-error :no-objects)}))

(defn find-first-object [ctx pred]
  (when (and ctx (:objects ctx))
    (first (filter pred (:objects ctx)))))

(defn has-catalog? [ctx]
  (if (find-first-object ctx #(= :catalog (:type %)))
    {:ok true}
    {:ok false :error (make-error :no-catalog)}))

(defn pages-non-empty? [ctx]
  (let [pages (find-first-object ctx #(= :pages (:type %)))]
    (if (and pages (seq (:kids pages)))
      {:ok true}
      {:ok false :error (make-error :pages-empty)})))

(defn valid-ref? [ctx ref]
  (some? (get (:objects ctx) (dec (:obj-num ref)) ref)))

(defn catalog-ref-valid? [ctx]
  (let [catalog (find-first-object ctx #(= :catalog (:type %)))]
    (if catalog
      (let [p (:pages catalog)]
        (if (valid-ref? ctx p)
          {:ok true}
          {:ok false :error (make-error :catalog-pages-ref-invalid)}))
      {:ok false :error (make-error :no-catalog)})))

(def default-validators
  [ctx-not-nil?
   has-objects?
   has-catalog?
   catalog-ref-valid?
   pages-non-empty?])

(defn run-validators
  "Run validators (fn [ctx] => {:ok true} or {:ok false :error err-map}) and collect errors.
  Returns {:valid? boolean :errors [err-map ...]}"
  [ctx validators]
  (let [results (map (fn [v] (v ctx)) validators)]
    {:valid (every? true? (map :ok results)) :errors (vec (keep :error results))}))

(defn validate-context
  "Run default validators. Returns {:valid? boolean :errors [...]}"
  ([ctx] (run-validators ctx default-validators))
  ([ctx validators] (run-validators ctx validators)))
