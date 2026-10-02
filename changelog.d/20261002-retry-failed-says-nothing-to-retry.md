### Fix — "Retry failed" now says why when it cannot retry

On the admin run-progress panel, pressing "Retry failed" did nothing visible when the backend refused
it (since #977 it answers 404 with an empty body when none of a run's failed places can be run again,
for example because they are no longer sky locations). The panel now shows one plain line under the
button: "Nothing to retry: this run's failed places can no longer be run again." for that 404, the
server's own sentence for any other refusal that carries one, otherwise "Could not start the retry.".
The line is announced once (`role="alert"`), clears when the button is pressed again, when a retry
succeeds and when the panel moves to another run. While a retry is out the button is `aria-disabled`
rather than `disabled`, so a keyboard reader who pressed it is not dropped onto `<body>`. `retryFailed` now
rejects with the HTTP status and the server's body instead of a statusless "Retry failed".
