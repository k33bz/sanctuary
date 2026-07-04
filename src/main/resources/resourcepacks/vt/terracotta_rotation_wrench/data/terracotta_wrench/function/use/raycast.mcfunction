# called from ../use

# Counts up raycast to move the look location forward
scoreboard players add @s wrench_raycast 1

# Attempts to find block and place spawner at block location
execute positioned ^ ^ ^ if block ^ ^ ^ #terracotta_wrench:glazed_terracotta run function terracotta_wrench:rotate/glazed_terracotta

execute if block ^ ^ ^ air positioned ^ ^ ^0.1 if score @s wrench_raycast matches 1..50 run function terracotta_wrench:use/raycast
