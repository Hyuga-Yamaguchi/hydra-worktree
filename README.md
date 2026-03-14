# hydra-worktree

Automation tool for Git worktree + Claude Code.
Creates a worktree per branch and launches Claude Code in a new iTerm2 tab.

## Dependencies

- [Babashka](https://github.com/babashka/babashka) (`bb`)
- [ghq](https://github.com/x-motemen/ghq) — repository management
- [gwq](https://github.com/because-and/gwq) — worktree management (fallback: `git worktree`)
- [fzf](https://github.com/junegunn/fzf) — interactive selection
- iTerm2

## Setup

Add to `.zshrc`:

```zsh
_hydra="bb --config $HOME/.config/hydra-worktree/bb.edn $HOME/.config/hydra-worktree/main.bb"
function ccw()           { eval "$_hydra" ccw "$@" }
function ccw-offline()   { eval "$_hydra" ccw "$1" --no-network }
function ccs()           { cd "$(eval "$_hydra" ccs)" }
function ccl()           { eval "$_hydra" ccl }
```

## Commands

### `ccw [<repo>] <branch> [--no-network]`

Create a worktree and launch Claude Code in a new iTerm2 tab.

```bash
# Specify a ghq-managed repository
ccw my-repo fix/ui

# Use a branch from the current repository
ccw feat/new-feature

# With network restrictions
ccw my-repo fix/bug --no-network
```

**How it works:**

1. Resolve repository path via `ghq` (when repo is specified)
2. Create worktree with `gwq add` (falls back to `git worktree add` on failure)
3. Open a new iTerm2 tab and start `claude` in the worktree directory

### `ccl`

List all worktrees with status.

```
worktree                                 branch                    uncommitted  sandbox
/path/to/repo=fix-bug                    fix/bug                   3            🔒 active
/path/to/repo=feat-new                   feat/new                  0            ⚠ no sandbox
```

### `ccs`

Select a worktree or repository with `fzf` and `cd` into it.
Aggregates paths from ghq, gwq, and git worktree.

## Structure

```
hydra-worktree/
├── bb.edn              # Babashka config
├── main.bb             # Entry point / CLI routing
└── modules/
    ├── worktree.bb     # Worktree creation, lookup, listing
    ├── iterm2.bb       # iTerm2 AppleScript control
    └── navigate_cmd.bb # ccs (fzf selection + cd)
```
