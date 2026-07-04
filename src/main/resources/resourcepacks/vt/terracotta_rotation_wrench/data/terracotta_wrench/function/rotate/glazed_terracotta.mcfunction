# Rotates the terracotta to it's next position, from the one found
#
# Called by: terracotta_wrench:use

scoreboard players set @s wrench_raycast 0

# swing
swing @s mainhand
playsound minecraft:item.spyglass.use block

# Rotates the White Glazed Terracotta
execute if block ~ ~ ~ white_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ white_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ white_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ white_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ white_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ white_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ white_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ white_glazed_terracotta[facing=south] strict

# Rotates the Orange Glazed Terracotta
execute if block ~ ~ ~ orange_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ orange_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ orange_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ orange_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ orange_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ orange_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ orange_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ orange_glazed_terracotta[facing=south] strict

# Rotates the Magenta Glazed Terracotta
execute if block ~ ~ ~ magenta_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ magenta_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ magenta_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ magenta_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ magenta_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ magenta_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ magenta_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ magenta_glazed_terracotta[facing=south] strict

# Rotates the Light_blue Glazed Terracotta
execute if block ~ ~ ~ light_blue_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ light_blue_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ light_blue_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ light_blue_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ light_blue_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ light_blue_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ light_blue_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ light_blue_glazed_terracotta[facing=south] strict

# Rotates the Yellow Glazed Terracotta
execute if block ~ ~ ~ yellow_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ yellow_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ yellow_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ yellow_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ yellow_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ yellow_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ yellow_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ yellow_glazed_terracotta[facing=south] strict

# Rotates the Lime Glazed Terracotta
execute if block ~ ~ ~ lime_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ lime_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ lime_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ lime_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ lime_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ lime_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ lime_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ lime_glazed_terracotta[facing=south] strict

# Rotates the Pink Glazed Terracotta
execute if block ~ ~ ~ pink_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ pink_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ pink_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ pink_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ pink_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ pink_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ pink_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ pink_glazed_terracotta[facing=south] strict

# Rotates the Gray Glazed Terracotta
execute if block ~ ~ ~ gray_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ gray_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ gray_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ gray_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ gray_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ gray_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ gray_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ gray_glazed_terracotta[facing=south] strict

# Rotates the Light_gray Glazed Terracotta
execute if block ~ ~ ~ light_gray_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ light_gray_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ light_gray_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ light_gray_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ light_gray_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ light_gray_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ light_gray_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ light_gray_glazed_terracotta[facing=south] strict

# Rotates the Cyan Glazed Terracotta
execute if block ~ ~ ~ cyan_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ cyan_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ cyan_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ cyan_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ cyan_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ cyan_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ cyan_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ cyan_glazed_terracotta[facing=south] strict

# Rotates the Purple Glazed Terracotta
execute if block ~ ~ ~ purple_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ purple_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ purple_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ purple_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ purple_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ purple_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ purple_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ purple_glazed_terracotta[facing=south] strict

# Rotates the Blue Glazed Terracotta
execute if block ~ ~ ~ blue_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ blue_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ blue_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ blue_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ blue_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ blue_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ blue_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ blue_glazed_terracotta[facing=south] strict

# Rotates the Brown Glazed Terracotta
execute if block ~ ~ ~ brown_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ brown_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ brown_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ brown_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ brown_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ brown_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ brown_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ brown_glazed_terracotta[facing=south] strict

# Rotates the Green Glazed Terracotta
execute if block ~ ~ ~ green_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ green_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ green_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ green_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ green_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ green_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ green_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ green_glazed_terracotta[facing=south] strict

# Rotates the Red Glazed Terracotta
execute if block ~ ~ ~ red_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ red_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ red_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ red_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ red_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ red_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ red_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ red_glazed_terracotta[facing=south] strict

# Rotates the Black Glazed Terracotta
execute if block ~ ~ ~ black_glazed_terracotta[facing=north] run return run setblock ~ ~ ~ black_glazed_terracotta[facing=west] strict
execute if block ~ ~ ~ black_glazed_terracotta[facing=east] run return run setblock ~ ~ ~ black_glazed_terracotta[facing=north] strict
execute if block ~ ~ ~ black_glazed_terracotta[facing=south] run return run setblock ~ ~ ~ black_glazed_terracotta[facing=east] strict
execute if block ~ ~ ~ black_glazed_terracotta[facing=west] run return run setblock ~ ~ ~ black_glazed_terracotta[facing=south] strict
