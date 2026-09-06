### Fixed — the window pill's dropdown is a valid listbox again

The dropdown's `role="listbox"` moved from the popup box onto an inner element holding only the
window rows. A listbox admits only `option` children, and the new "reopen the landing card" row is
not one — it chooses no window. It now sits in the popup beside the listbox rather than inside it,
so a listbox-navigating screen-reader user is not offered a child the container's own roles cannot
describe. The popup keeps the id `aria-controls` names, its test-id and its class.
