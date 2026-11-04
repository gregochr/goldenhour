### Fixed — a query string with an empty parameter name now gets 400 instead of 500

A request whose query string has a parameter with no name (`?=`, `?days=3&=`) was answered with a 500. Tomcat
fails to parse such a chunk and throws `InvalidParameterException` from every later `getParameter*` call, which
Spring MVC makes when it binds a `@RequestParam` or matches a handler, and `GlobalExceptionHandler`'s catch-all
turned it into a 500 with a stack trace in the log. `GlobalExceptionHandler` now maps it to 400 with the fixed
body `{"error":"Invalid request parameter"}`; the offending chunk is neither echoed nor logged. A bad
percent-escape in a query string is the same exception and gets the same answer. A unit and a filter-chain test
cover it.
