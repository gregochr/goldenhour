### Changed — The API no longer sends Lite the best-bet picks

`GET /api/briefing` now leaves each window's pick out for Lite readers, serves an empty best-bets list and drops the best-bet model, status and withdrawn flag, and `GET /api/briefing/digest` drops each window's pick, headline, region and location. Pro and Admin readers see no change, and the star rating, the verdict and the region's own description are still sent to everyone. This is the server half of the Lite best-bets lock; the app already hides these surfaces for Lite.
