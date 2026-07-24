# Reduced from the 1.20.4 project summon output.
summon minecraft:item_display ~ ~ ~ {Tags:["aj.global.root","demo.root","demo.entity","aj.new"],teleport_duration:0,interpolation_duration:2,Passengers:[{id:"minecraft:item_display",Tags:["demo.node","demo.entity"],item:{id:"minecraft:carrot_on_a_stick",Count:1b,tag:{CustomModelData:1}},item_display:"head",transformation:[1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,1f,0f,1f]}]}
execute as @e[type=minecraft:item_display,tag=demo.root,tag=aj.new,limit=1,distance=..0.01] at @s run function demo:init_root
