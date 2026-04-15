# Changelog

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
