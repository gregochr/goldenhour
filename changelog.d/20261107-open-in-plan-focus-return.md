### Fixed — focus after closing a sheet opened with "Open in Plan"

Closing a location sheet that the Map's "Open in Plan" opened (from a callout or from Ask's phone peek)
left keyboard focus nowhere, because the button that opened it is hidden with the Map pane. Focus now
returns to the Plan tab. Found by the jsdom 30.1.3 test update, which, like a browser, refuses to focus
a hidden element.
