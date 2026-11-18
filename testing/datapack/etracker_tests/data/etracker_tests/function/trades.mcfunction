# Villager trade tests (TESTING.md tests 40-47). Re-runnable.
kill @e[tag=ttest]
fill 255 99 226 300 99 250 stone
fill 255 100 226 300 106 250 air
# Librarian (Expert): mending book, paper, bookshelf
summon villager 270 100 232 {Tags:["ttest"],NoAI:1b,VillagerData:{profession:"minecraft:librarian",level:4,type:"minecraft:plains"},Xp:150,Offers:{Recipes:[{buy:{id:"minecraft:emerald",count:12},buyB:{id:"minecraft:book",count:1},sell:{id:"minecraft:enchanted_book",count:1,components:{"minecraft:stored_enchantments":{"minecraft:mending":1}}},maxUses:12,uses:0,xp:10,priceMultiplier:0.2f},{buy:{id:"minecraft:paper",count:24},sell:{id:"minecraft:emerald",count:1},maxUses:16,uses:16,xp:2,priceMultiplier:0.05f},{buy:{id:"minecraft:emerald",count:9},sell:{id:"minecraft:bookshelf",count:1},maxUses:12,uses:3,xp:1,priceMultiplier:0.05f}]}}
# Armorer "Smith" (Journeyman)
summon villager 274 100 232 {Tags:["ttest"],NoAI:1b,CustomName:"Smith",VillagerData:{profession:"minecraft:armorer",level:3,type:"minecraft:taiga"},Xp:80,Offers:{Recipes:[{buy:{id:"minecraft:emerald",count:5},sell:{id:"minecraft:iron_helmet",count:1},maxUses:12,uses:0,xp:1,priceMultiplier:0.2f},{buy:{id:"minecraft:coal",count:15},sell:{id:"minecraft:emerald",count:1},maxUses:16,uses:0,xp:2,priceMultiplier:0.05f}]}}
# Wandering trader
summon wandering_trader 278 100 232 {Tags:["ttest"],NoAI:1b,DespawnDelay:999999,Offers:{Recipes:[{buy:{id:"minecraft:emerald",count:1},sell:{id:"minecraft:sea_pickle",count:1},maxUses:5,uses:0,xp:1,priceMultiplier:0.05f}]}}
# Farmer (Novice), free to walk around, for the wander test
summon villager 282 100 232 {Tags:["ttest"],VillagerData:{profession:"minecraft:farmer",level:1,type:"minecraft:plains"},Offers:{Recipes:[{buy:{id:"minecraft:wheat",count:20},sell:{id:"minecraft:emerald",count:1},maxUses:16,uses:0,xp:2,priceMultiplier:0.05f}]}}
