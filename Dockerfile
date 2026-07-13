# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY . .

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
	./gradlew --no-daemon clean check buildPlugin && \
	cd build/distributions && \
	sha256sum *.zip > SHA256SUMS.txt

FROM scratch AS artifact

COPY --from=build /workspace/build/distributions/ /
