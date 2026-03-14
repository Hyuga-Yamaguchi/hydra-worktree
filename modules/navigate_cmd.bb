(ns navigate-cmd
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn- sh-lines [cmd]
  (let [result (-> (apply p/process {:out :string :err :string} cmd)
                   deref
                   :out
                   str/trim)]
    (if (str/blank? result) [] (str/split-lines result))))

(defn- collect-paths []
  (let [ghq-paths (try (sh-lines ["ghq" "list" "--full-path"]) (catch Exception _ []))
        gwq-paths (try (sh-lines ["gwq" "list" "--full-path"]) (catch Exception _ []))
        git-wt    (try (let [lines (sh-lines ["git" "worktree" "list" "--porcelain"])]
                         (->> lines
                              (filter #(str/starts-with? % "worktree "))
                              (map #(subs % (count "worktree ")))))
                       (catch Exception _ []))]
    (->> (concat ghq-paths gwq-paths git-wt)
         distinct
         sort
         vec)))

(defn- ghq-root []
  (try (let [result (-> (apply p/process {:out :string :err :string} ["ghq" "root"])
                        deref :out str/trim)]
         (when-not (str/blank? result) result))
       (catch Exception _ nil)))

(defn- shorten-path [ghq-root path]
  (if (and ghq-root (str/starts-with? path (str ghq-root "/")))
    (subs path (inc (count ghq-root)))
    (-> (java.io.File. path) .getName)))

(defn ccs []
  (let [paths (collect-paths)]
    (when (empty? paths)
      (println "No worktrees or repositories found.")
      (System/exit 1))
    (let [root       (ghq-root)
          path-map   (zipmap (map #(shorten-path root %) paths) paths)
          input      (str/join "\n" (keys path-map))
          result     (-> (p/process {:in input :out :string :err :inherit}
                                    "fzf" "--preview" (str "git -C "
                                                           (if root (str root "/{}") "{}")
                                                           " log --oneline -5 2>/dev/null || echo 'not a git repo'")
                                    "--header" "Select worktree / repository")
                         deref)]
      (if (zero? (:exit result))
        (let [selected  (str/trim (:out result))
              full-path (get path-map selected selected)]
          (println full-path))
        (System/exit 1)))))
