# Loops using wrench resetting score once a person stops using a wrench
# Stops the loop if no one is using a wrench
# From: ./use

execute if entity @a[tag=terracotta_wrench.using_wrench] run schedule function terracotta_wrench:using_wrench 1t replace

scoreboard players reset @a[tag=!terracotta_wrench.using_wrench] terracotta_wrench_using
tag @a remove terracotta_wrench.using_wrench
