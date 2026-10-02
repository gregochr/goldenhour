### Fix — "Retry failed" now says why it did nothing

On the admin run-progress panel, pressing "Retry failed" did nothing visible when the backend refused
it (since #977 it answers 404 with an empty body when none of a run's failed places can be run again,
for example because they are no longer sky locations). The panel now shows one plain line under the
buttons: "Nothing to retry: this run's failed places can no longer be run again." for that 404, the
server's own sentence for any other refusal that carries one, otherwise "Could not start the retry.".
The line is announced once (`role="alert"`) and clears when the button is pressed again or a retry
starts. The request now goes through the shared axios client, so an expired token is refreshed on the
way (a kept panel can sit open for a long time) and a refusal is read the same way every other
admin action's is. While a retry is out the Retry and Dismiss buttons are `aria-disabled` rather than
`disabled`, so focus is not dropped, and a visually hidden "Starting retry…" status is announced; an
accepted retry whose answer names no run says "Retry started." and offers no second press.
