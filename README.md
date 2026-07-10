# BiggerEnderChestsMC

Minecraft paper mod for 1.21.11 that makes your ender chest bigger and accessible from anywhere :3



-----------------------------



makes the enderchest double size (27 slots -> 54 slots)



saves everything in sqlite so your items stick around after restarts



has a command to open it from anywhere: /enderchest (or /echest or /ec)



migrates your vanilla enderchest data automatically (yes even offline players)



blocks the vanilla enderchest gui and replaces it with a custom 54 slot one



has animations and particles when you open a physical ender chest



thats the main stuff



-----------------------------



**how to install:**

drop the jar in plugins folder

restart server

done



**requires:**

paper 1.21.11 or forks that support paper api 1.21.11

java 21



**does it save?**

yes its just bigger enderchest data saves in sqlite (enderchest.db in the plugin folder)



**can i change size?**

no its just double (54 slots always)



**commands:**

/enderchest — opens your ender chest from anywhere

/echest and /ec are aliases for the same thing

/enderchest toggle — enable or disable the command (admin only)



**permissions:**

enderchestdb.command — lets you use /enderchest (default: op)

enderchestdb.admin — lets you use /enderchest toggle and admin stuff (default: op)



**config:**

has a config.yml with two settings:

commands-enabled — toggle whether /enderchest works (true/false)

migrated — internal flag dont touch this



**api:**

other plugins can use EnderChestAPI to read/write ender chests

four methods: getEnderChest, setEnderChest, hasEnderChest, clearEnderChest



-----------------------------



**how it works (nerd stuff):**

when you join it copies your vanilla enderchest into sqlite and clears the vanilla one

right clicking an ender chest block opens the custom 54 slot gui instead of vanilla

when you close the gui it saves everything to sqlite

the /enderchest command opens the same gui from anywhere

all items are serialized as nbt bytes so shulkers and bundles work fine



-----------------------------



**⚠️ IMPORTANT NOTE**

LISTEN UP



THIS MOD WORKS BY MAKING ENDERCHESTS ACT LIKE CHESTS — THATS WHY THINGS MIGHT GET WEIRD



IT MIGRATES YOUR VANILLA ENDERCHEST DATA ON FIRST JOIN AND CLEARS THE VANILLA ONE



IF YOU REMOVE THIS PLUGIN YOUR ITEMS WONT BE IN THE VANILLA ENDERCHEST ANYMORE — THEY'RE IN SQLITE NOW



IF YOU USE VERSIONS ABOVE 1.21.11 YOU CAN EXPECT BUGS — I ONLY TESTED ON 1.21.11



IF YOU FIND ANY DUPES OR ITEMS VANISHING REPORT THAT TO ME ASAP



BACKUP YOUR WORLD BEFORE USING THIS — YOU HAVE BEEN WARNED



DONT BLAME ME IF YOUR ITEMS GET MESSED UP — ITS A MOD NOT OFFICIAL CONTENT



IF YOU DONT LIKE IT FORK THE CODE ITS MIT FOR A REASON



TO REPORT BUGS JUST JOIN MY DISCORD SERVER
```https://discord.gg/ySZR6ZhBCG```





now go enjoy your bigger chest :3
 

