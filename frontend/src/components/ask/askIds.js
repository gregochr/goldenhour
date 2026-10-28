/**
 * Ask PhotoCast's two stable identifiers, kept apart from the components so the shell can read them
 * without importing the dock or the sheet (and, through them, the whole conversation) at first load.
 */

/** The dock's DOM id, the `controls` target of the field that opens it. */
export const ASK_DOCK_ID = 'wf-ask-dock';

/** The sheet's accessible name — also how the shell recognises it among the dialogs outside its root. */
export const ASK_SHEET_LABEL = 'Ask PhotoCast';
