# Book corpus v1 — rights assessment

Scope: Project Gutenberg eBook #11, *Alice's Adventures in Wonderland*, Plain Text UTF-8, revision
"Most recently updated: June 26, 2025", pinned in [`manifest.properties`](manifest.properties).

> This is an engineering assessment for repository policy, **not legal advice**. When the answer
> matters outside these documented uses, confirm with counsel in the relevant jurisdiction.

## Three separate layers

| Layer | Holder / source | Status | Consequence for this repository |
| --- | --- | --- | --- |
| The literary work | Lewis Carroll (C. L. Dodgson, died 1898); first published 1865 | Public domain in the US; public domain in practice everywhere (see below) | Free to use for experiments. |
| This edition / transcription | Gutenberg volunteers (credits: Arthur DiBianca, David Widger) | Faithful transcription; no new original expression claimed | Treated as public domain text; caveat below. |
| Platform wrapper | Project Gutenberg Literary Archive Foundation | Header, footer and "Full Project Gutenberg License"; "Project Gutenberg" is a registered trademark | Applies to the raw file as distributed. Normalized body removes it. |

### Literary work

- **United States:** published 1865, before 1931 → public domain. The Gutenberg catalog states
  "Public domain in the USA."
- **Life + 70 jurisdictions** (EU, UK, many others): expired at the end of 1968.
- **Life + 100** (the longest term, Mexico): expired at the end of 1998.
- The plain-text edition contains only `[Illustration]` placeholders, not John Tenniel's
  illustrations (Tenniel died 1914; those are public domain under the same terms anyway).
- No known perpetual or special statutory right covers this work.

### Edition caveat

A faithful transcription of a public-domain text does not create a new US copyright (no original
expression). Some jurisdictions grant neighbouring rights for *scientific/critical editions* or
*first publication of previously unpublished works* (e.g. Germany §§ 70, 71 UrhG). Neither fits a
transcription of a book published in 1865, so the residual risk is low but not formally cleared.
Gutenberg's own header still tells users outside the US to check local law before using the eBook.

### Platform license and trademark

- The raw file carries the Project Gutenberg License. It lets anyone copy and redistribute the
  eBook at no cost; charging for copies or derived works that keep the "Project Gutenberg" name
  triggers trademark/royalty terms.
- The license's own terms allow removing all references to Project Gutenberg and then using the
  public-domain text without those trademark conditions.
- Normalization policy v1 keeps only the lines between the START/END markers. Verified on the
  pinned bytes: the normalized body contains no "Gutenberg" string and no `™` sign.
- Attribution is not legally required for the body, but the manifest records source, eBook number,
  revision and transcription credits for provenance.

### Site terms

Project Gutenberg's website is meant for human users; automated or bulk downloading from the main
site is not allowed (robots must use mirrors or the official harvest endpoint). Hence
`source.retrievalMethod=MANUAL_OFFLINE_USER_DOWNLOAD` and no fetcher anywhere in the repository.

## Decision

| Question | Decision |
| --- | --- |
| Use the text for local experiments | **Allowed** (`rights.textUS=PUBLIC_DOMAIN`, `rights.textOutsideUS=PUBLIC_DOMAIN_LIKELY_AUTHOR_DIED_1898_VERIFY_LOCALLY`). |
| Commit raw file (with Gutenberg license/trademark) | **No.** |
| Commit normalized body | **No, not in v1** (`redistribution.status=NOT_REDISTRIBUTED_IN_GIT`). Legally low risk, but the Issue requires explicit territorial verification first; a future decision needs its own Issue. |
| Test snapshots / fixtures | Only original, hand-written fixtures (`monada-evaluation/src/test/resources/book-corpus-fixture-v1/`). No book bytes. |
| Short quotations in reports | Allowed sparingly (public domain), but keep reports focused on metrics and IDs. |

The contract test `BookCorpusV1ContractTest.gitTracksNoBookBytes` guards
the "no book bytes in Git" decision: it checks the tracked file set, not just this directory.
