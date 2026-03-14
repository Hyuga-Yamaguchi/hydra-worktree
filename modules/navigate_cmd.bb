(ns navigate-cmd
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [iterm2]))

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

(defn ccs []
  (let [paths (collect-paths)]
    (when (empty? paths)
      (println "No worktrees or repositories found.")
      (System/exit 1))
    (let [input   (str/join "\n" paths)
          result  (-> (p/process {:in input :out :string :err :inherit}
                                 "fzf" "--preview" "git -C {} log --oneline -5"
                                 "--header" "Select worktree / repository")
                      deref)]
      (if (zero? (:exit result))
        (let [selected (str/trim (:out result))]
          ;; Print the cd command for the shell wrapper to eval
          (println selected)
          ;; Update iTerm2 tab title
          (let [dir-name (-> (java.io.File. selected) .getName)]
            (iterm2/set-title dir-name)))
        (System/exit 1)))))
