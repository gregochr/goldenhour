### Changed — ZAP gate exceptions trimmed

The weekly ZAP gate no longer tolerates three alerts that stopped firing in the latest scan: backend
`30001` and `30002`, and frontend `10055`. Their entries are removed from `.zap/exceptions.json`, so
any recurrence now fails the gate. The remaining entries are unchanged.
