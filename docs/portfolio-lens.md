# Portfolio Lens

Portfolio Lens is the look-through view of Portfolio Performance. It opens under
**Reports** in the sidebar of a client and shows which companies and instruments
are held indirectly through funds, next to what is held directly. The internal
domain term is look-through; the visible feature name is Portfolio Lens.

## Scope and current values

The view calculates from the **current valuations of every security with a
nonzero net position** across all portfolios of the open client. No prior
selection is needed: opening the view is enough. The scope line states the
valuation date, and every row is expressed in one currency, the currency of the
valued subset.

The **denominator** is the current value of those held securities. Account cash
is excluded. When the denominator is missing, incomplete or zero, the view shows
the reason and withholds all share percentages instead of dividing by a partial
sum. A portfolio without any current holding shows an explicit empty state
instead of zero rows.

The three columns **Direct**, **Indirect** and **Total** contain exact values.
Share percentages are display values with a precision of one ten-thousandth of a
percent; they never decide whether coverage is complete.

## Coverage

Coverage is shown as **Complete coverage** or **Partial coverage**, and the
result is only labelled complete when all of the following hold:

- a denominator is present,
- the valuation of the held securities is complete and every position is
  supported,
- the unresolved value is exactly zero, no equity is left ungrouped and no
  unverified provider position is present,
- every composition used along a path declares itself complete.

A reconciled sum of 100 percent or a rounded display is not evidence for
completeness. The non-company categories stay visible even when they are zero,
because an absent row would look like complete coverage:

- **Ungrouped equity** is an instrument that is known to be an equity but not
  mapped to a company. The value is part of the exposure, only the company
  grouping is missing.
- **Unresolved** is a value that could not be attributed: the instrument could
  not be classified, no composition is available, the provider does not support
  the fund, or a composition reports a shortfall.
- **Unverified provider positions** are the positions of a fund whose provider
  weights are arithmetically usable but whose allocation semantics are not
  validated. They are listed individually with their weight-derived value and a
  note, so the portfolio can be inspected. They are never described as verified
  company exposure and they keep coverage partial.

**Cash or other assets** collects positions that are classified as neither an
equity nor a fund, for example cash-like or other asset types.

Held positions that the valuation excludes are listed separately with their
reason: unsupported negative position, missing valuation or missing currency
conversion. They are never silently dropped.

## Refresh and provider requests

Open Portfolio Lens. Opening the analysis schedules initial retrieval when a
credential is configured. While the view remains open, an eligibility pass runs
every minute; actual automatic requests occur at most once per rolling 24 hours
per instrument. Redraws, resizing and recalculation perform memory-only reads and
never request data.

**Refresh composition data** requests a manual refresh, bypassing only the daily
cadence. **Cancel refresh** ends the wait immediately; already queued provider
work can still finish and retain permitted session data. Correcting a key allows
requests to resume without restarting; the next automatic eligibility pass picks
up the change, or you can request a manual refresh.

Refresh candidates are the held securities that carry an ISIN, across all
portfolios. Nothing else leaves the application: no quantities, balances,
transactions, holdings lists or portfolio uploads. Only the identifiers of held
instruments are sent; securities without a position are never requested.

The portfolio model does not distinguish a fund from an individual stock either,
so the first refresh asks the provider once per held ISIN. An instrument that the
provider answers without a usable composition is marked in the data status and is
not requested again while Portfolio Performance runs; it never delays the other
instruments. Restarting Portfolio Performance clears that memory. An instrument
failure never suspends the account, only transport, authentication, entitlement
and rate-limit failures do.

The request budget is a conservative placeholder, not a published provider
allowance: 100 requests per rolling 24 hours, a 15 minute backoff after a
failure, shared across client windows. Held instruments are queued and admitted
one at a time, rather than dropping the remainder when transport capacity is
full. Concurrent requests for the same identifier coalesce. Enrichment lookups
stay at zero, because no verified instrument evidence service exists and nothing
is resolved from a guess. When the coordination knows the next possible attempt,
the status shows a retry hint, for example after a rate limit or during the
automatic cadence.

A refresh that is superseded by a newer one ends as cancelled, so a caller never
waits for an outcome that will not arrive.

## Data status

The **Data status** pane reports per fund:

- the provider and local account,
- when the provider last refreshed its source, or that it did not report this,
- when Portfolio Performance retrieved the data,
- the holdings effective date, or that it is unavailable,
- freshness, the completeness declaration of the source and the denominator
  basis,
- the result of the latest attempt and the next possible retry.

A source that the provider delivered but that is not validated stays visible in
the status with its own dates and declarations. The status therefore separates
"received" from "validated" and never presents a received answer as verified
data.

Freshness is a display fact: a retrieval up to 24 hours old is shown as fresh,
anything older as stale. It never decides whether a calculation may run. A
recent retrieval never implies a recent holdings date, and a missing holdings
date is shown as unavailable rather than substituted.

The pane also states the effective storage mode and its limitation, so the view
never looks more durable than the data actually is.

## Provider setup

Portfolio Lens uses an individual Eulerpool API key. Setup, encrypted storage,
session-only entry, replacement and deletion are described in
[portfolio-lens-credentials.md](portfolio-lens-credentials.md). The view shows no
status and offers no refresh until a composition source is available.

## Composition persistence

Composition persistence is a separate per provider and account preference. It
defaults to on, but the effective mode requires both the preference and a
provider policy that permits retention. For Eulerpool the permission is not
verified yet, so the control is disabled and compositions stay in memory
regardless of the setting. The mode, its reason and the resulting limitation are
visible in the data status.

Turning persistence off deletes only that account's retained files, invalidates
pending disk writes and keeps the current in-memory analysis usable. Turning it
on performs no retroactive write. Other providers and accounts keep their own
settings and data.

## Session-only limits and offline behaviour

In session-only mode, compositions and derived exposure live in memory while
Portfolio Performance runs and are discarded at exit. After a restart the initial
retrieval is needed again, and fund exposure stays unresolved offline until data
becomes available. Nothing is written into portfolio files, portable settings,
exports or diagnostic logs.

Where retention is permitted and enabled, the last valid composition stays
usable for the same session even if a later refresh fails.

## Removing local data

- **Remove key from this session and secure storage** deletes the credential of
  this provider account only and blocks it for the running session.
- Turning persistence off deletes the retained compositions of that provider
  account.
- Quitting Portfolio Performance discards all session-only data.
- Portfolio Lens writes no cache directory while persistence is disabled and
  never modifies the portfolio file.

## Known limits

- Only funds that the provider covers can be decomposed. Unsupported and
  unavailable funds stay unresolved and keep the coverage partial.
- The current Eulerpool adapter has no verified classification or company
  identity service and no validated allocation semantics. Provider weights are
  therefore shown as unverified positions with their own category, never as
  validated company exposure. Coverage stays partial and the provider evidence
  gate remains open.
- Directly held equities are shown at instrument level when no classification
  evidence exists. They are not merged into a guessed company.
- The provider also offers fund profile and instrument search endpoints. They can
  confirm fund-level metadata and an instrument type, but they do not supply
  per-constituent classification or company identity, and one lookup per
  constituent would exceed any reasonable request budget. They are deliberately
  not used for company grouping.
- Coverage is reported as the provider declares it. A reconciled sum or a
  rounded display never turns a partial result into complete coverage, and
  missing dates stay visible instead of being filled in.
