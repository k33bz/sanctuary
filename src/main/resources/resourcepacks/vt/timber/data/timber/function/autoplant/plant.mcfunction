# replace item-sapling with block-sapling
summon minecraft:falling_block ~ ~ ~ {BlockState:{Name:"minecraft:barrier"},Time:1,DropItem:0b,Tags:["timber_item_to_block"]}
data modify entity @e[type=minecraft:falling_block,tag=timber_item_to_block,distance=...1,sort=arbitrary,limit=1] BlockState.Name set from entity @s Item.id

item modify entity @s contents {"function": "minecraft:set_count", "count": -1, "add": true}

# sapling will be checked every time
tag @s remove timber_checked