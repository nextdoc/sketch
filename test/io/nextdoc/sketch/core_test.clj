(ns io.nextdoc.sketch.core-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [io.nextdoc.sketch.core :refer [insert-sorted-map-entry map-multi-schemas map-schema-keys
                                            next-map-entry]]
            [io.nextdoc.sketch.sync :refer [insert-sorted-registry-schema sort-registry registry-form-loc]]
            [malli.registry :as mr]
            [rewrite-clj.zip :as z]))

;;;; REGISTRY SYNC

(def registry-fixture-unsorted "
{
 :operations [:map
                [:name :string]]
 ; map level comment
 :deployment [:map
               ;; useful comment
               [:version :string]]
}
")

(deftest sort-registry-simple
  (let [root (z/of-string registry-fixture-unsorted)
        updated (sort-registry root)]
    (is (= '(:operations :deployment)
           (keys (edn/read-string (z/root-string root))))
        "input is unchanged")
    (is (= '(:deployment :operations)
           (keys (edn/read-string (z/root-string updated))))
        "updated is sorted")
    ; doc/reminder not avoid using comments in the registry between entries
    ; they are removed when sorted. instead use comments inside schemas only
    (is (not (str/includes? (z/root-string updated)
                            "; map level comment"))
        "top level comments not maintained")))

(def registry-fixture "
{:deployment [:map
               ;; useful comment
               [:name :string]]
 :operations [:map
               [:name :string]]
}
")

(deftest insert-schema-start
  (let [root (z/of-string registry-fixture)
        updated (insert-sorted-registry-schema root :about [:map])]
    (is (= '(:about :deployment :operations)
           (keys (edn/read-string (z/root-string updated))))
        "updated is sorted")
    ; note = doesn't check key sorting
    (is (= {:about      [:map]
            :deployment [:map
                         [:name :string]]
            :operations [:map
                         [:name :string]]}
           (edn/read-string (z/root-string updated)))
        "about entry present")))

(deftest insert-schema-between
  (let [root (z/of-string registry-fixture)
        updated (insert-sorted-registry-schema root :goal [:map])]
    (is (= '(:deployment :goal :operations)
           (keys (edn/read-string (z/root-string updated))))
        "updated is sorted")
    ; note = doesn't check key sorting
    (is (= {:deployment [:map
                         [:name :string]]
            :goal       [:map]                              ; << inserted between
            :operations [:map
                         [:name :string]]}
           (edn/read-string (z/root-string updated))))))

(deftest insert-schema-end
  (let [root (z/of-string registry-fixture)
        updated (insert-sorted-registry-schema root :save [:map])]
    (is (= '(:deployment :operations :save)
           (keys (edn/read-string (z/root-string updated))))
        "updated is sorted")
    ; note = doesn't check key sorting
    (is (= {:deployment [:map
                         [:name :string]]
            :operations [:map
                         [:name :string]]
            :save       [:map]}                             ; << inserted after
           (edn/read-string (z/root-string updated))))))

(deftest ^{:doc "can insert N map entries i.e. how it's called in sync-model"}
  insert-schema-end-twice
  (let [root (z/of-string registry-fixture)
        with-adds (reduce (fn [zloc new-key]
                            (-> zloc
                                (registry-form-loc)
                                (insert-sorted-registry-schema new-key [:map])))
                          root
                          [:save :source])]
    (is (= '(:deployment :operations :save :source)
           (keys (edn/read-string (z/root-string with-adds))))
        "updated is sorted")
    ; note = doesn't check key sorting
    (is (= {:deployment [:map
                         [:name :string]]
            :operations [:map
                         [:name :string]]
            :save       [:map]                              ; << inserted after
            :source     [:map]}
           (edn/read-string (z/root-string with-adds))))))

;;;; MAP SCHEMA SYNC

(def schema-fixture "
{:state [:map {:description \"context\"}
               [:deployment [:map]]
               ;; useful comment
               [:operations [:map
                             [:name :string]]]
         ]
}
")

(deftest ^{:doc "variations calling next-map-entry"} next-map-entries
  (let [root (z/of-string schema-fixture)
        map-subzip (-> root
                       (z/find z/next (comp #{:map} z/sexpr))
                       (z/up)
                       (z/subedit->))]
    (is (= '(:map {:description "context"})
           (take 2 (z/sexpr map-subzip)))
        "map loc correct")
    (is (nil? (-> map-subzip
                  (z/find z/next (comp #{:name} z/sexpr))
                  (next-map-entry)))
        "no entries are :operations")
    (is (= [:operations [:map [:name :string]]]
           (-> map-subzip
               (z/find z/next (comp #{:deployment} z/sexpr))
               (next-map-entry)
               (z/sexpr)))
        ":operations is after deployment")))

(deftest insert-entry-start
  (let [root (z/of-string schema-fixture)
        map-sub-zip (-> root
                        (z/find z/next (comp #{:map} z/sexpr))
                        (z/up)
                        (z/subedit->))
        updated (insert-sorted-map-entry map-sub-zip :about :string)]
    (is (= {:state [:map {:description "context"}
                    [:about :string]                        ; << inserted before
                    [:deployment [:map]]
                    [:operations [:map
                                  [:name :string]]]]}
           (edn/read-string (z/root-string updated))))))

(deftest insert-entry-between
  (let [root (z/of-string schema-fixture)
        map-sub-zip (-> root
                        (z/find z/next (comp #{:map} z/sexpr))
                        (z/up)
                        (z/subedit->))
        updated (insert-sorted-map-entry map-sub-zip :goal :string)]
    (is (= {:state [:map {:description "context"}
                    [:deployment [:map]]
                    [:goal :string]                         ; << inserted between
                    [:operations [:map
                                  [:name :string]]]]}
           (edn/read-string (z/root-string updated))))))

(deftest insert-entry-end
  (let [root (z/of-string schema-fixture)
        map-sub-zip (-> root
                        (z/find z/next (comp #{:map} z/sexpr))
                        (z/up)
                        (z/subedit->))
        updated (insert-sorted-map-entry map-sub-zip :save :string)]
    (is (= {:state [:map {:description "context"}
                    [:deployment [:map]]
                    [:operations [:map
                                  [:name :string]]]
                    [:save :string]]}                       ; << inserted between
           (edn/read-string (z/root-string updated))))))

(def map-registry-fixture
  (mr/registry
    {:my-map           [:map
                        [:a :int]
                        [:b :string]
                        [:c :nested-map]]
     :nested-map       [:map
                        [:d :int]
                        [:e :deep-map]]
     :deep-map         [:map
                        [:f :int]
                        [:g [:map-of :string :deeper-map]]]
     :deeper-map       [:map
                        [:h :int]]
     :lookup-of-maps   [:map-of :string :deeper-map]
     :nested-map-of    [:map
                        [:lookup :lookup-of-maps]]
     :many-maps        [:vector :deep-map]
     :many-nested-maps [:map
                        [:many [:vector {:description "props should not affect finding :deep-map"} :deep-map]]]
     :or-maps          [:or :typed-map1 :typed-map2]
     :orn-maps         [:orn
                        [:type1 :typed-map1]
                        [:type2 :typed-map2]
                        [:type3 [:map
                                 [:l [:vector :deep-map]]]]]
     :merge-maps       [:merge
                        :deep-map
                        :typed-map1
                        [:map
                         [:type2 :typed-map2]]]
     :and-maps         [:and
                        :deep-map
                        :typed-map1
                        [:map
                         [:type2 :typed-map2]]]
     :typed-maps       [:multi {:dispatch :type}
                        ; these 2 options use schema keys so are included
                        [:type1 :typed-map1]
                        [:type2 :typed-map2]
                        ; inline maps are difficult to name so are ignored (for now)
                        [:type3 [:map
                                 [:type [:= :multi3]]
                                 [:i [:vector :deep-map]]]]]
     :typed-map1       [:map
                        [:type [:= :multi1]]
                        [:j [:vector :deeper-map]]]
     :typed-map2       [:map
                        [:type [:= :multi3]]
                        [:k [:vector :deeper-map]]]}))

(deftest multi-maps
  (is (= #{:deeper-map :typed-map1 :typed-map2}
         (map-schema-keys map-registry-fixture :typed-maps))
      ; note :typed-maps not included because it is not a :map schema
      "keyword descendants found, ignoring inline maps")
  (is (= {:typed-map1 :typed-maps
          :typed-map2 :typed-maps}
         (map-multi-schemas map-registry-fixture))
      "multi hierarchy parents found, ignoring inline maps"))

(deftest or-orn
  (is (= #{:deeper-map :typed-map1 :typed-map2}
         (map-schema-keys map-registry-fixture :or-maps))
      "keyword descendants found, ignoring inline maps")
  (is (= #{:deeper-map :typed-map1 :typed-map2}
         (map-schema-keys map-registry-fixture :orn-maps))
      "keyword descendants found, ignoring inline maps"))

(deftest composed-nested
  (is (= #{:deep-map :deeper-map :typed-map1}
         (map-schema-keys map-registry-fixture :merge-maps))
      "nested named descendants found, ignoring inline maps")
  (is (= #{:deep-map :deeper-map :typed-map1}
         (map-schema-keys map-registry-fixture :and-maps))
      "nested named descendants found, ignoring inline maps"))

(deftest nested
  (is (= #{:deep-map :deeper-map}
         (map-schema-keys map-registry-fixture :deep-map))
      "map-of descendant found")
  (is (= #{:nested-map-of :deeper-map}
         (map-schema-keys map-registry-fixture :nested-map-of))
      "maps inside map-of descendant found")
  (is (= #{:deep-map :deeper-map}
         (map-schema-keys map-registry-fixture :many-maps))
      "vector descendants found")
  (is (= #{:deep-map :deeper-map :many-nested-maps}
         (map-schema-keys map-registry-fixture :many-nested-maps))
      "nested vector descendants found")
  (is (= #{:deep-map :deeper-map :nested-map}
         (map-schema-keys map-registry-fixture :nested-map))
      "entry and 2 descendant levels")
  (is (= #{:deep-map :deeper-map :my-map :nested-map}
         (map-schema-keys map-registry-fixture :my-map))
      "entry and 3 descendant levels"))
