data modify entity @s text set value {storage:'graves:main',nbt:'creation.owner_name',interpret:true}
data modify entity @e[type=minecraft:interaction,tag=graves.grave.initializing,distance=..0.01,limit=1] CustomName set from entity @s text
kill @s