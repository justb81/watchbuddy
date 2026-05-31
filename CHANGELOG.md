# Changelog

## [0.43.0](https://github.com/justb81/watchbuddy/compare/v0.42.0...v0.43.0) (2026-05-31)


### Features

* **tv:** improve show detail screen layout and usability ([#758](https://github.com/justb81/watchbuddy/issues/758)) ([b1be1ff](https://github.com/justb81/watchbuddy/commit/b1be1ffaee6513a443182b0804892bc7e8b5e521))

## [0.42.0](https://github.com/justb81/watchbuddy/compare/v0.41.0...v0.42.0) (2026-05-26)


### Features

* **tv:** standardise screen layout spacing with TvSpacing tokens ([#752](https://github.com/justb81/watchbuddy/issues/752)) ([e739edf](https://github.com/justb81/watchbuddy/commit/e739edfac3f84e326c86b28e3ba0f3fa801973ed)), closes [#687](https://github.com/justb81/watchbuddy/issues/687)

## [0.41.0](https://github.com/justb81/watchbuddy/compare/v0.40.0...v0.41.0) (2026-05-20)


### Features

* **tv:** remove scrobbling subsystem from TV app and core ([#749](https://github.com/justb81/watchbuddy/issues/749)) ([0205743](https://github.com/justb81/watchbuddy/commit/0205743df8349e66529c2ff13c3ea5589697372d)), closes [#741](https://github.com/justb81/watchbuddy/issues/741)


### Bug Fixes

* remove redundant TV connection badge below connection toggle ([#746](https://github.com/justb81/watchbuddy/issues/746)) ([6bda17b](https://github.com/justb81/watchbuddy/commit/6bda17b11fa25d2840e03ea03fb7312c3806e551)), closes [#740](https://github.com/justb81/watchbuddy/issues/740)
* show connected phone user on TV home screen immediately after discovery ([#747](https://github.com/justb81/watchbuddy/issues/747)) ([6171388](https://github.com/justb81/watchbuddy/commit/617138817b061514fe0effba96d9e585330d17a8)), closes [#739](https://github.com/justb81/watchbuddy/issues/739)

## [0.40.0](https://github.com/justb81/watchbuddy/compare/v0.39.0...v0.40.0) (2026-05-20)


### Features

* add cover art, season range and status to search results ([#742](https://github.com/justb81/watchbuddy/issues/742)) ([954522e](https://github.com/justb81/watchbuddy/commit/954522ee1db809ef774cf8fbef2e855857a2c111)), closes [#730](https://github.com/justb81/watchbuddy/issues/730)


### Bug Fixes

* show added via search appears in list immediately ([#744](https://github.com/justb81/watchbuddy/issues/744)) ([5435e1b](https://github.com/justb81/watchbuddy/commit/5435e1b0ecf23b35f705f678ae61392564c914b6)), closes [#731](https://github.com/justb81/watchbuddy/issues/731)

## [0.39.0](https://github.com/justb81/watchbuddy/compare/v0.38.0...v0.39.0) (2026-05-17)


### Features

* move episode list to dedicated All Episodes screen on TV ([#733](https://github.com/justb81/watchbuddy/issues/733)) ([ce8e2d6](https://github.com/justb81/watchbuddy/commit/ce8e2d6d8e1e2520b254dd079fe62f376c1ed079)), closes [#712](https://github.com/justb81/watchbuddy/issues/712)
* show notification when phone is actively connected to TV ([#732](https://github.com/justb81/watchbuddy/issues/732)) ([bab185c](https://github.com/justb81/watchbuddy/commit/bab185c29476000563a7263a8bb3b07ab9f6b29c)), closes [#721](https://github.com/justb81/watchbuddy/issues/721)

## [0.38.0](https://github.com/justb81/watchbuddy/compare/v0.37.0...v0.38.0) (2026-05-16)


### Features

* IMDb and TMDB link buttons on phone show detail screen ([#723](https://github.com/justb81/watchbuddy/issues/723)) ([b8366ac](https://github.com/justb81/watchbuddy/commit/b8366ac9d8de2d91c07a3492350c74fd375b12ff)), closes [#717](https://github.com/justb81/watchbuddy/issues/717)
* search and add shows from Trakt on phone app ([#727](https://github.com/justb81/watchbuddy/issues/727)) ([23dcb89](https://github.com/justb81/watchbuddy/commit/23dcb893b34ecfe1c104e8fbca10e4eddd55685a))


### Bug Fixes

* correct Joyn scrobbler profile package name to current TV app ([08d4f5e](https://github.com/justb81/watchbuddy/commit/08d4f5e76c11d6680990c7f84beac1fd702db633)), closes [#715](https://github.com/justb81/watchbuddy/issues/715)
* deep-link Netflix TV app to specific episode ([#726](https://github.com/justb81/watchbuddy/issues/726)) ([239fbf6](https://github.com/justb81/watchbuddy/commit/239fbf627b1c8c5f524560371368e3084e4aa2d0))
* detect ARD and ZDF Mediathek as installed on TV ([#729](https://github.com/justb81/watchbuddy/issues/729)) ([be6cdc4](https://github.com/justb81/watchbuddy/commit/be6cdc473d81dde007d61d84289e38efb5625023)), closes [#718](https://github.com/justb81/watchbuddy/issues/718)
* map JustWatch technicalName amazonhbomax to TMDB provider 1825 ([#722](https://github.com/justb81/watchbuddy/issues/722)) ([c2cf5a1](https://github.com/justb81/watchbuddy/commit/c2cf5a18b626ac6a95ee166e0f12e4051939a92c))
* **tv:** fall back to launch intent when streaming app is present ([#724](https://github.com/justb81/watchbuddy/issues/724)) ([5d5be02](https://github.com/justb81/watchbuddy/commit/5d5be02d86d79bb257a1258d02a05813935c3090)), closes [#720](https://github.com/justb81/watchbuddy/issues/720)

## [0.37.0](https://github.com/justb81/watchbuddy/compare/v0.36.0...v0.37.0) (2026-05-13)


### Features

* one-tap mark-as-watched button on TV show detail screen ([#671](https://github.com/justb81/watchbuddy/issues/671)) ([0c83628](https://github.com/justb81/watchbuddy/commit/0c83628b560cd5931042e0c18d3a25326b85e24f))
* **tv:** persist TV home screen cache across restarts ([#547](https://github.com/justb81/watchbuddy/issues/547)) ([b7f9b41](https://github.com/justb81/watchbuddy/commit/b7f9b41700c046397e93734ab46308d87e6f0716))


### Bug Fixes

* **phone:** resolve detekt violations and test compilation errors in route modules ([#707](https://github.com/justb81/watchbuddy/issues/707)) ([1257c51](https://github.com/justb81/watchbuddy/commit/1257c51792d7b45bd039f77b9d2e2f365c81f651))
* resolve build warnings across phone, TV app, and tests ([#706](https://github.com/justb81/watchbuddy/issues/706)) ([0795d60](https://github.com/justb81/watchbuddy/commit/0795d6058822a68f49590b7e8c832dfb0ae9a222)), closes [#683](https://github.com/justb81/watchbuddy/issues/683)
* **tv:** use array-based combine overload for 6 StateFlows in ShowDetailViewModel ([#710](https://github.com/justb81/watchbuddy/issues/710)) ([f0a9345](https://github.com/justb81/watchbuddy/commit/f0a9345800a8b847d4d574847850963bac5a791c))

## [0.36.0](https://github.com/justb81/watchbuddy/compare/v0.35.3...v0.36.0) (2026-05-10)


### Features

* backend-served provider catalog ([#666](https://github.com/justb81/watchbuddy/issues/666)) ([a77601e](https://github.com/justb81/watchbuddy/commit/a77601e98061b1f1d78df598f1feb0914d5784a7))
* **phone:** bulk-mark earlier episodes as watched up to a chosen episode ([#680](https://github.com/justb81/watchbuddy/issues/680)) ([46276cf](https://github.com/justb81/watchbuddy/commit/46276cf9a89bfcf7bbeda0ccea965aba6276b95b))
* **tv:** manually mark episodes watched with per-user scope picker ([#217](https://github.com/justb81/watchbuddy/issues/217)) ([#682](https://github.com/justb81/watchbuddy/issues/682)) ([79305d5](https://github.com/justb81/watchbuddy/commit/79305d584d384d972c0abab55547fd3a421664d7))


### Bug Fixes

* merge Amazon entries, correct Joyn TV package, drop bundled asset ([#685](https://github.com/justb81/watchbuddy/issues/685)) ([5c62398](https://github.com/justb81/watchbuddy/commit/5c623984dc0e9bb9b0c34a46d756521615fa7471))

## [0.35.3](https://github.com/justb81/watchbuddy/compare/v0.35.2...v0.35.3) (2026-05-08)


### Bug Fixes

* **build:** add workflow_dispatch inputs for Play Store track/status ([#577](https://github.com/justb81/watchbuddy/issues/577)) ([b2c9ad9](https://github.com/justb81/watchbuddy/commit/b2c9ad9314d677c6a9425fed456524a41c23bf44))
* four quick-win security, stability, and test improvements ([#530](https://github.com/justb81/watchbuddy/issues/530), [#528](https://github.com/justb81/watchbuddy/issues/528), [#536](https://github.com/justb81/watchbuddy/issues/536), [#569](https://github.com/justb81/watchbuddy/issues/569)) ([#661](https://github.com/justb81/watchbuddy/issues/661)) ([17560fa](https://github.com/justb81/watchbuddy/commit/17560fad2213139bc94014c17617bc50325ee0fd))
* **phone:** harden CompanionHttpServer against multi-interface exposure and abuse ([#525](https://github.com/justb81/watchbuddy/issues/525)) ([#663](https://github.com/justb81/watchbuddy/issues/663)) ([26b1ab9](https://github.com/justb81/watchbuddy/commit/26b1ab9d820cb53b9472a8492e4f864073ddff61))
* **phone:** prevent LLM inference from exhausting Ktor worker pool ([#526](https://github.com/justb81/watchbuddy/issues/526)) ([#664](https://github.com/justb81/watchbuddy/issues/664)) ([63cfa54](https://github.com/justb81/watchbuddy/commit/63cfa54bc2e26b3fa8529cb18cb2c46603363dc3))
* raise-mem-limits-for-e4b-to-8gb ([#655](https://github.com/justb81/watchbuddy/issues/655)) ([c99a0ec](https://github.com/justb81/watchbuddy/commit/c99a0ec7010a7003131e0c33005eb9d3fada9339))
* **tv:** periodic cleanup coroutine for PlaybackIntentRegistry ([#546](https://github.com/justb81/watchbuddy/issues/546)) ([b2c9ad9](https://github.com/justb81/watchbuddy/commit/b2c9ad9314d677c6a9425fed456524a41c23bf44))
* typed Hilt qualifiers, workflow_dispatch for Play Store, periodic intent cleanup ([#573](https://github.com/justb81/watchbuddy/issues/573), [#577](https://github.com/justb81/watchbuddy/issues/577), [#546](https://github.com/justb81/watchbuddy/issues/546)) ([#660](https://github.com/justb81/watchbuddy/issues/660)) ([b2c9ad9](https://github.com/justb81/watchbuddy/commit/b2c9ad9314d677c6a9425fed456524a41c23bf44))
* validation hardening and TV robustness ([#533](https://github.com/justb81/watchbuddy/issues/533), [#544](https://github.com/justb81/watchbuddy/issues/544), [#549](https://github.com/justb81/watchbuddy/issues/549), [#568](https://github.com/justb81/watchbuddy/issues/568)) ([#662](https://github.com/justb81/watchbuddy/issues/662)) ([9cd80c0](https://github.com/justb81/watchbuddy/commit/9cd80c0829d33ccafe0d9f7fd4051a77f5300607))

## [0.35.2](https://github.com/justb81/watchbuddy/compare/v0.35.1...v0.35.2) (2026-05-02)


### Bug Fixes

* **ci:** rebase before push in stats workflow to avoid non-fast-forward failures ([#648](https://github.com/justb81/watchbuddy/issues/648)) ([34764ef](https://github.com/justb81/watchbuddy/commit/34764efb88c9be051f112aa119edcc5c5e4152db))

## [0.35.1](https://github.com/justb81/watchbuddy/compare/v0.35.0...v0.35.1) (2026-05-02)


### Bug Fixes

* **justwatch:** update technicalName keys to match current JustWatch API ([#645](https://github.com/justb81/watchbuddy/issues/645)) ([d71ab19](https://github.com/justb81/watchbuddy/commit/d71ab192b87f0a06c3fd6c02a336796f4d1395b7))

## [0.35.0](https://github.com/justb81/watchbuddy/compare/v0.34.0...v0.35.0) (2026-05-02)


### Features

* **security:** authenticate TV→phone HTTP with BLE-distributed bearer token ([#644](https://github.com/justb81/watchbuddy/issues/644)) ([87e6d46](https://github.com/justb81/watchbuddy/commit/87e6d46a4e69fd0b12f0c5ac5155f6771cbcd630))


### Bug Fixes

* **core:** add PII redactor to DiagnosticLog ring buffer ([#641](https://github.com/justb81/watchbuddy/issues/641)) ([183acd8](https://github.com/justb81/watchbuddy/commit/183acd82a1fe9d2ae9b65cab73adebd2306571ab))
* **core:** BleDiscoveryContract.decode returns sealed DecodeResult ([#639](https://github.com/justb81/watchbuddy/issues/639)) ([abc1b90](https://github.com/justb81/watchbuddy/commit/abc1b902471965acb659187969123c98bcb5166c)), closes [#534](https://github.com/justb81/watchbuddy/issues/534)
* **tv:** unregister InstalledAppsProbe receiver on process destroy + race-free cache ([#642](https://github.com/justb81/watchbuddy/issues/642)) ([f7aeb17](https://github.com/justb81/watchbuddy/commit/f7aeb17b0f7a84bfdc73092b3250643183b04507))

## [0.34.0](https://github.com/justb81/watchbuddy/compare/v0.33.2...v0.34.0) (2026-05-01)


### Features

* **phone:** user-configurable country override in Advanced Settings ([#633](https://github.com/justb81/watchbuddy/issues/633)) ([058464f](https://github.com/justb81/watchbuddy/commit/058464fda909dd96105ee8dfcb0451a515f697c9))


### Bug Fixes

* **core:** add RateLimitInterceptor for 429 Retry-After handling ([#634](https://github.com/justb81/watchbuddy/issues/634)) ([c341342](https://github.com/justb81/watchbuddy/commit/c341342356e3876a4cf915bbfb765f29c1baaa75))
* **core:** log DiagnosticLog warning for unmapped JustWatch technicalNames and add TMDB provider validation ([#637](https://github.com/justb81/watchbuddy/issues/637)) ([e2c6943](https://github.com/justb81/watchbuddy/commit/e2c6943a3f5bfa316b602d8c98921c1e9c15f5fd)), closes [#570](https://github.com/justb81/watchbuddy/issues/570)
* **core:** make CrashReporter.install() idempotent and add chaining tests ([#635](https://github.com/justb81/watchbuddy/issues/635)) ([5cd8998](https://github.com/justb81/watchbuddy/commit/5cd8998dfa18413c9259b372c74e069480076f24)), closes [#575](https://github.com/justb81/watchbuddy/issues/575)
* **phone:** handle ForegroundServiceStartNotAllowedException in CompanionService ([#631](https://github.com/justb81/watchbuddy/issues/631)) ([1a7e036](https://github.com/justb81/watchbuddy/commit/1a7e036c17edfcb4e5a7e20cc8ad7faa1be7fb0b)), closes [#630](https://github.com/justb81/watchbuddy/issues/630)
* **tv:** replace fragile pipe-format encoding in LastUsedProviderRepository with JSON ([#636](https://github.com/justb81/watchbuddy/issues/636)) ([4fe2e31](https://github.com/justb81/watchbuddy/commit/4fe2e314c0fd7437010e5974be03906e07e17b56)), closes [#550](https://github.com/justb81/watchbuddy/issues/550)
* **tv:** replace runBlocking in BootReceiver with goAsync + coroutine ([#638](https://github.com/justb81/watchbuddy/issues/638)) ([bfd9bf0](https://github.com/justb81/watchbuddy/commit/bfd9bf071bdea22d9551b9c795aad8d7bf043631))

## [0.33.2](https://github.com/justb81/watchbuddy/compare/v0.33.1...v0.33.2) (2026-05-01)


### Bug Fixes

* **phone:** cap avatar JPEG output at 200 KB and stream /avatar via respondFile ([#627](https://github.com/justb81/watchbuddy/issues/627)) ([4887109](https://github.com/justb81/watchbuddy/commit/488710908deb492d30935914d21eda7673292ba8))
* **phone:** remove duplicate companion service toggle from settings ([#623](https://github.com/justb81/watchbuddy/issues/623)) ([fa86611](https://github.com/justb81/watchbuddy/commit/fa866115ba8bee8b709c29f53e2f9fad2f832858))
* **phone:** show TV connection status in notification and home screen ([#629](https://github.com/justb81/watchbuddy/issues/629)) ([5a7c4c1](https://github.com/justb81/watchbuddy/commit/5a7c4c1937acfb407e165596ce694fcfb0182799)), closes [#519](https://github.com/justb81/watchbuddy/issues/519)
* **tv:** resolve YouTube/Joyn deep links, country code, and provider chip ([#620](https://github.com/justb81/watchbuddy/issues/620)) ([#624](https://github.com/justb81/watchbuddy/issues/624)) ([bad1c2c](https://github.com/justb81/watchbuddy/commit/bad1c2c09a0a5a57377f1e2f69715fc77ef0d76c))

## [0.33.1](https://github.com/justb81/watchbuddy/compare/v0.33.0...v0.33.1) (2026-04-30)


### Bug Fixes

* fix JustWatch 422 GRAPHQL_VALIDATION_FAILED on seasons/episodes queries ([#617](https://github.com/justb81/watchbuddy/issues/617)) ([0acad5c](https://github.com/justb81/watchbuddy/commit/0acad5cdfb9fe76e538b0183bb9166bbf6029e7c))
* **tv:** query WatchNext provider without selection clause to avoid AOSP SecurityException ([#619](https://github.com/justb81/watchbuddy/issues/619)) ([740c1e1](https://github.com/justb81/watchbuddy/commit/740c1e12e1cf9835e19a8aeee70ca7226b24c53c))

## [0.33.0](https://github.com/justb81/watchbuddy/compare/v0.32.4...v0.33.0) (2026-04-30)


### Features

* **core:** centralize MediaSessionScrobbler tuning knobs in ScrobbleTuning ([#603](https://github.com/justb81/watchbuddy/issues/603)) ([73fcdbe](https://github.com/justb81/watchbuddy/commit/73fcdbebea9ca3e09fcb0de22faf5ad141a74942))


### Bug Fixes

* **backend:** reduce default upstream timeout from 15 s to 8 s; add FETCH_TIMEOUT_MS override ([#607](https://github.com/justb81/watchbuddy/issues/607)) ([0b99614](https://github.com/justb81/watchbuddy/commit/0b996148a6b52b2b14c5927e51f944211ff91a9d)), closes [#560](https://github.com/justb81/watchbuddy/issues/560)
* **backend:** split /health into public minimal + authenticated /health/detailed ([#608](https://github.com/justb81/watchbuddy/issues/608)) ([cd057a1](https://github.com/justb81/watchbuddy/commit/cd057a1a2635d1b52877247c01428925afb6b4f8)), closes [#557](https://github.com/justb81/watchbuddy/issues/557)
* **ci:** replace continue-on-error SARIF uploads with retried REST API step ([#605](https://github.com/justb81/watchbuddy/issues/605)) ([e8129b8](https://github.com/justb81/watchbuddy/commit/e8129b8c414aa31ef93d0452436deda338d004a9))
* **phone:** eliminate read-modify-write races in ShowRepository and HomeViewModel ([#609](https://github.com/justb81/watchbuddy/issues/609)) ([8559458](https://github.com/justb81/watchbuddy/commit/8559458b7678c0137babf40109fc173db731ca1d)), closes [#532](https://github.com/justb81/watchbuddy/issues/532)
* **tv:** atomic Mutex creation in JustWatchDeepLinkRepository via ConcurrentHashMap ([#602](https://github.com/justb81/watchbuddy/issues/602)) ([bfb9c41](https://github.com/justb81/watchbuddy/commit/bfb9c41186969cca19ee2c24061ad6e7b8a74035))

## [0.32.4](https://github.com/justb81/watchbuddy/compare/v0.32.3...v0.32.4) (2026-04-30)


### Bug Fixes

* **core:** add explicit OkHttpClient timeouts and fix TokenProxyServiceFactory client reuse ([#599](https://github.com/justb81/watchbuddy/issues/599)) ([5779957](https://github.com/justb81/watchbuddy/commit/577995787f19fc0a3c50256d7d9a5a7c8932bec8)), closes [#564](https://github.com/justb81/watchbuddy/issues/564)
* **release:** harden signing-secret hygiene in release workflow ([#597](https://github.com/justb81/watchbuddy/issues/597)) ([5826d52](https://github.com/justb81/watchbuddy/commit/5826d525763ff16dad90f4f704937a0e9c9d8dee))
* **tv:** auto-heal WatchNextMetadataSource permissionDenied flag on out-of-band grant ([#600](https://github.com/justb81/watchbuddy/issues/600)) ([0a316fd](https://github.com/justb81/watchbuddy/commit/0a316fd7d6b4fa2841f586a68dff236325010cac))
* **tv:** validate /capability response and guard HTTP body access ([#601](https://github.com/justb81/watchbuddy/issues/601)) ([cf0ec7f](https://github.com/justb81/watchbuddy/commit/cf0ec7fea986bf6213db961b6308f0d7f405d7f9))

## [0.32.3](https://github.com/justb81/watchbuddy/compare/v0.32.2...v0.32.3) (2026-04-30)


### Bug Fixes

* **backend:** rate-limit and cache /health to close DoS amplification gap ([#588](https://github.com/justb81/watchbuddy/issues/588)) ([dc6f32d](https://github.com/justb81/watchbuddy/commit/dc6f32dea43494b5601c96222a4a3702910de81b)), closes [#556](https://github.com/justb81/watchbuddy/issues/556)
* **backend:** redact Trakt response body from error logs — emit structured traktErrorCode instead ([#596](https://github.com/justb81/watchbuddy/issues/596)) ([22e1af6](https://github.com/justb81/watchbuddy/commit/22e1af6caed74464531aa25fc610263b390f4652)), closes [#558](https://github.com/justb81/watchbuddy/issues/558)
* **backend:** register SIGTERM/SIGINT handlers for clean shutdown ([#559](https://github.com/justb81/watchbuddy/issues/559)) ([06c8873](https://github.com/justb81/watchbuddy/commit/06c887303dae3193c8a91b6745b7ddb1b256ae20))
* **backend:** set NODE_ENV=production in Dockerfile and modernise npm ci flag ([#587](https://github.com/justb81/watchbuddy/issues/587)) ([c8d2d9a](https://github.com/justb81/watchbuddy/commit/c8d2d9aeb08d270c90a26ebbe0f515176e720a9c)), closes [#554](https://github.com/justb81/watchbuddy/issues/554)
* **core:** redact sensitive headers in HttpLoggingInterceptor (Level.BODY → Level.HEADERS) ([#592](https://github.com/justb81/watchbuddy/issues/592)) ([3d593dc](https://github.com/justb81/watchbuddy/commit/3d593dc5fed37041950a144518dca23bc1868618))
* **phone:** harden AEAD token storage against Keystore failure, key-rename AAD mismatch, and silent decrypt errors ([#590](https://github.com/justb81/watchbuddy/issues/590)) ([f36ac16](https://github.com/justb81/watchbuddy/commit/f36ac1677f14d9d6eb96d3b7139f3957977bff2e))
* **phone:** plug NetworkCallback leak in WifiStateProvider and CompanionService ([#529](https://github.com/justb81/watchbuddy/issues/529)) ([a611607](https://github.com/justb81/watchbuddy/commit/a6116077e8aa830a4b623a56e91906fe4ccf82ca))
* **tv:** add idle timeout to TvDiscoveryService to avoid Android 14+ FGS quota exhaustion ([#591](https://github.com/justb81/watchbuddy/issues/591)) ([3d017f0](https://github.com/justb81/watchbuddy/commit/3d017f0f5e86565b5acd33f5fd047200eae6fb58))
* **tv:** add per-phone backoff and Wi-Fi gating to discovery heartbeat ([#594](https://github.com/justb81/watchbuddy/issues/594)) ([76c4c9f](https://github.com/justb81/watchbuddy/commit/76c4c9f983456439baa135f57805fb9e2bbbcbd1))
* **tv:** prevent duplicate ambiguous scrobble overlays on transient phone failure ([#593](https://github.com/justb81/watchbuddy/issues/593)) ([301a743](https://github.com/justb81/watchbuddy/commit/301a7439925fdf8e62749a43033e3e6c9e473297))
* **tv:** restore focus to previously-selected show on back from detail ([#585](https://github.com/justb81/watchbuddy/issues/585)) ([8e843f6](https://github.com/justb81/watchbuddy/commit/8e843f682e964002a4d93a6def49db011c40b884))

## [0.32.2](https://github.com/justb81/watchbuddy/compare/v0.32.1...v0.32.2) (2026-04-29)


### Bug Fixes

* **backend:** add 404 + global error handlers so unhandled errors don't leak internals ([#583](https://github.com/justb81/watchbuddy/issues/583)) ([d12e07c](https://github.com/justb81/watchbuddy/commit/d12e07c466b1372aedfa293a6e773fb6688713d8)), closes [#553](https://github.com/justb81/watchbuddy/issues/553)
* **backend:** sanitize Trakt error bodies and cap JSON request size ([#580](https://github.com/justb81/watchbuddy/issues/580)) ([dee441b](https://github.com/justb81/watchbuddy/commit/dee441b36813a9bb8036a547512403ecc3763d88)), closes [#551](https://github.com/justb81/watchbuddy/issues/551) [#552](https://github.com/justb81/watchbuddy/issues/552)
* **tv:** restore D-pad scroll on Diagnostics screen ([#584](https://github.com/justb81/watchbuddy/issues/584)) ([96ae9f5](https://github.com/justb81/watchbuddy/commit/96ae9f5760ed90264bab5a7d3f8426755defa348))

## [0.32.1](https://github.com/justb81/watchbuddy/compare/v0.32.0...v0.32.1) (2026-04-29)


### Bug Fixes

* **tv:** make Streaming Links "Clear cache" D-pad accessible ([#515](https://github.com/justb81/watchbuddy/issues/515)) ([008c930](https://github.com/justb81/watchbuddy/commit/008c930cb697d377614df1286171e6b7286dff12))
* **tv:** make Watch Next permission-denied row open app settings ([#512](https://github.com/justb81/watchbuddy/issues/512)) ([bb314b0](https://github.com/justb81/watchbuddy/commit/bb314b0ed56f74dd36527accfa8ac8fb368ededf))
* **tv:** regenerate launcher icon from master logo ([#516](https://github.com/justb81/watchbuddy/issues/516)) ([ec46df8](https://github.com/justb81/watchbuddy/commit/ec46df8e8765c26eb8a0c636084f82e30af7fdad))
* **tv:** stop diagnostics cards from clipping on D-pad focus ([#517](https://github.com/justb81/watchbuddy/issues/517)) ([d272971](https://github.com/justb81/watchbuddy/commit/d27297129b09f939a76e19fe55042a6874354749))
* **tv:** surface JustWatch HTTP errors and add browser-like headers ([#522](https://github.com/justb81/watchbuddy/issues/522)) ([c1f9742](https://github.com/justb81/watchbuddy/commit/c1f97420729fb9332249931bcb5560bcf0de820b))


### Performance Improvements

* **phone:** cache on-device LLM engines and warm up on service start ([#521](https://github.com/justb81/watchbuddy/issues/521)) ([84992d4](https://github.com/justb81/watchbuddy/commit/84992d447a4db4bddcdf0608725dd155394ed547))

## [0.32.0](https://github.com/justb81/watchbuddy/compare/v0.31.0...v0.32.0) (2026-04-29)


### Features

* **tv:** auto-add unknown shows to Trakt library on overlay confirm ([#468](https://github.com/justb81/watchbuddy/issues/468)) ([#509](https://github.com/justb81/watchbuddy/issues/509)) ([f10362a](https://github.com/justb81/watchbuddy/commit/f10362a212ca28e43f33a029147c284771befb85))
* **tv:** Watch-Now intent as Phase 0 scrobble hint ([#475](https://github.com/justb81/watchbuddy/issues/475)) ([#504](https://github.com/justb81/watchbuddy/issues/504)) ([da536a2](https://github.com/justb81/watchbuddy/commit/da536a257def4f94b9cf0cbdacecc234a6e78e29))


### Bug Fixes

* **core:** introduce nextUnwatchedEpisodeNumbers for ShowDetail ([#498](https://github.com/justb81/watchbuddy/issues/498)) ([#507](https://github.com/justb81/watchbuddy/issues/507)) ([da9daff](https://github.com/justb81/watchbuddy/commit/da9daff8392fa8a7f01fa2abaaa5baf1b213ccec))
* **core:** use count-based episodesBehind in ShowProgressCalculator ([#503](https://github.com/justb81/watchbuddy/issues/503)) ([03b2010](https://github.com/justb81/watchbuddy/commit/03b20107d1645c69483813522de145d9a13df03c))
* **tv:** disable Watch Now button when no streaming provider is available ([#510](https://github.com/justb81/watchbuddy/issues/510)) ([b981e00](https://github.com/justb81/watchbuddy/commit/b981e00d448df29ae37aaa6e978722843bf2919f)), closes [#496](https://github.com/justb81/watchbuddy/issues/496)
* **tv:** request READ_TV_LISTINGS at runtime + cache denial in WatchNextMetadataSource ([#500](https://github.com/justb81/watchbuddy/issues/500)) ([cb35eed](https://github.com/justb81/watchbuddy/commit/cb35eed88181d63447d7754f3ceb70def145e043))
* **tv:** show TMDB synopsis as recap fallback when companion is unreachable ([#508](https://github.com/justb81/watchbuddy/issues/508)) ([a8b31d1](https://github.com/justb81/watchbuddy/commit/a8b31d17da6641d121826bf40ccc8744c6c61718)), closes [#497](https://github.com/justb81/watchbuddy/issues/497)
* **tv:** surface JustWatch errors, stop caching negatives on hard failures ([#502](https://github.com/justb81/watchbuddy/issues/502)) ([55da643](https://github.com/justb81/watchbuddy/commit/55da643f3345d9970324a026c405f83404cfbeac))

## [0.31.0](https://github.com/justb81/watchbuddy/compare/v0.30.0...v0.31.0) (2026-04-28)


### Features

* **core:** AppProfiles registry — per-app field priority, marker regex, source gating ([#492](https://github.com/justb81/watchbuddy/issues/492)) ([6560f7b](https://github.com/justb81/watchbuddy/commit/6560f7bc47951ac276aa21cd37b2fdc726c0f762))
* **scrobbler:** ambiguous-scrobble prompt with top-3 candidates and runtime-affinity scoring ([#474](https://github.com/justb81/watchbuddy/issues/474)) ([#493](https://github.com/justb81/watchbuddy/issues/493)) ([b6e7c82](https://github.com/justb81/watchbuddy/commit/b6e7c8228ce838e9931b9f0016dbfa0bfffda320))
* **tv:** NotificationMetadataSource — harvest title/episode evidence from media notifications ([#491](https://github.com/justb81/watchbuddy/issues/491)) ([f984491](https://github.com/justb81/watchbuddy/commit/f98449152af0d683307bda7768b175fa37274fbf))
* **tv:** WatchNextMetadataSource — harvest currently-playing metadata from TvContract WatchNext provider ([#489](https://github.com/justb81/watchbuddy/issues/489)) ([54711e8](https://github.com/justb81/watchbuddy/commit/54711e8b5c3e9fcfdb082f32100a26a4ee71b48b))

## [0.30.0](https://github.com/justb81/watchbuddy/compare/v0.29.0...v0.30.0) (2026-04-28)


### Features

* **tv:** JustWatch-powered per-episode deep links with persistent cache ([#478](https://github.com/justb81/watchbuddy/issues/478)) ([d1a3202](https://github.com/justb81/watchbuddy/commit/d1a320243b5cd82f57a4a2b52af58c8464c5db95))
* **tv:** retry dropped scrobbles when phones become reachable again ([#484](https://github.com/justb81/watchbuddy/issues/484)) ([39ad52d](https://github.com/justb81/watchbuddy/commit/39ad52d77d1ba3da9272f4bf65467d17d2387cc9))


### Bug Fixes

* **core:** assume 100% progress when duration/position unavailable on scrobble stop ([#482](https://github.com/justb81/watchbuddy/issues/482)) ([0075cab](https://github.com/justb81/watchbuddy/commit/0075cab88417587671ea34c99115184395dfdbca))
* **tv:** track real notification access in MediaSessionScrobbler per poll tick ([#483](https://github.com/justb81/watchbuddy/issues/483)) ([2f5ac84](https://github.com/justb81/watchbuddy/commit/2f5ac847c32831066f9b7e0546c76163c738e4f6)), closes [#404](https://github.com/justb81/watchbuddy/issues/404)

## [0.29.0](https://github.com/justb81/watchbuddy/compare/v0.28.3...v0.29.0) (2026-04-23)


### Features

* **tv-diagnostics:** show full MediaSession snapshot and scrobble null-TITLE sessions ([#465](https://github.com/justb81/watchbuddy/issues/465)) ([306a3ef](https://github.com/justb81/watchbuddy/commit/306a3ef4c8a9b3da1ab93e36ccedbc04a121f8e1))


### Bug Fixes

* **phone:** fall back to CPU when LiteRT-LM GPU inference fails ([#467](https://github.com/justb81/watchbuddy/issues/467)) ([403b603](https://github.com/justb81/watchbuddy/commit/403b6036c531c34fe5e27d135ba7a5d25776f2e6))

## [0.28.3](https://github.com/justb81/watchbuddy/compare/v0.28.2...v0.28.3) (2026-04-23)


### Bug Fixes

* use connectedDevice FGS type for companion services ([#460](https://github.com/justb81/watchbuddy/issues/460)) ([dabd256](https://github.com/justb81/watchbuddy/commit/dabd25605eb34156fc807ace2de287e58535945a))

## [0.28.2](https://github.com/justb81/watchbuddy/compare/v0.28.1...v0.28.2) (2026-04-23)


### Bug Fixes

* address Android Lint warnings and raise Gradle metaspace ([#458](https://github.com/justb81/watchbuddy/issues/458)) ([2ab622c](https://github.com/justb81/watchbuddy/commit/2ab622cf083e19bb28efe57c7d9340fe1d44cb2c))
* address GitHub code-scanning alerts ([#454](https://github.com/justb81/watchbuddy/issues/454)) ([333dbf9](https://github.com/justb81/watchbuddy/commit/333dbf93260e4e48790870a9d0f9eff12f71ab2a))

## [0.28.1](https://github.com/justb81/watchbuddy/compare/v0.28.0...v0.28.1) (2026-04-23)


### Bug Fixes

* match Android unit-test XML path in README stats script ([#447](https://github.com/justb81/watchbuddy/issues/447)) ([737b5db](https://github.com/justb81/watchbuddy/commit/737b5dbdb8196070096953d1e207d1b999957f42))

## [0.28.0](https://github.com/justb81/watchbuddy/compare/v0.27.0...v0.28.0) (2026-04-22)


### Features

* **scrobbler:** multi-field MediaMetadata cascade + LLM fallback title extractor ([#414](https://github.com/justb81/watchbuddy/issues/414)) ([855e72e](https://github.com/justb81/watchbuddy/commit/855e72e8de10b4ccec803b84bac0fb463d51a60c))

## [0.27.0](https://github.com/justb81/watchbuddy/compare/v0.26.1...v0.27.0) (2026-04-22)


### Features

* **tv:** recent events section on Diagnostics + debug media-session firehose ([#409](https://github.com/justb81/watchbuddy/issues/409)) ([0d99701](https://github.com/justb81/watchbuddy/commit/0d99701fda412110392b4d105a0ec46035dfa4c9)), closes [#407](https://github.com/justb81/watchbuddy/issues/407)

## [0.26.1](https://github.com/justb81/watchbuddy/compare/v0.26.0...v0.26.1) (2026-04-21)


### Bug Fixes

* **core:** rescue silently-dropped scrobbles ([#401](https://github.com/justb81/watchbuddy/issues/401), [#402](https://github.com/justb81/watchbuddy/issues/402)) ([#406](https://github.com/justb81/watchbuddy/issues/406)) ([24a8e74](https://github.com/justb81/watchbuddy/commit/24a8e74126ea4f12786104460c40d12428b6508c))
* **phone:** move finished shows to "All Shows" instead of "Continue Watching" ([#399](https://github.com/justb81/watchbuddy/issues/399)) ([c465f4c](https://github.com/justb81/watchbuddy/commit/c465f4c72fb68f279e49fa15c4a06f8dbac4807d)), closes [#398](https://github.com/justb81/watchbuddy/issues/398)

## [0.26.0](https://github.com/justb81/watchbuddy/compare/v0.25.0...v0.26.0) (2026-04-20)


### Features

* **readme:** add auto-updated code statistics section ([#393](https://github.com/justb81/watchbuddy/issues/393)) ([4c6ae83](https://github.com/justb81/watchbuddy/commit/4c6ae83852732287360c4c46242b808da8bd1b2d))

## [0.25.0](https://github.com/justb81/watchbuddy/compare/v0.24.0...v0.25.0) (2026-04-20)


### Features

* **tv:** integrate TMDB watch providers, installed-app filter, and last-used ranking on ShowDetail ([#388](https://github.com/justb81/watchbuddy/issues/388)) ([387dd21](https://github.com/justb81/watchbuddy/commit/387dd211c7ea0b1af378c5d2b0a3b3e62f396768))


### Bug Fixes

* **ci:** match kotlinc error lines directly in build-log filter ([#389](https://github.com/justb81/watchbuddy/issues/389)) ([c45cb95](https://github.com/justb81/watchbuddy/commit/c45cb95913efea3c1ebce782180ac3dc388fcd60))

## [0.24.0](https://github.com/justb81/watchbuddy/compare/v0.23.0...v0.24.0) (2026-04-20)


### Features

* **tv:** show next-episode still image and actual title on ShowDetail ([#366](https://github.com/justb81/watchbuddy/issues/366)) ([#385](https://github.com/justb81/watchbuddy/issues/385)) ([7fe5ee8](https://github.com/justb81/watchbuddy/commit/7fe5ee81eb148b53e89809305e9a52b395e98bc7))

## [0.23.0](https://github.com/justb81/watchbuddy/compare/v0.22.0...v0.23.0) (2026-04-20)


### Features

* **ci:** add detekt, Android Lint SARIF and backend ESLint/Prettier with PR statistics ([#379](https://github.com/justb81/watchbuddy/issues/379)) ([7944a68](https://github.com/justb81/watchbuddy/commit/7944a68277079d4f93ae4579adef69faef06fd13))
* **home:** highlight shows with a new season available ([#363](https://github.com/justb81/watchbuddy/issues/363)) ([#383](https://github.com/justb81/watchbuddy/issues/383)) ([1c58701](https://github.com/justb81/watchbuddy/commit/1c587016d2acd7a59fd4bce46b34bf4936e83296))
* **home:** split HomeScreen into Continue Watching / All Shows by 30-day window ([#380](https://github.com/justb81/watchbuddy/issues/380)) ([c952510](https://github.com/justb81/watchbuddy/commit/c95251070af828ee0d8be7252fe4f8a48f1e8c1e))
* **phone:** multi-line layout for last-watched / last-aired on HomeScreen ([#359](https://github.com/justb81/watchbuddy/issues/359)) ([#384](https://github.com/justb81/watchbuddy/issues/384)) ([74bd7e9](https://github.com/justb81/watchbuddy/commit/74bd7e9da51c1c1b2c3f9d48fdc3b59d9fd99a4d))
* **tv:** hide fully-watched shows from TV HomeScreen ([#362](https://github.com/justb81/watchbuddy/issues/362)) ([#382](https://github.com/justb81/watchbuddy/issues/382)) ([d2ce611](https://github.com/justb81/watchbuddy/commit/d2ce611e3e4a26228d8dc0afcbc8d716aa56b07f))

## [0.22.0](https://github.com/justb81/watchbuddy/compare/v0.21.0...v0.22.0) (2026-04-19)


### Features

* **tv:** wire up MediaSession scrobbling end-to-end ([#376](https://github.com/justb81/watchbuddy/issues/376)) ([7110c05](https://github.com/justb81/watchbuddy/commit/7110c058bb99da55071119ae7c558287bd0a9960))

## [0.21.0](https://github.com/justb81/watchbuddy/compare/v0.20.1...v0.21.0) (2026-04-19)


### Features

* **phone,tv:** editable identity ([#342](https://github.com/justb81/watchbuddy/issues/342)) + derive active viewers from discovery ([#353](https://github.com/justb81/watchbuddy/issues/353)) ([#374](https://github.com/justb81/watchbuddy/issues/374)) ([31a2a0f](https://github.com/justb81/watchbuddy/commit/31a2a0f66788546ff9ca0e5641415569873f7d1f))


### Bug Fixes

* **phone:** exclude Season 0 specials from episodes-behind delta ([#357](https://github.com/justb81/watchbuddy/issues/357)) ([#371](https://github.com/justb81/watchbuddy/issues/371)) ([2606f20](https://github.com/justb81/watchbuddy/commit/2606f200620fe2e3e05e27c5c1650d6560bc597c))
* **phone:** pick highest S×E episode as last-watched, not latest timestamp ([#375](https://github.com/justb81/watchbuddy/issues/375)) ([a9e5067](https://github.com/justb81/watchbuddy/commit/a9e50674456f0e29c9008f4f8c3e6d16765d95f8))
* **tv:** fix title line-height overlap and remove redundant Back button on ShowDetail ([#373](https://github.com/justb81/watchbuddy/issues/373)) ([4465d26](https://github.com/justb81/watchbuddy/commit/4465d26c6c3b19a961c998a22203da8fb4ff7620)), closes [#364](https://github.com/justb81/watchbuddy/issues/364) [#365](https://github.com/justb81/watchbuddy/issues/365)

## [0.20.1](https://github.com/justb81/watchbuddy/compare/v0.20.0...v0.20.1) (2026-04-19)


### Bug Fixes

* **TV:** update german string for back ([#368](https://github.com/justb81/watchbuddy/issues/368)) ([ca261d9](https://github.com/justb81/watchbuddy/commit/ca261d9f6cfc42b021a363e5718459a137fa01fd))

## [0.20.0](https://github.com/justb81/watchbuddy/compare/v0.19.1...v0.20.0) (2026-04-19)


### Features

* BLE throttling + NSD re-register dedup ([#345](https://github.com/justb81/watchbuddy/issues/345) PR 2) ([#349](https://github.com/justb81/watchbuddy/issues/349)) ([5a987db](https://github.com/justb81/watchbuddy/commit/5a987dbc305056f7d374d3618a88e3a5786f77f9))
* **tv:** Settings hub, phone-discovery toggle, boot autostart ([#344](https://github.com/justb81/watchbuddy/issues/344)) ([#360](https://github.com/justb81/watchbuddy/issues/360)) ([ef00a9a](https://github.com/justb81/watchbuddy/commit/ef00a9ad83fca3d614ba05e2847e266c8cd6d816))

## [0.19.1](https://github.com/justb81/watchbuddy/compare/v0.19.0...v0.19.1) (2026-04-19)


### Bug Fixes

* BLE advertising overflow on Android 16 + TV discovery cleanup ([#345](https://github.com/justb81/watchbuddy/issues/345), [#281](https://github.com/justb81/watchbuddy/issues/281)) ([#347](https://github.com/justb81/watchbuddy/issues/347)) ([23928e5](https://github.com/justb81/watchbuddy/commit/23928e5ff8c9b3fbada25e0624eed5a0d6344c24))

## [0.19.0](https://github.com/justb81/watchbuddy/compare/v0.18.0...v0.19.0) (2026-04-19)


### Features

* **phone:** add pull-to-refresh to Home and Show Detail screens ([#339](https://github.com/justb81/watchbuddy/issues/339)) ([3c69418](https://github.com/justb81/watchbuddy/commit/3c6941811c2c8a68530b0ce615aac686a4a29247))
* **phone:** wire DiagnosticLog into companion service / HTTP / BLE / Wi-Fi ([#343](https://github.com/justb81/watchbuddy/issues/343)) ([741b004](https://github.com/justb81/watchbuddy/commit/741b00463e420fbaf8df6b799352e51a5f44b9f6))

## [0.18.0](https://github.com/justb81/watchbuddy/compare/v0.17.0...v0.18.0) (2026-04-19)


### Features

* in-app diagnostics view + Settings version footer ([#331](https://github.com/justb81/watchbuddy/issues/331), [#330](https://github.com/justb81/watchbuddy/issues/330)) ([#335](https://github.com/justb81/watchbuddy/issues/335)) ([76bc0d6](https://github.com/justb81/watchbuddy/commit/76bc0d6b6f00a11d9a4b53a7f3ed803cb4335548))


### Bug Fixes

* sort shows by last-watched DESC and episodes by number DESC ([#326](https://github.com/justb81/watchbuddy/issues/326)) ([#332](https://github.com/justb81/watchbuddy/issues/332)) ([0455054](https://github.com/justb81/watchbuddy/commit/0455054973fba8e206556216ebe5173fb8633a8c))

## [0.17.0](https://github.com/justb81/watchbuddy/compare/v0.16.3...v0.17.0) (2026-04-18)


### Features

* **discovery:** add BLE fallback channel for phone↔TV pairing ([#315](https://github.com/justb81/watchbuddy/issues/315)) ([f3ad543](https://github.com/justb81/watchbuddy/commit/f3ad543bf791093eff0c06353be8be579ca8882c))
* **phone:** current-season-first detail view with per-episode watched toggle ([#314](https://github.com/justb81/watchbuddy/issues/314)) ([34639b8](https://github.com/justb81/watchbuddy/commit/34639b80f897050a79f63dabad98fbb1827044eb))

## [0.16.3](https://github.com/justb81/watchbuddy/compare/v0.16.2...v0.16.3) (2026-04-18)


### Bug Fixes

* **phone:** gate companion service start on Wi-Fi connectivity ([#278](https://github.com/justb81/watchbuddy/issues/278)) ([#302](https://github.com/justb81/watchbuddy/issues/302)) ([4c7f921](https://github.com/justb81/watchbuddy/commit/4c7f921c9018f19fa9c4623446ab3550ee1b4504))
* **release:** upload per-AAB R8 mapping files to Play Store ([#273](https://github.com/justb81/watchbuddy/issues/273)) ([#303](https://github.com/justb81/watchbuddy/issues/303)) ([a59c268](https://github.com/justb81/watchbuddy/commit/a59c268187ead8cba226655028d0675353184ccc))

## [0.16.2](https://github.com/justb81/watchbuddy/compare/v0.16.1...v0.16.2) (2026-04-18)


### Bug Fixes

* **phone:** remove phone-side auto-scrobble and notification-listener permission ([#272](https://github.com/justb81/watchbuddy/issues/272)) ([5f8dd4f](https://github.com/justb81/watchbuddy/commit/5f8dd4fdadc9d8692afaf3493ad497f61eb4eaf5)), closes [#269](https://github.com/justb81/watchbuddy/issues/269)
* **release:** revert Play release name grouping and upload native debug symbols ([#270](https://github.com/justb81/watchbuddy/issues/270)) ([e30174a](https://github.com/justb81/watchbuddy/commit/e30174a8e2e145a95e25d60f9bbf754083b612c5))

## [0.16.1](https://github.com/justb81/watchbuddy/compare/v0.16.0...v0.16.1) (2026-04-18)


### Bug Fixes

* **phone:** harden NSD registration lifecycle and cross-device discovery ([#268](https://github.com/justb81/watchbuddy/issues/268)) ([f14dd81](https://github.com/justb81/watchbuddy/commit/f14dd81c089754716753a0bb8bde4a2312c92c9b))
* **phone:** show foreground notification while watching TV ([#263](https://github.com/justb81/watchbuddy/issues/263)) ([2f833ed](https://github.com/justb81/watchbuddy/commit/2f833ed94f7d071a26c299899dbdcf7b7e9d4e78))

## [0.16.0](https://github.com/justb81/watchbuddy/compare/v0.15.1...v0.16.0) (2026-04-18)


### Features

* **phone:** auto-scrobble MediaSessions on phone, share scrobbler from core ([#257](https://github.com/justb81/watchbuddy/issues/257)) ([23a74e0](https://github.com/justb81/watchbuddy/commit/23a74e0c070b0736d69cfc586ab9e1f28f291d1a))


### Bug Fixes

* diagnose + harden TV phone discovery (closes [#259](https://github.com/justb81/watchbuddy/issues/259)) ([#260](https://github.com/justb81/watchbuddy/issues/260)) ([cc0d8a0](https://github.com/justb81/watchbuddy/commit/cc0d8a0a25dbfdd2b5dbcffa713de84903257cf8))

## [0.15.1](https://github.com/justb81/watchbuddy/compare/v0.15.0...v0.15.1) (2026-04-17)


### Bug Fixes

* **tv:** make phone discovery reliable on Google TV via multicast lock + retry ([#256](https://github.com/justb81/watchbuddy/issues/256)) ([3673252](https://github.com/justb81/watchbuddy/commit/367325251eeb4e13203f771559b0e484382ab181))
* **tv:** remove diagnostic surface and unsafe setContent guard (closes [#252](https://github.com/justb81/watchbuddy/issues/252)) ([#254](https://github.com/justb81/watchbuddy/issues/254)) ([eebdc2c](https://github.com/justb81/watchbuddy/commit/eebdc2c88776d4abdcebd144908183241169d30e))

## [0.15.0](https://github.com/justb81/watchbuddy/compare/v0.14.5...v0.15.0) (2026-04-17)


### Features

* **home:** show episode progress vs aired episodes with collapsible all-shows section (closes [#239](https://github.com/justb81/watchbuddy/issues/239)) ([#248](https://github.com/justb81/watchbuddy/issues/248)) ([cab074b](https://github.com/justb81/watchbuddy/commit/cab074b4fe25340b645a22bdc3b68fb188de67b3))
* **tv:** send real playback progress to Trakt instead of hardcoded 0/50/100 ([#249](https://github.com/justb81/watchbuddy/issues/249)) ([11a0f86](https://github.com/justb81/watchbuddy/commit/11a0f863d2e11fd72a44c66ba0c84634efec2ba2))


### Bug Fixes

* **tv:** pin Retrofit service interfaces to stop R8 CCE on app launch ([#251](https://github.com/justb81/watchbuddy/issues/251)) ([912c57a](https://github.com/justb81/watchbuddy/commit/912c57a37972aaa08a7db8cec56a3f63f0b09ed1)), closes [#247](https://github.com/justb81/watchbuddy/issues/247) [#238](https://github.com/justb81/watchbuddy/issues/238)

## [0.14.5](https://github.com/justb81/watchbuddy/compare/v0.14.4...v0.14.5) (2026-04-17)


### Bug Fixes

* **tv:** exclude androidx.work from classpath and harden Room R8 keeps (refs [#244](https://github.com/justb81/watchbuddy/issues/244) [#238](https://github.com/justb81/watchbuddy/issues/238)) ([#245](https://github.com/justb81/watchbuddy/issues/245)) ([8e845b2](https://github.com/justb81/watchbuddy/commit/8e845b22acd88f89aab6e62602195c216daaee1c))

## [0.14.4](https://github.com/justb81/watchbuddy/compare/v0.14.3...v0.14.4) (2026-04-17)


### Bug Fixes

* **tv:** harden startup path and add early crash capture (refs [#238](https://github.com/justb81/watchbuddy/issues/238)) ([#242](https://github.com/justb81/watchbuddy/issues/242)) ([bb7c379](https://github.com/justb81/watchbuddy/commit/bb7c3797ba81dfa5ae172df1a7861f24fd15de48))

## [0.14.3](https://github.com/justb81/watchbuddy/compare/v0.14.2...v0.14.3) (2026-04-17)


### Bug Fixes

* **tv:** remove broken service declarations that block app launch ([#238](https://github.com/justb81/watchbuddy/issues/238)) ([#240](https://github.com/justb81/watchbuddy/issues/240)) ([81bafa7](https://github.com/justb81/watchbuddy/commit/81bafa738746ee1cdc8cb838aa71bed6136f2ce5))

## [0.14.2](https://github.com/justb81/watchbuddy/compare/v0.14.1...v0.14.2) (2026-04-17)


### Bug Fixes

* **auth:** stop breaking Trakt device-flow polling on pending 400 responses ([#236](https://github.com/justb81/watchbuddy/issues/236)) ([eb677fe](https://github.com/justb81/watchbuddy/commit/eb677fef06a34761b03a26085d4f649e4a5e1fbd)), closes [#235](https://github.com/justb81/watchbuddy/issues/235)

## [0.14.1](https://github.com/justb81/watchbuddy/compare/v0.14.0...v0.14.1) (2026-04-17)


### Bug Fixes

* **build:** preserve Room no-arg constructors in R8 + native debug symbols (closes [#232](https://github.com/justb81/watchbuddy/issues/232)) ([#233](https://github.com/justb81/watchbuddy/issues/233)) ([2219f25](https://github.com/justb81/watchbuddy/commit/2219f25e684c282a0c4d6d8633b5e4c52a99cfbc))

## [0.14.0](https://github.com/justb81/watchbuddy/compare/v0.13.10...v0.14.0) (2026-04-17)


### Features

* **diagnostics:** capture crashes and breadcrumbs so the Settings bug becomes observable ([#230](https://github.com/justb81/watchbuddy/issues/230)) ([cbf0620](https://github.com/justb81/watchbuddy/commit/cbf062097005cc363c15e36eb7dbafa5fea6d6a6))

## [0.13.10](https://github.com/justb81/watchbuddy/compare/v0.13.9...v0.13.10) (2026-04-17)


### Bug Fixes

* **phone:** cancel stale coroutines on Retry and distinguish server misconfiguration ([#228](https://github.com/justb81/watchbuddy/issues/228)) ([f598d45](https://github.com/justb81/watchbuddy/commit/f598d459947723ac594888d266e35e833bced87c))

## [0.13.9](https://github.com/justb81/watchbuddy/compare/v0.13.8...v0.13.9) (2026-04-16)


### Bug Fixes

* **phone:** prevent settings force-close via CoroutineExceptionHandler safety net (closes [#224](https://github.com/justb81/watchbuddy/issues/224)) ([#226](https://github.com/justb81/watchbuddy/issues/226)) ([4761887](https://github.com/justb81/watchbuddy/commit/4761887dbaae368a1d3c59d5fc0d809192e18568))

## [0.13.8](https://github.com/justb81/watchbuddy/compare/v0.13.7...v0.13.8) (2026-04-16)


### Bug Fixes

* **build:** use whatsnew- filename prefix for Play Store release notes ([#221](https://github.com/justb81/watchbuddy/issues/221)) ([49a3c61](https://github.com/justb81/watchbuddy/commit/49a3c61317b10a422187ecd57264502ec160b1a1)), closes [#219](https://github.com/justb81/watchbuddy/issues/219)

## [0.13.7](https://github.com/justb81/watchbuddy/compare/v0.13.6...v0.13.7) (2026-04-16)


### Bug Fixes

* resolve Trakt auth errors — trust proxy, 401/403 handling ([#210](https://github.com/justb81/watchbuddy/issues/210), [#211](https://github.com/justb81/watchbuddy/issues/211)) ([#213](https://github.com/justb81/watchbuddy/issues/213)) ([dee1052](https://github.com/justb81/watchbuddy/commit/dee10528556b889c57915a524058317ddc4f7b26))

## [0.13.6](https://github.com/justb81/watchbuddy/compare/v0.13.5...v0.13.6) (2026-04-16)


### Bug Fixes

* **backend:** read User-Agent version from package.json ([#208](https://github.com/justb81/watchbuddy/issues/208)) ([e9dca19](https://github.com/justb81/watchbuddy/commit/e9dca190bd788b16cdb8ca4d42ffd81fffd4a43b))

## [0.13.5](https://github.com/justb81/watchbuddy/compare/v0.13.4...v0.13.5) (2026-04-16)


### Bug Fixes

* **backend:** add User-Agent header to Trakt API requests ([#206](https://github.com/justb81/watchbuddy/issues/206)) ([3bfbbe8](https://github.com/justb81/watchbuddy/commit/3bfbbe8154971c1dbfefcb85ddcbd6548a344cdb))

## [0.13.4](https://github.com/justb81/watchbuddy/compare/v0.13.3...v0.13.4) (2026-04-16)


### Bug Fixes

* **backend:** fix token refresh, credential verification, and device flow handling ([#204](https://github.com/justb81/watchbuddy/issues/204)) ([0b147bf](https://github.com/justb81/watchbuddy/commit/0b147bf3e6639ba8988bd8df6b6a6be23e8d4d9c))

## [0.13.3](https://github.com/justb81/watchbuddy/compare/v0.13.2...v0.13.3) (2026-04-16)


### Bug Fixes

* resolve issues [#187](https://github.com/justb81/watchbuddy/issues/187), [#192](https://github.com/justb81/watchbuddy/issues/192), [#193](https://github.com/justb81/watchbuddy/issues/193) — settings UX and build warnings ([#202](https://github.com/justb81/watchbuddy/issues/202)) ([2962ca0](https://github.com/justb81/watchbuddy/commit/2962ca0a692100a3c1b8664388c8756ff97646c4))

## [0.13.2](https://github.com/justb81/watchbuddy/compare/v0.13.1...v0.13.2) (2026-04-16)


### Bug Fixes

* **backend:** use /certifications/shows for Trakt credential check ([#200](https://github.com/justb81/watchbuddy/issues/200)) ([1dd660f](https://github.com/justb81/watchbuddy/commit/1dd660ff2d7d047235b4d191bc3171738c36f9f4))
* **phone:** guard all TokenRepository calls against Keystore unavailability (closes [#196](https://github.com/justb81/watchbuddy/issues/196)) ([#199](https://github.com/justb81/watchbuddy/issues/199)) ([eed0d72](https://github.com/justb81/watchbuddy/commit/eed0d72d194c9a941a5cabce815631bdab727f88))

## [0.13.1](https://github.com/justb81/watchbuddy/compare/v0.13.0...v0.13.1) (2026-04-15)


### Bug Fixes

* **backend:** add missing Trakt API headers and redirect_uri to fix 403 errors ([#197](https://github.com/justb81/watchbuddy/issues/197)) ([ed79184](https://github.com/justb81/watchbuddy/commit/ed79184e05f76c421d798fbf51839a7f9ee67f5f))

## [0.13.0](https://github.com/justb81/watchbuddy/compare/v0.12.0...v0.13.0) (2026-04-15)


### Features

* **backend:** use runtime environment variables instead of .env file ([#194](https://github.com/justb81/watchbuddy/issues/194)) ([c63c45a](https://github.com/justb81/watchbuddy/commit/c63c45ada9e116609e0b48d800fa94400b7bf680))

## [0.12.0](https://github.com/justb81/watchbuddy/compare/v0.11.1...v0.12.0) (2026-04-15)


### Features

* refactor watch/checkin process ([#188](https://github.com/justb81/watchbuddy/issues/188)) ([#189](https://github.com/justb81/watchbuddy/issues/189)) ([80eb399](https://github.com/justb81/watchbuddy/commit/80eb3995f6d2cb4b2c1bbbc8fe59d5851755131d))


### Bug Fixes

* **backend:** validate Trakt credentials at startup and improve error logging ([#190](https://github.com/justb81/watchbuddy/issues/190)) ([d3c9727](https://github.com/justb81/watchbuddy/commit/d3c97270d0b46d37aa7931650de2b4c3b8fde6ee))

## [0.11.1](https://github.com/justb81/watchbuddy/compare/v0.11.0...v0.11.1) (2026-04-15)


### Bug Fixes

* handle non-JSON Trakt responses and fix SELF_HOSTED client ID (closes [#182](https://github.com/justb81/watchbuddy/issues/182), closes [#183](https://github.com/justb81/watchbuddy/issues/183)) ([#185](https://github.com/justb81/watchbuddy/issues/185)) ([63b26fd](https://github.com/justb81/watchbuddy/commit/63b26fd5ed5422a449a5d173f12173238e2cff3a))

## [0.11.0](https://github.com/justb81/watchbuddy/compare/v0.10.0...v0.11.0) (2026-04-15)


### Features

* **ci:** send changelog to Play Console on release ([#178](https://github.com/justb81/watchbuddy/issues/178)) ([9a6f3f2](https://github.com/justb81/watchbuddy/commit/9a6f3f2006e7ea84d6c2ffb9a5ff9f676b56fa9c))


### Bug Fixes

* **phone:** improve Trakt connect flow ([#176](https://github.com/justb81/watchbuddy/issues/176)) ([#180](https://github.com/justb81/watchbuddy/issues/180)) ([8df9751](https://github.com/justb81/watchbuddy/commit/8df9751d2d1c167d5dba96474b055d169ac210ef))
* **phone:** prevent force close when opening settings screen ([#177](https://github.com/justb81/watchbuddy/issues/177)) ([#181](https://github.com/justb81/watchbuddy/issues/181)) ([7430f67](https://github.com/justb81/watchbuddy/commit/7430f67599c642f2b3fd51856151306ae8e0cd84))

## [0.10.0](https://github.com/justb81/watchbuddy/compare/v0.9.0...v0.10.0) (2026-04-15)


### Features

* **discovery:** publish NSD TXT records on phone and cache them on TV for bestPhone ranking ([#174](https://github.com/justb81/watchbuddy/issues/174)) ([9d6c96d](https://github.com/justb81/watchbuddy/commit/9d6c96de3df26a1d430c10aa76a7a6e1dbf47b61))

## [0.9.0](https://github.com/justb81/watchbuddy/compare/v0.8.0...v0.9.0) (2026-04-15)


### Features

* show Trakt library on phone app + fix [#167](https://github.com/justb81/watchbuddy/issues/167)/[#169](https://github.com/justb81/watchbuddy/issues/169) ([#171](https://github.com/justb81/watchbuddy/issues/171)) ([1f56f52](https://github.com/justb81/watchbuddy/commit/1f56f5272e9bdf9236b9a8358fbbaf7327a04007))


### Bug Fixes

* **phone:** prevent force close when opening settings screen ([#170](https://github.com/justb81/watchbuddy/issues/170)) ([d3c1c3c](https://github.com/justb81/watchbuddy/commit/d3c1c3ca5646beb8edd76d49da0156c2699d09b0)), closes [#168](https://github.com/justb81/watchbuddy/issues/168)

## [0.8.0](https://github.com/justb81/watchbuddy/compare/v0.7.2...v0.8.0) (2026-04-15)


### Features

* **phone:** automatic Trakt token refresh via TokenRefreshManager ([#157](https://github.com/justb81/watchbuddy/issues/157)) ([3b186e0](https://github.com/justb81/watchbuddy/commit/3b186e07ecc34f72c495eb2266eeede2c7baaa7e))


### Bug Fixes

* **phone:** handle Trakt API exceptions in ShowRepository and serve stale cache ([#159](https://github.com/justb81/watchbuddy/issues/159)) ([19ed289](https://github.com/justb81/watchbuddy/commit/19ed2899908936c5cc41697f329bc5d494d6c814)), closes [#158](https://github.com/justb81/watchbuddy/issues/158)
* **phone:** replace exception messages in HTTP error responses with generic strings ([#161](https://github.com/justb81/watchbuddy/issues/161)) ([752e3cb](https://github.com/justb81/watchbuddy/commit/752e3cb7f0ffdb03cc77681aefc890e32d877c62)), closes [#160](https://github.com/justb81/watchbuddy/issues/160)
* R8 missing class + route TV scrobble events through phone HTTP API ([#165](https://github.com/justb81/watchbuddy/issues/165)) ([833127c](https://github.com/justb81/watchbuddy/commit/833127c4df85eeafaa920ebc55e32e32f47e5c63))
* **tv:** add missing permission, recommended TV manifest settings, and optimize versionCode scheme ([#155](https://github.com/justb81/watchbuddy/issues/155)) ([d31973f](https://github.com/justb81/watchbuddy/commit/d31973f1c6f100f148dd9a3c9b02cfc55739c89b))

## [0.7.2](https://github.com/justb81/watchbuddy/compare/v0.7.1...v0.7.2) (2026-04-14)


### Bug Fixes

* **tv:** resolve deep links for slug-only and no-variable services when tmdb_id is absent ([#152](https://github.com/justb81/watchbuddy/issues/152)) ([d98b52b](https://github.com/justb81/watchbuddy/commit/d98b52bc16fea02123312b6b9c7764b210d1e19d))
* use draft status for Play Store uploads on pre-1.0.0 releases ([#153](https://github.com/justb81/watchbuddy/issues/153)) ([690724c](https://github.com/justb81/watchbuddy/commit/690724c19c408afef9955a65f9b32b386f02316b))

## [0.7.1](https://github.com/justb81/watchbuddy/compare/v0.7.0...v0.7.1) (2026-04-14)


### Bug Fixes

* **tv:** add R8 keep rule for errorprone annotations (Tink transitive dep) ([#149](https://github.com/justb81/watchbuddy/issues/149)) ([c4100a6](https://github.com/justb81/watchbuddy/commit/c4100a6f37f9c26f78b0f043e685ac62fdf75c3c)), closes [#148](https://github.com/justb81/watchbuddy/issues/148)

## [0.7.0](https://github.com/justb81/watchbuddy/compare/v0.6.1...v0.7.0) (2026-04-14)


### Features

* **ci:** automate Google Play Store publishing via release workflow ([#145](https://github.com/justb81/watchbuddy/issues/145)) ([70018f0](https://github.com/justb81/watchbuddy/commit/70018f0cb5c6e88b695c10da975d7cb827eb1cab))
* **phone:** default TMDB API key + clickable registration link ([#142](https://github.com/justb81/watchbuddy/issues/142)) ([8023204](https://github.com/justb81/watchbuddy/commit/802320423118e910f90e9366eacd95ced17db33c))


### Bug Fixes

* **phone:** group advanced settings by function and fix toggle label ([#144](https://github.com/justb81/watchbuddy/issues/144)) ([dd163be](https://github.com/justb81/watchbuddy/commit/dd163be82c580a615f67314367f8238f705a9953)), closes [#134](https://github.com/justb81/watchbuddy/issues/134)

## [0.6.1](https://github.com/justb81/watchbuddy/compare/v0.6.0...v0.6.1) (2026-04-14)


### Bug Fixes

* **ci:** pass Trakt secrets to Gradle build steps ([#136](https://github.com/justb81/watchbuddy/issues/136)) ([c5c105d](https://github.com/justb81/watchbuddy/commit/c5c105d3504e5b6a3326cc40798885620636c9ab)), closes [#135](https://github.com/justb81/watchbuddy/issues/135)

## [0.6.0](https://github.com/justb81/watchbuddy/compare/v0.5.1...v0.6.0) (2026-04-14)


### Features

* **accessibility:** add contentDescriptions to interactive TV UI elements for TalkBack ([#129](https://github.com/justb81/watchbuddy/issues/129)) ([bc23e6e](https://github.com/justb81/watchbuddy/commit/bc23e6e8fdbd046e71f412500d18e70e1ab5ccad))
* **tv:** add pagination to TvHomeViewModel show loading for large libraries ([#131](https://github.com/justb81/watchbuddy/issues/131)) ([f69e2e1](https://github.com/justb81/watchbuddy/commit/f69e2e18117c540b90a87b22b822c962322efb47))


### Bug Fixes

* **build:** sign debug APKs with release keystore to enable seamless upgrades ([#128](https://github.com/justb81/watchbuddy/issues/128)) ([9582c0b](https://github.com/justb81/watchbuddy/commit/9582c0b7983376ec22761a7cc8d8f518ce096553)), closes [#105](https://github.com/justb81/watchbuddy/issues/105)

## [0.5.1](https://github.com/justb81/watchbuddy/compare/v0.5.0...v0.5.1) (2026-04-14)


### Bug Fixes

* **onboarding:** improve NotConfigured state with specific reasons and error UI ([#125](https://github.com/justb81/watchbuddy/issues/125)) ([2f22d79](https://github.com/justb81/watchbuddy/commit/2f22d792644579cc230780a665500d8347af5304))
* **scrobble:** scrobble to Trakt for all connected users, not just the best phone ([#124](https://github.com/justb81/watchbuddy/issues/124)) ([11b2b44](https://github.com/justb81/watchbuddy/commit/11b2b442f000bf6214b275937be0f79e312bef4d))
* **tmdb:** fix Prime Video deep link, remove dead code, add response cache ([#122](https://github.com/justb81/watchbuddy/issues/122)) ([3e75fdb](https://github.com/justb81/watchbuddy/commit/3e75fdb7f77fa76defea2b244d396c9529c96638))
* **tv:** surface actionable error messages when companion phone is unreachable ([#126](https://github.com/justb81/watchbuddy/issues/126)) ([5372014](https://github.com/justb81/watchbuddy/commit/5372014ba7876c0f97e637bf3ca98d5a61939d4f))

## [0.5.0](https://github.com/justb81/watchbuddy/compare/v0.4.0...v0.5.0) (2026-04-14)


### Features

* **backend:** add debug request logging toggled via DEBUG_MODE env var ([#121](https://github.com/justb81/watchbuddy/issues/121)) ([1d24dd4](https://github.com/justb81/watchbuddy/commit/1d24dd4a0ee4597356a9c9dfa89e6b016b33f114)), closes [#106](https://github.com/justb81/watchbuddy/issues/106)


### Bug Fixes

* resolve TMDB language, API key validation, HTML encoding, and parallel fetches ([#119](https://github.com/justb81/watchbuddy/issues/119)) ([daa3cc9](https://github.com/justb81/watchbuddy/commit/daa3cc900594ecc6d89121f2ecc330883d56c2f6))

## [0.4.0](https://github.com/justb81/watchbuddy/compare/v0.3.0...v0.4.0) (2026-04-14)


### Features

* implement AICore LLM provider with Gemini Nano ([#101](https://github.com/justb81/watchbuddy/issues/101)) ([fcbc388](https://github.com/justb81/watchbuddy/commit/fcbc3887300d776b58fdeecca2d170debe933409)), closes [#83](https://github.com/justb81/watchbuddy/issues/83)


### Bug Fixes

* make Connect with Trakt work for all auth modes ([#104](https://github.com/justb81/watchbuddy/issues/104)) ([4583f3c](https://github.com/justb81/watchbuddy/commit/4583f3c6928c823f788ffbf06c56f23e9b756438))
* prevent OOM crash during model download and add GPU fallback ([#103](https://github.com/justb81/watchbuddy/issues/103)) ([b5eb14d](https://github.com/justb81/watchbuddy/commit/b5eb14d41b1675603753862df85f44f3147207ea))

## [0.3.0](https://github.com/justb81/watchbuddy/compare/v0.2.2...v0.3.0) (2026-04-13)


### Features

* add TMDB API key configuration UI and propagation ([#94](https://github.com/justb81/watchbuddy/issues/94)) ([#97](https://github.com/justb81/watchbuddy/issues/97)) ([d7f9c00](https://github.com/justb81/watchbuddy/commit/d7f9c00e39e11643116cc0c4a98234be5ddc7c82))


### Bug Fixes

* add Connect to Trakt buttons to Home and Settings screens ([#95](https://github.com/justb81/watchbuddy/issues/95)) ([abb962c](https://github.com/justb81/watchbuddy/commit/abb962c6752171c1895742e1f2193a9cf18ef19d)), closes [#93](https://github.com/justb81/watchbuddy/issues/93)

## [0.2.2](https://github.com/justb81/watchbuddy/compare/v0.2.1...v0.2.2) (2026-04-13)


### Bug Fixes

* add skip button and NotConfigured state to onboarding screen ([#91](https://github.com/justb81/watchbuddy/issues/91)) ([79d3d27](https://github.com/justb81/watchbuddy/commit/79d3d2723532605ecfd088b683e78e5fb952e922)), closes [#90](https://github.com/justb81/watchbuddy/issues/90)

## [0.2.1](https://github.com/justb81/watchbuddy/compare/v0.2.0...v0.2.1) (2026-04-13)


### Bug Fixes

* correct activity class references in AndroidManifest.xml ([#88](https://github.com/justb81/watchbuddy/issues/88)) ([a467336](https://github.com/justb81/watchbuddy/commit/a46733650581b059f2f733c44b8c19a78d31d732)), closes [#86](https://github.com/justb81/watchbuddy/issues/86)

## [0.2.0](https://github.com/justb81/watchbuddy/compare/v0.1.5...v0.2.0) (2026-04-13)


### Features

* migrate from MediaPipe Tasks to LiteRT-LM runtime with Gemma 4 models ([#85](https://github.com/justb81/watchbuddy/issues/85)) ([7d417a3](https://github.com/justb81/watchbuddy/commit/7d417a35649d214ab166a84f50db54af9ab086dc))
* Remove node-fetch dependency and use native fetch API ([#75](https://github.com/justb81/watchbuddy/issues/75)) ([c447743](https://github.com/justb81/watchbuddy/commit/c447743791fca3ec96edfb96d9601da291cc406e))
* replace hardcoded colors with MaterialTheme tokens and align brand identity ([#50](https://github.com/justb81/watchbuddy/issues/50)) ([#81](https://github.com/justb81/watchbuddy/issues/81)) ([ead1a92](https://github.com/justb81/watchbuddy/commit/ead1a924e750cdbf375831379f0b7e04f970a24c))


### Bug Fixes

* resolve P2-medium i18n, safety, and dependency issues ([#79](https://github.com/justb81/watchbuddy/issues/79)) ([b2c3129](https://github.com/justb81/watchbuddy/commit/b2c31296933ef4a153e19471010982b861dc0149))

## [0.1.5](https://github.com/justb81/watchbuddy/compare/v0.1.4...v0.1.5) (2026-04-13)


### Bug Fixes

* **backend:** harden token proxy with timeouts, validation, and security headers ([7621b23](https://github.com/justb81/watchbuddy/commit/7621b236186f202318f66aff480443a785cd85f0))
* **backend:** harden token proxy with timeouts, validation, and security headers ([27e61e0](https://github.com/justb81/watchbuddy/commit/27e61e07f06b96dfea92b12223440b1426c563d4)), closes [#45](https://github.com/justb81/watchbuddy/issues/45)
* declare ES module type in backend package.json ([6fcc5ed](https://github.com/justb81/watchbuddy/commit/6fcc5eda4ef1af91a4d7657394e1c36654d91941))
* declare ES module type in backend package.json ([16e1ae5](https://github.com/justb81/watchbuddy/commit/16e1ae57bbb9b1c30699fc285456e197761191f5)), closes [#69](https://github.com/justb81/watchbuddy/issues/69)

## [0.1.4](https://github.com/justb81/watchbuddy/compare/v0.1.3...v0.1.4) (2026-04-13)


### Features

* add comprehensive unit test suite with JUnit 5 ([5341c0e](https://github.com/justb81/watchbuddy/commit/5341c0e2054c6737eb4809ad5e4f76829f6ad5b3))
* add comprehensive unit test suite with JUnit 5 ([ce823ec](https://github.com/justb81/watchbuddy/commit/ce823ec6273405cfe56eefb1175f7b15f0891acc))
* make Ollama server URL configurable via Settings UI ([e0e7dc3](https://github.com/justb81/watchbuddy/commit/e0e7dc39684b08eb5cfc1b67db08bdde587f9df4))
* make Ollama server URL configurable via Settings UI ([6260b2f](https://github.com/justb81/watchbuddy/commit/6260b2f92dfcaf56f84c9d1457a8f1d01c22c304))


### Bug Fixes

* pass attemptCount to createWorker before construction ([f40f638](https://github.com/justb81/watchbuddy/commit/f40f638530ef2499deeafc3169883e90e648fa62))
* replace ad-hoc HTTP clients with injected shared OkHttpClient ([4805b00](https://github.com/justb81/watchbuddy/commit/4805b0012235328e85a2d806a1b95bced37a11b1))
* replace ad-hoc HTTP clients with injected shared OkHttpClient ([c0650b4](https://github.com/justb81/watchbuddy/commit/c0650b4b6a5a2379d5500403dfb2fcd9c2b90add))
* resolve 6 high-priority bugs across Android apps ([e5b62a1](https://github.com/justb81/watchbuddy/commit/e5b62a1a287c8215454e33c21e7baeab50ce8460))
* resolve 7 high-priority bugs across Android apps and backend ([60021ec](https://github.com/justb81/watchbuddy/commit/60021ec3bfc6801768d298ebb3dd98b2ff9cd70d))
* resolve 7 high-priority bugs across Android apps and backend ([5ca90cd](https://github.com/justb81/watchbuddy/commit/5ca90cd95cd5caa32fe29ed5ca45222b17e28d7f))
* resolve all P0-critical issues ([#3](https://github.com/justb81/watchbuddy/issues/3), [#4](https://github.com/justb81/watchbuddy/issues/4), [#5](https://github.com/justb81/watchbuddy/issues/5)) ([514450f](https://github.com/justb81/watchbuddy/commit/514450fe38ed731439b03afe685d4ac3e2e695c1))
* resolve all P0-critical issues ([#3](https://github.com/justb81/watchbuddy/issues/3), [#4](https://github.com/justb81/watchbuddy/issues/4), [#5](https://github.com/justb81/watchbuddy/issues/5)) ([82aaf43](https://github.com/justb81/watchbuddy/commit/82aaf43418355283b172f74ad7362978b6f8bbc2))
* resolve instant crash on launch for both phone and TV apps ([399adfb](https://github.com/justb81/watchbuddy/commit/399adfb7384712e7e0448cbfbc0f55ad4a923589))
* resolve ModelDownloadWorkerTest hanging on setProgress ([8987f36](https://github.com/justb81/watchbuddy/commit/8987f36529c6ea5544c1d1eeebe7488431052d09))
* **tv:** add missing backendUrl Hilt binding to prevent crash on launch ([abab24f](https://github.com/justb81/watchbuddy/commit/abab24f9059a9269d83285c6c6f8a9291c04c369))

## [0.1.3](https://github.com/justb81/watchbuddy/compare/v0.1.2...v0.1.3) (2026-04-12)


### Features

* **phone:** implement /shows, /recap and /auth/token endpoints ([7abae27](https://github.com/justb81/watchbuddy/commit/7abae27f89595981bd4627700768793310f04988))
* **phone:** implement /shows, /recap and /auth/token endpoints in CompanionHttpServer ([ff0ae58](https://github.com/justb81/watchbuddy/commit/ff0ae58e4e7d544bdc10636d1649c1b147be0a81)), closes [#8](https://github.com/justb81/watchbuddy/issues/8)
* **phone:** implement CompanionService as Android Foreground Service ([a3319eb](https://github.com/justb81/watchbuddy/commit/a3319eb8dbde2f6687f8a53b6c69a0c40035a69e))
* **phone:** implement CompanionService as Android Foreground Service ([e8fa845](https://github.com/justb81/watchbuddy/commit/e8fa845f47d828bb299a4cf6bd6df729043db0e5)), closes [#12](https://github.com/justb81/watchbuddy/issues/12)
* **phone:** implement LLM model download via WorkManager ([f3745ff](https://github.com/justb81/watchbuddy/commit/f3745ff07e9300c65b77ed9b453bd6fa31681dd0))
* **phone:** implement LLM model download via WorkManager ([0b6567d](https://github.com/justb81/watchbuddy/commit/0b6567dc5c2f0efbbd92b9e8d0cdc06f059dfbe3)), closes [#13](https://github.com/justb81/watchbuddy/issues/13)
* **phone:** implement LLM provider routing in RecapGenerator ([5487619](https://github.com/justb81/watchbuddy/commit/54876198ce77849b12a65b8e74938072f3172479))
* **phone:** implement LLM provider routing in RecapGenerator ([351baab](https://github.com/justb81/watchbuddy/commit/351baab44caedb7cce0d87f76630052419cc5388)), closes [#9](https://github.com/justb81/watchbuddy/issues/9)
* **phone:** load username from Trakt profile in DeviceCapabilityProvider ([5a25904](https://github.com/justb81/watchbuddy/commit/5a2590437ebf6deb0ea84c8d9cbd0798a1cdc916))
* **phone:** load username from Trakt profile in DeviceCapabilityProvider ([678b743](https://github.com/justb81/watchbuddy/commit/678b743123c327e16309bdf920b6e6f6d2ce4092)), closes [#21](https://github.com/justb81/watchbuddy/issues/21)
* **phone:** navigate to Onboarding on Trakt disconnect ([6b8dc9c](https://github.com/justb81/watchbuddy/commit/6b8dc9c64932dd5094d7739ee678118335b550f4))
* **phone:** navigate to Onboarding on Trakt disconnect ([234a9fb](https://github.com/justb81/watchbuddy/commit/234a9fb335fab30c5d4b75b5fd403b44c4c4f5d0)), closes [#10](https://github.com/justb81/watchbuddy/issues/10)
* **phone:** persist settings in DataStore ([6bdfb14](https://github.com/justb81/watchbuddy/commit/6bdfb1466eaa8962d7d4f7cd6810f0dbebcb3fd5))
* **phone:** persist settings in DataStore ([c1777ec](https://github.com/justb81/watchbuddy/commit/c1777ec6954039323b424da9eeab459c256306c0)), closes [#11](https://github.com/justb81/watchbuddy/issues/11)
* **phone:** persist Trakt token in EncryptedSharedPreferences ([7136bd2](https://github.com/justb81/watchbuddy/commit/7136bd213942d012443bf20300b37a8fe56ca24c))
* **phone:** persist Trakt token in EncryptedSharedPreferences ([52fb511](https://github.com/justb81/watchbuddy/commit/52fb511ffcd00f24b71c575fcd2d01b5a0c05b3a)), closes [#7](https://github.com/justb81/watchbuddy/issues/7)
* Trakt token proxy integration ([6cad884](https://github.com/justb81/watchbuddy/commit/6cad884300ed3db0584f528eabcc46ce4fd12214))
* **tv:** implement scrobble system (Issues [#15](https://github.com/justb81/watchbuddy/issues/15), [#16](https://github.com/justb81/watchbuddy/issues/16), [#18](https://github.com/justb81/watchbuddy/issues/18)) ([9552456](https://github.com/justb81/watchbuddy/commit/9552456554ca7c49791f215a127dd0200f9dc02a))
* **tv:** implement scrobble system (Issues [#15](https://github.com/justb81/watchbuddy/issues/15), [#16](https://github.com/justb81/watchbuddy/issues/16), [#18](https://github.com/justb81/watchbuddy/issues/18)) ([7538ce5](https://github.com/justb81/watchbuddy/commit/7538ce5a3705d5504090e20e5ca85f02d7cf64b9))
* **tv:** implement scrobble system with fuzzy matching, Trakt API, and overlay wiring ([3b82d52](https://github.com/justb81/watchbuddy/commit/3b82d523dac75f9ba96e1baea95b427abfb5f58d))
* **tv:** load shows from Phone companion via HTTP API ([8aed994](https://github.com/justb81/watchbuddy/commit/8aed9941e41e8e728ee870a4f53e5e9a6d8e8fa1))
* **tv:** load shows from Phone companion via HTTP API ([853560e](https://github.com/justb81/watchbuddy/commit/853560ef9fd48024ec01bdd1e4f00ada32258c44)), closes [#14](https://github.com/justb81/watchbuddy/issues/14)
* **tv:** persist selected user IDs in DataStore ([5833c62](https://github.com/justb81/watchbuddy/commit/5833c62a59d80f3d332337c9faae3eccc47fd3a2))
* **tv:** persist selected user IDs in DataStore ([213f943](https://github.com/justb81/watchbuddy/commit/213f943e51146289c4d2060df1be5148263fb1a4)), closes [#17](https://github.com/justb81/watchbuddy/issues/17)
* **tv:** resolve streaming availability via user preference mapping ([3aadfd3](https://github.com/justb81/watchbuddy/commit/3aadfd3bd77f5dda64262b749b26bca3a587969e))
* **tv:** resolve streaming availability via user preference mapping ([6d2bdfa](https://github.com/justb81/watchbuddy/commit/6d2bdfaa7143b91c8975682cc57b7bca1353b463)), closes [#19](https://github.com/justb81/watchbuddy/issues/19)


### Bug Fixes

* **core:** disable HTTP logging in release builds ([61f41bc](https://github.com/justb81/watchbuddy/commit/61f41bc09f71249d0f9187320ee181f707832d4a))
* **core:** disable HTTP logging in release builds ([585d983](https://github.com/justb81/watchbuddy/commit/585d983342ebcf6575f2c538776df43fe0a2774e)), closes [#20](https://github.com/justb81/watchbuddy/issues/20)
* **core:** inject Boolean instead of OkHttp type for logging level ([2d02a0e](https://github.com/justb81/watchbuddy/commit/2d02a0ed6c4ca9ad03b5fce07fb5dfbcff561c8e))
* **tv:** use correct DataStore preferences key factory functions ([33da4fa](https://github.com/justb81/watchbuddy/commit/33da4fabe5ba9c3140c5bb922047f0c0bd1aae14))

## [0.1.2](https://github.com/justb81/watchbuddy/compare/v0.1.1...v0.1.2) (2026-04-12)


### Features

* add FR/ES translations and locale-aware LLM recap language ([5951f89](https://github.com/justb81/watchbuddy/commit/5951f895b7a2f7cc22755076f961e1fe8119e148))
* Add multilingual support (i18n) – EN + DE ([2a8722f](https://github.com/justb81/watchbuddy/commit/2a8722fa45902546df01ef9112ede8836ad4f778))
* add multilingual support (i18n) with English and German translations ([5a7dd1a](https://github.com/justb81/watchbuddy/commit/5a7dd1a8bd51991635cafc2cc780797a9e1d2395))
* replace all app icons and TV banner with new WatchBuddy logo family ([ecff3e4](https://github.com/justb81/watchbuddy/commit/ecff3e4e145dea0182b382bb0df87b9a3f1c4567))


### Bug Fixes

* remove duplicate tv_banner.xml to resolve Duplicate resources build error ([c87644f](https://github.com/justb81/watchbuddy/commit/c87644f0b05ee5e998164d5acf5aa9be1bdd2b0d))

## [0.1.1](https://github.com/justb81/watchbuddy/compare/v0.1.0...v0.1.1) (2026-04-11)


### Features

* add MIT license + download links in release notes ([f359e90](https://github.com/justb81/watchbuddy/commit/f359e90f2834e28d6989851e1a0a8c06f0bab595))
