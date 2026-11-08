.DEFAULT_GOAL := help

MVN ?= mvn
PYTHON ?= python3
JAVA ?= java
PORT ?= 8080
EXAMPLES_PORT ?= 8081
BACKEND ?= native-candybox
RUNTIME ?= $(CURDIR)/target/runtime

export MVN PYTHON JAVA

.PHONY: help build test test-java test-tools package demo demo-fake examples browser-test run clean

help:
	@printf '%s\n' \
	  'make build         Build pinned Candybox, verify Java code, package the runtime' \
	  'make test          Run Java/integration and Python tool tests (after first build)' \
	  'make test-java     Run Java and real-storage integration tests' \
	  'make test-tools    Run archive and portable-packaging tests' \
	  'make package       Repackage existing build outputs without rerunning Maven' \
	  'make release TAG=v0.1.0-rc.1  Bundle tested Linux amd64 VPS release assets' \
	  'make demo          Start the temporary real-storage fixture (PORT=8080)' \
	  'make demo-fake     Start the test-only in-memory fixture (PORT=8080)' \
	  'make examples      Serve the browser example (EXAMPLES_PORT=8081)' \
	  'make browser-test  Run Chromium checks (BACKEND=native-candybox or fake)' \
	  'make run           Run production HTTP against a configured private cluster' \
	  'make clean         Remove Maven outputs; retain the pinned upstream checkout' \
	  'Overrides: MVN=/path/to/mvn PYTHON=/path/to/python3 JAVA=/path/to/java'

build:
	./scripts/build.sh

test: test-java test-tools

test-java:
	"$(MVN)" -B -ntp verify

test-tools:
	"$(PYTHON)" -m unittest discover -s tools -p 'test_*.py' -v

package:
	./scripts/package.sh

demo:
	./scripts/demo.sh "$(PORT)"

demo-fake:
	"$(MVN)" -B -ntp test-compile package -DskipTests
	"$(JAVA)" -XX:ActiveProcessorCount=2 -cp 'target/test-classes:target/classes:target/lib/*' dev.jellydb.DemoMain "$(PORT)"

examples:
	"$(PYTHON)" -m http.server "$(EXAMPLES_PORT)" --bind 127.0.0.1 --directory examples

browser-test:
	"$(PYTHON)" tools/browser_test.py --backend-label "$(BACKEND)" --page-url "http://127.0.0.1:$(EXAMPLES_PORT)/" --api-base "http://127.0.0.1:$(PORT)"

run:
	JELLY_RUNTIME="$(RUNTIME)" ./scripts/run.sh

clean:
	"$(MVN)" -B -ntp clean

# Versioned release assets only; publishing is performed by GitHub Actions on a tag push.
TAG ?=
RELEASE_FLAGS ?=
.PHONY: release
release:
	"$(PYTHON)" scripts/release.py --tag "$(TAG)" $(RELEASE_FLAGS)
