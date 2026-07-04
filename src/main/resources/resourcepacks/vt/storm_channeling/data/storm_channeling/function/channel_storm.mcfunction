data modify storage storm_channeling:main cooldown set value true
schedule function storm_channeling:clear_cooldown 5s
schedule clear storm_channeling:check_tridents
weather thunder
summon minecraft:lightning_bolt
execute if items entity @s contents *[minecraft:unbreakable] run return 1
execute store result score $damage storm_channeling.dummy run data get entity @s item.components.minecraft:damage
execute store result entity @s item.components.minecraft:damage int 1.0 run scoreboard players add $damage storm_channeling.dummy 150