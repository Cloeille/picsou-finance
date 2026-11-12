# One command per task. CI calls the same targets (.github/workflows/ci.yml);
# CONTRIBUTING.md documents them.

COMPOSE  := docker compose -f docker/docker-compose.yml
SIDECARS := $(patsubst services/%/Dockerfile,%,$(wildcard services/*-auth/Dockerfile))
REMOTE   ?= origin

.PHONY: help dev db dev-backend dev-frontend format deps test test-backend test-frontend lint-frontend unit-frontend test-e2e \
        test-sidecars test-sidecar-base test-sidecar build release

help:
	@echo "make dev                       PostgreSQL in Docker, backend on :8080, Vite on :5173"
	@echo "make format                    Prettier on the frontend sources"
	@echo "make test                      backend, frontend (unit and e2e), every sidecar"
	@echo "make test-backend [TEST=Class] backend Maven tests, or one class or method"
	@echo "make test-frontend             lint, typecheck, Vitest"
	@echo "make lint-frontend             ESLint (zero warnings) and TypeScript"
	@echo "make unit-frontend [SHARD=i/n] Vitest, optionally one shard (VITEST_JSON=file writes a JSON report)"
	@echo "make test-e2e                  Playwright in demo mode"
	@echo "make test-sidecar SIDECAR=x    one sidecar image and its tests (x: $(firstword $(SIDECARS)), ...)"
	@echo "make test-sidecars             the shared base, then every sidecar"
	@echo "make build [SERVICES=...]      the app and sidecar images, through Compose"
	@echo "make release VERSION=x.y.z     check CHANGELOG.md, tag, push the tag (CI publishes)"

# --- dev ---------------------------------------------------------------------

dev: db
	$(MAKE) -j2 dev-backend dev-frontend

# Reuses the container across runs; its data stays until `docker rm -f picsou-db`.
# The probe logs in with the backend's credentials (application.yml) over TCP on the
# container's address: the socket and localhost trust any user, and pg_isready also
# answers while the first-boot init still runs on the socket.
# The container also holds the backend's dev secrets, generated when it is created:
# every checkout that uses this database gets the CRYPTO_ENCRYPTION_KEY its data was
# encrypted with, and recreating the container renews the key with the data.
db:
	@docker start picsou-db 2>/dev/null || { \
	  jwt=$$(openssl rand -base64 48) && key=$$(openssl rand -base64 32) || exit 1; \
	  docker run -d --name picsou-db -p 127.0.0.1:5432:5432 \
	    -e POSTGRES_DB=picsou -e POSTGRES_USER=picsou -e POSTGRES_PASSWORD=picsou \
	    -e PICSOU_DEV_JWT_SECRET="$$jwt" -e PICSOU_DEV_CRYPTO_ENCRYPTION_KEY="$$key" postgres:16-alpine; \
	}
	@for i in $$(seq 30); do \
	  err=$$(docker exec -e PGPASSWORD=picsou picsou-db sh -c 'psql -h "$$(hostname -i | cut -d" " -f1)" -U picsou -d picsou -Atqc "select 1"' 2>&1) && exit 0; \
	  sleep 1; \
	done; \
	echo "picsou-db does not accept user picsou, password picsou on database picsou: $$err"; \
	echo "If it was created with other settings, 'docker rm -f picsou-db' recreates it (its data is lost)."; \
	exit 1

# Values already in the environment win over the ones picsou-db holds.
dev-backend:
	@JWT_SECRET="$${JWT_SECRET:-$$(docker exec picsou-db printenv PICSOU_DEV_JWT_SECRET 2>/dev/null)}"; \
	CRYPTO_ENCRYPTION_KEY="$${CRYPTO_ENCRYPTION_KEY:-$$(docker exec picsou-db printenv PICSOU_DEV_CRYPTO_ENCRYPTION_KEY 2>/dev/null)}"; \
	if [ -z "$$JWT_SECRET" ] || [ -z "$$CRYPTO_ENCRYPTION_KEY" ]; then \
	  state=$$(docker container inspect -f '{{.State.Running}}' picsou-db 2>/dev/null); \
	  if [ -z "$$state" ]; then echo "picsou-db does not exist: run 'make db' first."; exit 1; fi; \
	  if [ "$$state" != true ]; then echo "picsou-db is stopped: run 'make db' first."; exit 1; fi; \
	  echo "picsou-db holds no dev secrets: 'make db' adds them only when it creates the container."; \
	  echo "Export the JWT_SECRET and CRYPTO_ENCRYPTION_KEY its data was encrypted with, or"; \
	  echo "'docker rm -f picsou-db' and 'make db' to start over (its data is lost)."; \
	  exit 1; \
	fi; \
	export JWT_SECRET CRYPTO_ENCRYPTION_KEY; \
	cd backend && exec mvn spring-boot:run -Dspring-boot.run.profiles=dev

dev-frontend: | frontend/node_modules
	cd frontend && bun run dev

format: | frontend/node_modules
	cd frontend && bun run format

# `deps` reinstalls after a lockfile change; other targets only install when missing.
deps frontend/node_modules:
	cd frontend && bun install --frozen-lockfile

# --- test --------------------------------------------------------------------

test: test-backend test-frontend test-e2e test-sidecars

# The suite never calls a real sidecar: any key will do. SOFIDY_PYTHON=python3 also
# runs the Java/Python Sofidy contract test (needs `pip install -r services/base/requirements.txt`).
# CI shards the suite with SHARD_INCLUDES or SHARD_EXCLUDES: an absolute surefire
# includesFile or excludesFile (.github/scripts/backend_shards.py).
test-backend: export APP_SIDECAR_API_KEY ?= test-only
test-backend: export PYTHONPATH := $(CURDIR)/services/base
test-backend:
	cd backend && mvn -B test $(if $(SOFIDY_PYTHON),-Dsofidy.contract.python=$(SOFIDY_PYTHON)) $(if $(TEST),-Dtest=$(TEST)) $(if $(SHARD_INCLUDES),-Dsurefire.includesFile=$(SHARD_INCLUDES)) $(if $(SHARD_EXCLUDES),-Dsurefire.excludesFile=$(SHARD_EXCLUDES))

test-frontend: lint-frontend unit-frontend

lint-frontend: | frontend/node_modules
	cd frontend && bun run lint --max-warnings=0 && bun run typecheck

# CI runs one shard per job and checks the JSON reports together cover every test file.
unit-frontend: | frontend/node_modules
	cd frontend && bun run test $(if $(SHARD),--shard=$(SHARD)) $(if $(VITEST_JSON),--reporter=default --reporter=json --outputFile.json=$(abspath $(VITEST_JSON)))

# PLAYWRIGHT_DEPS=--with-deps also installs Chromium's system libraries through apt. CI no
# longer sets it: it installs them only when a headless launch probe fails.
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
	docker run --rm --ipc=host --entrypoint python --env PYTHONDONTWRITEBYTECODE=1 --env APP_SIDECAR_API_KEY=test-key --env CORUM_BROWSER_TESTS=1 --env LBP_BROWSER_TESTS=1 \
	  --volume "$(CURDIR)/services/base/picsou_sidecar/contract.py:/opt/sidecar-base/lib/picsou_sidecar/contract.py:ro" \
	  --volume "$(CURDIR)/services/base/euro_information/testing.py:/opt/sidecar-base/lib/euro_information/testing.py:ro" \
	  --volume "$(CURDIR)/services/base/bpce/testing.py:/opt/sidecar-base/lib/bpce/testing.py:ro" \
	  --volume "$(CURDIR)/services:/src/services:ro" --workdir /src/services/$(SIDECAR) $(IMAGE) -m unittest discover -v

# --- build and release -------------------------------------------------------

# Every image Compose builds (app, sidecars and their bases), or only SERVICES, resolved
# without a profile as a user's install does (the bases are build-only services).
build:
	$(COMPOSE) $(if $(SERVICES),,--profile all-connectors) build $(SERVICES)

# Pushing a version tag runs CI, which publishes the images and the chart once green.
# REMOTE is the repository that publishes (REMOTE=upstream from a fork).
# Only a commit already on $(REMOTE)/main is tagged, so the tag never publishes
# unreviewed work. A rerun after a failed push reuses the local tag if it is on HEAD.
# Same rule as the chart step in docker.yml: no leading zero in a numeric identifier.
SEMVER_N := (0|[1-9][0-9]*)
SEMVER_PRE := ($(SEMVER_N)|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)
release:
	@echo "$(VERSION)" | grep -Eq "^$(SEMVER_N)\.$(SEMVER_N)\.$(SEMVER_N)(-$(SEMVER_PRE)(\.$(SEMVER_PRE))*)?$$" \
	  || { echo "usage: make release VERSION=x.y.z (no leading v)"; exit 1; }
	@grep -q '^## \[$(VERSION)\]' CHANGELOG.md \
	  || { echo "CHANGELOG.md has no '## [$(VERSION)]' section"; exit 1; }
	@test -z "$$(git status --porcelain)" || { echo "the working tree has uncommitted changes"; exit 1; }
	git fetch $(REMOTE) main
	@git merge-base --is-ancestor HEAD FETCH_HEAD || { echo "HEAD is not on $(REMOTE)/main"; exit 1; }
	@test -z "$$(git ls-remote --tags $(REMOTE) refs/tags/$(VERSION))" || { echo "$(REMOTE) already has tag $(VERSION)"; exit 1; }
	@if git rev-parse -q --verify refs/tags/$(VERSION) >/dev/null; then \
	  test "$$(git rev-parse refs/tags/$(VERSION)^{commit})" = "$$(git rev-parse HEAD)" \
	    || { echo "local tag $(VERSION) is not on HEAD (git tag -d $(VERSION) removes it)"; exit 1; }; \
	else git tag $(VERSION); fi
	git push $(REMOTE) refs/tags/$(VERSION)
