(ns leiningen.pinkeys
  (:require
   [cemerick.pomegranate.aether :as aether]
   [clj-pgp.keyring :as keyring]
   [clj-pgp.core :as pgp]
   [clojure.java.io :as io]
   [leiningen.core.classpath :as classpath]
   [clj-pgp.signature :as pgp-sig]
   leiningen.core.main
   clojure.pprint
   [clojure.edn :as edn])
  (:import (org.sonatype.aether.resolution DependencyResolutionException)))

(def keyring-path (str (System/getProperty "user.home") "/.gnupg/pubring.gpg"))

;; A lot of code freely based on deps.clj:
;; <https://github.com/technomancy/leiningen/blob/master/src/leiningen/deps.clj>
(defn- walk-deps*
  ([deps f level]
   (apply concat (for [[dep subdeps] deps]
                   (cons
                    [dep (f dep level)]
                    (when subdeps
                      (walk-deps* subdeps f (inc level)))))))
  ([deps f]
   (walk-deps* deps f 0)))

(defn- walk-deps [deps f] (into {} (walk-deps* deps f)))

(defn- get-jar [project dep]
  (try (->> (aether/resolve-dependencies
             :repositories (:repositories project)
             :mirrors (:mirrors project)
             :coordinates [dep])
            (aether/dependency-files)
            (filter #(.endsWith (.getName %) ".jar"))
            (first))
       (catch DependencyResolutionException _)))

(defn- get-signature [project dep]
  (let [dep-map (assoc (apply hash-map (drop 2 dep))
                       ;; TODO: check pom signature too
                       :extension "jar.asc")
        dep (into (vec (take 2 dep)) (apply concat dep-map))]
    (try (->> (aether/resolve-dependencies
               :repositories (:repositories project)
               :mirrors (:mirrors project)
               :coordinates [dep])
              (aether/dependency-files)
              (filter #(.endsWith (.getName %) ".asc"))
              (first))
         (catch DependencyResolutionException _))))

(defn walk-project-deps
  [project f]
  (walk-deps (classpath/managed-dependency-hierarchy
              :dependencies
              :managed-dependencies
              project)
             f))

(defn load-keyring []
  (keyring/load-public-keyring (io/file keyring-path)))

(defn get-fingerprint
  [project keyring dep _]
  (let [jar-file (get-jar project dep)
        signature-file (get-signature project dep)]
    (if-let [signature (some->> signature-file
                                (pgp/decode-signatures)
                                first)]
      (if-let [key (some->> signature
                            (pgp/key-id)
                            (keyring/get-public-key keyring))]
        (when (and key (pgp-sig/verify jar-file signature key))
          (pgp/hex-fingerprint key))
        (leiningen.core.main/warn
         (str "Key not found: " (pgp/hex-id signature) " (" dep ")")))
      (leiningen.core.main/warn
       (str "Signature not found or broken for " dep)))))

(defn- map-map-keys [f coll] (into {} (for [[k v] coll] [(f k) v])))

(defn load-pins []
  (with-open [pin-file (java.io.PushbackReader. (io/reader "pinkeys.edn"))]
    (edn/read pin-file)))

(defn pinkeys
  "Pin dependency GPG keys."
  [project & args]
  (let [keyring (load-keyring)
        old-map (load-pins)
        new-map (->> (walk-project-deps project
                                        (partial get-fingerprint project keyring))
                     (map-map-keys first))
        merged-map
        (into {} (for [[dep new-fp] new-map]
                   (if-let [old-fp (get old-map dep)]
                     (if (= old-fp new-fp)
                       [dep old-fp]
                       (do
                         (println
                          (format (str "WARN: Fingerprint for %s does not match "
                                       "the pinned fingerprint.\n      "
                                       "actual = %s, pinned = %s")
                                  dep old-fp new-fp))
                         [dep old-fp]))
                     (do
                       (when new-fp
                         (println (format "New fingerprint for %s, pinning" dep)))
                       [dep new-fp]))))]
    (with-open [pin-file (io/writer "pinkeys.edn")]
      (.write pin-file (pr-str merged-map)))))