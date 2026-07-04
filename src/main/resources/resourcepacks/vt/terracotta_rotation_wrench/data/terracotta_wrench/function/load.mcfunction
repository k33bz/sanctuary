# Desc: Initialised scores needed for wrench function
#
# Called by: Player

scoreboard objectives add wrench_raycast dummy "Wrench Raycast"
scoreboard objectives add terracotta_wrench_using dummy "Terracotta Wrench Using"
advancement revoke @a only terracotta_wrench:use_wrench
