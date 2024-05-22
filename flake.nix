{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    devenv.url = "github:cachix/devenv";
    clj-nix.url = "github:jlesquembre/clj-nix";
    clj-nix.inputs.nixpkgs.follows = "nixpkgs";
  };
  nixConfig = {
    extra-trusted-public-keys = "devenv.cachix.org-1:w1cLUi8dv3hnoSPGAuibQv+f9TZLr6cv/Hm9XgU50cw=";
    extra-substituters = "https://devenv.cachix.org";
  };
  outputs =
    {
      nixpkgs,
      devenv,
      clj-nix,
      ...
    }@inputs:
    {
      devShells = builtins.mapAttrs (system: pkgs: {
        default = devenv.lib.mkShell {
          inherit inputs pkgs;
          modules = [
            (
              { config, pkgs, ... }:
              {
                # https://devenv.sh/reference/options/
                packages = [
                  pkgs.jet
                  pkgs.neovim
                  pkgs.nixfmt-rfc-style
                  pkgs.vscode
                ];

                languages.clojure.enable = true;

                # N.B. picks up quotes and inline comments
                dotenv.enable = true;

                scripts.format.exec = ''
                  nixfmt *.nix
                '';
                scripts.lock.exec = ''
                  nix flake lock
                  nix run .#deps-lock
                '';
                scripts.update.exec = ''
                  nix flake update
                  nix run .#deps-lock
                '';
                scripts.build.exec = ''
                  nix build .
                '';

                enterShell = ''
                  # start editor
                  code .
                '';
              }
            )
          ];
        };
      }) nixpkgs.legacyPackages;
      packages = builtins.mapAttrs (system: pkgs: {
        deps-lock = clj-nix.packages.${system}.deps-lock;
        default = clj-nix.lib.mkCljApp {
          inherit pkgs;
          modules = [
            {
              projectSrc = ./.;
              name = "com.noblepayne/link-hoarder";
              main-ns = "noblepayne.link-hoarder";
              nativeImage.enable = true;
              nativeImage.extraNativeImageBuildArgs = [
                "--no-fallback"
                "--features=clj_easy.graal_build_time.InitClojureClasses"
                "--initialize-at-build-time=org.yaml.snakeyaml.DumperOptions$FlowStyle"
                "--initialize-at-build-time=com.vladsch.flexmark"
                "--enable-url-protocols=http"
                "--enable-url-protocols=https"
              ];
            }
          ];
        };
      }) nixpkgs.legacyPackages;
    };
}
