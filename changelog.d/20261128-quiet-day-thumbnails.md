### Fixed — the card maps no longer go black when today folds as a quiet day

When the first day of the week was a quiet day (every window two stars or less), the small map on every card stayed
black. The page waited to measure the first card, which was folded away and so had no size, gave up, and never drew
any of them. It now measures the first card that is actually showing, and draws the folded days' maps when they are
opened.
