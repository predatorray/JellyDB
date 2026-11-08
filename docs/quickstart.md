# Testing and deployment quickstart

The easiest first test runs real Candybox locally with temporary storage. Deployment against an existing private Candybox cluster is a separate path. The complete three-host cluster still needs the validation listed in the [operations runbook](operations.md#launch-gates).

## 1. Build and run automated tests

For local builds use macOS or Linux/WSL with Make, JDK 17+ (including `javac`), Maven 3.9+, Git and Python 3. Packaging uses Python’s standard library rather than platform-specific tar flags. Production systemd deployment remains Linux-only. The tested build used JDK 21. Internet access is needed for the pinned Candybox source and Maven dependencies.

```sh
git clone https://github.com/predatorray/JellyDB.git
cd JellyDB
git switch main
make build
make test-tools
```

The build compiles the exact source revision in `candybox.lock`, runs application tests plus real ZooKeeper/BookKeeper/Candybox integration, and creates `target/jellydb-runtime.tar.gz`. It starts temporary local services for tests; no pre-existing cluster or real SMTP account is needed. Use `make build MVN=/absolute/path/to/mvn` if Maven is not on PATH; `PYTHON` and `JAVA` are executable overrides too. After the first build, `make test` runs Java/integration and Python tool tests without rebuilding upstream. `make test-java` and `make test-tools` run each suite separately. If Java already built successfully and only packaging failed (including the old macOS `tar --sort` error), run `make package` to retry packaging without Maven. `make clean` removes generated Maven outputs but preserves the downloaded upstream checkout.

## 2. Try the API and browser example locally

In the first terminal:

```sh
make demo
```

Wait for `NATIVE_BROWSER_READY`. Set `PORT=8090` to use a different API port (`make demo PORT=8090`). Set `EXAMPLES_PORT` for the static page and pass the same port overrides to `make browser-test`. This test fixture runs real temporary ZooKeeper, three bookies, one Candybox owner and the native HTTP adapter. It uses a 768 MiB Java heap cap; allow extra memory for native allocations and the OS. It is a development fixture, not the persistent production topology or evidence that the full VM budget fits.

In a second terminal, from the repository root:

```sh
make examples
```

Open <http://127.0.0.1:8081> and click **Run example**. The page calls the API on port 8080, exercising actual cross-origin requests.

You can also test with curl. Copy the `id` from the first response and replace `YOUR_NAMESPACE_ID`:

```sh
curl -fsS -X POST http://127.0.0.1:8080/v1/namespaces
curl -fsS -X PUT -H 'Content-Type: application/json' \
  -d '{"value":"hello"}' \
  http://127.0.0.1:8080/v1/namespaces/YOUR_NAMESPACE_ID/items/~
curl -fsS http://127.0.0.1:8080/v1/namespaces/YOUR_NAMESPACE_ID/items/~
curl -fsS http://127.0.0.1:8080/v1/namespaces/YOUR_NAMESPACE_ID/keys
```

`~` represents the empty string key; other keys use tilde plus base64url UTF-8. Successful PUT renews the namespace for seven days; reads do not. Anyone knowing the address can change it. Use synthetic data.

Optional automated Chromium checks require the Python `playwright` package and an installed Chromium browser. Set `CHROMIUM_PATH` if it is not discoverable as `chromium`, then run:

```sh
make browser-test
```

Stop the fixture and static server with Ctrl-C. Do not use the temporary fixture to keep valuable data.

## 3. Run the production HTTP service against an existing cluster

First prepare a private Candybox cluster at the exact revision in `candybox.lock`, with its ZooKeeper and BookKeeper storage configured. Managed namespaces require upgraded owners and clients together; older owners cannot open managed Box descriptor v3. This runtime archive does **not** install those servers.

From the build machine, or as a private smoke test:

```sh
JELLY_ZK_CONNECT=10.20.0.11:2181,10.20.0.12:2181,10.20.0.13:2181 \
JELLY_BOX=jellydb-v1 JELLY_PARTITIONS=8 \
JELLY_BIND=127.0.0.1 JELLY_PORT=8080 \
make run
```

Replace the private addresses. Startup creates the single shared managed Box if absent and rejects a legacy Box or mismatched partition count. The configured cluster identity needs namespace and maintenance permissions. Optional ZooKeeper authentication belongs in protected host configuration, not browser code.

Check readiness before routing traffic:

```sh
curl -fsS http://127.0.0.1:8080/healthz
curl -fsS http://127.0.0.1:8080/readyz
```

Liveness alone is insufficient. Backend/owner failures produce 503; do not blindly replay ambiguous mutations.

## 4. Install the HTTP runtime under systemd

For the simpler release-based path (no build tools on the VPS), download a versioned GitHub Release tarball and checksums, verify and extract it, then run `sudo python3 bin/install-http.py`. See [releases.md](releases.md) for exact commands, upgrade behavior and prerequisites. The installer preserves configuration and does not start services. The manual installation below remains available for development build archives.


Build off-host and copy only `target/jellydb-runtime.tar.gz` to the VM. Install a Java 17+ runtime and Python 3 on the VM. The following example installs **only the HTTP service**, assuming the private cluster above already works. Run user/directory setup once; choose a new release directory for later upgrades.

```sh
sudo useradd --system --home /var/lib/jellydb --shell /usr/sbin/nologin jellydb
sudo install -d -m 0755 /opt/jellydb/releases/0.1.0 /opt/jellydb/bin
sudo tar -xzf /tmp/jellydb-runtime.tar.gz -C /opt/jellydb/releases/0.1.0
cd /opt/jellydb/releases/0.1.0
sha256sum -c SHA256SUMS
sudo ln -sfn /opt/jellydb/releases/0.1.0 /opt/jellydb/current
sudo ln -sfn /opt/jellydb/current/bin/run.sh /opt/jellydb/bin/run.sh
sudo install -d -m 0750 /etc/jellydb
sudo install -d -o jellydb -g jellydb -m 0750 /var/lib/jellydb
sudo install -m 0600 conf/http.env.example /etc/jellydb/http.env
sudoedit /etc/jellydb/http.env
```

Set the actual private ZooKeeper addresses and desired managed Box/partition count in `http.env`; leave HTTP bound to loopback when using the TLS proxy. Skip `useradd` if the service user already exists.

```sh
sudo install -m 0644 conf/jellydb.service /etc/systemd/system/jellydb.service
sudo systemctl daemon-reload
sudo systemctl enable --now jellydb
curl -fsS http://127.0.0.1:8080/readyz
sudo journalctl -u jellydb -n 100 --no-pager
```

For public browser access, configure a real domain and HTTPS reverse proxy using `ops/Caddyfile` as a template. Validate it with the installed proxy, replace the placeholder domain, verify certificate renewal and test proxy-generated error CORS. Keep ZooKeeper, BookKeeper, Candybox TCP, metrics and admin interfaces private. Archive services require separate protected SMTP configuration and a delivery/retrieval drill; do not enable sending before configuring and authorizing it.

For the complete three-VM layout, persistent identities, one-time metadata initialization, startup order, firewall, archive services and upgrade/rollback procedure, follow [operations.md](operations.md). These assets are templates: full-stack memory/recovery sizing, physical reclamation, public HTTPS validation, mailbox delivery, fault drills and a 24-hour soak remain unverified. Start with a private staging deployment rather than opening public ingress immediately.
