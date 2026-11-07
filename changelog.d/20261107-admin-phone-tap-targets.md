### Fixed — 44px phone tap targets on the Operations screens

On a phone (narrower than 640px) many Operations controls were 16 to 36px tall and easy to miss. The Lite limit
dialog (trigger, picks input, Save, Close), the temporary-password Copy and Close, the Areas tab (buttons,
inputs and member checkbox rows), the Aurora simulate modal, the Hot Topics simulation toggle and checkbox rows,
Rewind (buttons and date input), the Run Config tab buttons, model Switch buttons and extended-thinking toggle,
and the Manage section menu are now at least 44px tall. The small "i" info tips keep their look but get a 44px
tall invisible tap area on a phone; that is the shared `InfoTip`, so it also applies to the tips in the Map
filters panel and the window popup's topic rows, for every role. Phone-only classes, so tablet and desktop are
unchanged.
