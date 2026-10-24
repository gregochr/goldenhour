### Changed — Ask PhotoCast is Pro and Admin only (API)

`POST /api/ask` and `GET /api/ask/ready` now answer **403** `{"error": "Ask PhotoCast is part of Pro.", "code": "NOT_ENTITLED"}` to a Lite reader, with the Ask flag on or off. The refusal is made in the security chain, ahead of the body-size filter, the flag interceptor and the rate limiter, so a Lite request is never read or counted; `AskService.admit` and `ask` repeat the check, because Ready matches, cache hits and can't-answers skip the allowance. `GET /api/user/settings/ask` stays 200 for every role; for a Lite reader with Ask on it reads `{enabled: true, used: 0, limit: 0, left: 0, typedAvailable: false}`.

The `photocast.ask.limit-lite` setting is removed (a config that still carries it starts normally and ignores it). The Lite lock in the client ships separately.
