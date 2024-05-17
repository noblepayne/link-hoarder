{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    clj-nix.url = "github:jlesquembre/clj-nix";
  };
  outputs =
    { nixpkgs, clj-nix, ... }@inputs:
    {
      packages = builtins.mapAttrs (system: pkgs: {
        deps-lock = clj-nix.packages.${system}.deps-lock;
        default = clj-nix.lib.mkCljApp {
          inherit pkgs;
          modules = [
            {
              projectSrc = ./.;
              name = "com.noblepayne/link-hoarder";
              main-ns = "com.noblepayne.link-hoarder";
              nativeImage.enable = false;
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
