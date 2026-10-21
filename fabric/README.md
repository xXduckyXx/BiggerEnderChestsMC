# BiggerEnderChestsMC (Fabric)
Fabric version of the bigger ender chest for 1.21.11 :3

just makes the enderchest double size

thats it

no extra pages no permissions no config

enderchest goes from 27 slots to 54 slots

works on fabric 1.21.11 probably idk test it

vanilla enderchest gets moved into the database when a player joins, items get merged and never deleted

window is just the normal vanilla chest window with six rows so a vanilla client can join

-----------------------------

**how to install:**
drop the mod jar and fabric api in mods folder
restart server
done

**requires:**
minecraft 1.21.11
fabric loader 0.19.5 or newer
fabric api 0.141.6+1.21.11 or newer
java 21

built against mojang mappings with fabric loom 1.17.21

tested on a real fabric server (loader 0.19.5, api 0.141.6+1.21.11, mc 1.21.11, java 21)
commands, item serialization and database were checked with /enderchest selftest
game itself was not played, no client connected, so the window was not tested with a real player

**does it save?**
yes its just bigger enderchest data saves like normal (in sqlite at config/biggerenderchests/enderchest.db)

**can i change size?**
no its just double

**permissions?**
fabric has no permission nodes, commands use operator levels
enderchestdb.command = op level 1 for /enderchest
enderchestdb.admin = op level 2 for export, import, backups, restore and selftest
a permission plugin like fabric-permissions-api can change that, mod doesnt depend on it

**commands?**
/enderchest - opens your ender chest from anywhere
/echest, /ec - aliases
/enderchest export [player] - moves chest back into vanilla ender chest
/enderchest import [player] - moves vanilla ender chest into bigger ender chest
/enderchest backups [player] - lists the backups of the chest
/enderchest restore [player] [number] - restores a backup of the chest
/enderchest help - shows commands
/enderchest selftest - checks item serialization, database and the merge

player has to be online for export, import, backups and restore

**config?**
no

just bigger enderchest :3

-----------------------------

**uninstalling without losing items:**

items live in the database not in the player file
before you remove the mod move chests back with:
/enderchest export <player>

player has to be online for that
items go into vanilla ender chest, database row is backed up then deleted, player marked as exported so it doesnt import again on next join

vanilla ender chest has 27 slots, a chest with more than 27 stacks cant be exported
command refuses then, nothing is lost

/enderchest import <player> moves it back into the bigger ender chest

-----------------------------

**stored format:**

database has same tables as paper version

CREATE TABLE enderchest (uuid TEXT PRIMARY KEY, contents BLOB NOT NULL);
CREATE TABLE enderchest_backup (uuid TEXT NOT NULL, backed_up_at INTEGER NOT NULL, reason TEXT, contents BLOB NOT NULL);
CREATE TABLE enderchest_export (uuid TEXT PRIMARY KEY, exported_at INTEGER NOT NULL);
CREATE TABLE enderchest_import (uuid TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, imported_at INTEGER NOT NULL);

the fingerprint is the hash of the items that were imported, it stops the same content from being imported twice

contents blob holds the 54 slots, every slot is:
boolean present (1 byte)
if present:
  int length (4 bytes, big endian)
  byte[] payload (length bytes)

payload is the NBT of the item stack written compressed with the data version of the server in the root
same layout as the paper version, item is written with ItemStack.CODEC through the registry lookup of the server

two databases were not tested against each other, layout is the same but they never ran on the same data so dont rely on it
paper db is at plugins/EnderChestDB/enderchest.db, fabric db is at config/biggerenderchests/enderchest.db, different files

pure parts of storage (slot format, merge, save planner, cache) are the same code in both versions in com.echest.enderchest.storage

-----------------------------

**1.1.1 - the duplication bug is fixed:**

in 1.1.0 the items of the vanilla ender chest went into the database but they also stayed in the
vanilla ender chest, so every join moved them in again and every join added another copy of
every item

1.1.1 does this:
- an item that made it into the bigger ender chest is removed from the vanilla ender chest
- the data is written to the database BEFORE the vanilla ender chest is cleared, if the write fails the items stay where they are
- the content that was imported is remembered (fingerprint in the enderchest_import table) so the same content never gets imported twice, that also stops the dupe when the server dies between the import and the save of the player file
- /enderchest selftest checks all of that, it has to say "Merge without duplication: ok"

chests that are already duplicated can be fixed with the backups:
/enderchest backups <player> lists the old versions of the row
/enderchest restore <player> <number> restores one of them
the row that gets replaced is backed up again so a restore can be undone

-----------------------------

**building:**

cd fabric
./gradlew build

jar ends up in fabric/build/libs/BiggerEnderChests-1.1.1.jar, sqlite driver is put into the jar
unit tests with ./gradlew test, they cover slot format, merge, save planner, cache and database, no running server needed
server with ./gradlew runServer, first start asks for eula in fabric/run/eula.txt
/enderchest selftest on the server checks the items, the database and the merge, it has to say "Merge without duplication: ok"

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

-----------------------------

**notes and known limits:**

mod is server side only, window is vanilla chest window with six rows
a client that is not a vanilla client cannot join

items that cannot be read keep their raw bytes, slot shows a placeholder, bytes written back unchanged next save
an item that cannot be serialized keeps the bytes already stored, if there are none the write is refused instead of deleting the item
content of the window is saved when closed and when server stops
a crash of the server loses the items that were in the window, there is no autosave yet
bigger ender chest cant be moved into vanilla ender chest when it holds more than 27 stacks

now go enjoy your bigger chest :3
