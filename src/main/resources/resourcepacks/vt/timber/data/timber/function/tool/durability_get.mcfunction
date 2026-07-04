scoreboard players set unbreakable timber 0
execute store result score durability timber run data get entity @s SelectedItem.components."minecraft:damage"
scoreboard players remove durability timber 1
execute unless items entity @s weapon.mainhand * run scoreboard players set durability timber 9999
execute if items entity @s weapon.mainhand *[unbreakable] run scoreboard players set unbreakable timber 1