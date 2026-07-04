execute store result storage graves:main creation.pre_death_item_index int 1.0 run data get storage graves:main creation.pre_death_items
data modify storage graves:main creation.pre_death_items append value {slot:'armor.head'}
data modify storage graves:main creation.pre_death_items[-1].index set from storage graves:main creation.pre_death_item_index
data modify storage graves:main creation.pre_death_items[-1].value set from storage graves:main creation.pre_death_equipment.head