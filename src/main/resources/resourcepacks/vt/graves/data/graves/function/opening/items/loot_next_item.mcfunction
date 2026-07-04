execute unless data storage graves:main opening.items[-1] run return fail
data modify storage graves:main opening.item set from storage graves:main opening.items[-1]
data remove storage graves:main opening.items[-1]
data modify storage graves:main opening.item.components merge value {}
execute unless data storage graves:main opening.item.count run data modify storage graves:main opening.item.count set value 1
function graves:opening/items/drop_item with storage graves:main opening.item
function graves:opening/items/loot_next_item