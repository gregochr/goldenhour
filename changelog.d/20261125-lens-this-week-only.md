### Changed — the Drive and Rated bar belongs to This week

The drive tier and the rating floor gate only This week (the cards, the heatmap and the count), so once you scroll
past it into Further ahead the bar is no longer drawn, at every width, and it comes back the moment This week does.
It replaces the phone's one-line collapse, which still spent about 36px on every Further ahead screen for a control
that did nothing there. The bar is hidden with `visibility` rather than removed, so it keeps its place and the page does
not jump, and Further ahead's heading bar now sticks at the very top (so the Further ahead jump lands with no
lens-sized gap above it). The bar stays drawn under keyboard focus and while a plan-conflict warning shows; a tapped
chip no longer holds it, and focus moves to the Further ahead heading. The summary button, the spacer and the lens's
body wrapper are gone, and the guide's folded-bar paragraph and screenshot are replaced.
