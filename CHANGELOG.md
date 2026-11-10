## 0.2.1
- added mobile data saver: separate sync modes for wifi and mobile data
- added new lyrics system: separate lrclib, genius and manual sources with per-song defaults, genius annotations and syntax, dynamic fetch, mark as instrumental
- added manual lyrics editor with text and synced modes, stamping, drag reorder, timing copy between sections and offset
- added credits (producers, writers etc.) that display in song info and can be used in search (search can be disabled in settings)
- added play history linking to fix stats split across differently named songs
- added handling for the album filed for singles (customizable: fake album with the same name / empty / '-')
- added seek buttons and configurable seek step, also in the taskbar widget along with shuffle/repeat buttons
- added advanced shuffle with queue/chaos picking, album handling and smarter artist spreading
- added drag songs out of the window as real files with tags and cover
- added schema guard so older builds dont downgrade a newer synced library
- added missing requirements to the installer
- search results are now ranked by relevance instead of the column sort
- music videos now use a saved link instead of auto searching, with youtube block fallbacks and better error messages
- reworked metadata into layers (source, genius, your edits) so genius can no longer overwrite what youtube music gave you, with an option to turn genius metadata off
- rewrote synced lyrics scrolling with a spring based engine customizable in settings
- improved song edit popup, added plays alteration, credits editing and more
- improved download logic: credits, duplicate detection by source id, confirmation before downloading youtube videos or soundcloud fallbacks
- improved queue: previously played rows, queue switch for "play from here next", right click menu and reorder fixes
- stats now only count real plays (skips and under 10s spotify plays are ignored)
- fixed downloads from spotify urls
- other various bugfixes and styling improvements meow meow

## 0.2.0
- added liquidglass-like refraction through motionui
- added windows integration: tray, taskbar widget + controls
- added cover art colored animated background
- added live sync option to dropbox sync and improved it overall
- added artist info editing and improved song editing
- added unified global search with keyword advanced search for songs (title:, artist:, feat:, album:, source:, plays:, dur:)
- improved artist and album pages
- improved hue sync and bleed color extraction
- improved settings layout and navigation animations + phone navigation with android back button
- improved shuffle and playback logic
- navigation and qol improvements like song select and reorder
- fixed and improved mediacontrols
- fixed downloads on mobile app and other devices
- other various bugfixes and styling improvements

## 0.1.0.2
- improved dropbox sync
- improved the autoupdater
- added icon editor

## 0.1.0.1
- test update, added changelog view to the in-app updater

## 0.1.0
first tagged release, last changes:
- added proper select and reorder to both desktop and mobile ui
- added prototype autoupdate feature
- created proper windows and linux installers
- other bugfixes and styling improvements