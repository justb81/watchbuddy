# Changelog

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
