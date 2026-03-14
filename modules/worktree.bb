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
  "Resolve a ghq repository by query (basename exact match). Returns the full path or nil."
  [query]
  (let [result @(apply p/process {:out :string :err :string}
                       ["ghq" "list" "--full-path"])
        all-paths (when (zero? (:exit result))
                    (->> (str/split-lines (str/trim (:out result)))
                         (filter #(= (-> (java.io.File. %) .getName) query))
                         vec))]
    (cond
      (empty? all-paths) nil
      (= 1 (count all-paths)) (first all-paths)
      :else
      (let [ghq-root (sh-out ["ghq" "root"])
            display  (map #(subs % (inc (count ghq-root))) all-paths)
            input    (str/join "\n" display)
            fzf      @(p/process {:in input :out :string :err :inherit}
                                 "fzf" "--header" "Select repository")]
        (when (zero? (:exit fzf))
          (str ghq-root "/" (str/trim (:out fzf))))))))

(defn checkout-interactive
  "Select repo and branch interactively with fzf. Returns {:repo-root :branch} or exits."
  []
  (let [result   @(apply p/process {:out :string :err :string}
                         ["ghq" "list" "--full-path"])
        all-paths (when (zero? (:exit result))
                    (->> (str/split-lines (str/trim (:out result)))
                         (remove #(str/includes? (.getName (java.io.File. %)) "="))
                         vec))]
    (when (empty? all-paths)
      (println "No repositories found.")
      (System/exit 1))
    (let [ghq-root  (sh-out ["ghq" "root"])
          display   (map #(subs % (inc (count ghq-root))) all-paths)
          path-map  (zipmap display all-paths)
          input     (str/join "\n" display)
          repo-fzf  @(p/process {:in input :out :string :err :inherit}
                                "fzf" "--header" "Select repository")]
      (when-not (zero? (:exit repo-fzf))
        (System/exit 1))
      (let [selected  (str/trim (:out repo-fzf))
            repo-root (get path-map selected)
            branches  (sh-out ["git" "-C" repo-root "branch" "--format=%(refname:short)"])
            br-fzf    @(p/process {:in branches :out :string :err :inherit}
                                  "fzf" "--header" (str "Select branch (" selected ")")
                                  "--print-query")]
        (when-not (zero? (:exit br-fzf))
          (System/exit 1))
        (let [lines  (str/split-lines (str/trim (:out br-fzf)))
              branch (last lines)]
          {:repo-root repo-root :branch branch})))))

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

(defn resolve-path
  "Resolve worktree path from repo name and branch. Returns path or nil."
  [repo branch]
  (when-let [repo-root (ghq-resolve repo)]
    (let [path (compute-path repo-root branch)]
      (when (.exists (java.io.File. path))
        path))))

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

(defn- gwq-entries-global []
  "Get all worktrees globally via gwq list -g --json."
  (try
    (let [result @(apply p/process {:out :string :err :string}
                         ["gwq" "list" "-g" "--json"])
          entries (when (zero? (:exit result))
                    (json/parse-string (:out result) true))]
      (vec (map #(select-keys % [:path :branch]) entries)))
    (catch Exception _ [])))

(defn- confirm! [message]
  (print (str message " [y/N] "))
  (flush)
  (let [answer (str/trim (read-line))]
    (when-not (#{"y" "Y" "yes"} answer)
      (println "Aborted.")
      (System/exit 1))))

(defn destroy!
  "Remove a worktree and its branch by repo-root path and branch name."
  [repo-root branch]
  (let [path      (compute-path repo-root branch)
        repo-name (.getName (java.io.File. repo-root))]
    (confirm! (str "Remove worktree and branch '" branch "' (" repo-name ")?"))
    ;; 1. Remove worktree (gwq or git)
    (let [gwq-result @(apply p/process {:out :string :err :string}
                             ["gwq" "remove" "-g" "--force" (str repo-name ":" branch)])]
      (when-not (zero? (:exit gwq-result))
        (if (.exists (java.io.File. path))
          (p/shell {:dir repo-root} "git" "worktree" "remove" "--force" path)
          (do (println (str "Error: worktree not found: " path))
              (System/exit 1)))))
    ;; 2. Always delete the branch
    (let [br-result @(apply p/process {:out :string :err :string :dir repo-root}
                            ["git" "branch" "-D" branch])]
      (when-not (zero? (:exit br-result))
        (println (str "⚠ Branch not deleted: " (str/trim (:err br-result))))))
    (println (str "✅ Worktree and branch removed: " branch))))

(defn destroy-interactive!
  "Select a worktree with fzf and remove it."
  []
  (let [entries  (gwq-entries-global)
        ghq-root (try (sh-out ["ghq" "root"]) (catch Exception _ nil))
        shorten  (fn [path]
                   (if (and ghq-root (str/starts-with? path (str ghq-root "/")))
                     (subs path (inc (count ghq-root)))
                     (-> (java.io.File. path) .getName)))]
    (if (empty? entries)
      (do (println "No worktrees to remove.")
          (System/exit 1))
      (let [lines  (map #(str (:branch %) "\t" (shorten (:path %))) entries)
            input  (str/join "\n" lines)
            result @(p/process {:in input :out :string :err :inherit}
                               "fzf" "--header" "Select worktree to destroy")]
        (if (zero? (:exit result))
          (let [selected (str/trim (:out result))
                branch   (first (str/split selected #"\t"))
                entry     (first (filter #(= (:branch %) branch) entries))
                wt-path   (:path entry)
                wt-file   (java.io.File. wt-path)
                parent    (.getParent wt-file)
                repo-name (first (str/split (.getName wt-file) #"="))
                repo-root (str parent "/" repo-name)]
            (destroy! repo-root branch))
          (System/exit 1))))))

(defn list-all []
  "List all worktrees globally via gwq."
  (let [ghq-root (try (sh-out ["ghq" "root"]) (catch Exception _ nil))
        entries  (gwq-entries-global)]
    (for [{:keys [path branch]} entries]
      (let [display     (if (and ghq-root (str/starts-with? path (str ghq-root "/")))
                          (subs path (inc (count ghq-root)))
                          (.getName (java.io.File. path)))
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
        {:path        display
         :branch      branch
         :uncommitted uncommitted
         :sandbox     (if has-sandbox? "🔒 active" "⚠ no sandbox")}))))
