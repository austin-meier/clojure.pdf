(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.austin-meier/clojure.pdf)
;; Release version comes from the git tag, passed in as VERSION by CI (see
;; .github/workflows/publish.yml). Local builds fall back to a snapshot, so a
;; hand-run `deploy` can never land as a real release.
(def version (or (System/getenv "VERSION") "0.0.0-SNAPSHOT"))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def scm-url "https://github.com/austin-meier/clojure.pdf")

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url                 scm-url
                      :connection          (str "scm:git:git://github.com/austin-meier/clojure.pdf.git")
                      :developerConnection (str "scm:git:ssh://git@github.com/austin-meier/clojure.pdf.git")
                      :tag                 (str "v" version)}
                :pom-data [[:description "A pure-Clojure PDF library: authoring, layout, vector/SVG, images, and a parser, with a portable ClojureScript ESM build."]
                           [:url scm-url]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/licenses/MIT"]]]]})
  ;; skinny jar: source only, no bundled deps (there are none anyway)
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn deploy
  "Build a fresh jar and push it to Clojars. Reads CLOJARS_USERNAME and
   CLOJARS_PASSWORD (a Clojars deploy token) from the environment."
  [_]
  (clean nil)
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))
