# Detection tests (TESTING.md tests 33-39). Re-runnable.
kill @e[tag=dtest]
fill 255 99 190 300 99 220 stone
fill 255 100 190 300 108 220 air
# A: two unnamed chest minecarts, different contents
summon chest_minecart 260.5 100 200.5 {Tags:["dtest","a1"],Items:[{Slot:0b,id:"minecraft:cod",count:1}]}
summon chest_minecart 262.5 100 200.5 {Tags:["dtest","a2"],Items:[{Slot:0b,id:"minecraft:salmon",count:1}]}
# E: chest that will be replaced by a barrel
setblock 266 100 200 chest{Items:[{Slot:0b,id:"minecraft:lantern",count:1}]}
# F: chested donkey whose chest will be removed
summon donkey 270.5 100 200.5 {CustomName:"F",ChestedHorse:1b,Tame:1b,NoAI:1b,Tags:["dtest","f"],Items:[{Slot:2b,id:"minecraft:melon_slice",count:1}]}
# G: hints
setblock 274 99 200 hopper
setblock 274 100 200 chest{CustomName:"G-below",Items:[{Slot:0b,id:"minecraft:kelp",count:1}]}
setblock 276 100 200 chest{CustomName:"G-fed",Items:[{Slot:0b,id:"minecraft:cactus",count:1}]}
setblock 277 100 200 hopper[facing=west]
setblock 278 100 202 furnace{Items:[{Slot:0b,id:"minecraft:raw_gold",count:1}]}
summon hopper_minecart 280.5 100 202.5 {CustomName:"G-hopcart",Tags:["dtest"],Items:[{Slot:0b,id:"minecraft:wheat",count:1}]}
setblock 282 99 200 hopper
setblock 282 100 200 rail
summon chest_minecart 282.5 100 200.5 {CustomName:"G-overhop",Tags:["dtest"],Items:[{Slot:0b,id:"minecraft:egg",count:1}]}
# D: vanishing tests
summon donkey 286.5 100 200.5 {CustomName:"D-tp",ChestedHorse:1b,Tame:1b,NoAI:1b,Tags:["dtest","dtp"],Items:[{Slot:2b,id:"minecraft:apple",count:1}]}
summon donkey 288.5 100 200.5 {CustomName:"D-tp2",ChestedHorse:1b,Tame:1b,NoAI:1b,Tags:["dtest","dtp2"],Items:[{Slot:2b,id:"minecraft:carrot",count:1}]}
summon chest_minecart 290.5 100 200.5 {CustomName:"D-break",Tags:["dtest","dbreak"],Items:[{Slot:0b,id:"minecraft:beetroot",count:1}]}
fill 291 100 206 294 104 206 obsidian
fill 292 101 206 293 103 206 air
fill 292 101 206 293 103 206 nether_portal[axis=x]
summon donkey 296.5 100 201.5 {CustomName:"D-portal",ChestedHorse:1b,Tame:1b,NoAI:1b,Tags:["dtest","dportal"],Items:[{Slot:2b,id:"minecraft:sugar",count:1}]}
# C: donkey to record, then watch from 110 blocks away
fill 375 99 198 385 99 203 stone
fill 375 100 198 385 104 203 air
summon donkey 380.5 100 200.5 {CustomName:"C-far",ChestedHorse:1b,Tame:1b,NoAI:1b,Tags:["dtest","cfar"],Items:[{Slot:2b,id:"minecraft:potato",count:1}]}
tellraw @a {"text":"etracker_tests: detect setup done","color":"green"}
