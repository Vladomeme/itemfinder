## About
Mod allows to search your world for items with selected parameters.  
Designed to be only used in singleplayer. If installed on a server, some functions may not work.  
[YetAnotherConfigLib](https://modrinth.com/mod/yacl) for config.  
  
Works on:
* All block entities;
* Entities:
  
  ![Снимок экрана от 2024-09-26 20-30-03](https://github.com/user-attachments/assets/0c3e14ca-0f71-4f07-8c81-54c74d544102)

****
### Commands
**Base command:** `/finditem`  
  
`/finditem id <id>`  
Matches items with exact id.  
  
`/finditem name <name>`  
Matches items if their name contains argument string, case-insensitive (uses base name or a custom name if present).  
  
Multi-word search parameters could be put in quotes like `"monument block"`.  
  
`/finditem data <data>`  
Matches items that contain argument string in their NBT (literally everything: name, lore, enchantments, attributes, etc etc).  
On 1.21: if used in normal mode, checks data components (less flexible). In global uses NBT anyway.
  
`/finditem loot_table <loot_table>`  
Allows searching for lootable block entities (block entities that can have a loot table).  
Argument - loot table's identifier path (there's autocomplete, don't worry).  

Special arguments:  
* `any` - returns all lootable block entities that **have** a loot table.  
* `none` - returns all lootable block entities that **do not** have a loot table.  
* `none_empty` - returns all lootable block entities that **do not** have a loot table and **do not** have any items inside.
  
Vanilla loot tables (and custom loot tables under vanilla paths) should be enabled in the config to be included in command autocomplete options.  

### Search modes
**Normal** mode only checks currently loaded chunks & entities, runs instantly.  
  
**Global** mode can be activated by adding `global` to the command: `/finditem <search_type> <argument> global`  
Searches all generated chunks in the current dimension, may take a few seconds.

****
### Additional
* You can teleport to search results in order by pressing a keybind (`N` by default).
* Mod has keybinds to perform a normal/global item search using the currently held item (unset by default).
* Hovering mouse over the BE/entity names in search results will show the item stacks/loot tables that matched the search parameters.
* Mod will do nested search for shulker box items and bundles. Only works reliably with **Global** mode.
* Check the config!
