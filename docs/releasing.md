# Releasing (maintainers)

How a new Android SDK version reaches other developers. The coordinates are
`ly.dojoon.poslink:poslink-android:<version>`.

## Before every release

- [ ] All tests pass: `cd android && ./gradlew :poslink:testDebugUnitTest`.
- [ ] The vectors still match: `python/.venv/bin/python spec/tools/generate_vectors.py --check`.
- [ ] Hardware check with the sample app on two P3 devices, **over USB and over Bluetooth**: pair,
      approve, decline, cancel, and pull the link during a payment.
- [ ] The version is bumped in `PosLinkInfo.SDK_VERSION`
      (`android/poslink/src/main/kotlin/ly/dojoon/poslink/PosLinkInfo.kt`). The build reads it
      from there, so there is no other place to change.
- [ ] `CHANGELOG.md`: the "unreleased" section gets the version number and today's date.
- [ ] `poslink.repoUrl` in `android/gradle.properties` points at the real public repository. It
      goes into the published POM and the PDF guide.
- [ ] The PDF guide builds: `uv run docs/tools/build_pdf.py` (needs Google Chrome). It also fails
      on a broken link in the docs.

## GitHub Pages: each release

Apps install the library from `https://alharudi.github.io/poslink-sdk/maven`: the `maven/` folder
of the `gh-pages` branch. Gradle adds the new version next to the old ones and updates the version
list. From the repository root:

```bash
git worktree add ../poslink-pages gh-pages     # once; afterwards: git -C ../poslink-pages pull
(cd android && ./gradlew :poslink:publishReleasePublicationToPagesRepository -Pposlink.pagesDir=../poslink-pages)
uv run docs/tools/build_pdf.py && cp docs/build/poslink-android-guide-*.pdf ../poslink-pages/
cd ../poslink-pages && git add -A && git commit -m "poslink-android <version>" && git push
```

`poslink.pagesDir` is relative to the repository root. The site's `index.html` shows the newest
version by itself. Pages updates within a minute or two.

**Never change a version that is already on Pages.** Apps cache what they downloaded, so a
replaced file reaches some apps and not others. Any change, even a one-line fix, gets a new
version number.

## Give a build to someone privately (no Maven Central)

```bash
cd android && ./gradlew :poslink:publishReleasePublicationToStagingRepository
cd poslink/build/staging-repo && zip -r ../poslink-repo.zip .
```

Send `poslink/build/poslink-repo.zip`. The receiver unzips it and adds the folder as a repository
([setup](android/setup.md#1-add-the-dependency), "Got the SDK as a zip file").

Send the guide with it as a PDF. From the repository root:

```bash
uv run docs/tools/build_pdf.py   # docs/build/poslink-android-guide-<version>.pdf
```

`./gradlew :poslink:publishToMavenLocal` puts the same files in `~/.m2` on this computer. Use it to
try a local change in another project that lists `mavenLocal()`.

## Maven Central: one-time setup

1. Sign in at [central.sonatype.com](https://central.sonatype.com) with the company account.
2. **Namespaces → Add namespace → `ly.dojoon`.** The site shows a code. Add it as a DNS TXT record
   on `dojoon.ly`, then press **Verify**. This proves the company owns the name.
3. Create a signing key (Maven Central rejects unsigned files):

   ```bash
   gpg --full-generate-key                       # RSA 4096 or ed25519; name "Dojoon"
   gpg --list-secret-keys --keyid-format short   # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   ```

   Store the key and its passphrase in the company password manager. Losing them means future
   releases are signed by a different key.

## Maven Central: each release

```bash
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys <KEY_ID>)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='<passphrase>'
cd android && ./gradlew :poslink:centralBundle
```

This builds `poslink/build/distributions/poslink-android-<version>-central-bundle.zip`: the library,
its sources, the API docs, the POM, checksums and signatures.

1. On central.sonatype.com, **Publish → Publish Component**, and upload the zip.
2. Wait for validation, then press **Publish**. The version appears on Maven Central within about
   30 minutes.
3. Tag the commit `android-v<version>` and create a GitHub release with the changelog section.
   Attach the PDF guide to it.

**A published version is permanent.** It can't be deleted or replaced. If something is wrong,
release a new version.

## Other SDKs (later)

Each SDK goes to its language's usual registry, under the company's name:

| SDK | Registry | Planned name (not reserved yet) |
|---|---|---|
| Android | GitHub Pages now, Maven Central later | `ly.dojoon.poslink:poslink-android` |
| Flutter | pub.dev (verified publisher `dojoon.ly`) | `poslink` |
| TypeScript | npm | `@dojoon/poslink` |
| .NET | NuGet | `Dojoon.PosLink` |
| Python | PyPI | `poslink` |

Each needs a one-time account and name reservation, similar to the steps above.
