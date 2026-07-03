summon minecraft:item_frame ~ ~1000 ~ {Tags:["craXPBot.enchTable","craXPBot.new"],Fixed:1b,Invisible:1b,Item:{id:"minecraft:enchanting_table",components:{custom_data:{craXPBotData:{}}}}}
data modify entity @e[type=minecraft:item_frame,tag=craXPBot.new,limit=1] Item.components."minecraft:custom_data".craXPBotData set from block ~ ~ ~ {}
setblock ~ ~ ~ minecraft:snow[layers=6]
scoreboard players set #steps craXPBot.dummy 0
schedule function xp_bottling:restore_enchanting_tables 2t append