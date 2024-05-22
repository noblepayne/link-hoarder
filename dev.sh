#!/usr/bin/env sh
# workaround so devenv+flakes pick up .env when added to .gitignore
nix develop --impure path:.
