### Testing — Cover Ask, runs, Coming up and notification branches (backend B2)

Behaviour tests now cover the `service.ask`, `service.runs`, `service.comingup` and `service.notification` classes that sat below 95% of lines or 90% of branches: the Ask spend guard, engine edge cases, thread context and snapshot builder, the Runs loaders and cost view, the Lite Coming up shaper and the user emails.
No production code changed; the branches that cannot be reached are listed in the pull request as override candidates for the coverage floor.
