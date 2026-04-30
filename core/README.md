# core

Shared Kotlin library module consumed by `app-phone` and `app-tv`.

## HTTP Logging Policy

`NetworkModule` attaches an `HttpLoggingInterceptor` to the shared `OkHttpClient`:

| Build type | Log level | Body logged |
|------------|-----------|-------------|
| DEBUG      | `HEADERS` | No          |
| Release    | `NONE`    | No          |

### Redacted headers

In DEBUG builds the following headers are replaced with `██` in logcat so that secrets never appear in plain text:

| Header          | Contains                              |
|-----------------|---------------------------------------|
| `Authorization` | Trakt Bearer access / refresh tokens  |
| `trakt-api-key` | Trakt client ID                       |
| `X-API-Key`     | Generic API key used by future routes |

**Do not change the log level to `BODY` in any build type** — `/oauth/token` responses carry plaintext `access_token` and `refresh_token` fields that would be broadcast to any process that reads logcat (e.g. Firebase Crashlytics, crash-reporting SDKs, testers with `adb logcat`).

### Adding new sensitive headers

If a new endpoint requires an additional secret header (e.g. a TMDB `X-Auth-Token`), add a corresponding `redactHeader("X-Auth-Token")` call inside the `HttpLoggingInterceptor.apply { }` block in `NetworkModule.provideOkHttpClient` **before** landing the change. The list of redacted headers is the authoritative registry; keep it in sync with this table.
