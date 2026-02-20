set -e

if [ -z "$__DEVBOX_SKIP_INIT_HOOK_b46f5bb3f4385b1b7aafe226dda9f96b105f89fe5963dfecd27f520ae4e8c85e" ]; then
    . "/Volumes/SSD2TB/work/antigravity/rr-sidecar/.devbox/gen/scripts/.hooks.sh"
fi

mvn quarkus:dev
