# Playtesting checklist, Great Chasms

Work through this before reporting an issue or calling a build good. Tick what you have actually
seen, not what you expect to work. If something fails, say which item it was when you report it.

**A green build proves nothing about whether the mod loads**, and loading proves nothing about
whether it plays. Most of the failures in this project surfaced only by launching it and using it.

## Setup

* [ ] Minecraft 26.1.2 with NeoForge 26.1.2.95 or newer
* [ ] Java 25 or newer
* [ ] Fresh instance, with no config left over from an earlier version
* [ ] This mod installed on its own first, before testing inside a full pack
* [ ] Built from the current repo, not a cached jar from an earlier run

## Installation and load

* [ ] Launches to the main menu without crashing
* [ ] `logs/latest.log` has no error or warning naming this mod at startup
* [ ] Appears in the mod list with the right name and version
* [ ] A world creates and loads without crashing

## Core functionality

* [ ] Every changed recipe crafts and yields the right item
* [ ] Tooltips read correctly, with no missing text, broken formatting or raw translation keys
* [ ] Screens open, draw and close without error
* [ ] Blocks, items and behaviour match what the README describes

## Edge cases

* [ ] Crafting with a full inventory
* [ ] Interrupting a craft partway through
* [ ] Invalid combinations fail gracefully rather than crashing
* [ ] Stacking at max stack size
* [ ] Items at 0 durability, and fully repaired

## Compatibility

* [ ] Tested inside a large pack, not only on its own
* [ ] No rendering, tooltip or recipe conflict with another mod
* [ ] Works with anything that hooks into the same systems this one touches

## Multiplayer and server

* [ ] Tested on a dedicated server, not only singleplayer
* [ ] Two players using the same systems at once causes no desync or error
* [ ] The server console is clean of anything naming this mod

## Extended session

* [ ] 20 to 30 minutes of ordinary play, not only isolated tests
* [ ] No memory growth, lag spike or delayed error over that time
* [ ] The world saves and reloads correctly afterwards

## Reporting

Open an issue giving:

* which item failed, by section and wording
* Minecraft and NeoForge versions, and the full mod list
* the relevant part of `logs/latest.log`, or the crash report

For a failure to load, `logs/latest.log` is usually not enough on its own. `logs/debug.log` carries
the real stack, and a crash report often shows only the launcher wrapper.
