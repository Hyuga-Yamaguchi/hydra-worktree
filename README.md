# hydra-worktree

Automation tool for Git worktree + Claude Code.
Creates a worktree per branch, cd into it, and launches Claude Code.

## Dependencies

- [Babashka](https://github.com/babashka/babashka) (`bb`)
- [ghq](https://github.com/x-motemen/ghq) — repository management
- [gwq](https://github.com/because-and/gwq) — worktree management (fallback: `git worktree`)
- [fzf](https://github.com/junegunn/fzf) — interactive selection

## Setup

Add to `.zshrc`:

```zsh
_hydra="bb --config $HOME/.config/hydra-worktree/bb.edn $HOME/.config/hydra-worktree/main.bb"
function hw() {
  case "$1" in
    cd|checkout|co)
      local target
      target="$(eval "$_hydra" "$@")"
      [ -n "$target" ] && cd "$target" && claude
      ;;
    *)
      eval "$_hydra" "$@"
      ;;
  esac
}
```

## Commands

### `hw checkout [<repo>] <branch>`

Create a worktree, cd into it, and start Claude Code.

Alias: `hw co`

```bash
# Specify a ghq-managed repository
hw co ags-fujitv-corp-com-ai fix/ui

# Interactive selection (fzf for repo, then branch)
hw co
```

**How it works:**

1. Resolve repository path via `ghq` (when repo is specified)
2. Create worktree with `gwq add` (falls back to `git worktree add` on failure)
3. cd into the worktree and start `claude`

### `hw cd [<repo> <branch>]`

cd into an existing worktree and start Claude Code.

```bash
# Direct
hw cd my-repo feat/branch

# Interactive selection with fzf
hw cd
```

### `hw list`

List all worktrees globally with status.

Alias: `hw ls`

```
worktree                                          branch                    uncommitted  sandbox
github.com/org/repo=fix-bug                       fix/bug                   3            active
github.com/org/repo=feat-new                      feat/new                  0            no sandbox
```

### `hw destroy <repo> <branch>`

Remove a worktree and its branch. If no args, opens an interactive fzf selector.

Alias: `hw rm`

```bash
# Remove by repo and branch
hw rm my-repo feat/old-branch

# Interactive selection
hw rm
```

### `hw help`

Show help message with all available commands.

## Structure

```
hydra-worktree/
├── bb.edn              # Babashka config
├── main.bb             # Entry point / CLI routing
└── modules/
    ├── worktree.bb     # Worktree creation, lookup, listing, destroy
    └── navigate_cmd.bb # cd (fzf selection)
```
