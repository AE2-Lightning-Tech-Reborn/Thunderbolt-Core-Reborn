// Forge applies this method transformer independently of Mixin plugin callbacks.
function initializeCoreMod() {
    var descriptor = '(Lnet/minecraft/world/level/ItemLike;)Lcom/gregtechceu/gtceu/api/data/chemical/material/stack/MaterialEntry;';
    return {
        gto_material_cache: {
            target: {
                type: 'METHOD',
                'class': 'com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper',
                methodName: 'getMaterialEntry',
                methodDesc: descriptor
            },
            transformer: function(method) {
                if (method.name !== 'getMaterialEntry' || method.desc !== descriptor || (method.access & 8) === 0) {
                    throw new Error('GTO material cache method changed');
                }
                // The JVM monitor covers cache reads, lazy fill/rehash and pending-list clear,
                // and releases automatically if the original lookup throws.
                method.access = method.access | 32;
                return method;
            }
        }
    };
}
