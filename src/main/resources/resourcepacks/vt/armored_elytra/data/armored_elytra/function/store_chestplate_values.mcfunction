execute store result score #chestplateValue armEly.dummy run data get entity @s Item.components.minecraft:repair_cost
data modify storage armored_elytra:storage enchantments set value []
data modify storage armored_elytra:storage parse_enchantments_args.levels set from entity @s Item.components.minecraft:enchantments
function armored_elytra:parse_enchantments/start with storage armored_elytra:storage parse_enchantments_args
data modify storage armored_elytra:storage chestplateEnch set from storage armored_elytra:storage enchantments