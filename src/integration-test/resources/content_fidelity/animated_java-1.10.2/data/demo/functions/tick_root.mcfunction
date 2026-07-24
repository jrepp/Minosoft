scoreboard players add @s demo.frame 1
execute if score @s demo.frame matches 1 on passengers if entity @s[tag=demo.node] run data modify entity @s item.tag.CustomModelData set value 27
execute if score @s demo.frame matches 1 on passengers run data modify entity @s start_interpolation set value -1
