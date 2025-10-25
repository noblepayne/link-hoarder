#!/usr/bin/env sh
# workaround so devenv+flakes pick up .env when added to .gitignore
# from: https://mtlynch.io/notes/use-nix-flake-without-git/
NIXPKGS_ALLOW_UNFREE=1 nix develop --impure path:. -c $SHELL
