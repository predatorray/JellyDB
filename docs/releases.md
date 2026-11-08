# GitHub releases and Linux VPS deployment

## Recommendation and options

| Option | Benefits | Cost / limitations | Recommendation |
| --- | --- | --- | --- |
| Runtime tarball + host Java + systemd | Small runtime download, direct logs, simple version directories and rollback | Install a JRE 17+ and Python 3.9+ on each VPS | **Default implemented here** |
| Tarball bundling a Java runtime | No Java provisioning/version mismatch | Much larger, per-architecture builds, JRE security updates become release work | Consider later if host setup becomes painful |
| OCI container image / Compose | Familiar immutable image distribution | Container daemon/images add footprint; cross-host discovery/storage still needs configuration | Optional future deployment path |
| deb/rpm packages | Familiar package-manager installation | Distribution-specific packaging and upgrade hooks | Defer until the deployment contract stabilizes |

Current release scope is the **JellyDB HTTP service only**, targeting Linux amd64. It includes application/dependency JARs, systemd unit/config examples, archive tools, installer, dependency inventory, license and source/build identity. It does not include a JDK/JRE, Maven, source checkout, frontend toolchain, test fixtures, or Candybox owner/bookie/ZooKeeper/admin server distributions. It requires a separately configured private storage cluster at the exact Candybox commit in `release.json`. Public HTTPS and SMTP configuration remain operator inputs. No paid resource or automatic VPS deployment is performed.

ARM64 assets are intentionally not advertised: native/transitive dependencies and the full stack have not been validated there. The full multi-JVM three-VM footprint and recovery/soak gates remain unproven; see the bundled `share/docs/operations.md`. A release is a tested distributable, not proof those deployment gates have passed.

## Trigger a release

1. Update `pom.xml`'s application version before changing the release base version. For the current `0.1.0-SNAPSHOT`, use `v0.1.0` or `v0.1.0-rc.1`.
2. Commit and push reviewed changes to main, then push a version tag:

```sh
git switch main
git pull --ff-only origin main
git tag -a v0.1.0-rc.1 -m 'JellyDB 0.1.0 release candidate 1'
git push origin v0.1.0-rc.1
```

The **Linux VPS release** workflow builds the pinned upstream and this project on Ubuntu amd64 with Temurin 21, runs Java/real-storage integration and Python tool/installer tests, packages the result, verifies checksums and publishes GitHub Release assets. All v0 releases and suffixed versions are marked prereleases. No initial tag/release is created merely by adding the workflow.

Use Actions → Linux VPS release → Run workflow for a dry run: this creates downloadable workflow artifacts (14-day retention) but no GitHub Release. Manual builds use a `vBASE-preview.RUN` distribution label. Local packaging after `make build`: `make release TAG=v0.1.0-rc.1`; local dirty-source bundles are recorded as such. CI publication requires a clean checkout. Release tags are checked against the build commit immediately before publication; existing releases/assets are not overwritten on rerun. Uploads are assembled in a draft and published only after upload succeeds. A failed upload can leave a draft: inspect/remove that incomplete draft before retrying; use a new version to replace a published build.

Action dependencies are pinned to commit SHAs. Build jobs have read-only repository permissions; only the separate tag publication job has contents:write. No SSH key or server secret is required. Checksums detect corruption; authenticity relies on the repository/release distribution channel. Cryptographic build attestations/signatures are a possible later addition, not claimed here.

## Assets

* `jellydb-vVERSION-linux-amd64.tar.gz`: installable HTTP runtime.
* `SHA256SUMS`: checksums of every other external asset.
* `release.json`: exact source and Candybox commits, platform, dirty-source flag, Java requirement and archive name.
* `dependencies.txt`: complete resolved Maven dependency tree, including separately marked test scopes. The archive's internal checksums identify actual shipped files.
* `DEPLOY.md` and `release-notes.md`: prerequisites and deployment instructions.

The archive also contains an internal `SHA256SUMS` inventory and `release.json`; ownership, executable permissions and timestamps are normalized. Dependency classes remain in versioned JARs; no shaded second storage engine is copied into the application.

## Install on a Linux amd64 VPS

First provision the private Candybox/ZooKeeper/BookKeeper cluster independently; do not use the disposable test fixture as production storage. Install a Java 17+ runtime and Python 3.9+. On Ubuntu 24.04, for example:

```sh
sudo apt-get update
sudo apt-get install -y openjdk-21-jre-headless python3
```

Download all assets from the same GitHub release so the complete SHA256SUMS inventory can be checked. With GitHub CLI installed, the download can be:

```sh
mkdir jellydb-release
cd jellydb-release
gh release download v0.1.0-rc.1 --repo predatorray/JellyDB
sha256sum -c SHA256SUMS
tar -xzf jellydb-v0.1.0-rc.1-linux-amd64.tar.gz
sudo python3 bin/install-http.py
```

Or download assets through the Releases page; `gh` is not needed on the VPS for installation. Archives extract directly into the current directory, so use a dedicated directory. The installer verifies the full internal inventory, installs an immutable version under `/opt/jellydb/releases/vVERSION`, preserves `/etc/jellydb/http.env` and existing systemd unit overrides, creates the service user/data directory, and atomically changes `/opt/jellydb/current`. It runs daemon-reload but **does not start services**, configure public ingress, initialize/format a cluster, or send email.

Configure your actual private backend addresses and Box settings:

```sh
sudoedit /etc/jellydb/http.env
sudo systemctl enable --now jellydb
curl -fsS http://127.0.0.1:8080/readyz
sudo journalctl -u jellydb -n 100 --no-pager
```

Defaults bind HTTP to loopback. Configure HTTPS ingress separately using the Caddy template and verify certificate renewal/CORS. Keep all storage/admin/metrics ports private. Archive tools need protected SMTP configuration and a separately authorized delivery/retrieval drill; installation does not enable the sender.

For a nonprivileged layout preview/test: `python3 bin/install-http.py --stage /tmp/jellydb-stage`. This writes only under the staging directory and performs no user/systemd/service changes; staged units still describe the real `/opt`, `/etc`, `/var` layout.

## Upgrade and rollback

Drain ingress before installing an upgrade, then restart the HTTP service explicitly and check readiness before returning traffic. Reinstalling identical bytes is safe; a different build under the same version is refused. Existing configuration/unit files are preserved; compare new templates manually when release notes call for changes.

For HTTP-only rollback, point `/opt/jellydb/current` back to the previously retained version, restart, and verify readiness, provided the older application/Candybox formats are compatible. Never roll Candybox owners back across managed descriptor/record incompatibilities. Managed Box descriptor v3 requires a stopped-cluster owner/client upgrade; the HTTP installer does not coordinate a storage upgrade. Old release directories are retained; prune them manually after the rollback window.
