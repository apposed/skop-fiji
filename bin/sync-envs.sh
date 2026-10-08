#!/bin/sh
# Copies scikit-ops' environment recipes into this plugin, so a Fiji with no
# scikit-ops checkout can still build the environments. Run after the recipes
# change -- after a pin bump, above all -- and commit what it writes.
#
#   bin/sync-envs.sh [path/to/scikit-ops]     # default ../scikit-ops
set -e
here=$(cd "$(dirname "$0")/.." && pwd)
checkout=${1:-$here/../scikit-ops}
dest=$here/src/main/resources/org/apposed/skop/fiji/envs

rm -rf "$dest"
mkdir -p "$dest"
for recipe in "$checkout"/envs/*/pixi.toml; do
	env=$(basename "$(dirname "$recipe")")
	mkdir -p "$dest/$env"
	cp "$recipe" "$dest/$env/"
	if [ -f "$checkout/envs/$env/init.py" ]; then
		cp "$checkout/envs/$env/init.py" "$dest/$env/"
	fi
	echo "$env" >> "$dest/index.txt"
done
echo "Copied $(wc -l < "$dest/index.txt") environment(s) from $checkout"
