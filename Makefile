DOCKER_OUTPUT ?= $(CURDIR)/build/docker-distributions

.PHONY: build docker-build

build: docker-build

docker-build:
	./scripts/build-plugin-docker.sh "$(DOCKER_OUTPUT)"
