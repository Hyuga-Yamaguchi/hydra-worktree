(ns worktree
  "Git worktree operations. gwq → git worktree fallback."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- sh-out [cmd]
  (-> (apply p/process {:out :string :err :string} cmd)
      deref :out str/trim))

(defn- sh-ok? [cmd opts]
  (let [proc (apply p/process (merge {:out :string :err :string} opts) cmd)]
    (zero? (:exit @proc))))

(defn git-repo-root []
  (sh-out ["git" "rev-parse" "--show-toplevel"]))

(defn ghq-resolve
  "Resolve a ghq repository by query. Returns the full path.
   If multiple matches, uses fzf to select. If none, returns nil."
  [query]
  (let [result @(apply p/process {:out :string :err :string}
                       ["ghq" "list" "--full-path"])
        all-paths (when (zero? (:exit result))
                    (->> (str/split-lines (str/trim (:out result)))
                         (filter #(str/includes? % query))
                         vec))]
    (cond
      (empty? all-paths) nil
      (= 1 (count all-paths)) (first all-paths)
      :else
      (let [input  (str/join "\n" all-paths)
            fzf    @(p/process {:in input :out :string :err :inherit}
                               "fzf" "--header" "Select repository")]
        (when (zero? (:exit fzf))
          (str/trim (:out fzf)))))))

(defn- branch-exists? [repo-root branch]
  (sh-ok? ["git" "rev-parse" "--verify" (str "refs/heads/" branch)]
          {:dir repo-root}))

(defn- gwq-find
  "Find existing worktree for a branch via gwq list --json."
  [branch]
  (try
    (let [result @(apply p/process {:out :string :err :string}
                         ["gwq" "list" "-g" "--json"])
          entries (when (zero? (:exit result))
                    (json/parse-string (:out result) true))]
      (->> entries
           (filter #(= (:branch %) branch))
           first :path))
    (catch Exception _ nil)))

(defn- compute-path [repo-root branch]
  (let [parent    (-> (java.io.File. repo-root) .getParent)
        repo-name (-> (java.io.File. repo-root) .getName)
        safe-br   (str/replace branch "/" "-")]
    (str parent "/" repo-name "=" safe-br)))

(defn create!
  "Create a worktree. Tries gwq first, falls back to git worktree.
   Reuses existing worktree if found."
  [repo-root branch]
  (or (gwq-find branch)

      (let [exists?    (branch-exists? repo-root branch)
            gwq-cmd    (if exists?
                         ["gwq" "add" branch]
                         ["gwq" "add" "-b" branch])
            gwq-result @(apply p/process {:out :string :err :string :dir repo-root}
                               gwq-cmd)]
        (or (gwq-find branch)

            (do
              (when-not (zero? (:exit gwq-result))
                (let [err-msg (str/trim (:err gwq-result))]
                  (when-not (str/blank? err-msg)
                    (println (str "⚠ gwq: " (first (str/split-lines err-msg))))))
                (println "Falling back to git worktree..."))
              (let [path (compute-path repo-root branch)]
                (when-not (.exists (java.io.File. path))
                  (let [git-cmd (if exists?
                                  ["git" "worktree" "add" path branch]
                                  ["git" "worktree" "add" "-b" branch path])]
                    (apply p/shell {:dir repo-root} git-cmd)))
                path))))))

(defn list-all []
  "List all worktrees for current repo as [{:path :branch :dir :uncommitted :sandbox}]."
  (let [raw (sh-out ["git" "worktree" "list" "--porcelain"])]
    (for [wt    (str/split raw #"\n\n")
          :let  [lines       (str/split-lines wt)
                 path-line   (first (filter #(str/starts-with? % "worktree ") lines))
                 branch-line (first (filter #(str/starts-with? % "branch ") lines))]
          :when (and path-line branch-line)]
      (let [path   (subs path-line (count "worktree "))
            branch (-> (subs branch-line (count "branch "))
                       (str/replace "refs/heads/" ""))
            uncommitted (try
                          (let [status (sh-out ["git" "-C" path "status" "--porcelain"])]
                            (if (str/blank? status) 0
                                (count (str/split-lines status))))
                          (catch Exception _ "?"))
            has-sandbox? (->> (file-seq (java.io.File. "/tmp"))
                              (filter #(let [n (.getName %)]
                                         (and (str/starts-with? n "cc-sandbox-")
                                              (str/ends-with? n ".sb"))))
                              (some #(str/includes? (slurp %) path)))]
        {:path        path
         :dir         (-> (java.io.File. path) .getName)
         :branch      branch
         :uncommitted uncommitted
         :sandbox     (if has-sandbox? "🔒 active" "⚠ no sandbox")}))))
