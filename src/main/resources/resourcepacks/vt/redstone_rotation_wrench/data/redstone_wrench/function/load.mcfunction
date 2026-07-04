# Desc: Initialised scores needed for wrench function
#
# Called by: Player

scoreboard objectives add wrench_raycast dummy "Wrench Raycast"
scoreboard objectives add redstone_wrench_using dummy "Redstone Wrench Using"
advancement revoke @a only redstone_wrench:use_wrench
