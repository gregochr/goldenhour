### Fixed — map panels no longer use recessive ink that fails contrast

Measured on the running app, bone at 0.42 composites to 3.53:1 on the window menu's plate and
3.56:1 on the callout's — under AA's 4.5:1 for the 9.5–11px type those panels are set in. Two live
surfaces sat at that value, both meaningful words rather than decoration: the window dropdown's
unscored marker and the callout strip cell's. Every panel inside the map frame now defaults to the
passing ink through one rule on the Map tab's own wrapper — and one on the bottom-sheet host, since
the phone's Filters and Regions panels portal out of that subtree and would otherwise have kept the
failing ink on the one screen where the type is smallest. The three "unscored" markers that took
their meaning from being quieter than the text around them now name their own colour, since
recessiveness inside a panel is opt-in once the default passes. Swept afterwards across every text node in every panel with each opened in turn:
window picker 5.34 · regions 6.88 · filters 6.75 · legend 6.75 · callout 6.29 · sheet 5.03 · counts
footer 6.88 · chips 11.24. Zero failing.
