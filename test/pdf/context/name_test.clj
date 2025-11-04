(ns pdf.context.name-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.name :refer [aliases key->pdf-name pdf-name->key]]))

(deftest key->pdf-name-mapping
  (testing "Aliased keywords use their exact PDF name"
    (is (= "MediaBox" (key->pdf-name :media-box)))
    (is (= "Subtype" (key->pdf-name :subtype)))
    (is (= "CIDToGIDMap" (key->pdf-name :cid-to-gid-map)))
    (is (= "ID" (key->pdf-name :id))))

  (testing "Unregistered keywords fall back to kebab->Pascal"
    (is (= "SomeNewName" (key->pdf-name :some-new-name)))))

(deftest pdf-name->key-inversion
  (testing "Registered names invert to their keyword"
    (is (= :media-box (pdf-name->key "MediaBox")))
    (is (= :cid-to-gid-map (pdf-name->key "CIDToGIDMap"))))

  (testing "Unregistered names invert to an exact-name symbol"
    (is (= 'ca (pdf-name->key "ca")))
    (is (= 'SomeNewName (pdf-name->key "SomeNewName")))))

(deftest round-trips
  (testing "Every alias round-trips keyword -> name -> keyword"
    (doseq [k (keys aliases)]
      (is (= k (pdf-name->key (key->pdf-name k))))))

  (testing "Alias names are unique so the inverse is well defined"
    (is (= (count aliases) (count (set (vals aliases)))))))
