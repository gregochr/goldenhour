### Fixed — the order of fields in Claude's forecast answers no longer changes with each restart

The structured-output schema sent with every forecast request listed its fields in an order that
changed each time the backend started, and Claude writes its fields in that order. So on some days
the model committed to a star rating before explaining it, and on others after. The order is now
fixed (rating, scores, summary, headline, then the optional fields) for the sky, woodland and
bluebell evaluations, and a test pins it.
