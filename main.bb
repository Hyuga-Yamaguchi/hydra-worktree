#!/usr/bin/env bb

(ns hydra-worktree.main
  (:require [clojure.string :as str]
            [worktree]
            [navigate-cmd]))

(defn cmd-checkout [{:keys [repo repo-root branch no-network?]}]
  (let [repo-root (or repo-root
                      (if repo
                        (or (worktree/ghq-resolve repo)
                            (do (binding [*out* *err*]
                                  (println (str "Error: no ghq repository found for: " repo)))
                                (System/exit 1)))
                        (worktree/git-repo-root)))
        _       (binding [*out* *err*]
                  (println (str "📦 Creating worktree for branch: " branch)))
        wt-path (worktree/create! repo-root branch)
        _       (binding [*out* *err*]
                  (println (str "📁 Worktree: " wt-path)))]
    (println wt-path)))

(defn cmd-list []
  (let [worktrees (worktree/list-all)]
    (if (empty? worktrees)
      (println "No worktrees found.")
      (do (println (format "%-60s %-25s %-12s %s" "worktree" "branch" "uncommitted" "sandbox"))
          (doseq [{:keys [path branch uncommitted sandbox]} worktrees]
            (println (format "%-60s %-25s %-12s %s" path branch uncommitted sandbox)))))))

(defn cmd-cd [{:keys [repo branch]}]
  (if (and repo branch)
    (let [path (worktree/resolve-path repo branch)]
      (if path
        (println path)
        (do (binding [*out* *err*]
              (println (str "Error: worktree not found for " repo " " branch)))
            (System/exit 1))))
    (navigate-cmd/ccs)))

(defn cmd-destroy [{:keys [repo branch]}]
  (if (and repo branch)
    (let [repo-root (or (worktree/ghq-resolve repo)
                        (do (println (str "Error: no ghq repository found for: " repo))
                            (System/exit 1)))]
      (worktree/destroy! repo-root branch))
    (worktree/destroy-interactive!)))

(defn usage []
  (println "hydra-worktree — Git worktree + Claude Code automation")
  (println)
  (println "Usage: hw <command> [args]")
  (println)
  (println "Commands:")
  (println "  checkout [<repo>] <branch> [--no-network]  Create worktree and start Claude Code")
  (println "  list                                       List all worktrees with status")
  (println "  cd                                         Select worktree with fzf and cd")
  (println "  destroy <repo> <branch>                    Remove a worktree (interactive if no args)")
  (println "  help                                       Show this help")
  (println)
  (println "Aliases:")
  (println "  co    → checkout")
  (println "  ls    → list")
  (println "  rm    → destroy")
  (println)
  (println "Examples:")
  (println "  hw checkout feat/new-feature")
  (println "  hw co my-repo fix/bug --no-network")
  (println "  hw ls")
  (println "  hw cd")
  (println "  hw destroy my-repo feat/old-branch")
  (println "  hw rm")
  (println))

(let [args    *command-line-args*
      command (first args)]
  (case command
    ("checkout" "co")
    (let [rest-args   (rest args)
          positional  (vec (remove #(str/starts-with? % "--") rest-args))
          no-network? (some #{"--no-network"} rest-args)]
      (case (count positional)
        0 (let [{:keys [repo-root branch]} (worktree/checkout-interactive)]
            (cmd-checkout {:repo-root   repo-root
                           :branch      branch
                           :no-network? (boolean no-network?)}))
        1 (cmd-checkout {:branch      (first positional)
                         :no-network? (boolean no-network?)})
        2 (cmd-checkout {:repo        (first positional)
                         :branch      (second positional)
                         :no-network? (boolean no-network?)})
        (do (println "Usage: hw checkout [<repo>] <branch>")
            (System/exit 1))))

    ("list" "ls") (cmd-list)
    "cd"
    (let [rest-args (rest args)
          [repo branch] (case (count rest-args)
                          0 [nil nil]
                          2 rest-args
                          (do (println "Usage: hw cd [<repo> <branch>]")
                              (System/exit 1)))]
      (cmd-cd {:repo repo :branch branch}))

    ("destroy" "rm")
    (let [rest-args  (rest args)
          [repo branch] (case (count rest-args)
                          0 [nil nil]
                          2 rest-args
                          (do (println "Usage: hw destroy <repo> <branch>")
                              (System/exit 1)))]
      (cmd-destroy {:repo repo :branch branch}))

    ("help" "--help" "-h") (do (usage) (System/exit 0))

    (do (when command
          (println (str "Unknown command: " command))
          (println))
        (usage)
        (System/exit 1))))
