summon minecraft:area_effect_cloud ~ ~ ~ {Tags:["craXPBot.marker"],Duration:0,Radius:0.0f,WaitTime:0}
execute align y if entity @e[type=minecraft:area_effect_cloud,tag=craXPBot.marker,distance=..0.75] run function xp_bottling:replace_enchanting_table
kill @e[type=minecraft:area_effect_cloud,tag=craXPBot.marker]