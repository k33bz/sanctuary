# Rotates the redstone component to it's next position, from the one found
#
# Called by: wrench:use

scoreboard players set @s wrench_raycast 0

# swing
swing @s mainhand
playsound minecraft:item.spyglass.use block

# Rotates the Repeater with delay 1
execute if block ~ ~ ~ repeater[facing=north,delay=1] run return run setblock ~ ~ ~ repeater[facing=west,delay=1] strict
execute if block ~ ~ ~ repeater[facing=east,delay=1] run return run setblock ~ ~ ~ repeater[facing=north,delay=1] strict
execute if block ~ ~ ~ repeater[facing=south,delay=1] run return run setblock ~ ~ ~ repeater[facing=east,delay=1] strict
execute if block ~ ~ ~ repeater[facing=west,delay=1] run return run setblock ~ ~ ~ repeater[facing=south,delay=1] strict

# Rotates the Repeater with delay 2
execute if block ~ ~ ~ repeater[facing=north,delay=2] run return run setblock ~ ~ ~ repeater[facing=west,delay=2] strict
execute if block ~ ~ ~ repeater[facing=east,delay=2] run return run setblock ~ ~ ~ repeater[facing=north,delay=2] strict
execute if block ~ ~ ~ repeater[facing=south,delay=2] run return run setblock ~ ~ ~ repeater[facing=east,delay=2] strict
execute if block ~ ~ ~ repeater[facing=west,delay=2] run return run setblock ~ ~ ~ repeater[facing=south,delay=2] strict

# Rotates the Repeater with delay 3
execute if block ~ ~ ~ repeater[facing=north,delay=3] run return run setblock ~ ~ ~ repeater[facing=west,delay=3] strict
execute if block ~ ~ ~ repeater[facing=east,delay=3] run return run setblock ~ ~ ~ repeater[facing=north,delay=3] strict
execute if block ~ ~ ~ repeater[facing=south,delay=3] run return run setblock ~ ~ ~ repeater[facing=east,delay=3] strict
execute if block ~ ~ ~ repeater[facing=west,delay=3] run return run setblock ~ ~ ~ repeater[facing=south,delay=3] strict

# Rotates the Repeater with delay 4
execute if block ~ ~ ~ repeater[facing=north,delay=4] run return run setblock ~ ~ ~ repeater[facing=west,delay=4] strict
execute if block ~ ~ ~ repeater[facing=east,delay=4] run return run setblock ~ ~ ~ repeater[facing=north,delay=4] strict
execute if block ~ ~ ~ repeater[facing=south,delay=4] run return run setblock ~ ~ ~ repeater[facing=east,delay=4] strict
execute if block ~ ~ ~ repeater[facing=west,delay=4] run return run setblock ~ ~ ~ repeater[facing=south,delay=4] strict

# Rotates the Comparator with mode subtract
execute if block ~ ~ ~ comparator[facing=north,mode=subtract] run return run setblock ~ ~ ~ comparator[facing=west,mode=subtract] strict
execute if block ~ ~ ~ comparator[facing=east,mode=subtract] run return run setblock ~ ~ ~ comparator[facing=north,mode=subtract] strict
execute if block ~ ~ ~ comparator[facing=south,mode=subtract] run return run setblock ~ ~ ~ comparator[facing=east,mode=subtract] strict
execute if block ~ ~ ~ comparator[facing=west,mode=subtract] run return run setblock ~ ~ ~ comparator[facing=south,mode=subtract] strict

# Rotates the Comparator with mode compare
execute if block ~ ~ ~ comparator[facing=north,mode=compare] run return run setblock ~ ~ ~ comparator[facing=west,mode=compare] strict
execute if block ~ ~ ~ comparator[facing=east,mode=compare] run return run setblock ~ ~ ~ comparator[facing=north,mode=compare] strict
execute if block ~ ~ ~ comparator[facing=south,mode=compare] run return run setblock ~ ~ ~ comparator[facing=east,mode=compare] strict
execute if block ~ ~ ~ comparator[facing=west,mode=compare] run return run setblock ~ ~ ~ comparator[facing=south,mode=compare] strict

# Rotates the Piston
execute if block ~ ~ ~ piston[facing=north] run return run setblock ~ ~ ~ piston[facing=west] strict
execute if block ~ ~ ~ piston[facing=east] run return run setblock ~ ~ ~ piston[facing=north] strict
execute if block ~ ~ ~ piston[facing=south] run return run setblock ~ ~ ~ piston[facing=east] strict
execute if block ~ ~ ~ piston[facing=up] run return run setblock ~ ~ ~ piston[facing=south] strict
execute if block ~ ~ ~ piston[facing=down] run return run setblock ~ ~ ~ piston[facing=up] strict
execute if block ~ ~ ~ piston[facing=west] run return run setblock ~ ~ ~ piston[facing=down] strict
execute if block ~ ~ ~ piston run return run fill ~-1 ~-1 ~-1 ~1 ~1 ~1 air replace piston_head

# Rotates the Sticky Piston
execute if block ~ ~ ~ sticky_piston[facing=north] run return run setblock ~ ~ ~ sticky_piston[facing=west] strict
execute if block ~ ~ ~ sticky_piston[facing=east] run return run setblock ~ ~ ~ sticky_piston[facing=north] strict
execute if block ~ ~ ~ sticky_piston[facing=south] run return run setblock ~ ~ ~ sticky_piston[facing=east] strict
execute if block ~ ~ ~ sticky_piston[facing=up] run return run setblock ~ ~ ~ sticky_piston[facing=south] strict
execute if block ~ ~ ~ sticky_piston[facing=down] run return run setblock ~ ~ ~ sticky_piston[facing=up] strict
execute if block ~ ~ ~ sticky_piston[facing=west] run return run setblock ~ ~ ~ sticky_piston[facing=down] strict
execute if block ~ ~ ~ sticky_piston run return run fill ~-1 ~-1 ~-1 ~1 ~1 ~1 air replace piston_head

# Rotates the Dropper
execute if block ~ ~ ~ dropper[facing=north] run return run setblock ~ ~ ~ dropper[facing=west] strict
execute if block ~ ~ ~ dropper[facing=east] run return run setblock ~ ~ ~ dropper[facing=north] strict
execute if block ~ ~ ~ dropper[facing=south] run return run setblock ~ ~ ~ dropper[facing=east] strict
execute if block ~ ~ ~ dropper[facing=up] run return run setblock ~ ~ ~ dropper[facing=south] strict
execute if block ~ ~ ~ dropper[facing=down] run return run setblock ~ ~ ~ dropper[facing=up] strict
execute if block ~ ~ ~ dropper[facing=west] run return run setblock ~ ~ ~ dropper[facing=down] strict
# execute if block ~ ~ ~ dropper unless block ~ ~ ~ dropper{Items:[]} run return run title @s actionbar ["",{"text":"You cannot rotate a block with items inside!","color":"red"}]

# Rotates the Dispenser
execute if block ~ ~ ~ dispenser[facing=north] run return run setblock ~ ~ ~ dispenser[facing=west] strict
execute if block ~ ~ ~ dispenser[facing=east] run return run setblock ~ ~ ~ dispenser[facing=north] strict
execute if block ~ ~ ~ dispenser[facing=south] run return run setblock ~ ~ ~ dispenser[facing=east] strict
execute if block ~ ~ ~ dispenser[facing=up] run return run setblock ~ ~ ~ dispenser[facing=south] strict
execute if block ~ ~ ~ dispenser[facing=down] run return run setblock ~ ~ ~ dispenser[facing=up] strict
execute if block ~ ~ ~ dispenser[facing=west] run return run setblock ~ ~ ~ dispenser[facing=down] strict
# execute if block ~ ~ ~ dispenser unless block ~ ~ ~ dispenser{Items:[]} run return run title @s actionbar ["",{"text":"You cannot rotate a block with items inside!","color":"red"}]

# Rotates the Observer
execute if block ~ ~ ~ observer[facing=north] run return run setblock ~ ~ ~ observer[facing=west] strict
execute if block ~ ~ ~ observer[facing=east] run return run setblock ~ ~ ~ observer[facing=north] strict
execute if block ~ ~ ~ observer[facing=south] run return run setblock ~ ~ ~ observer[facing=east] strict
execute if block ~ ~ ~ observer[facing=up] run return run setblock ~ ~ ~ observer[facing=south] strict
execute if block ~ ~ ~ observer[facing=down] run return run setblock ~ ~ ~ observer[facing=up] strict
execute if block ~ ~ ~ observer[facing=west] run return run setblock ~ ~ ~ observer[facing=down] strict

# Rotates the Hopper
execute if block ~ ~ ~ hopper[facing=north] run return run setblock ~ ~ ~ hopper[facing=west] strict
execute if block ~ ~ ~ hopper[facing=east] run return run setblock ~ ~ ~ hopper[facing=north] strict
execute if block ~ ~ ~ hopper[facing=south] run return run setblock ~ ~ ~ hopper[facing=east] strict
execute if block ~ ~ ~ hopper[facing=down] run return run setblock ~ ~ ~ hopper[facing=south] strict
execute if block ~ ~ ~ hopper[facing=west] run return run setblock ~ ~ ~ hopper[facing=down] strict
# execute if block ~ ~ ~ hopper unless block ~ ~ ~ hopper{Items:[]} run return run title @s actionbar ["",{"text":"You cannot rotate a block with items inside!","color":"red"}]

# Rotates the Crafter
# crafting=false, triggered=false
execute if block ~ ~ ~ crafter[orientation=down_east,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=down_north,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=down_north,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=down_south,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=down_south,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=down_west,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=down_west,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=east_up,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=east_up,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=north_up,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=north_up,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=south_up,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=south_up,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=up_east,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=up_east,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=up_north,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=up_north,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=up_south,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=up_south,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=up_west,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=up_west,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=west_up,triggered=false,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=west_up,triggered=false,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=down_east,triggered=false,crafting=false] strict

# crafting=false, triggered=true
execute if block ~ ~ ~ crafter[orientation=down_east,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=down_north,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=down_north,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=down_south,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=down_south,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=down_west,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=down_west,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=east_up,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=east_up,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=north_up,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=north_up,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=south_up,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=south_up,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=up_east,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=up_east,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=up_north,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=up_north,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=up_south,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=up_south,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=up_west,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=up_west,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=west_up,triggered=true,crafting=false] strict
execute if block ~ ~ ~ crafter[orientation=west_up,triggered=true,crafting=false] run return run setblock ~ ~ ~ crafter[orientation=down_east,triggered=true,crafting=false] strict

# crafting=true, triggered=false
execute if block ~ ~ ~ crafter[orientation=down_east,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=down_north,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=down_north,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=down_south,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=down_south,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=down_west,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=down_west,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=east_up,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=east_up,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=north_up,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=north_up,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=south_up,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=south_up,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=up_east,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=up_east,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=up_north,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=up_north,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=up_south,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=up_south,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=up_west,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=up_west,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=west_up,triggered=false,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=west_up,triggered=false,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=down_east,triggered=false,crafting=true] strict

# crafting=true, triggered=true
execute if block ~ ~ ~ crafter[orientation=down_east,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=down_north,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=down_north,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=down_south,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=down_south,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=down_west,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=down_west,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=east_up,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=east_up,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=north_up,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=north_up,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=south_up,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=south_up,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=up_east,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=up_east,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=up_north,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=up_north,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=up_south,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=up_south,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=up_west,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=up_west,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=west_up,triggered=true,crafting=true] strict
execute if block ~ ~ ~ crafter[orientation=west_up,triggered=true,crafting=true] run return run setblock ~ ~ ~ crafter[orientation=down_east,triggered=true,crafting=true] strict