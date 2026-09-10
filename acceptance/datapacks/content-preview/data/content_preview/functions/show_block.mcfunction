# Place the previewed block as a lit block_display at the executor origin.
# Invoked via content.execute-local with macro argument `asset` = the block id.
$summon minecraft:block_display ~ ~ ~ {block_state:{Name:"$(asset)"},brightness:{sky:15,block:15}}
