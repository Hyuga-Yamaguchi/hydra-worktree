#!/usr/bin/env bb

(ns hydra-worktree.main
  (:require [clojure.string :as str]
            [worktree]
            [iterm2]
            [navigate-cmd]))

(defn cmd-ccw [{:keys [repo branch no-network?]}]
  (let [repo-root (if repo
                    (or (worktree/ghq-resolve repo)
                        (do (println (str "Error: no ghq repository found for: " repo))
                            (System/exit 1)))
                    (worktree/git-repo-root))
        _         (println (str "📦 Creating worktree for branch: " branch))
        wt-path   (worktree/create! repo-root branch)
        _         (println (str "📁 Worktree: " wt-path))]
    (iterm2/new-tab {:wt-path     wt-path
                     :branch      branch
                     :no-network? no-network?})
    (println (str "✅ Claude Code started in new tab for: " branch))))

(defn cmd-ccl []
  (let [worktrees (worktree/list-all)]
    (println (format "%-40s %-25s %-12s %s" "worktree" "branch" "uncommitted" "sandbox"))
    (doseq [{:keys [dir branch uncommitted sandbox]} worktrees]
      (println (format "%-40s %-25s %-12s %s" dir branch uncommitted sandbox)))))

(defn cmd-ccs []
  (navigate-cmd/ccs))

(defn usage []
  (println "Usage: bb main.bb <command> [args]")
  (println)
  (println "Commands:")
  (println "  ccw [<repo>] <branch> [--no-network]  Create worktree and start Claude Code")
  (println "  ccs                          Select worktree with fzf and cd")
  (println "  ccl                          List all worktrees with status")
  (System/exit 1))

(let [args    *command-line-args*
      command (first args)]
  (case command
    "ccw" (let [rest-args   (rest args)
                positional  (remove #(str/starts-with? % "--") rest-args)
                no-network? (some #{"--no-network"} rest-args)
                [repo branch] (case (count positional)
                                1 [nil (first positional)]
                                2 positional
                                (do (println "Error: branch name is required")
                                    (System/exit 1)))]
            (cmd-ccw {:repo        repo
                      :branch      branch
                      :no-network? (boolean no-network?)}))
    "ccl" (cmd-ccl)
    "ccs" (cmd-ccs)
    (usage)))
