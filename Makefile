# One command per task. CI calls the same targets (.github/workflows/ci.yml);
# CONTRIBUTING.md documents them.

COMPOSE  := docker compose -f docker/docker-compose.yml
SIDECARS := $(patsubst services/%/Dockerfile,%,$(wildcard services/*-auth/Dockerfile))
REMOTE   ?= origin

.PHONY: help dev db dev-backend dev-frontend deps test test-backend test-frontend test-e2e \
        test-sidecars test-sidecar-base test-sidecar build release

help:
	@echo "make dev                       PostgreSQL in Docker, backend on :8080, Vite on :5173"
	@echo "make test                      backend, frontend (unit and e2e), every sidecar"
	@echo "make test-backend [TEST=Class] backend Maven tests, or one class or method"
	@echo "make test-frontend             lint, typecheck, Vitest"
	@echo "make test-e2e                  Playwright in demo mode"
	@echo "make test-sidecar SIDECAR=x    one sidecar image and its tests (x: $(firstword $(SIDECARS)), ...)"
	@echo "make test-sidecars             the shared base, then every sidecar"
	@echo "make build [SERVICES=...]      the app and sidecar images, through Compose"
	@echo "make release VERSION=x.y.z     check CHANGELOG.md, tag, push the tag (CI publishes)"

# --- dev ---------------------------------------------------------------------

dev: db
	$(MAKE) -j2 dev-backend dev-frontend

# Reuses the container across runs; its data stays until `docker rm -f picsou-db`.
db:
	docker start picsou-db 2>/dev/null || docker run -d --name picsou-db -p 127.0.0.1:5432:5432 \
	  -e POSTGRES_DB=picsou -e POSTGRES_USER=picsou -e POSTGRES_PASSWORD=picsou postgres:16-alpine
	for i in $$(seq 30); do docker exec picsou-db pg_isready -q -U picsou && exit 0; sleep 1; done; echo "picsou-db is not ready"; exit 1

dev-backend: | backend/.local/dev.env
	. ./backend/.local/dev.env && cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Secrets the backend refuses to start without, generated once per checkout (gitignored).
# Kept between runs: a new CRYPTO_ENCRYPTION_KEY could not decrypt the stored ones.
# Values already in the environment win.
backend/.local/dev.env:
	mkdir -p backend/.local
	set -e; umask 077; jwt=$$(openssl rand -base64 48); key=$$(openssl rand -base64 32); \
	  printf 'export JWT_SECRET="$${JWT_SECRET:-%s}"\nexport CRYPTO_ENCRYPTION_KEY="$${CRYPTO_ENCRYPTION_KEY:-%s}"\n' "$$jwt" "$$key" > $@.tmp; \
	  mv $@.tmp $@

dev-frontend: | frontend/node_modules
	cd frontend && bun run dev

# `deps` reinstalls after a lockfile change; other targets only install when missing.
deps frontend/node_modules:
	cd frontend && bun install --frozen-lockfile

# --- test --------------------------------------------------------------------

test: test-backend test-frontend test-e2e test-sidecars

# The suite never calls a real sidecar: any key will do. SOFIDY_PYTHON=python3 also
# runs the Java/Python Sofidy contract test (needs `pip install -r services/base/requirements.txt`).
test-backend: export APP_SIDECAR_API_KEY ?= test-only
test-backend: export PYTHONPATH := $(CURDIR)/services/base
test-backend:
	cd backend && mvn -B test $(if $(SOFIDY_PYTHON),-Dsofidy.contract.python=$(SOFIDY_PYTHON)) $(if $(TEST),-Dtest=$(TEST))

test-frontend: | frontend/node_modules
	cd frontend && bun run lint && bun run typecheck && bun run test

# CI adds PLAYWRIGHT_DEPS=--with-deps: a fresh runner lacks Chromium's system libraries.
test-e2e: | frontend/node_modules
	cd frontend && bunx playwright install $(PLAYWRIGHT_DEPS) chromium && VITE_DEMO_MODE=true bun run test:e2e

test-sidecars: test-sidecar-base
	for sidecar in $(SIDECARS); do $(MAKE) --no-print-directory test-sidecar SIDECAR=$$sidecar || exit 1; done

# The Camoufox base includes the Python one: discovery runs every services/base test.
test-sidecar-base:
	docker build --target camoufox --tag picsou-sidecar-camoufox services/base
	docker run --rm --entrypoint python --volume "$(CURDIR)/services:/services:ro" --workdir /services/base picsou-sidecar-camoufox -m unittest discover -v

# The tests run in the built image, from the read-only checkout, with the test-only
# helpers the image does not ship mounted back in. ARCH is the architecture the
# image must report (CI passes the runner's; macOS calls aarch64 arm64).
test-sidecar: BASE = $(shell sed -n 's/^ARG SIDECAR_BASE=picsou-sidecar-//p' services/$(SIDECAR)/Dockerfile 2>/dev/null)
test-sidecar: IMAGE = picsou-$(SIDECAR):test
test-sidecar: ARCH ?= $(subst arm64,aarch64,$(shell uname -m))
test-sidecar:
	@test -n "$(BASE)" || { echo "SIDECAR must be one of: $(SIDECARS)"; exit 1; }
	docker build --target $(BASE) --tag picsou-sidecar-$(BASE) services/base
	docker build --tag $(IMAGE) services/$(SIDECAR)
	docker run --rm --network none --entrypoint sh $(IMAGE) -c 'test "$$(id -u)" -ne 0 && test "$$PICSOU_SIDECAR_BASE" = sidecar-$(BASE) && python -c "import main"'
	$(if $(filter camoufox,$(BASE)),docker run --rm --network none --entrypoint sh $(IMAGE) -c 'test "$$(uname -m)" = $(ARCH)')
	$(if $(filter camoufox,$(BASE)),docker run --rm --network none --entrypoint sh $(IMAGE) /opt/sidecar-base/entrypoint.sh python /opt/sidecar-base/smoke_camoufox.py)
	docker run --rm --ipc=host --entrypoint python --env PYTHONDONTWRITEBYTECODE=1 --env APP_SIDECAR_API_KEY=test-key --env CORUM_BROWSER_TESTS=1 \
	  --volume "$(CURDIR)/services/base/picsou_sidecar/contract.py:/opt/sidecar-base/lib/picsou_sidecar/contract.py:ro" \
	  --volume "$(CURDIR)/services/base/euro_information/testing.py:/opt/sidecar-base/lib/euro_information/testing.py:ro" \
	  --volume "$(CURDIR)/services:/src/services:ro" --workdir /src/services/$(SIDECAR) $(IMAGE) -m unittest discover -v

# --- build and release -------------------------------------------------------

# Every image Compose builds (app, sidecars and their bases), or only SERVICES, resolved
# without a profile as a user's install does (the bases are build-only services).
build:
	$(COMPOSE) $(if $(SERVICES),,--profile all-connectors) build $(SERVICES)

# Pushing a version tag runs CI, which publishes the images and the chart once green.
# REMOTE is the repository that publishes (REMOTE=upstream from a fork).
# Only a commit already on $(REMOTE)/main is tagged, so the tag never publishes
# unreviewed work.
release:
	@echo "$(VERSION)" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$$' \
	  || { echo "usage: make release VERSION=x.y.z (no leading v)"; exit 1; }
	@grep -q '^## \[$(VERSION)\]' CHANGELOG.md \
	  || { echo "CHANGELOG.md has no '## [$(VERSION)]' section"; exit 1; }
	@test -z "$$(git status --porcelain)" || { echo "the working tree has uncommitted changes"; exit 1; }
	git fetch $(REMOTE) main
	@git merge-base --is-ancestor HEAD FETCH_HEAD || { echo "HEAD is not on $(REMOTE)/main"; exit 1; }
	@test -z "$$(git ls-remote --tags $(REMOTE) refs/tags/$(VERSION))" || { echo "$(REMOTE) already has tag $(VERSION)"; exit 1; }
	git tag $(VERSION)
	git push $(REMOTE) $(VERSION)
