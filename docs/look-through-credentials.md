# Breakdown credentials

## Individual setup

In Settings, open **API Keys > Eulerpool**. Obtain an individual
API key from Eulerpool under the terms applicable to your account. Enter the API
key, not the website password. PP does not ship a shared key. Do not paste keys
into a portfolio, notes, URLs, ordinary preferences or diagnostic reports.

Choose **Save encrypted / replace key** to use local Equinox secure storage, or
explicitly choose **Use for this session only / replace key**. These actions take
effect immediately, even if the settings dialog is later cancelled. Existing keys
are not displayed or prefilled. A masked placeholder indicates an active key;
it contains no secret and is not submitted as a replacement. The status text
distinguishes encrypted storage, session-only entry and unavailable storage.
The page includes a link to obtain an individual API key and configures the local `default` Eulerpool
account; this is an opaque local identifier, not a provider login.

Encrypted storage requires a usable password provider on the running platform.
Presence of the Equinox API does not establish that the system credential store
can be unlocked. Storage/encryption/decryption/flush failures are reported without
their exception payloads. Unlock the system credential store and retry, or choose
session-only entry explicitly. PP never substitutes plaintext storage. Background
access supplies Equinox's no-prompt hint; providers are responsible for honoring
that hint. No unlock dialog is implemented by this feature.

A session-only key is held in memory until PP exits or the key is removed or
replaced. It must be entered again after restart. Selecting session-only entry
also removes the previous encrypted entry, where storage is accessible. This
credential choice is separate from composition-data persistence and its provider
permissions. It does not grant caching rights or certify fund data.

## Replacement and deletion

Enter a new key and choose either replacement action. Requests made after the
change use the new key, never the old one. A request already handed to the
transport cannot be recalled by changing credentials.

Choose **Remove key from this session and secure storage** to stop new authenticated
requests and delete only this account's Breakdown credential. Other providers,
other local accounts and unrelated PP credentials/settings are not changed.
This does not revoke the key at Eulerpool; use the provider's account controls to
revoke it as well if required.

If deletion cannot be confirmed, the old key is blocked for the current PP
session, but may remain available after restart. The page explicitly warns about
this condition. Unlock storage and retry removal before quitting. A deliberately
selected new session-only key stays active despite an old-entry deletion failure.
Do not interpret that warning as successful deletion from disk.

## Storage and integration

`CompositionCredentials.local()` is the application-lifetime credential service.
The core service has no SWT or UI dependency. Its backend opens a separate
`platform:/meta/name.abuchen.portfolio/lookthrough_credentials` Equinox store,
not the default user-wide or existing OAuth store. Provider/account node segments
are separately encoded to avoid collisions and path traversal. The backend
always requests encryption with `put(..., true)`, verifies encrypted round-trip
access and flushes before reporting success. Reads reject unexpected plaintext.
There is no portfolio/preference-store serializer, export, logger or network
operation in the credential service.

Explicit provider construction supplies the seam:

```java
var credentials = CompositionCredentials.local();
var provider = new EulerpoolFundCompositionProvider(credentials::getBearerToken);
```

Use the same `ProviderAccount` as the configuration page (currently Eulerpool,
`default`). The no-argument service-discovery provider remains deliberately
unconfigured; discovering a provider does not open or unlock storage. Application
background integration must use the explicitly configured instance.

The service exposes only mode and a monotonically increasing, non-secret,
account-local configuration revision through `state(account)`. Before scheduling
after setup/replacement/removal, application integration must pass that revision
to `CompositionRefreshCoordinator.configurationChanged(account, revision)` to
clear authentication/entitlement suspension. Revisions are not key hashes and
are not persisted. Application jobs and cache preference integration are separate
work; the credential service does not start requests or refresh jobs itself.

Eulerpool requests carry the API key only in the Bearer authorization header.
Their URL contains the selected fund identifier, not credentials, quantities,
balances, transactions or a portfolio upload. Provider failures do not propagate
transport exception messages or response bodies.

## Platform and verification boundary

The target declares Eclipse 4.40 Platform and e4 RCP features. The existing core
manifest already imports `org.eclipse.equinox.security.storage`, used by the
separate OAuth implementation. The product includes e4 RCP and
`org.eclipse.equinox.p2.core.feature` and automatically includes bundle
requirements. The [Eclipse R4_40 p2 core feature definition](https://github.com/eclipse-equinox/p2/blob/R4_40/features/org.eclipse.equinox.p2.core.feature/feature.xml)
includes `org.eclipse.equinox.security` and the OS-filtered
`org.eclipse.equinox.security.macosx`, `.linux` and `.win32` fragments. No new
platform feature is needed. This implementation adds only the supported
`org.eclipse.equinox.security.storage.provider` import for `IProviderHints`.
It does not modify existing quote-provider or OAuth credential behavior.

Offline tests use synthetic keys, fake backends and mocked secure preferences.
They test explicit encryption, plaintext rejection, replacement/removal, failed
deletion, session fallback, account isolation, redaction and request contents.
They do not prove native platform unlocking or OS-keychain protection. Before
release, verify encrypted setup/reopen, locked/unavailable storage, explicit
session entry/restart, replacement and deletion in an isolated application on
each supported platform. Use only synthetic keys and do not send live requests.

## Composition persistence preference

Each provider/account has a separate non-secret, application-local (instance-scope)
composition persistence preference. It is keyed by `ProviderAccount`, stored outside the
portfolio and outside credential storage, and exposed through
`CompositionPersistencePreferences`. Tests use `CompositionPersistencePreferences.InMemory`;
the desktop UI uses `EclipseCompositionPersistencePreferences`.

The preference defaults to on, but effective persistence requires both the preference
and the provider's `CompositionPersistencePolicy`. The policy may be permitted,
prohibited or unverified. `CompositionPersistencePolicy.effectiveModeDetail(...)` reports
both the mode (`PERSISTENT` or `SESSION_ONLY`) and the reason (`PERMITTED`,
`USER_OPT_OUT`, `POLICY_PROHIBITED` or `POLICY_UNVERIFIED`). For Eulerpool the policy
is currently `UNVERIFIED`, so the control is disabled and retained storage stays
disabled regardless of the preference. The control becomes usable only after task 6.3
verifies permission and retention and the application supplies a permitted policy.

Turning persistence off immediately deletes only that account's retained files,
invalidates pending disk writes and keeps the current in-memory analysis usable.
Turning it on performs no retroactive write; only later successful refreshes may persist
when policy also permits it. Other accounts and providers keep their settings and data.
No preference change creates a cache directory while persistence is disabled.

Session-only mode keeps compositions, enrichment mappings and derived exposure results in
memory while PP runs and discards them at exit. It writes no provider payloads to caches,
portfolio files, portable settings or diagnostic logs. After restart, initial retrieval is
needed again and offline fund exposure remains unresolved until data becomes available.
Refresh failures can retain a valid in-memory snapshot for the same session where permitted.

The value is stored in the Eclipse instance scope under the UI bundle node
(`name.abuchen.portfolio.ui`, key prefix `lookthrough.persistence.` followed by the
hex-encoded provider and account identifiers), not in the portfolio or credential
storage. The UI control is currently disabled because the Eulerpool persistence policy
is unverified. Enabling retained storage requires both the provider policy to permit it
and the user preference to be on.
