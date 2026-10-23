### Fixed — a dead sign-in always ends in the sign-in prompt

A refused request with no renewal pass stored now signs the dead session out instead of leaving the app half signed-in. Account-recovery and sign-up calls no longer disturb another user's session on a shared browser. A renewed sign-in that the browser couldn't save is still used for the requests waiting on it.
