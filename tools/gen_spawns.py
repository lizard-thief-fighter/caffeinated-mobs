# writes spawn tags + biome modifiers. edit, then: python3 tools/gen_spawns.py
# weights are relative within a mob category per biome
import json, os, shutil

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src/main/resources/data/caffeinated_mobs')
TAGS = os.path.join(ROOT, 'tags/worldgen/biome/spawns')
MODS = os.path.join(ROOT, 'neoforge/biome_modifier/spawns')
for d in (TAGS, MODS):
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)

def entry(v):
    # non vanilla/c: entries optional so it works without terralith etc
    return v if v.startswith(('minecraft:', '#minecraft:', '#c:', '#caffeinated_mobs:')) else {'id': v, 'required': False}

def tag(name, *values):
    with open(os.path.join(TAGS, name + '.json'), 'w') as f:
        json.dump({'values': [entry(v) for v in values]}, f, indent=2)
    return '#caffeinated_mobs:spawns/' + name

def but(biomes, excluded):
    return {'type': 'neoforge:and', 'values': [biomes, {'type': 'neoforge:not', 'value': excluded}]}

def write(name, data):
    with open(os.path.join(MODS, name + '.json'), 'w') as f:
        json.dump(data, f, indent=2)

def add(name, biomes, *spawners, kind='neoforge:add_spawns', category=None):
    data = {'type': kind, 'biomes': biomes,
            'spawners': [{'type': e, 'weight': w, 'minCount': a, 'maxCount': b} for e, w, a, b in spawners]}
    if category:
        data['category'] = category
    mods = sorted({e.split(':')[0] for e, *_ in spawners} - {'minecraft'})
    if mods:
        data = {'neoforge:conditions': [{'type': 'neoforge:mod_loaded', 'modid': m} for m in mods], **data}
    write(name, data)

def set_(name, biomes, *spawners, category=None):
    # set_spawns: replaces existing entries, runs after neo adds/removes
    add(name, biomes, *spawners, kind='caffeinated_mobs:set_spawns', category=category)

def remove(name, biomes, *entities):
    write(name, {'type': 'neoforge:remove_spawns', 'biomes': biomes, 'entity_types': list(entities)})

def mc(*names): return ['minecraft:' + n for n in names]
def tl(*names): return ['terralith:' + n for n in names]
def es(*names): return ['espresso:' + n for n in names]

humid = tag('humid', '#c:is_jungle', '#c:is_swamp', 'minecraft:lush_caves', 'terralith:cave/underground_jungle',
            *es('mixed_jungle_forest', 'mixed_jungle_birch_forest', 'mixed_jungle_dark_forest'))
forests = tag('normal_forests', *mc('forest', 'birch_forest', 'flower_forest', 'dark_forest', 'old_growth_birch_forest'),
              *es('mixed_jungle_forest', 'mixed_jungle_birch_forest', 'mixed_jungle_dark_forest',
                  'mixed_taiga_forest', 'mixed_taiga_birch_forest', 'mixed_taiga_dark_forest', 'windswept_woodland'))
taigas = tag('normal_taigas', *mc('taiga', 'snowy_taiga', 'old_growth_pine_taiga', 'old_growth_spruce_taiga'))
dark_forests = tag('dark_forests', 'minecraft:dark_forest', *es('mixed_jungle_dark_forest', 'mixed_taiga_dark_forest'))
jungle_edges = tag('jungle_edges', *es('mixed_jungle_forest', 'mixed_jungle_birch_forest', 'mixed_jungle_dark_forest'))

remove('no_creepers_lush_caves', tag('no_creepers', 'minecraft:lush_caves', *tl('cave/fungal_caves', 'cave/underground_jungle')),
       'minecraft:creeper')
remove('no_spiders_fungal_caves', tag('no_spiders', *tl('cave/fungal_caves', 'cave/underground_jungle')), 'minecraft:spider')
add('mantle_caves', tag('mantle_caves', 'terralith:cave/mantle_caves'),
    ('minecraft:wither_skeleton', 55, 1, 2), ('minecraft:magma_cube', 55, 2, 3))
add('frostfire_caves', tag('frostfire_caves', 'terralith:cave/frostfire_caves'), ('minecraft:wither_skeleton', 10, 1, 2))

rare_slimes = tag('rare_slimes', *mc('dripstone_caves', 'lush_caves'),
                  *tl('cave/andesite_caves', 'cave/deep_caves', 'cave/diorite_caves', 'cave/granite_caves',
                      'cave/tuff_caves', 'cave/fungal_caves', 'cave/mantle_caves', 'cave/infested_caves'))
thermal = tag('thermal_caves', 'terralith:cave/thermal_caves')
underground_jungle = tag('underground_jungle', 'terralith:cave/underground_jungle')
tag('cave_slimes', rare_slimes, thermal, underground_jungle)
set_('slimes_rare', rare_slimes, ('minecraft:slime', 22, 1, 1))
set_('slimes_thermal_caves', thermal, ('minecraft:slime', 57, 3, 3))
set_('slimes_underground_jungle', underground_jungle, ('minecraft:slime', 4, 3, 3))

fungal = tag('fungal_caves', 'terralith:cave/fungal_caves')
add('hoglins_fungal_caves', fungal, ('minecraft:hoglin', 25, 1, 1))
add('hoglins_underground_jungle', underground_jungle, ('minecraft:hoglin', 10, 1, 1))
add('hoglins_caves', but('#c:is_cave', tag('boar_caves', fungal, underground_jungle)), ('minecraft:hoglin', 3, 1, 1))
haunts = tag('boar_haunts', dark_forests, '#c:is_old_growth')
set_('hoglins_woods', haunts, ('minecraft:hoglin', 2, 1, 1), category='creature')
set_('hoglins_rare_woods', but(tag('boar_range', '#minecraft:is_forest', '#minecraft:is_taiga'), haunts),
     ('minecraft:hoglin', 1, 1, 1), category='creature')

rivers = tag('rivers', 'minecraft:river', 'terralith:warm_river')
set_('drowned_rivers', rivers, ('minecraft:drowned', 27, 1, 1))
add('strays_cold', but(tag('cold', '#c:is_cold/overworld', '#c:is_snowy'),
                       tag('stray_biomes', *mc('snowy_plains', 'ice_spikes'),
                           *tl('frozen_cliffs', 'glacial_chasm', 'gravel_desert', 'skylands_winter', 'cave/frostfire_caves'))),
    ('minecraft:stray', 25, 1, 2))
add('bogged_humid', but(humid, tag('bogged_biomes', *mc('swamp', 'mangrove_swamp'), 'terralith:orchid_swamp')),
    ('minecraft:bogged', 20, 2, 3))

add('ocelots_jungles', '#c:is_jungle', ('minecraft:ocelot', 10, 1, 2))
add('ocelots_jungle_edges', jungle_edges, ('minecraft:ocelot', 3, 1, 1))
add('ocelots_savannas', '#c:is_savanna', ('minecraft:ocelot', 4, 1, 1))
add('sheep_cold', '#c:is_cold/overworld', ('minecraft:sheep', 12, 2, 4))
set_('sheep_hot', '#c:is_hot/overworld', ('minecraft:sheep', 4, 2, 3))
set_('pigs_dry', '#c:is_dry/overworld', ('minecraft:pig', 3, 2, 3))
add('chickens_humid', humid, ('minecraft:chicken', 5, 2, 4))
add('foxes_forests', forests, ('minecraft:fox', 1, 1, 1))
add('deer_woods', tag('deer_woods', forests, taigas), ('nomansland:deer', 5, 1, 3))
remove('no_armadillos_white_cliffs', tag('no_armadillos', 'terralith:white_cliffs'), 'minecraft:armadillo')
desert = tag('desert', 'minecraft:desert')
set_('camels_desert', desert, ('minecraft:camel', 20, 2, 4))
add('camels_desert_edges', tag('desert_edges', *mc('savanna', 'savanna_plateau', 'badlands', 'wooded_badlands'),
                                *tl('arid_highlands', 'brushland', 'hot_shrubland', 'shrubland'), 'espresso:savanna_woodland'),
    ('minecraft:camel', 1, 1, 1))
add('goats_highlands', tag('goat_highlands', *mc('windswept_hills', 'windswept_gravelly_hills'),
                           *tl('highlands', 'alpine_highlands', 'temperate_highlands', 'forested_highlands', 'haze_mountain',
                               'rocky_mountains', 'emerald_peaks', 'scarlet_mountains', 'yosemite_cliffs', 'caldera',
                               'windswept_spires'), 'espresso:alpine_meadow'),
    ('minecraft:goat', 5, 1, 3))
add('llamas', tag('llama_lands', *mc('savanna', 'savanna_plateau', 'windswept_savanna'), 'espresso:savanna_woodland',
                  *tl('highlands', 'alpine_highlands', 'temperate_highlands')),
    ('minecraft:llama', 2, 2, 3))
add('parrots_forests', '#minecraft:is_forest', ('minecraft:parrot', 5, 1, 2))
add('frogs_jungles', '#c:is_jungle', ('minecraft:frog', 10, 2, 4))
add('frogs_rare', tag('rare_frogs', '#c:is_desert', '#c:is_badlands', 'minecraft:meadow', 'espresso:alpine_meadow', forests),
    ('minecraft:frog', 1, 1, 2))
add('geese_swamps', tag('goose_swamps', 'minecraft:mangrove_swamp', 'terralith:ice_marsh'), ('nomansland:goose', 8, 1, 4))
add('mooshrooms_dark_forests', dark_forests, ('minecraft:mooshroom', 10, 2, 6))
add('mooshrooms_old_growth', '#c:is_old_growth', ('minecraft:mooshroom', 4, 1, 3))
add('billhook_bass', tag('bass_waters', 'minecraft:river', 'terralith:warm_river', 'minecraft:mangrove_swamp', 'terralith:ice_marsh'),
    ('nomansland:billhook_bass', 7, 1, 4))
add('axolotls_rare', tag('rare_axolotls', 'terralith:warm_river', *mc('swamp', 'mangrove_swamp'), 'terralith:orchid_swamp'),
    ('minecraft:axolotl', 1, 1, 2))
remove('glow_squid', but('#minecraft:is_overworld', '#minecraft:is_deep_ocean'), 'minecraft:glow_squid')
set_('glow_squid_deep_oceans', '#minecraft:is_deep_ocean', ('minecraft:glow_squid', 10, 2, 4))
tag('surface_bats', '#c:is_temperate/overworld', humid)

remove('no_zombified_piglins', '#minecraft:is_nether', 'minecraft:zombified_piglin')
add('wither_skeletons_valleys', tag('soul_valleys', 'minecraft:soul_sand_valley', 'abyssaline:infected_valley'),
    ('minecraft:wither_skeleton', 5, 1, 2))
warped = tag('warped_forests', 'minecraft:warped_forest', 'abyssaline:twisting_thicket')
set_('endermen_warped', warped, ('minecraft:enderman', 1, 4, 6))

print(len(os.listdir(TAGS)), 'tags,', len(os.listdir(MODS)), 'modifiers')
