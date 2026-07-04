# Loops using wrench resetting score once a person stops using a wrench
# Stops the loop if no one is using a wrench
# From: ./use

execute if entity @a[tag=redstone_wrench.using_wrench] run schedule function redstone_wrench:using_wrench 1t replace

scoreboard players reset @a[tag=!redstone_wrench.using_wrench] redstone_wrench_using
tag @a remove redstone_wrench.using_wrench
