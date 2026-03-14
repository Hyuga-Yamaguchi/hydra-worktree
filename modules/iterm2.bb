(ns iterm2
  (:require [babashka.process :as p]))

(defn- applescript [script]
  (p/shell {:out :string :err :string}
           "osascript" "-e" script))

(defn new-tab [{:keys [wt-path branch no-network?]}]
  (let [cmd    (str "cd " (pr-str wt-path)
                    (when no-network? " && export HYDRA_NO_NETWORK=1")
                    " && claude")
        script (str "tell application \"iTerm2\"\n"
                    "  tell current window\n"
                    "    create tab with default profile\n"
                    "    tell current session\n"
                    "      write text " (pr-str cmd) "\n"
                    "      set name to " (pr-str (str "CC: " branch)) "\n"
                    "    end tell\n"
                    "  end tell\n"
                    "end tell")]
    (applescript script)))

(defn set-title [text]
  (print (str "\033]0;" text "\007"))
  (flush))
