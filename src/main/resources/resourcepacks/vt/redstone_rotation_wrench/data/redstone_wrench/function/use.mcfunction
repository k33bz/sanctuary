# Desc: Sends a ray out to find redstone component and rotates it
#
# Called by adv: redstone_wrench:use_wrench

advancement revoke @s only redstone_wrench:use_wrench

scoreboard players set @s wrench_raycast 0
execute unless entity @s[scores={redstone_wrench_using=1..}] anchored eyes run function redstone_wrench:use/raycast
scoreboard players add @s redstone_wrench_using 1
tag @s add redstone_wrench.using_wrench

schedule function redstone_wrench:using_wrench 1t replace
