# BiggerEnderChestsMC
Minecraft paper mod for 1.21.11 to idk you find out :3

-----------------------------

just makes the enderchest double size

thats it

no extra pages no config

enderchest goes from 27 slots to 54 slots

works on paper 1.21.11+ probably idk test it

-----------------------------

**how to install:**
drop the jar in plugins folder
restart server
done

use EnderChestDB.jar, thats the one with the sqlite driver in it

**requires:**
paper 1.21.11 or newer (probably works on forks too idc)

**does it save?**
yes its just bigger enderchest data saves like normal (in sqlite)

**can i change size?**
no its just double

**permissions?**
enderchestdb.command for /enderchest
enderchestdb.admin for export, import, backups, restore, selftest and toggle
both are op by default

**commands?**
/enderchest - opens your ender chest
/echest, /ec - aliases
/enderchest export [player] - moves chest back into vanilla ender chest
/enderchest import [player] - moves vanilla ender chest into bigger ender chest
/enderchest backups [player] - lists the backups of the chest
/enderchest restore [player] [number] - restores a backup of the chest
/enderchest selftest - checks the items, the database and the merge
/enderchest toggle - turns the command on and off

player has to be online for export, import, backups and restore
backups, restore, selftest and toggle need enderchestdb.admin

**config?**
no

just bigger enderchest :3

-----------------------------

**1.1.1 - the dupe bug is fixed:**

in 1.1.0 the items of the vanilla ender chest went into the database but they also stayed in the
vanilla ender chest, so every join moved them in again and every join added another copy of
every item

1.1.1 does this:
- an item that made it into the bigger ender chest is removed from the vanilla ender chest
- the data is written to the database BEFORE the vanilla ender chest is cleared, if the write fails the items stay where they are
- the content that was imported is remembered (fingerprint in the enderchest_import table) so the same content never gets imported twice, that also stops the dupe when the server dies between the import and the save of the player file
- the stored chest is never overwritten by the import, items only get added
- /enderchest selftest checks all of that, it has to say "Merge without duplication: ok" and "Backup restore: ok"

chests that are already duplicated can be fixed with the backups:
/enderchest backups <player> lists the old versions of the row
/enderchest restore <player> <number> restores one of them
the row that gets replaced is backed up again so a restore can be undone

-----------------------------

**stored format:**

CREATE TABLE enderchest (uuid TEXT PRIMARY KEY, contents BLOB NOT NULL);
CREATE TABLE enderchest_backup (uuid TEXT NOT NULL, backed_up_at INTEGER NOT NULL, reason TEXT, contents BLOB NOT NULL);
CREATE TABLE enderchest_export (uuid TEXT PRIMARY KEY, exported_at INTEGER NOT NULL);
CREATE TABLE enderchest_import (uuid TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, imported_at INTEGER NOT NULL);

-----------------------------

**building:**

./gradlew build

jar ends up in build/libs/EnderChestDB.jar, sqlite driver is put into the jar
unit tests with ./gradlew test, they cover slot format, merge, save planner, cache and database, no running server needed

-----------------------------

# ⚠️ IMPORTANT NOTE

LISTEN UP

THIS MOD WORKS BY MAKING ENDERCHESTS ACT LIKE CHESTS - THATS WHY THINGS MIGHT GET WEIRD

IT USES THE MIGRATION MANAGER FROM MINECRAFT SO DATA SHOULD TRANSFER CLEANLY

BUT IF YOU USE VERSIONS ABOVE 1.21.11 YOU CAN EXPECT BUGS - I ONLY TESTED ON 1.21.11

IF YOU FIND ANY DUPES OR ITEMS VANISHING REPORT THAT TO ME ASAP

BACKUP YOUR WORLD BEFORE USING THIS - YOU HAVE BEEN WARNED

DONT BLAME ME IF YOUR ITEMS GET MESSED UP - ITS A MOD NOT OFFICIAL CONTENT

IF YOU DONT LIKE IT FORK THE CODE ITS MIT FOR A REASON

now go enjoy your bigger chest :3
