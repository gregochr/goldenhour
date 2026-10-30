### Fixed — Job Runs "Load More" repeated the newest runs

With the "All Run Types" filter, Operations → Job Runs ignored the page index, so "Load More" appended the newest 20 runs again instead of older ones. The all-types list now pages like the filtered one, orders runs that share a start second by id so pages cannot overlap, and the grid drops any run it already shows.
