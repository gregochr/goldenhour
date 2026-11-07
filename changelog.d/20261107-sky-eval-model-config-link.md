### Added — Sky Eval lanes link to their Model Config tab

Each production lane card on Operations > Testing > Sky Eval now has a "Change model" link that opens Operations > Runs > Model Config on that lane's run-type tab, so the model can be switched straight after reading the eval. Model Config now keeps its selected sub-tab in the URL hash (`#manage/models/BATCH_NEAR_TERM`), so the link, a reload and a pasted address all land on the same tab; clicking a sub-tab updates the hash without adding history entries.
