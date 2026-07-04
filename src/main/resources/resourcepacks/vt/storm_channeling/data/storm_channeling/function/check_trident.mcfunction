execute unless predicate storm_channeling:is_trident_eligible run return run tag @s add storm_channeling.ineligible
execute if entity @s[tag=!storm_channeling.thrown_upward_above_world] unless function storm_channeling:was_thrown_upward_above_world run return run tag @s add storm_channeling.ineligible
schedule function storm_channeling:check_tridents 1t