## Usage
Mod allows to search your world for items with selected parameters.  
Runs on server side, so can be used in **singleplayer or on a fabric server**.
  
Full list of blocks/entities checked:  
  
![изображение](https://github.com/user-attachments/assets/56d6ba75-f632-4097-8c82-c768ba3506d6)
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
Searches all generated chunks in the current dimension, may take a few seconds, on my machine checks ~1000 chunks per second.
