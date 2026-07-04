execute if data storage storm_channeling:main {cooldown: true} run return fail
execute as @e[type=minecraft:trident,tag=!storm_channeling.ineligible] at @s run function storm_channeling:check_trident
execute as @e[type=minecraft:trident,tag=!storm_channeling.ineligible,predicate=storm_channeling:is_trident_near_peak,sort=random,limit=1] at @s run function storm_channeling:channel_storm