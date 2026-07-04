# Desc: Sends a ray out to find terracotta and rotates it
#
# Called by adv: terracotta_wrench:use_wrench

advancement revoke @s only terracotta_wrench:use_wrench

scoreboard players set @s wrench_raycast 0
execute unless entity @s[scores={terracotta_wrench_using=1..}] anchored eyes run function terracotta_wrench:use/raycast
scoreboard players add @s terracotta_wrench_using 1
tag @s add terracotta_wrench.using_wrench

schedule function terracotta_wrench:using_wrench 1t replace
