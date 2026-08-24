# Minimal image for running gitfsd inside a gVisor (runsc) sandbox or
# any other Linux container. See docs/gvisor.md for how to run it.
#
# Build from the repo root:
#   docker build -f deploy/Dockerfile -t gitfs:latest .

FROM golang:1.24-bookworm AS build
WORKDIR /src
COPY gitfs/go.mod gitfs/go.sum ./
RUN go mod download
COPY gitfs/ ./
RUN CGO_ENABLED=0 go build -o /out/gitfsd ./cmd/gitfsd

# git itself is a runtime dependency: gitfsd shells out to
# `git cat-file` / `git diff-tree` / `git rev-parse` rather than
# reimplementing git's object format. ca-certificates is needed for any
# promisor-remote fetch a partial clone triggers (e.g. https:// remotes).
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
        git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /out/gitfsd /usr/local/bin/gitfsd

ENTRYPOINT ["gitfsd"]
CMD ["--help"]
