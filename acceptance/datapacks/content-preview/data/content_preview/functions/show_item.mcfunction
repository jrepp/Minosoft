# Place the previewed item as a lit item_display at the executor origin.
# Invoked via content.execute-local with macro argument `asset` = the item id.
$summon minecraft:item_display ~ ~ ~ {item:{id:"$(asset)",Count:1b},brightness:{sky:15,block:15}}
