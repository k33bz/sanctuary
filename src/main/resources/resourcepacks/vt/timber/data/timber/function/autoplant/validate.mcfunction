# don't check items twice (except saplings, look in plant.mcfunction)
tag @s add timber_checked

# check if item is in tag #minecraft:sapling
execute if items entity @s contents #minecraft:saplings run function timber:autoplant/plant