#!/bin/sh

set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output_dir=${1:-"$project_dir/build/docker-distributions"}

mkdir -p "$output_dir"

docker buildx build \
	--target artifact \
	--output "type=local,dest=$output_dir" \
	"$project_dir"

printf 'Plugin package written to %s\n' "$output_dir"
