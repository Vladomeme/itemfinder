## Usage
Mod allows to search your world for items with selected parameters.  
Runs on server side, so can be used in **singleplayer or on a fabric server**.  
[Cloth Config](https://modrinth.com/mod/cloth-config) for config.  
  
Works on:
* All Block Entities
* Entities:
  
  ![Снимок экрана от 2024-09-26 20-30-03](https://github.com/user-attachments/assets/0c3e14ca-0f71-4f07-8c81-54c74d544102)

****
**Base command:** `/finditem`  
  
`/finditem id <id>`  
Matches items with exact id  
  
`/finditem name <name>`  
Matches items if their name contains argument string, case insensitive (uses base name or a custom name if present)  
  
`/finditem data <data>`  
Matches items that contain argument string in their NBT (literally everything: name, lore, enchantments, attributes, etc etc)  
On 1.21: if used in normal mode, checks data components (less flexible). In global uses NBT anyway.
  
Multi-word search parameters could be put in quotes like `"monument block"`
### Search modes
**Normal** mode only checks currently loaded chunks & entities, runs almost instantly.  
  
**Global** mode can be activated by adding `global` to the command: `/finditem <type> <string> global`  
Searches all generated chunks in the current dimension, may take a few seconds.

****
### Additional
* You can teleport to search results in order by pressing a keybind (`N` by default).
* Mod will do nested search for shulker box items and bundles. Only works reliably with **Global** mode.
