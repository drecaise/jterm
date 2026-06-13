# Licensing analysis

Analysis of what license `jterm` itself can be released under, given its third-party
dependencies. See [`THIRD-PARTY-LICENSES.md`](../THIRD-PARTY-LICENSES.md) for the full
per-dependency inventory and notices.

> **Not legal advice.** This is an engineering summary to inform the decision. For a
> commercial release, have counsel confirm — especially the LGPL relink obligation against
> the shaded fat-jar packaging.

## Dependency licenses at a glance

| License | Dependencies | Effect on our choice |
|---|---|---|
| Apache-2.0 | FlatLaf (+extras), Jackson, Apache MINA SSHD (core, sftp) | None beyond attribution |
| MIT | slf4j-simple | None beyond attribution |
| CC0-1.0 | eddsa (ed25519) | None (public domain) |
| ISC* | java-keyring | None beyond attribution |
| **LGPL-3.0** | **JediTerm (core + ui)** | **Weak copyleft — see below** |
| **EPL-1.0** | **pty4j** | **Weak copyleft + rules out GPL** |

\* java-keyring 1.0.4 declares no license in its POM; upstream repo is ISC. Verify before
relying on it.

The six permissive/public-domain licenses impose no constraint on our license choice
beyond preserving attribution. Only **JediTerm (LGPL-3.0)** and **pty4j (EPL-1.0)** shape
our options.

## What we CAN license jterm's own code as

Both LGPL and EPL are **library-/file-level ("weak") copyleft** — they do **not** force our
own source code open. For our code we may choose, among others:

- **MIT / BSD / Apache-2.0** — cleanest path. Release our code permissively; ship the
  libraries under their own licenses with notices.
- **Proprietary / closed-source / commercial** — allowed. LGPL and EPL both permit a
  proprietary application that merely *uses* the libraries. Our source can stay closed,
  provided the obligations below are met.
- **LGPL-3.0 or EPL** — also fine if we want matching copyleft.

## What we CANNOT do

- **GPL (v2 or v3) on the distributed work is ruled out** by **pty4j's EPL-1.0**, which the
  FSF treats as GPL-incompatible (EPL-1.0 has no GPL secondary-license escape hatch). A
  GPL'd combined jar would be a license conflict. To go GPL we would first have to drop
  pty4j. This is the only hard "no" in the stack.

## Obligations we carry regardless of the chosen license

Because we distribute these libraries inside `jterm.jar`:

1. **JediTerm (LGPL-3.0):** include the LGPL text + a notice that JediTerm is used, and
   allow the user to replace/relink it with a modified version. We don't modify JediTerm,
   so this is a notice + relink-ability concern, not source disclosure.
2. **pty4j (EPL-1.0):** include the EPL text + notices and make pty4j's source available
   (a link to the unmodified upstream source suffices). Note EPL's patent-retaliation
   clause.
3. **Apache-2.0 libs:** preserve their `NOTICE`/attribution files.

## The packaging gotcha: the shaded fat jar

The build produces an uber-jar (`maven-shade-plugin` → `target/jterm.jar`) that merges
JediTerm and pty4j classes in. This is where LGPL's "user must be able to relink/replace
the library" requirement gets awkward — merged/relocated classes aren't trivially
swappable. Cleanest mitigations:

- Do **not** relocate the JediTerm/pty4j packages in the shade config (we currently
  don't relocate), and
- document how to substitute them, or
- ship them as separate side jars rather than inlined.

Since we modify neither library, this stays a notice/relink-ability issue, not a
source-disclosure one.

## Recommendation

**MIT or Apache-2.0 on our own code** is the path of least friction: maximum freedom for
us, with only two duties — bundle the third-party license texts (done in
`THIRD-PARTY-LICENSES.md`) and keep JediTerm replaceable + pty4j source linked. If a GPL
release is ever desired, pty4j must be replaced first.

The final choice is deferred (a `LICENSE` file has not yet been added).
