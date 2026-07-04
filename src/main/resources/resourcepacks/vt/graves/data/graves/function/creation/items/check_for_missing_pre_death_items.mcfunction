execute store result score $missing_pre_death_item_count graves.dummy run data get storage graves:main creation.grave_item.pre_death_inventory
execute store result score $missing_pre_death_equipment_count graves.dummy run data get storage graves:main creation.grave_item.pre_death_equipment
scoreboard players operation $missing_pre_death_item_count graves.dummy += $missing_pre_death_equipment_count graves.dummy
execute store result score $saved_pre_death_item_count graves.dummy run data get storage graves:main creation.items
scoreboard players operation $saved_pre_death_item_count graves.dummy -= $non_pre_death_item_count graves.dummy
execute store result storage graves:main creation.grave.missing_pre_death_item_count int 1.0 run scoreboard players operation $missing_pre_death_item_count graves.dummy -= $saved_pre_death_item_count graves.dummy
execute if score $missing_pre_death_item_count graves.dummy matches 0 run return fail
execute summon minecraft:interaction run function graves:creation/items/warn_of_missing_pre_death_items